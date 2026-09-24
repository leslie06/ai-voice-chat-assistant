package com.vca.telephony.session;

import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.AudioFrame;
import com.vca.orchestrator.session.ConversationSession;
import com.vca.orchestrator.session.TurnListener;
import com.vca.orchestrator.vad.HandsFreeVad;
import com.vca.orchestrator.vad.PcmAudio;
import com.vca.orchestrator.vad.VadConfig;
import com.vca.orchestrator.vad.VoiceActivityDetector;
import com.vca.telephony.spi.CallEvent;
import com.vca.telephony.summary.EndedCall;
import com.vca.telephony.spi.CallLeg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 一路电话通话的编排。<b>它与 {@code VoiceWebSocketHandler.Connection} 是平级的两个接入层</b>:
 * 同样负责"VAD 接线 + 回合管理 + epoch 门闸", 只是 IO 两端从 WebSocket 换成了 {@link CallLeg}。
 * 中间的 {@link ConversationSession}(ASR→LLM→TTS、Skill、RAG、记忆、落库)完全不感知自己在打电话。
 *
 * <h2>与浏览器版的三处实质差异</h2>
 * <ol>
 *   <li><b>下行必须节流</b>: 经 {@link PacingBuffer} 每 {@code pacingMs} 吐一帧, 不能尽快发。</li>
 *   <li><b>"机器人还在说话"不再靠估算</b>: 浏览器版要用 {@code playbackEndsAtMs} 推算前端播放进度,
 *       这里 {@code !pacing.isEmpty()} 就是精确答案。打断也退化成一次 {@link PacingBuffer#clear()}。</li>
 *   <li><b>回合结束后何时回到聆听是精确的</b>: 浏览器版要起一个"等前端播完"的定时器(定早了打断窗口
 *       提前关闭, 就是"说话打不断"的老根因); 这里等缓冲排空即可, 不存在估算误差。</li>
 * </ol>
 *
 * <p>本类<b>不自己持有定时线程</b>: {@link #tick()} 是公开的, {@link #start()} 只是把它挂到
 * {@code Flux.interval} 上。单测可以直接手动步进, 不需要真等 20ms。
 *
 * <p>线程模型: 上行音频(网络线程)、节流(定时线程)、回合收尾(reactor 线程)会并发触碰 VAD 与回合状态,
 * 故触碰共享状态的方法一律 {@code synchronized}(与 {@code Connection} 同构)。
 * 向 {@link CallLeg#writeAudio} 的写出放在锁外, 避免网络 IO 卡住整路会话。
 */
public final class CallSession {

    private static final Logger log = LoggerFactory.getLogger(CallSession.class);

    private final CallLeg leg;
    private final ConversationSession conversation;
    private final HandsFreeVad vad;
    private final PacingBuffer pacing;
    private final CallConfig cfg;
    private final int mediaRate;

    /** 预合成开场白(已是线路采样率的 PCM); 为空则接通后直接进入聆听 */
    private final byte[] greeting;
    /** 预合成的兜底话术: 回合出错且一个字都没播出去时顶上, 别让客户对着一片安静 */
    private final byte[] errorPrompt;
    /** 空闲时补发的静音帧; 接入层不需要连续媒体时为 null */
    private final byte[] silenceFrame;

    private final AtomicLong seq = new AtomicLong();
    private final long startedAtMs = System.currentTimeMillis();

    private Disposable inboundSub;
    private Disposable eventSub;
    private Disposable ticker;

    private volatile boolean answered;
    private volatile boolean closed;
    /** AI 说完这句就挂机(由 end_call 工具置位) —— 必须等缓冲播完, 否则客户听不到告别语 */
    private volatile boolean hangupAfterPlayback;
    /** AI 说完这句就转人工(由 transfer_to_human 置位, 值是拨号串) —— 同样要等"请稍等"播完 */
    private volatile String transferAfterPlayback;
    /**
     * 转人工进行到哪了。{@link Transfer#RINGING}: 正在呼坐席, 客户这一路还在我们手里, 给他放回铃音、不听上行;
     * {@link Transfer#CONNECTED}: 已接给坐席, 不再出声、不计单通时长(真人聊多久是他们的事), 只等挂机。
     * 坐席没接时 {@link CallEvent.Type#TRANSFER_FAILED} 把状态放回 {@link Transfer#NONE}, AI 接着聊。
     */
    private enum Transfer { NONE, RINGING, CONNECTED }

    private Transfer transfer = Transfer.NONE;
    /** 回铃音放到第几帧了(按节流拍数计, 决定"响 1 秒停 4 秒"的节奏) */
    private int ringbackFrame;
    /** 转人工没接通时说的话(预合成); null = 不说, 直接回到聆听 */
    private volatile byte[] transferFailedPrompt;
    /** 通话结束时的回调(事后摘要); 默认不做事 */
    private volatile Consumer<EndedCall> onEnded = ended -> { };

    /** 回合代号: 每开启一轮 +1, 打断时也 +1。只有"当前代号"的下行音频会进缓冲。 */
    private volatile long epoch;
    /** >=0 表示该代号的回合已产完, 等缓冲排空后回到聆听; -1 表示无待处理。 */
    private volatile long resumeEpoch = -1;

    private Sinks.Many<AudioFrame> turnSink;
    private Disposable turnSubscription;
    /** 本轮有没有真的播出过音频; 决定出错时要不要顶一句兜底话术 */
    private boolean turnProducedAudio;

    /** 接通以来已经收到多少毫秒的上行音频; 接通保护期按它计(音频时长, 可确定性单测) */
    private long inboundSinceAnswerMs;
    /** 开场白还要等几拍才放(按节流拍数计, 可确定性单测); 0 = 已放或不需要 */
    private int greetingTicksLeft;
    /** 本轮最近一次识别中间转写: 判停时拿它提前发起知识库检索, 不等最终文本 */
    private volatile String lastInterimText = "";
    /** 线路信号音检测(忙音/拨号音); 配置关掉时为 null */
    private final LineToneDetector toneDetector;
    /** 客户此刻是否处在"正在说话"(VAD 已判开口、还没判说完)的阶段 */
    private boolean userSpeaking;
    /** 本次"说话"已经持续了多少毫秒的音频。按音频时长计而不是墙钟: 可确定性单测, 媒体断流也不会误计 */
    private long speakingMs;
    /** 本次"说话"期间识别有没有出过哪怕一个字(中间结果也算); 由识别线程写, 故 volatile */
    private volatile boolean asrTextThisUtterance;

    /**
     * @param vadConfig VAD 阈值。<b>务必用电话专用的一组</b>: 浏览器那套是按 48k 麦克风调的,
     *                  窄带 + 线路底噪下的电平分布完全不同
     * @param detector  逐帧人声打分器(Silero 或能量法), 每路通话一个实例(Silero 的 RNN 状态不可共享)
     * @param greeting  预合成开场白, 已是线路采样率的 PCM; null 则接通后直接聆听
     */
    public CallSession(CallLeg leg, ConversationSession conversation, VadConfig vadConfig,
                       VoiceActivityDetector detector, CallConfig cfg, byte[] greeting) {
        this(leg, conversation, vadConfig, detector, cfg, greeting, null);
    }

    /**
     * @param errorPrompt 预合成兜底话术(线路采样率 PCM)。回合彻底失败时播它 —— 厂商熔断、密钥过期、
     *                    网络抖动这类事故在电话里的表现都是"AI 突然不吭声", 客户只会以为断线了就挂断,
     *                    连重说一遍的机会都没有。null 则维持原样(静默)。
     */
    public CallSession(CallLeg leg, ConversationSession conversation, VadConfig vadConfig,
                       VoiceActivityDetector detector, CallConfig cfg, byte[] greeting,
                       byte[] errorPrompt) {
        this.leg = leg;
        this.conversation = conversation;
        this.cfg = cfg == null ? CallConfig.defaults() : cfg;
        this.mediaRate = leg.sampleRate();
        this.pacing = new PacingBuffer(mediaRate, this.cfg.pacingMs(), this.cfg.maxBufferedMs());
        this.greeting = greeting;
        this.errorPrompt = errorPrompt;
        this.toneDetector = this.cfg.toneHangup() ? new LineToneDetector(mediaRate) : null;
        // 只为一件事监听识别: "这段声音里到底有没有字"。忙音之外的线路噪声靠它兜底, 见 onInboundAudio
        conversation.setTurnListener(new TurnListener() {
            @Override
            public void onAsrPartial(String text) {
                if (text != null && !text.isBlank()) {
                    asrTextThisUtterance = true;
                    lastInterimText = text;
                    vad.setInterimText(text);   // 语义判停: 据"说完了没"动态调句尾静音阈值
                }
            }

            @Override
            public void onAsrFinal(String text) {
                if (text != null && !text.isBlank()) {
                    asrTextThisUtterance = true;
                }
            }
        });
        this.silenceFrame = leg.needsContinuousMedia() ? new byte[pacing.frameBytes()] : null;
        this.vad = new HandsFreeVad(vadConfig, vadListener(), detector);
    }

    /** VAD 决策回调 —— 接线方式与浏览器版逐条对应。 */
    private HandsFreeVad.Listener vadListener() {
        return new HandsFreeVad.Listener() {
            @Override
            public void onSpeechStart() {
                ensureTurnStarted();
            }

            @Override
            public void onAudio(byte[] pcm16le) {
                emitFrame(pcm16le);
            }

            @Override
            public void onSpeechEnd() {
                // 同浏览器链路: 体感延迟从客户闭嘴那一刻起算, 判停等待的静音要减掉
                conversation.markUserSpeechEnd(
                        System.currentTimeMillis() - vad.lastEndpointSilenceMs(),
                        vad.lastEndpointSilenceMs(), vad.lastEndpointReason());
                commitTurn();
            }

            @Override
            public void onBargeIn() {
                log.info("[{}] 打断: 客户插话", leg.callId());
                bargeIn();
            }
        };
    }

    /**
     * 注册"通话结束"回调: 收尾时把对话快照交出去做事后摘要。
     *
     * <p>为什么在收尾那一刻给快照, 而不是让回调自己去查库: 这时内存里就有完整对话, 而
     * {@code conversation.close()} 之后就只剩数据库里的行了, 还得等落库线程写完。
     */
    public void onEnded(Consumer<EndedCall> handler) {
        this.onEnded = handler == null ? ended -> { } : handler;
    }

    /** 转人工没接通时说的话, 已是线路采样率的 PCM(预合成) */
    public void transferFailedPrompt(byte[] pcm) {
        this.transferFailedPrompt = pcm;
    }

    /** 订阅媒体与信令, 并起节流器。生产入口。 */
    public void start() {
        attach();
        ticker = Flux.interval(Duration.ofMillis(cfg.pacingMs()), Schedulers.parallel())
                .subscribe(t -> tick());
    }

    /**
     * 只订阅媒体与信令, <b>不起节流器</b> —— 由调用方自己驱动 {@link #tick()}。
     * 单测用它做确定性步进, 不必真等 20ms 一拍。
     */
    public void attach() {
        eventSub = leg.events().subscribe(this::onEvent,
                err -> {
                    log.warn("[{}] 信令流异常: {}", leg.callId(), err.toString());
                    close("signaling-error");
                });
        inboundSub = leg.inboundAudio().subscribe(this::onInboundAudio,
                err -> {
                    log.warn("[{}] 上行媒体异常: {}", leg.callId(), err.toString());
                    close("media-error");
                });
    }

    // ---- 信令 ----

    private void onEvent(CallEvent event) {
        switch (event.type()) {
            case ANSWERED -> onAnswered();
            case HANGUP -> close(event.detail() == null ? "peer-hangup" : event.detail());
            case DTMF -> log.info("[{}] DTMF: {}", leg.callId(), event.detail());
            case TRANSFER_FAILED -> onTransferFailed(event.detail());
            case TRANSFER_CONNECTED -> onTransferConnected();
            // 早期媒体(彩铃/运营商提示音)不进对话: 人还没接, 跑 ASR/LLM/TTS 是纯烧钱
            case EARLY_MEDIA, RINGING -> log.debug("[{}] 信令: {}", leg.callId(), event.type());
        }
    }

    /**
     * 真接通。开场白是<b>预合成</b>的, 直接灌进缓冲即可出声 —— 外呼接通后前 3 秒是挂机高发区,
     * 走一遍 LLM+TTS 的首包延迟在这里是致命的, 而开场白文本本来就是固定的。
     */
    private synchronized void onAnswered() {
        if (answered || closed) {
            return;
        }
        answered = true;
        if (greeting != null && greeting.length > 0) {
            // 不立刻放: 摘机后头几百毫秒对方那边的语音通道还没建好, 先发的会被吞。按节流拍数倒数。
            greetingTicksLeft = cfg.greetingDelayMs() > 0
                    ? Math.max(1, cfg.greetingDelayMs() / Math.max(1, cfg.pacingMs())) : 0;
            if (greetingTicksLeft == 0) {
                pacing.offer(greeting);
            }
        }
        vad.start(mediaRate);
        log.info("[{}] 接通, 开场白 {}ms{}", leg.callId(),
                greeting == null ? 0 : greeting.length * 500 / mediaRate,
                greetingTicksLeft > 0 ? ", 延迟 " + cfg.greetingDelayMs() + "ms 再放" : "");
    }

    // ---- 上行 ----

    /** 上行音频。未接通前一律丢弃(见 {@link CallEvent.Type#EARLY_MEDIA})。 */
    private synchronized void onInboundAudio(byte[] pcm) {
        if (!answered || closed || transfer != Transfer.NONE) {
            return;
        }
        // 接通保护期: 摘机瞬间线上有个冲击脉冲, 响度时长都够得上"开口", 会把开场白当成被插话清掉。
        // 这段时间开场白本来就在播, 挡掉它的代价几乎为零。
        if (inboundSinceAnswerMs < cfg.answerGuardMs()) {
            inboundSinceAnswerMs += Math.max(1, pcm.length * 500L / mediaRate);
            return;
        }
        // 第一道: 听出忙音就挂。模拟线没有挂机信令, 客户挂断后线上只是开始放忙音, 网关漏检时
        // 这通电话会被忙音一直"撑"着 —— VAD 把循环的忙音当成有人在不停说话, 永远等不到句尾。
        if (toneDetector != null && toneDetector.accept(pcm)) {
            log.warn("[{}] 线上是信号音(忙音/拨号音), 对端已挂断而网关没拆线, 主动挂机。"
                    + "根治要在网关上开忙音检测(HT813: Enable PSTN Disconnect Tone Detection)", leg.callId());
            close("line-tone");
            return;
        }
        vad.accept(pcm, botPlaying());
        // 第二道: 450Hz 之外的噪声(别的制式的信号音、传真音、串线)。判据是"声音一直有、字一个没有"。
        if (userSpeaking && cfg.noSpeechHangupMs() > 0) {
            speakingMs += Math.max(1, pcm.length * 500L / mediaRate);
            if (speakingMs > cfg.noSpeechHangupMs() && !asrTextThisUtterance) {
                log.warn("[{}] 连续 {}ms 有声音却一个字都没识别出来, 判定为线路噪声, 主动挂机",
                        leg.callId(), speakingMs);
                close("no-speech");
            }
        }
    }

    // ---- 回合(与 Connection 同构) ----

    private synchronized void ensureTurnStarted() {
        if (turnSubscription != null) {
            return;
        }
        // 客户开口就掐掉还在播的音频。正常回合走到这里时缓冲本就是空的; 唯一有内容的情况是
        // 客户在开场白播放中途就插话 —— 那时 VAD 还在 AWAIT, 不会触发 onBargeIn, 得在这里兜住。
        if (cfg.greetingBargeIn() && !pacing.isEmpty()) {
            pacing.clear();
        }
        resumeEpoch = -1;
        seq.set(0);
        turnProducedAudio = false;
        userSpeaking = true;
        speakingMs = 0;
        asrTextThisUtterance = false;
        lastInterimText = "";
        final long myEpoch = ++epoch;
        turnSink = Sinks.many().unicast().onBackpressureBuffer();
        turnSubscription = conversation.handleUserTurn(turnSink.asFlux())
                .subscribe(chunk -> onDownlink(chunk, myEpoch),
                        err -> onTurnFinished(myEpoch, err),
                        () -> onTurnFinished(myEpoch, null));
    }

    private synchronized void emitFrame(byte[] pcm16le) {
        if (turnSink != null) {
            turnSink.tryEmitNext(AudioFrame.of(pcm16le, seq.getAndIncrement(), System.currentTimeMillis()));
        }
    }

    /** 客户说完: 补一帧 endOfSpeech 并结束上行流, 触发 ASR 出 final。 */
    private synchronized void commitTurn() {
        userSpeaking = false;   // VAD 判了句尾: 这次"说话"正常结束, 无字计时停表
        if (turnSink == null) {
            return;
        }
        // 判停那一刻就用中间转写去查知识库, 与识别收尾并行: 最终文本多半与它只差标点,
        // 检索(一次向量接口调用, 100~200ms)就不用等最终文本回来才开始
        conversation.prefetchKnowledge(lastInterimText);
        lastInterimText = "";
        turnSink.tryEmitNext(AudioFrame.endOfSpeech(seq.getAndIncrement(), System.currentTimeMillis()));
        turnSink.tryEmitComplete();
    }

    /**
     * 下行音频块 → 降采样到线路速率 → 进节流缓冲。
     * {@code chunkEpoch} 不等于当前代号说明这轮已被打断, 残留块一律丢弃(即便上游 TTS 取消有延迟)。
     */
    private synchronized void onDownlink(AudioChunk chunk, long chunkEpoch) {
        if (chunkEpoch != epoch || closed) {
            return;
        }
        byte[] data = chunk.data();
        if (data == null || data.length == 0) {
            return;   // 收尾空块
        }
        turnProducedAudio = true;
        pacing.offer(toMediaRate(data));
    }

    private synchronized void bargeIn() {
        // 先翻代号, 再取消上游 —— 顺序不能反: conversation.bargeIn() 会同步触发旧轮收尾,
        // 代号没先翻的话 onTurnFinished 的守卫挡不住, 会把刚开始的新一轮误判成"已结束"。
        epoch++;
        resumeEpoch = -1;
        pacing.clear();          // 立刻停声, 不依赖对端配合
        conversation.bargeIn();
        if (turnSubscription != null) {
            turnSubscription.dispose();
        }
        resetTurn();
    }

    /** 回合产完/出错。运行在 reactor 线程, 故加锁。 */
    private synchronized void onTurnFinished(long turnEpoch, Throwable err) {
        if (turnEpoch != epoch) {
            return;   // 旧轮的收尾信号(已被打断/换轮), 忽略
        }
        if (err != null) {
            log.warn("[{}] 回合出错: {}", leg.callId(), err.toString());
            // 一个字都没说出去就失败了 = 客户听到的是一片安静, 顶一句兜底话术请他再说一遍。
            // 已经播过一部分再出错的不补, 免得话说到一半突然插进来一句莫名其妙的道歉。
            if (!turnProducedAudio && !closed && errorPrompt != null && errorPrompt.length > 0) {
                pacing.offer(errorPrompt);
            }
        }
        resetTurn();
        // 不在这里 resumeListening: 缓冲里通常还压着好几秒没播的音频, 那段时间 VAD 必须留在 WAIT
        // 才能被客户插话打断。改为等 tick() 发现缓冲排空后再回到聆听。
        if (vad.isActive()) {
            resumeEpoch = epoch;
        }
    }

    private synchronized void resetTurn() {
        turnSink = null;
        turnSubscription = null;
        userSpeaking = false;
    }

    // ---- 下行节流 ----

    /**
     * 节流一拍: 取一帧发给对端。缓冲空且本轮已产完时, 顺带把 VAD 放回"等你开口"。
     * 公开是为了单测能手动步进, 不必真等定时器。
     */
    public void tick() {
        byte[] frame;
        String transferTo = null;
        synchronized (this) {
            if (closed || transfer == Transfer.CONNECTED) {
                return;
            }
            if (transfer == Transfer.RINGING) {
                frame = ringbackFrame();   // 坐席振铃期间给客户放回铃音, 别让他以为断线了
            } else {
                frame = nextFrame();
                if (closed) {
                    return;
                }
                // 与挂机同理: "好的, 我帮您转接"播完才去呼坐席
                if (frame == null && transferAfterPlayback != null && turnSubscription == null) {
                    transferTo = transferAfterPlayback;
                    transferAfterPlayback = null;
                    transfer = Transfer.RINGING;
                    ringbackFrame = 0;
                }
                // 要转接时停在这里: VAD 保持"机器人说话中", 转接失败后说完兜底话术再回到聆听
                if (frame == null && transferTo == null && resumeEpoch >= 0 && resumeEpoch == epoch) {
                    resumeEpoch = -1;
                    vad.resumeListening();   // 已播完, 现在回到聆听才不会关掉打断窗口
                }
                if (frame == null && transferTo == null && answered && silenceFrame != null) {
                    frame = silenceFrame;    // 没话说也保持 RTP 连续, 见 CallLeg#needsContinuousMedia
                }
            }
        }
        if (transferTo != null) {
            startTransfer(transferTo);   // 网络 IO 放锁外
            return;
        }
        if (frame != null) {
            leg.writeAudio(frame);   // 网络 IO 放锁外
        }
    }

    /** 正常对话时的一拍: 单通时长、开场白倒计时、取缓冲、播完挂机。调用方持锁; 可能已 close */
    private byte[] nextFrame() {
        if (cfg.maxCallSeconds() > 0
                && System.currentTimeMillis() - startedAtMs > cfg.maxCallSeconds() * 1000L) {
            log.info("[{}] 达单通时长上限, 挂机", leg.callId());
            close("max-duration");
            return null;
        }
        if (greetingTicksLeft > 0 && --greetingTicksLeft == 0 && turnSubscription == null) {
            pacing.offer(greeting);   // 客户要是在等待期就开口了(turnSubscription != null), 开场白就不放了
        }
        byte[] frame = pacing.nextFrame();
        // 必须同时满足"本轮已产完"与"缓冲已排空"。只看缓冲是不够的: 工具是在回合<b>进行中</b>
        // 置的位, 那一刻告别语还没合成出来、缓冲本来就是空的, 只看缓冲会立刻挂断, 客户一个字都听不到。
        if (frame == null && hangupAfterPlayback && turnSubscription == null) {
            log.info("[{}] 告别语已播完, 按 AI 的判断挂机", leg.callId());
            close("agent-ended");
            return null;
        }
        return frame;
    }

    private void startTransfer(String dialString) {
        log.info("[{}] 确认语已播完, 转人工: {}", leg.callId(), dialString);
        if (!leg.transfer(dialString)) {
            onTransferFailed("send-failed");
        }
    }

    /**
     * 坐席没接通, 通话回到 AI: 说一句"同事没接到, 留个称呼我让他们回电", 播完回到聆听。
     * 对话历史里 AI 说过"帮您转接", 客户接下来报称呼和需求, 模型会顺着去调留资。
     */
    private synchronized void onTransferFailed(String cause) {
        if (transfer == Transfer.NONE || closed) {
            return;
        }
        transfer = Transfer.NONE;
        log.warn("[{}] 转人工没接通({}), 回到 AI 接待", leg.callId(), cause);
        byte[] prompt = transferFailedPrompt;
        if (prompt != null && prompt.length > 0) {
            pacing.offer(prompt);
        }
        if (vad.isActive()) {
            resumeEpoch = epoch;   // 说完再回到聆听, 期间客户开口照样能打断
        }
    }

    /**
     * 说完当前这段就挂机。给 {@code end_call} 这类"AI 判断该结束了"的工具用:
     * 直接挂会把告别语掐掉, 所以只置位, 由 {@link #tick()} 在<b>本轮产完且缓冲排空</b>后执行。
     */
    public void hangupAfterPlayback() {
        hangupAfterPlayback = true;
    }

    private synchronized void onTransferConnected() {
        if (transfer == Transfer.RINGING && !closed) {
            transfer = Transfer.CONNECTED;
            log.info("[{}] 已接给坐席, 之后的对话不再经过 AI", leg.callId());
        }
    }

    /** 国内回铃音: 450Hz, 响 1 秒停 4 秒。电平取约 -12dBFS, 与开场白响度相当而不刺耳 */
    private byte[] ringbackFrame() {
        int frameBytes = pacing.frameBytes();
        int samples = frameBytes / 2;
        int framesPerCycle = 5000 / Math.max(1, cfg.pacingMs());
        int onFrames = 1000 / Math.max(1, cfg.pacingMs());
        int idx = ringbackFrame++;
        byte[] pcm = new byte[frameBytes];
        if (idx % framesPerCycle >= onFrames) {
            return pcm;
        }
        double amp = 0.25 * 32767;
        long base = (long) idx * samples;
        for (int i = 0; i < samples; i++) {
            short v = (short) (amp * Math.sin(2 * Math.PI * 450 * (base + i) / mediaRate));
            pcm[2 * i] = (byte) (v & 0xff);
            pcm[2 * i + 1] = (byte) ((v >> 8) & 0xff);
        }
        return pcm;
    }

    /**
     * 说完当前这段就转人工。给 {@code transfer_to_human} 用: 确认语是工具返回之后才生成、合成的,
     * 立刻桥接的话客户一个字都听不到, 只听见突然响起的回铃音。
     */
    public void transferAfterPlayback(String dialString) {
        if (dialString != null && !dialString.isBlank()) {
            transferAfterPlayback = dialString;
        }
    }

    /** 机器人此刻是否还在出声 —— 缓冲里还有没有货就是精确答案, 不用估算。 */
    private boolean botPlaying() {
        return !pacing.isEmpty();
    }

    // ---- 收尾 ----

    public synchronized void close(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        int durationSec = (int) ((System.currentTimeMillis() - startedAtMs) / 1000);
        log.info("[{}] 通话结束: {} (时长 {}s)", leg.callId(), reason, durationSec);
        // 先把对话快照交给事后处理, 再关会话 —— 关掉之后 historyView 就没了
        try {
            onEnded.accept(new EndedCall(leg.callId(), leg.peerNumber(), leg.calledNumber(),
                    durationSec, reason, conversation.historyView()));
        } catch (RuntimeException e) {
            // 事后处理不能影响收尾: 录音落库、资源释放都还在后面
            log.warn("[{}] 触发通话事后处理失败: {}", leg.callId(), e.toString());
        }
        dispose(ticker);
        dispose(inboundSub);
        dispose(eventSub);
        dispose(turnSubscription);
        pacing.clear();
        vad.stop();
        conversation.close();
        leg.hangup(reason);
    }

    private static void dispose(Disposable d) {
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
    }

    private byte[] toMediaRate(byte[] pcm) {
        if (cfg.ttsSampleRate() == mediaRate) {
            return pcm;
        }
        return PcmAudio.encodeLe(PcmAudio.resample(PcmAudio.decodeLe(pcm), cfg.ttsSampleRate(), mediaRate));
    }

    // ---- 诊断 ----

    public boolean isAnswered() {
        return answered;
    }

    public boolean isClosed() {
        return closed;
    }

    public int pendingPlaybackMs() {
        return pacing.bufferedMs();
    }
}
