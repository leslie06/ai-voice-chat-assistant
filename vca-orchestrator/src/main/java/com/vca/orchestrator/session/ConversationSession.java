package com.vca.orchestrator.session;

import com.vca.domain.enums.SessionState;
import com.vca.domain.enums.VendorType;
import com.vca.domain.model.AsrEvent;
import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.AudioFrame;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.Message;
import com.vca.domain.model.SessionContext;
import com.vca.domain.model.TtsConfig;
import com.vca.domain.model.S2sEvent;
import com.vca.domain.spi.AsrProvider;
import com.vca.domain.spi.LlmProvider;
import com.vca.domain.spi.S2sProvider;
import com.vca.domain.spi.S2sSession;
import com.vca.domain.spi.TtsProvider;
import com.vca.orchestrator.metrics.TurnMetrics;
import com.vca.orchestrator.pipeline.SentenceSplitter;
import com.vca.orchestrator.skill.MusicIntent;
import com.vca.orchestrator.statemachine.ConversationStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一路会话的编排核心。把 ASR→LLM→TTS 串成全链路流式管道, 实现:
 * <ul>
 *   <li><b>句子级流水线</b>: LLM token 流经 {@link SentenceSplitter} 实时切句, 逐句送 TTS,
 *       首句一出就开播, 不等整段;</li>
 *   <li><b>可打断(barge-in)</b>: {@link #bargeIn()} 通过 interrupt sink 取消当前回合的整条流,
 *       上游 ASR/LLM/TTS 连接随订阅取消而释放;</li>
 *   <li><b>状态机驱动</b>: LISTENING→THINKING→SPEAKING→IDLE, 打断走 INTERRUPTED;</li>
 *   <li><b>对话记忆</b>: 维护 system + 多轮 user/assistant 历史。</li>
 * </ul>
 *
 * <p>本类只依赖 domain 的 SPI 接口, 不感知具体厂商 —— 具体实现由治理层注入。
 * 每个 {@code ConversationSession} 实例对应一路会话, 非跨会话共享。
 */
public class ConversationSession {

    private static final Logger log = LoggerFactory.getLogger(ConversationSession.class);

    /** 默认保留的最大非 system 历史消息数(≈8 轮 user/assistant)。语音对话无需长记忆。 */
    private static final int DEFAULT_MAX_HISTORY_MESSAGES = 16;

    private final SessionContext context;
    private final AsrProvider asr;
    private final LlmProvider llm;
    private final TtsProvider tts;
    private final S2sProvider s2s;
    private final SentenceSplitter splitter;
    private final MusicIntent musicIntent = new MusicIntent();

    private final ConversationStateMachine stateMachine = new ConversationStateMachine();
    private final List<Message> history = new ArrayList<>();
    /** 历史滑动窗口: 仅保留最近这么多条非 system 消息, 防止历史无限膨胀诱导模型复述旧回复 */
    private final int maxHistoryMessages;
    /** 当前回合的打断信号; 每个回合一个, barge-in 时触发以取消该回合 */
    private final AtomicReference<Sinks.One<Void>> currentInterrupt = new AtomicReference<>();
    /** 回合事件回调(字幕透传), 默认空实现 */
    private volatile TurnListener listener = TurnListener.NOOP;
    /**
     * 当前生效的 LLM 配置, <b>语音三段式回合与打字回合共用</b>; 默认取自上下文。
     * 前端经 {@link #selectLlm} 在线切换模型/厂商时更新它, 语音与打字同时改用新模型。
     * (s2s 模式下语音走端到端 Omni 模型、不读它; 那时它仅供打字回合使用。)
     */
    private volatile LlmConfig activeLlmConfig;
    /**
     * 当前生效的对话模式(可热切)。初值取自上下文; 前端经 {@link #switchMode} 在线切换"三段式/端到端"时更新。
     * 切换<b>仅对下一回合生效</b>, 进行中的回合按原模式跑完; 对话历史保留, 两种模式延续同一段上下文。
     */
    private volatile SessionContext.Mode mode;
    /**
     * 当前生效的 TTS 配置(三段式语音用); 前端经 {@link #selectVoice} 在线切换音色时更新, 默认取自上下文。
     * 仅作用于三段式; s2s 端到端语音的音色由 s2sConfig 决定, 不读它。
     */
    private volatile TtsConfig activeTtsConfig;
    /** 延迟埋点; 测试/未注入时为 noop */
    private final TurnMetrics metrics;

    public ConversationSession(SessionContext context,
                               AsrProvider asr, LlmProvider llm, TtsProvider tts, S2sProvider s2s,
                               SentenceSplitter splitter) {
        this(context, asr, llm, tts, s2s, splitter, DEFAULT_MAX_HISTORY_MESSAGES);
    }

    public ConversationSession(SessionContext context,
                               AsrProvider asr, LlmProvider llm, TtsProvider tts, S2sProvider s2s,
                               SentenceSplitter splitter, int maxHistoryMessages) {
        this(context, asr, llm, tts, s2s, splitter, maxHistoryMessages, TurnMetrics.noop());
    }

    public ConversationSession(SessionContext context,
                               AsrProvider asr, LlmProvider llm, TtsProvider tts, S2sProvider s2s,
                               SentenceSplitter splitter, int maxHistoryMessages, TurnMetrics metrics) {
        this.context = context;
        this.asr = asr;
        this.llm = llm;
        this.tts = tts;
        this.s2s = s2s;
        this.splitter = splitter;
        this.maxHistoryMessages = maxHistoryMessages > 0 ? maxHistoryMessages : DEFAULT_MAX_HISTORY_MESSAGES;
        this.metrics = metrics == null ? TurnMetrics.noop() : metrics;
        this.activeLlmConfig = context.llmConfig();
        this.mode = context.mode();
        this.activeTtsConfig = context.ttsConfig();
        seedSystemPrompt();
    }

    /** 设置回合事件回调(用于把 ASR/回复文本透传给前端) */
    public void setTurnListener(TurnListener listener) {
        this.listener = listener == null ? TurnListener.NOOP : listener;
    }

    /**
     * 在线切换对话使用的 LLM 厂商/模型(前端下拉选模型时调用)。<b>语音三段式回合与打字回合同时改用</b>
     * 新模型。沿用原有的 systemPrompt/temperature/maxTokens, 只换 vendor/model。
     *
     * <p>若会话本就没有 LLM 配置(如 s2s 且未注入), 则据传入的 vendor/model 现造一份,
     * 让 s2s 模式下打字也能选模型并出文字回复。
     */
    public void selectLlm(VendorType vendor, String model) {
        LlmConfig base = activeLlmConfig;
        if (base == null) {
            this.activeLlmConfig = LlmConfig.defaults(
                    vendor, model == null || model.isBlank() ? null : model);
            return;
        }
        this.activeLlmConfig = new LlmConfig(
                vendor != null ? vendor : base.vendor(),
                model != null && !model.isBlank() ? model : base.model(),
                base.systemPrompt(), base.temperature(), base.maxTokens());
    }

    /**
     * 在线切换对话模式: 三段式(ASR→LLM→TTS) ↔ 端到端语音大模型(s2s)。前端切换时调用。
     * <b>仅对下一回合生效</b>, 进行中的回合按原模式跑完; 对话历史保留, 两种模式延续同一段上下文。
     *
     * <p>只在会话备齐目标模式所需配置时才切换(三段式需 asr+tts, 端到端需 s2s); 缺失则忽略,
     * 避免切到一个跑不起来的模式。{@code null} 或与当前相同则无操作。
     */
    public void switchMode(SessionContext.Mode target) {
        if (target == null || target == mode) {
            return;
        }
        boolean ready = target == SessionContext.Mode.PIPELINE
                ? context.asrConfig() != null && context.ttsConfig() != null
                : context.s2sConfig() != null;
        if (!ready) {
            log.warn("切换对话模式被忽略: 目标 {} 所需配置缺失, session={}", target, context.sessionId());
            return;
        }
        this.mode = target;
        log.info("对话模式切换为 {}, session={}", target, context.sessionId());
    }

    /**
     * 在线切换三段式 TTS 厂商+音色(前端选音色时调用)。沿用原格式/采样率/语速, 换 vendor/voice。
     * 音色是厂商相关的(CosyVoice 与 Qwen-TTS 各一套), 故选音色时一并把厂商切到对应方,
     * 治理层据 {@code vendor} 路由到该厂商候选。{@code vendor} 为空则沿用原厂商。
     *
     * <p><b>仅影响三段式语音回合</b>; s2s 端到端语音的音色由 s2sConfig 决定, 不受此影响。
     * 会话无 TTS 配置(未注入)或音色为空时忽略。
     */
    public void selectVoice(VendorType vendor, String voice) {
        TtsConfig base = activeTtsConfig;
        if (base == null || voice == null || voice.isBlank()) {
            return;
        }
        VendorType v = vendor != null ? vendor : base.vendor();
        this.activeTtsConfig = new TtsConfig(v, voice, base.format(), base.sampleRate(), base.speed());
        log.debug("切换 TTS 厂商/音色: vendor={}, voice={}", v, voice);
    }

    /**
     * 处理用户的一轮说话: 输入上行音频流, 返回可直接回传前端播放的下行音频块流。
     */
    public Flux<AudioChunk> handleUserTurn(Flux<AudioFrame> userAudio) {
        Sinks.One<Void> interrupt = beginTurn();
        Flux<AudioChunk> turn = mode == SessionContext.Mode.PIPELINE
                ? pipelineTurn(userAudio)
                : speechToSpeechTurn(userAudio);
        return finishTurn(turn, interrupt);
    }

    /**
     * 处理用户打字输入的一轮: 直接把文本当作"本轮用户说了什么"注入, 绕过 ASR,
     * 因此无论用真实 ASR 还是桩 ASR 都能进大模型。
     *
     * <p><b>与对话模式无关</b>: 三段式(pipeline)固然走 LLM; 端到端(s2s)模型只吃音频、不接受文本,
     * 故打字时同样回退到普通 LLM 出文字回复(需会话注入了 LLM 配置)。
     *
     * <p><b>文字进、文字出</b>: 打字输入不合成 TTS, 只把 LLM 文本流式回传(经 listener),
     * 与"语音进、语音出"({@link #handleUserTurn})区分开 —— 打字时不应该有语音回复。
     */
    public Flux<AudioChunk> handleTextTurn(String text) {
        Sinks.One<Void> interrupt = beginTurn();
        // 打字一律走"文字进、文字出"的 LLM 链路, 与对话模式无关 —— 端到端(s2s)模型不吃文本,
        // 此时靠会话里另注入的 LLM 出文字回复(不合成语音)。无 LLM 配置则不支持打字。
        Flux<AudioChunk> turn = (activeLlmConfig == null || text == null || text.isBlank())
                ? Flux.empty()
                : respondTextOnly(text.trim());
        return finishTurn(turn, interrupt);
    }

    /** 开启一轮: 建新的打断信号并转入 LISTENING */
    private Sinks.One<Void> beginTurn() {
        Sinks.One<Void> interrupt = Sinks.one();
        currentInterrupt.set(interrupt);
        stateMachine.tryTransition(SessionState.LISTENING);
        return interrupt;
    }

    /** 给回合流挂上"可打断 + 收尾置 IDLE"的通用尾巴 */
    private Flux<AudioChunk> finishTurn(Flux<AudioChunk> turn, Sinks.One<Void> interrupt) {
        return turn
                // 打断: companion 一旦完成即取消整条流, 释放上游 ASR/LLM/TTS
                .takeUntilOther(interrupt.asMono())
                .doFinally(sig -> {
                    if (!stateMachine.is(SessionState.CLOSED)) {
                        stateMachine.tryTransition(SessionState.IDLE);
                    }
                    currentInterrupt.compareAndSet(interrupt, null);
                    log.debug("回合结束, signal={}, state={}", sig, stateMachine.current());
                });
    }

    /** 三段式: ASR(取 final) → 交给 {@link #respond} 走 LLM → 分句 → TTS */
    private Flux<AudioChunk> pipelineTurn(Flux<AudioFrame> userAudio) {
        return asr.transcribe(userAudio, context.asrConfig())
                .filter(AsrEvent::isFinal)
                .next()                                  // 取本轮最终识别结果
                .filter(ev -> !ev.isBlank())
                .flatMapMany(ev -> {
                    log.debug("ASR final: {}", ev.text());
                    return respond(ev.text());
                });
    }

    /** 拿到"本轮用户说了什么"之后的公共回复链路: 写历史 → LLM → 分句 → TTS */
    private Flux<AudioChunk> respond(String userText) {
        return Flux.defer(() -> {
            long startNanos = System.nanoTime();
            AtomicBoolean started = new AtomicBoolean(false);
            AtomicBoolean firstToken = new AtomicBoolean(false);
            // 第一句交给 TTS 的时刻, 用于算"纯 TTS 延迟"(排除前面 LLM 出首句的时间)
            AtomicLong firstSentenceNanos = new AtomicLong();
            StringBuilder assistant = new StringBuilder();

            appendHistory(Message.user(userText));
            safeNotify(() -> listener.onAsrFinal(userText));

            // 点歌意图: 短路普通对话, 改去 QQ 音乐; 语音回合用 TTS 念一句确认
            Optional<String> song = musicIntent.parsePlay(userText);
            if (song.isPresent()) {
                return musicTurn(song.get(), true);
            }

            stateMachine.tryTransition(SessionState.THINKING);
            // LLM 原始 token 流: 边吐边累计全文, 并把增量实时推给前端做"打字机"流式显示
            // (与 TTS 解耦 —— 真实 TTS 的音频块不带文本, 不能靠它驱动字幕)。
	            Flux<String> tokens = llm.chatStream(historySnapshot(), activeLlmConfig)
	                    .doOnNext(tok -> {
	                        if (firstToken.compareAndSet(false, true)) {
	                            Duration ttft = elapsed(startNanos);
	                            metrics.recordLlmFirstToken(ttft);
	                            logLlmFirstToken("voice", activeLlmConfig, ttft);
	                        }
	                        assistant.append(tok);
	                        safeNotify(() -> listener.onAssistantDelta(tok));
	                    });
            // 记录第一句出现的时刻(分句器切出首句即将送 TTS)
            Flux<String> sentences = splitter.split(tokens)
                    .doOnNext(s -> firstSentenceNanos.compareAndSet(0, System.nanoTime()));

            return tts.synthesize(sentences, activeTtsConfig)
                    .doOnNext(chunk -> {
                        if (started.compareAndSet(false, true)) {
                            Duration ttfa = elapsed(startNanos);
                            metrics.recordTtsFirstAudio(ttfa);
                            // 纯 TTS 延迟 = 首句送进 TTS → 首块音频返回; 拿不到首句时刻则退回总耗时
                            long fs = firstSentenceNanos.get();
                            Duration ttsOnly = fs == 0 ? ttfa : Duration.ofNanos(System.nanoTime() - fs);
                            logTtsFirstAudio(activeTtsConfig, ttfa, ttsOnly);
                            stateMachine.tryTransition(SessionState.SPEAKING);
                        }
                    })
                    .doOnComplete(() -> {
                        if (!assistant.isEmpty()) {
                            appendHistory(Message.assistant(assistant.toString()));
                            safeNotify(() -> listener.onAssistantText(assistant.toString()));
                        }
                    })
                    .doFinally(sig -> {
                        metrics.recordTurnTotal(elapsed(startNanos));
                        metrics.countTurn("voice", outcomeOf(sig));
                    });
        });
    }

	    /** 自起点到现在的耗时 */
	    private static Duration elapsed(long startNanos) {
	        return Duration.ofNanos(System.nanoTime() - startNanos);
	    }

	    private void logLlmFirstToken(String mode, LlmConfig cfg, Duration ttft) {
	        String vendor = cfg == null || cfg.vendor() == null ? "-" : cfg.vendor().code();
	        String model = cfg == null || cfg.model() == null || cfg.model().isBlank() ? "-" : cfg.model();
	        log.info("LLM 首 token 耗时: {} ms, session={}, mode={}, vendor={}, model={}",
	                ttft.toMillis(), context.sessionId(), mode, vendor, model);
	    }

	    /**
	     * 打印 TTS 首块音频耗时。{@code total} = 本轮开始(ASR final 后)到首块音频, 含 LLM 出首句的时间;
	     * {@code ttsOnly} = 首句送进 TTS 到首块音频返回, 即纯 TTS 合成延迟 —— 排查"TTS 慢"看这个。
	     */
	    private void logTtsFirstAudio(TtsConfig cfg, Duration total, Duration ttsOnly) {
	        String vendor = cfg == null || cfg.vendor() == null ? "-" : cfg.vendor().code();
	        String voice = cfg == null || cfg.voice() == null || cfg.voice().isBlank() ? "-" : cfg.voice();
	        log.info("TTS 首音频耗时: 纯TTS={} ms, 含LLM出句={} ms, session={}, vendor={}, voice={}",
	                ttsOnly.toMillis(), total.toMillis(), context.sessionId(), vendor, voice);
	    }

	    /** 把 reactor 结束信号归一成埋点用的 outcome 标签 */
    private static String outcomeOf(reactor.core.publisher.SignalType sig) {
        return switch (sig) {
            case ON_COMPLETE -> "complete";
            case CANCEL -> "interrupted";
            case ON_ERROR -> "error";
            default -> sig.toString().toLowerCase();
        };
    }

    /**
     * 文字进、文字出: 写历史 → LLM 流式出文本(经 listener 实时回传前端), 不分句、不合成 TTS。
     * 返回的音频流恒为空 —— 打字输入不产生语音回复。
     */
    private Flux<AudioChunk> respondTextOnly(String userText) {
        return Flux.defer(() -> {
            long startNanos = System.nanoTime();
            AtomicBoolean firstToken = new AtomicBoolean(false);
            StringBuilder assistant = new StringBuilder();

            appendHistory(Message.user(userText));
            // 不回传 onAsrFinal: 打字输入前端已本地回显, 再 echo 会重复显示;
            // onAsrFinal 仅用于"语音识别结果"这类前端尚未看到的文本。

            // 点歌意图: 短路普通对话, 改去 QQ 音乐; 文字回合不合成语音
            Optional<String> song = musicIntent.parsePlay(userText);
            if (song.isPresent()) {
                return musicTurn(song.get(), false);
            }

            stateMachine.tryTransition(SessionState.THINKING);
	            return llm.chatStream(historySnapshot(), activeLlmConfig)
	                    .doOnNext(tok -> {
	                        if (firstToken.compareAndSet(false, true)) {
	                            Duration ttft = elapsed(startNanos);
	                            metrics.recordLlmFirstToken(ttft);
	                            logLlmFirstToken("text", activeLlmConfig, ttft);
	                        }
	                        assistant.append(tok);
	                        safeNotify(() -> listener.onAssistantDelta(tok));
	                    })
                    .doOnComplete(() -> {
                        if (!assistant.isEmpty()) {
                            appendHistory(Message.assistant(assistant.toString()));
                            safeNotify(() -> listener.onAssistantText(assistant.toString()));
                        }
                    })
                    .doFinally(sig -> {
                        metrics.recordTurnTotal(elapsed(startNanos));
                        metrics.countTurn("text", outcomeOf(sig));
                    })
                    .thenMany(Flux.<AudioChunk>empty());   // 文本回合不回传任何音频块
        });
    }

    /**
     * 点歌回合: 通知接入层让前端去 QQ 音乐播放, 并给一句确认。
     * 不走 LLM —— 点歌是确定性动作。{@code speak=true}(语音回合)时用 TTS 念确认语,
     * {@code speak=false}(文字回合)则只把动作交给前端(歌曲卡片即反馈), 不发声。
     *
     * @param query 想听的歌(歌名/歌手)
     * @param speak 是否合成语音确认
     */
    private Flux<AudioChunk> musicTurn(String query, boolean speak) {
        String reply = "好的，为您播放音乐" + query;
        appendHistory(Message.assistant(reply));
        // 动作: 让前端打开 QQ 音乐(具体 URL 由接入层按厂商拼装, 编排层不感知)
        safeNotify(() -> listener.onMusicRequest("play", query));
        if (!speak) {
            return Flux.empty();
        }
        stateMachine.tryTransition(SessionState.THINKING);
        return tts.synthesize(Flux.just(reply), activeTtsConfig)
                .doOnNext(chunk -> stateMachine.tryTransition(SessionState.SPEAKING));
    }

    /**
     * 端到端语音大模型: 直接音频进、音频出。除音频外, 厂商还会回吐"用户语音转写"和"机器人回复转写",
     * 这里把它们透传给前端做字幕 —— 走与三段式相同的 listener 通道(onAsrFinal / onAssistantDelta),
     * 前端因此无需区分模式即可显示双方说话内容。
     */
    private Flux<AudioChunk> speechToSpeechTurn(Flux<AudioFrame> userAudio) {
        StringBuilder assistant = new StringBuilder();
        AtomicBoolean thinking = new AtomicBoolean(false);
        AtomicBoolean speaking = new AtomicBoolean(false);
        // 回灌截至本轮开始前的历史(含 system + 之前的 user/assistant), 让端到端模型记住上文
        return s2s.converse(userAudio, historySnapshot(), context.s2sConfig())
                .doOnNext(chunk -> {
                    if (thinking.compareAndSet(false, true)) {
                        stateMachine.tryTransition(SessionState.THINKING);
                    }
                    routeTranscript(chunk, assistant);
                    // 状态机转 SPEAKING 以第一块真实音频为准(纯字幕块不算开口)
                    if (chunk.size() > 0 && speaking.compareAndSet(false, true)) {
                        stateMachine.tryTransition(SessionState.SPEAKING);
                    }
                })
                .doOnComplete(() -> {
                    if (!assistant.isEmpty()) {
                        appendHistory(Message.assistant(assistant.toString()));
                        safeNotify(() -> listener.onAssistantText(assistant.toString()));
                    }
                });
    }

    /** S2S 字幕分流: 用户转写 → onAsrFinal(显示"你说了什么"); 机器人回复转写 → 逐段 onAssistantDelta 并累计成全文。 */
    private void routeTranscript(AudioChunk chunk, StringBuilder assistant) {
        String text = chunk.text();
        if (text == null || text.isBlank()) {
            return;
        }
        if (chunk.textRole() == AudioChunk.TextRole.USER) {
            appendHistory(Message.user(text));
            safeNotify(() -> listener.onAsrFinal(text));
        } else {
            assistant.append(text);
            safeNotify(() -> listener.onAssistantDelta(text));
        }
    }

    /**
     * 持久 S2S 全双工会话(P2): 开一条长连贯穿多轮, <b>服务端 VAD 接管回合切分与打断</b>, 取代
     * {@link #speechToSpeechTurn} 每轮一连接、应用侧判停的伪级联用法 —— 这是把 Omni 用出原生全双工/
     * 原生打断能力的形态。返回句柄供接入层持续喂音频({@link S2sLiveSession#pushAudio})、订阅下行音频;
     * 双方字幕与打断信号经 {@link #listener} 旁路透传(与三段式同款通道), 接入层无需区分模式。
     *
     * <p>状态机在全双工下尽力而为驱动(仅供观测), 每次回复内 THINKING/SPEAKING 各迁移一次以避免噪声日志。
     * 对话历史与三段式/每轮 S2S 共享同一段上下文, 切回其它模式时延续。
     */
    public S2sLiveSession openS2sLive() {
        S2sSession session = s2s.open(historySnapshot(), context.s2sConfig());
        stateMachine.tryTransition(SessionState.LISTENING);
        LiveResponse resp = new LiveResponse();
        Flux<AudioChunk> audioOut = session.events()
                .<AudioChunk>handle((ev, sink) -> onS2sLiveEvent(ev, resp, sink))
                .doFinally(sig -> {
                    if (!stateMachine.is(SessionState.CLOSED)) {
                        stateMachine.tryTransition(SessionState.IDLE);
                    }
                    log.debug("持久 S2S 会话结束, signal={}", sig);
                });
        return new S2sLiveSession(session, audioOut);
    }

    /** 持久 S2S 下行事件 → 音频块 + 字幕(listener) + 历史 + 状态机。运行在单一订阅线程上, 串行。 */
    private void onS2sLiveEvent(S2sEvent ev, LiveResponse resp,
                               reactor.core.publisher.SynchronousSink<AudioChunk> sink) {
        if (ev instanceof S2sEvent.UserTranscript u) {
            appendHistory(Message.user(u.text()));
            safeNotify(() -> listener.onAsrFinal(u.text()));
        } else if (ev instanceof S2sEvent.AssistantText t) {
            markThinking(resp);
            resp.assistant.append(t.delta());
            safeNotify(() -> listener.onAssistantDelta(t.delta()));
        } else if (ev instanceof S2sEvent.AudioDelta a) {
            markThinking(resp);
            if (!resp.speaking) {
                resp.speaking = true;
                stateMachine.tryTransition(SessionState.SPEAKING);
            }
            sink.next(AudioChunk.of(a.pcm(), com.vca.domain.enums.AudioFormat.PCM, a.sequence()));
        } else if (ev instanceof S2sEvent.UserSpeechStarted) {
            // 全双工打断: 落已说出的部分、回到聆听, 并通知接入层冲掉前端播放缓冲(止住已下发的音频)
            flushAssistant(resp);
            stateMachine.tryTransition(SessionState.INTERRUPTED);
            stateMachine.tryTransition(SessionState.LISTENING);
            safeNotify(listener::onUserSpeechStarted);
        } else if (ev instanceof S2sEvent.ResponseDone) {
            // 本次回复正常结束(会话不关): 落历史, 回到聆听等下一轮
            flushAssistant(resp);
            stateMachine.tryTransition(SessionState.LISTENING);
        }
    }

    /** 本次回复首次出现内容时迁入 THINKING(每次回复仅一次, 避免噪声日志)。 */
    private void markThinking(LiveResponse resp) {
        if (!resp.thinking) {
            resp.thinking = true;
            stateMachine.tryTransition(SessionState.THINKING);
        }
    }

    /** 把当前累计的机器人回复落历史并通知 listener, 然后复位本次回复态; 空则只复位。 */
    private void flushAssistant(LiveResponse resp) {
        if (!resp.assistant.isEmpty()) {
            String full = resp.assistant.toString();
            appendHistory(Message.assistant(full));
            safeNotify(() -> listener.onAssistantText(full));
        }
        resp.assistant.setLength(0);
        resp.thinking = false;
        resp.speaking = false;
    }

    /** 持久 S2S 单次回复的累计态: 在 handle 闭包内跨事件维护(单订阅线程, 无需同步)。 */
    private static final class LiveResponse {
        final StringBuilder assistant = new StringBuilder();
        boolean thinking;
        boolean speaking;
    }

    /**
     * 用户打断: 取消当前回合, 状态走 INTERRUPTED → (回合 doFinally 落到) IDLE。
     * 由上层在 SPEAKING/THINKING 状态下检测到用户开口(VAD)时调用。
     */
    public void bargeIn() {
        stateMachine.tryTransition(SessionState.INTERRUPTED);
        Sinks.One<Void> interrupt = currentInterrupt.get();
        if (interrupt != null) {
            interrupt.tryEmitEmpty();
        }
        log.debug("用户打断, state={}", stateMachine.current());
    }

    /** 关闭会话: 取消进行中的回合并置终态 */
    public void close() {
        Sinks.One<Void> interrupt = currentInterrupt.get();
        if (interrupt != null) {
            interrupt.tryEmitEmpty();
        }
        stateMachine.close();
    }

    public SessionState state() {
        return stateMachine.current();
    }

    /** 当前生效的对话模式(可热切)。接入层据此决定是否走持久 S2S 路径。 */
    public SessionContext.Mode currentMode() {
        return mode;
    }

    public String sessionId() {
        return context.sessionId();
    }

    /** 历史只读快照(供观测/测试) */
    public List<Message> historyView() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    // ---- 内部: 历史维护(synchronized 保护) ----

    private void seedSystemPrompt() {
        String prompt = context.isPipeline()
                ? (context.llmConfig() == null ? null : context.llmConfig().systemPrompt())
                : (context.s2sConfig() == null ? null : context.s2sConfig().systemPrompt());
        if (prompt != null && !prompt.isBlank()) {
            appendHistory(Message.system(prompt));
        }
    }

    /** 回调不应影响主流程, 出错只记录 */
    private void safeNotify(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.warn("TurnListener 回调出错: {}", e.toString());
        }
    }

    private void appendHistory(Message message) {
        synchronized (history) {
            history.add(message);
            trimHistory();
        }
    }

    /**
     * 历史滑动窗口: 始终保留 system 提示, 只留最近 {@link #maxHistoryMessages} 条 user/assistant。
     * 防止历史无限累积 —— 长历史回喂会诱导小模型把上一轮回复也复述出来。须在持有 {@code history} 锁时调用。
     */
    private void trimHistory() {
        long nonSystem = history.stream().filter(m -> m.role() != Message.Role.SYSTEM).count();
        Iterator<Message> it = history.iterator();
        // 从最旧开始裁掉多余的非 system 消息(保留 system 提示)
        while (nonSystem > maxHistoryMessages && it.hasNext()) {
            if (it.next().role() != Message.Role.SYSTEM) {
                it.remove();
                nonSystem--;
            }
        }
        // 让保留的对话从一条 user 开始: 丢弃开头悬空的 assistant(无对应 user),
        // 既省 token, 也避免被当成"待续写的前缀"而诱导模型复述上一轮。
        Iterator<Message> head = history.iterator();
        while (head.hasNext()) {
            Message m = head.next();
            if (m.role() == Message.Role.SYSTEM) {
                continue;
            }
            if (m.role() == Message.Role.ASSISTANT) {
                head.remove();
            } else {
                break; // 遇到第一条 USER, 窗口已对齐
            }
        }
    }

    private List<Message> historySnapshot() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }
}
