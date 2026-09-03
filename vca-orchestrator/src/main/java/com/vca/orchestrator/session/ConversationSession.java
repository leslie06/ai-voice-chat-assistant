package com.vca.orchestrator.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vca.domain.enums.SessionState;
import com.vca.domain.enums.VendorType;
import com.vca.domain.model.AsrEvent;
import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.AudioFrame;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.LlmEvent;
import com.vca.domain.model.Message;
import com.vca.domain.model.S2sConfig;
import com.vca.domain.model.SessionContext;
import com.vca.domain.model.ToolCall;
import com.vca.domain.model.TtsConfig;
import com.vca.domain.model.S2sEvent;
import com.vca.domain.spi.AsrProvider;
import com.vca.domain.spi.LlmProvider;
import com.vca.domain.spi.S2sProvider;
import com.vca.domain.spi.S2sSession;
import com.vca.domain.spi.TtsProvider;
import com.vca.orchestrator.agent.AgentPlan;
import com.vca.orchestrator.agent.AgentPlanner;
import com.vca.orchestrator.agent.AgentPrompts;
import com.vca.orchestrator.agent.AgentReflection;
import com.vca.orchestrator.agent.AgentRun;
import com.vca.orchestrator.agent.AgentStep;
import com.vca.orchestrator.agent.AgentTriage;
import com.vca.orchestrator.knowledge.KnowledgeStore;
import com.vca.orchestrator.memory.MemoryStore;
import com.vca.orchestrator.search.WebSearchHeuristic;
import com.vca.orchestrator.search.WebSearchProvider;
import com.vca.orchestrator.metrics.TurnMetrics;
import com.vca.orchestrator.recorder.ConversationRecorder;
import com.vca.orchestrator.recorder.TurnRecord;
import com.vca.orchestrator.pipeline.SentenceSplitter;
import com.vca.orchestrator.skill.MusicIntent;
import com.vca.orchestrator.skill.VolumeIntent;
import com.vca.orchestrator.skill.PlayMusicSkill;
import com.vca.orchestrator.skill.RememberSkill;
import com.vca.orchestrator.skill.Skill;
import com.vca.orchestrator.skill.SkillRegistry;
import com.vca.orchestrator.skill.SkillResult;
import com.vca.orchestrator.statemachine.ConversationStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** 单回合内 LLM↔工具的最大往返轮数, 防止模型反复调工具不收口导致死循环。 */
    private static final int MAX_TOOL_ROUNDS = 4;

    /** Agent 逐步执行: 计划步骤最多执行这么多步(规划器已 cap 6, 这里再兜一道)。 */
    private static final int MAX_AGENT_STEPS = 6;

    /** Agent 反思后最多再补做这么多额外步(有界重规划, 防模型自我无限延长)。 */
    private static final int MAX_EXTRA_AGENT_STEPS = 2;

    /** Agent 单回合墙钟预算(ms): 超时即停止再开新步, 用已收集信息直接整合答复(绝不空手而归/卡死)。 */
    private static final long AGENT_DEADLINE_MS = 30_000;

    /** Agent 单回合工具调用总数预算: 跨所有步骤累计, 用尽后不再执行工具, 让模型用已有信息作答。 */
    private static final int MAX_AGENT_TOOL_CALLS = 12;

    private static final TypeReference<Map<String, Object>> ARGS_TYPE = new TypeReference<>() {
    };
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SessionContext context;
    private final AsrProvider asr;
    private final LlmProvider llm;
    private final TtsProvider tts;
    private final S2sProvider s2s;
    private final SentenceSplitter splitter;
    private final MusicIntent musicIntent = new MusicIntent();
    /** function-calling 技能目录; 空时退回普通文本对话(不下发 tools)。 */
    private final SkillRegistry skills;
    /** 多步任务规划器(无状态); 仅 {@link #agentEnabled} 且命中 {@link AgentTriage} 的复杂回合才用。 */
    private final AgentPlanner planner = new AgentPlanner();

    private final ConversationStateMachine stateMachine = new ConversationStateMachine();
    private final List<Message> history = new ArrayList<>();
    /** 历史滑动窗口: 仅保留最近这么多条非 system 消息, 防止历史无限膨胀诱导模型复述旧回复 */
    private final int maxHistoryMessages;
    /** 当前回合的打断信号; 每个回合一个, barge-in 时触发以取消该回合 */
    private final AtomicReference<Sinks.One<Void>> currentInterrupt = new AtomicReference<>();
    /** 回合事件回调(字幕透传), 默认空实现 */
    private volatile TurnListener listener = TurnListener.NOOP;
    /** 对话存档端口(数据飞轮), 默认不落库; 异步、失败不影响对话 */
    private volatile ConversationRecorder recorder = ConversationRecorder.NOOP;
    /** 长期记忆端口, 默认不记忆; 登录用户才有 userId */
    private volatile MemoryStore memory = MemoryStore.NOOP;
    /** 知识库检索端口(RAG), 默认空; 每回合据用户问题自动召回相关资料注入上下文(不依赖模型自调工具) */
    private volatile KnowledgeStore knowledge = KnowledgeStore.NOOP;
    /** 联网搜索端口, 默认空; 据用户问题时效性自动联网检索并注入(不依赖模型自调工具)。不分用户。 */
    private volatile WebSearchProvider webSearch = WebSearchProvider.NOOP;
    /** 自动注入式联网搜索开关与每次取的条数(由接入层配置注入)。 */
    private volatile boolean webSearchAuto = true;
    private volatile int webSearchCount = 5;
    /**
     * 多步 Agent 规划开关(默认关)。开启后, 命中 {@link AgentTriage} 的复杂回合会先让模型出一份分步计划,
     * 注入工具回合循环引导逐步执行; 未命中的回合按原路零延迟走。规划失败静默退回普通回合。
     */
    private volatile boolean agentEnabled = false;
    /** 当前登录用户 id(账号系统启用且已登录时非空); 用于长期记忆/知识库按用户隔离 */
    private volatile String userId;
    /**
     * 待附加图片(data URL): 前端发图后暂存于此, 由<b>下一个</b>走 LLM 的回合(打字/三段式语音)取走,
     * 作为该轮用户消息的图片。取走即清空; 一图一轮。
     */
    private final AtomicReference<String> pendingImage = new AtomicReference<>();
    /**
     * 视觉模型(多模态): 回合上下文含图片时该轮自动改用它(需支持 OpenAI 兼容 image_url)。
     * {@code visionModel} 为空 = 不切换, 带图回合沿用当前对话模型。
     */
    private volatile VendorType visionVendor;
    private volatile String visionModel;
    /** 本会话回合序号(落库用, 从 1 递增) */
    private final AtomicInteger turnSeq = new AtomicInteger();
    /**
     * 用户<b>真正闭嘴</b>的墙钟时刻(ms), 由接入层在 VAD 判停时写入(= 判停时刻 - 实际等待的句尾静音);
     * 0 表示本回合不是语音输入(打字)。体感延迟的起点只能是它 —— 从 ASR final 起表会漏掉最大的一段。
     */
    private final java.util.concurrent.atomic.AtomicLong userSpeechEndAtMs =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 前端播放器是否有当前曲目(迷你播放器开着), 由接入层按 music_state 上报同步。
     * 控歌命令的<b>总开关</b>: 没有当前曲目时"下一首""继续"这些话多半是正常对话的一部分, 不该被当成命令吞掉。
     */
    private volatile boolean musicActive;
    /** 当前曲目是否正在响(相对于已暂停)。区分它才能让"暂停"与"继续"各自只在有意义的状态下生效。 */
    private volatile boolean musicPlaying;
    /**
     * 最近一次识别到的用户文本。S2S(每轮/持久)路径里"用户说了什么"与"机器人回了什么"是<b>分离的异步事件</b>,
     * 落库需要把二者配成一轮 —— 故在用户转写到达时暂存, 机器人回复收尾时取它配对。三段式 {@code respond}
     * 直接有 userText 参数, 不读它。
     */
    private volatile String lastUserText;
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

    /**
     * 被打断的助手消息落历史时附加的标记。加它是因为模型看到的是<b>文本</b>, 而"这句只说了一半"
     * 是纯粹的时序事实, 文本本身带不出来 —— 不标注, 模型会以为自己已经完整表达过, 用户下一句
     * "你刚说的那个"它就接不上, 或者把没说完的部分当成已说过而不再重复。
     *
     * <p>用中文括注而不是自造符号(如 {@code <cut/>}): 模型对自然语言注释的理解比对私有标记稳,
     * 且这段文本可能进入任意厂商的模型, 不能指望它们认同一套约定。
     */
    private static final String INTERRUPTED_SUFFIX = "（这段回复被用户打断了，用户多半只听到了开头一部分）";

    /**
     * 本回合助手已经"说出口"的文本。存在的唯一目的是<b>兜住打断</b>: 正常收尾走 {@code doOnComplete}
     * 落历史, 而打断走的是 {@code takeUntilOther} 的 cancel, {@code doOnComplete} 根本不触发 ——
     * 没有它, 被打断的半句会整段蒸发。
     *
     * <p><b>累计粒度按回合类型分</b>, 这是准确性的关键:
     * <ul>
     *   <li>语音回合累计<b>交给 TTS 合成的句子</b>。不能用 LLM 的 token —— 模型生成远快于语音播放,
     *       一段五句话的回复往往在第一句还没播完时就整段生成好了, 按 token 记会把"说过的话"
     *       夸大好几倍, 模型下一轮就会以为四句没说的话已经说过。</li>
     *   <li>打字回合没有 TTS, 字幕即所见, 按 token 累计就是准的。</li>
     * </ul>
     *
     * <p><b>语音回合拿到的仍是上界, 不是精确值</b>, 这是刻意接受的: 分句流被 TTS 的 concatMap
     * 按 prefetch 提前拉取, 前端还有一段播放缓冲会在打断时冲掉。要做到精确, 得把 TTS 调用改成
     * 一句一次、在首个音频块回来时才记账 —— 那会让 {@code QwenTtsProvider} 退化成一句一条连接,
     * 丢掉流式输入(它正是延迟最低的那条路), 为了历史里几个字的精度不值当。
     *
     * <p>所以标记({@link #INTERRUPTED_SUFFIX})的措辞只说"多半只听到开头一部分", 不报具体位置 ——
     * 宁可让模型知道自己不确定, 也不能给它一个精确但错误的边界。
     *
     * <p>会话内回合串行(同一时刻只有一个 {@code currentInterrupt}), 一个会话共用一份即可;
     * 增量可能来自不同线程, 用自身做锁。
     */
    private final StringBuilder spokenThisTurn = new StringBuilder();

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
        this(context, asr, llm, tts, s2s, splitter, maxHistoryMessages, metrics, SkillRegistry.empty());
    }

    public ConversationSession(SessionContext context,
                               AsrProvider asr, LlmProvider llm, TtsProvider tts, S2sProvider s2s,
                               SentenceSplitter splitter, int maxHistoryMessages, TurnMetrics metrics,
                               SkillRegistry skills) {
        this.context = context;
        this.asr = asr;
        this.llm = llm;
        this.tts = tts;
        this.s2s = s2s;
        this.splitter = splitter;
        this.skills = skills == null ? SkillRegistry.empty() : skills;
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

    /** 设置长期记忆端口与当前登录用户(账号系统启用且已登录时); 未设则不记忆。 */
    public void setMemory(MemoryStore memory, String userId) {
        this.memory = memory == null ? MemoryStore.NOOP : memory;
        this.userId = userId;
    }

    /** 设置知识库检索端口(RAG); 未设则不检索。userId 由 {@link #setMemory} 一并设置(同一登录用户)。 */
    public void setKnowledge(KnowledgeStore knowledge) {
        this.knowledge = knowledge == null ? KnowledgeStore.NOOP : knowledge;
    }

    /** 设置联网搜索端口及自动注入参数; 未设则不联网。联网信息非个人数据, 不分用户。 */
    public void setWebSearch(WebSearchProvider webSearch, boolean auto, int count) {
        this.webSearch = webSearch == null ? WebSearchProvider.NOOP : webSearch;
        this.webSearchAuto = auto;
        this.webSearchCount = count > 0 ? count : 5;
    }

    /** 开/关多步 Agent 规划路径; 未设默认关(所有回合按原路走)。 */
    public void setAgentEnabled(boolean enabled) {
        this.agentEnabled = enabled;
    }

    /** 设置视觉模型(多模态); {@code model} 为空则带图回合不切换模型。 */
    public void setVisionModel(VendorType vendor, String model) {
        this.visionVendor = vendor;
        this.visionModel = model == null || model.isBlank() ? null : model;
    }

    /**
     * 附加一张图片(data URL)到<b>下一个</b>走 LLM 的回合(打字/三段式语音均可)。
     * 前端发图即调; 覆盖式暂存(连发多张取最后一张), 回合取走即清。
     */
    public void attachImage(String imageDataUrl) {
        if (imageDataUrl != null && !imageDataUrl.isBlank()) {
            pendingImage.set(imageDataUrl);
        }
    }

    /** 带图回合的 LLM 配置: 配置了视觉模型则切换厂商+模型(沿用人设/采样参数), 否则原配置。 */
    private LlmConfig llmConfigFor(boolean vision) {
        LlmConfig base = activeLlmConfig;
        if (!vision || visionModel == null || base == null) {
            return base;
        }
        return new LlmConfig(
                visionVendor != null ? visionVendor : base.vendor(),
                visionModel, base.systemPrompt(), base.temperature(), base.maxTokens());
    }

    /**
     * 据当前问题的时效性自动联网检索, 命中则拼成一条 system 注入本回合(否则返回 null)。
     * 自动注入式: 不依赖模型自调 web_search 工具, 凡命中时效启发式就直接搜并喂结果; 普通闲聊不触发, 不产生调用。
     */
    /**
     * 组装本回合喂给模型的<b>工作消息列表</b>: [当前时间] + [长期记忆] + [知识库 RAG] + [联网检索] + 历史快照。
     * 时间/日期是廉价上下文, 直接给比靠模型调工具更可靠 —— 模型据此直接答对, 无需也无延迟。
     *
     * <p><b>为什么是异步的</b>: 记忆召回与知识库检索都要先发一次 embedding HTTP 再查库, 联网要发一次搜索
     * HTTP —— 三者都是阻塞调用({@code Embedder} 的契约明写"只应在 boundedElastic / 后台 worker 上调用")。
     * 此前它们在 {@code respond()} 里被<b>同步串行</b>调用, 而 respond 跑在订阅线程上(打字回合是 WebSocket
     * 的 netty 事件循环, 语音回合是 ASR 回调线程), 于是有两个问题叠加:
     * <ol>
     *   <li><b>阻塞了事件循环</b>: 一次慢检索会连坐同一事件循环线程上的其它所有会话(它们的音频收发一起卡住);</li>
     *   <li><b>三次网络往返串成一条</b>, 原样叠加进首字延迟 —— 这是语音助手最不该付的延迟。</li>
     * </ol>
     *
     * <p>这里改成三路各自 {@code subscribeOn(boundedElastic)} <b>并行</b>发起, 只等最慢的一路;
     * 注入顺序仍固定为 时间→记忆→知识库→联网, 模型看到的提示词结构不变。三路彼此独立, 任一路
     * 返回 null(未启用/无命中/失败降级)即跳过, 与改造前语义完全一致。
     *
     * <p>没启用的那几路不做线程切换(就地跑, 立即返回 null), 避免为纯闲聊白付一次调度开销。
     */
    private Mono<List<Message>> assembleContext(String userText) {
        boolean memBlocking = memory != MemoryStore.NOOP && userId != null;
        boolean kbBlocking = knowledge != KnowledgeStore.NOOP && userId != null
                && userText != null && !userText.isBlank();
        boolean webBlocking = webSearchAuto && webSearch != WebSearchProvider.NOOP
                && WebSearchHeuristic.isTimeSensitive(userText);

        Mono<Optional<String>> mem = offload(memBlocking, () -> memoryContext(userText));
        Mono<Optional<String>> kb = offload(kbBlocking, () -> knowledgeContext(userText));
        Mono<Optional<String>> web = offload(webBlocking, () -> webSearchContext(userText));

        return Mono.zip(mem, kb, web).map(ctx -> {
            List<Message> working = new ArrayList<>();
            working.add(Message.system(currentTimeContext()));
            ctx.getT1().ifPresent(m -> working.add(Message.system(m)));   // 长期记忆: 让助手记得用户(跨会话)
            ctx.getT2().ifPresent(m -> working.add(Message.system(m)));   // RAG: 自动检索知识库并注入
            ctx.getT3().ifPresent(m -> working.add(Message.system(m)));   // 联网: 时效性问题自动检索并注入
            working.addAll(historySnapshot());
            return working;
        });
    }

    /**
     * 把一次可能阻塞的外部检索包成 Mono。{@code blocking=false}(该能力未启用)时就地执行 —— 此时被包的方法
     * 只做几个判空就返回 null, 没有 IO, 不值得切线程。返回 null 统一映射成 {@code Optional.empty()}。
     */
    private static Mono<Optional<String>> offload(boolean blocking, Supplier<String> fetch) {
        if (!blocking) {
            return Mono.fromSupplier(() -> Optional.ofNullable(fetch.get()));
        }
        return Mono.fromCallable(() -> Optional.ofNullable(fetch.get()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String webSearchContext(String query) {
        if (!webSearchAuto || webSearch == WebSearchProvider.NOOP
                || !WebSearchHeuristic.isTimeSensitive(query)) {
            log.info("联网自动注入: 跳过(auto={}, provider={}, 时效命中={}), query={}",
                    webSearchAuto, webSearch == WebSearchProvider.NOOP ? "NOOP" : "ON",
                    WebSearchHeuristic.isTimeSensitive(query), query);
            return null;
        }
        List<WebSearchProvider.Result> hits;
        try {
            hits = webSearch.search(query, webSearchCount);
        } catch (Exception e) {
            log.warn("联网搜索失败(忽略): {}", e.toString());
            return null;
        }
        if (hits == null || hits.isEmpty()) {
            log.info("联网自动注入: 命中时效但博查无结果, query={}", query);
            return null;
        }
        log.info("联网自动注入: 命中, 来源 {} 条, 已透传前端, query={}", hits.size(), query);
        // 把来源透传给前端展示(在模型据此作答之前), 让用户看到答案出处
        final List<WebSearchProvider.Result> sources = hits;
        safeNotify(() -> listener.onWebSearchSources(sources));
        StringBuilder sb = new StringBuilder(
                "以下是刚刚联网检索到的实时信息, 回答时<b>以此为准</b>(可注明来源/时间, 不要编造):");
        for (WebSearchProvider.Result r : hits) {
            if (r == null) {
                continue;
            }
            sb.append("\n- ");
            if (r.title() != null && !r.title().isBlank()) {
                sb.append(r.title().strip()).append(": ");
            }
            if (r.snippet() != null) {
                sb.append(r.snippet().strip());
            }
            if (r.date() != null && !r.date().isBlank()) {
                sb.append(" (").append(r.date().strip()).append(")");
            }
            if (r.url() != null && !r.url().isBlank()) {
                sb.append(" 来源: ").append(r.url().strip());
            }
        }
        return sb.toString();
    }

    /**
     * 据当前问题自动检索知识库, 命中则拼成一条 system 上下文注入本回合(没命中/未登录返回 null)。
     * 这是<b>自动注入</b>式 RAG: 不依赖模型自己决定调 search_knowledge 工具, 凡问题能召回到相关资料就直接喂给它,
     * 召回为空(阈值过滤)时不注入、不影响闲聊。条数/阈值由 {@link KnowledgeStore#search} 实现决定。
     */
    private String knowledgeContext(String query) {
        if (knowledge == KnowledgeStore.NOOP || userId == null || query == null || query.isBlank()) {
            return null;
        }
        List<String> hits;
        try {
            hits = knowledge.search(userId, query);
        } catch (Exception e) {
            log.debug("知识库检索失败(忽略): {}", e.toString());
            return null;
        }
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(
                "从用户知识库检索到以下相关资料, 回答时<b>优先据此作答</b>(与你的预设不一致时以资料为准):");
        for (String h : hits) {
            if (h != null && !h.isBlank()) {
                sb.append("\n- ").append(h.strip());
            }
        }
        return sb.toString();
    }

    /**
     * 把该用户的长期记忆拼成一条 system 上下文(没有记忆/未登录则返回 null), 每回合注入,
     * 让助手据此"记得"用户。条数已由 {@link MemoryStore#recall} 截断。
     */
    private String memoryContext(String query) {
        if (memory == MemoryStore.NOOP || userId == null) {
            return null;
        }
        List<String> mems;
        try {
            mems = memory.recall(userId, query);
        } catch (Exception e) {
            log.debug("读取长期记忆失败(忽略): {}", e.toString());
            return null;
        }
        if (mems == null || mems.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("关于当前用户你已知道的(长期记忆, 自然地运用, 不要生硬复述):");
        for (String m : mems) {
            if (m != null && !m.isBlank()) {
                sb.append("\n- ").append(m.strip());
            }
        }
        return sb.toString();
    }

    /**
     * 接入层在 VAD 判停时调用, 交出体感延迟的起点与本轮判停依据。
     *
     * @param speechEndAtMs     用户真正闭嘴的墙钟时刻(判停时刻 - 实际等待的静音)
     * @param endpointSilenceMs 本轮实际等待的句尾静音
     * @param endpointReason    判停依据: complete/incomplete/neutral/fixed
     */
    public void markUserSpeechEnd(long speechEndAtMs, int endpointSilenceMs, String endpointReason) {
        userSpeechEndAtMs.set(speechEndAtMs);
        metrics.recordEndpointSilence(Duration.ofMillis(Math.max(0, endpointSilenceMs)), endpointReason);
    }

    /**
     * 同步前端播放状态; 仅影响控歌命令是否生效。
     *
     * @param active  是否有当前曲目(迷你播放器开着)
     * @param playing 当前曲目是否正在响(暂停时为 false)
     */
    public void setMusicState(boolean active, boolean playing) {
        this.musicActive = active;
        this.musicPlaying = playing && active;
    }

    /**
     * 某个控歌命令在当前播放状态下是否成立。把"句式"与"状态"分开判, 是为了让每条规则都有说得出的理由:
     * <ul>
     *   <li><b>没有当前曲目</b> → 一律不认。"上一首""继续"这些话在没放歌时几乎都是正常对话的一部分;</li>
     *   <li><b>继续</b> → 只在<b>已暂停</b>时认。"继续"是日常高频词("继续说""继续讲"), 音乐正响着时
     *       用户说"继续"几乎不可能是指音乐, 那种情况必须放给模型;</li>
     *   <li><b>暂停</b> → 只在<b>正在响</b>时认。已经停了还说"暂停", 更可能是在讲别的事;</li>
     *   <li>上一首/下一首/停止播放 → 有曲目即可(暂停状态下切歌也是合理操作)。</li>
     * </ul>
     */
    private boolean musicControlAllowed(String action) {
        if (!musicActive) {
            return false;
        }
        return switch (action) {
            case MusicIntent.CONTROL_RESUME -> !musicPlaying;
            case MusicIntent.CONTROL_PAUSE -> musicPlaying;
            default -> true;
        };
    }

    /** 设置对话存档端口(数据飞轮); 不设则不落库。 */
    public void setRecorder(ConversationRecorder recorder) {
        this.recorder = recorder == null ? ConversationRecorder.NOOP : recorder;
    }

    /**
     * 把一轮对话交给 recorder 异步落库。<b>对热路径透明</b>: 未启用落库({@link ConversationRecorder#NOOP})
     * 时直接返回; 提交本身只入队、不阻塞; 任何异常就地吞掉, 绝不影响正在进行的对话。
     */
    private void recordTurn(String userText, String assistantText, String mode, String outcome, Long totalMs) {
        recordTurn(userText, assistantText, mode, outcome, totalMs, null, null);
    }

    private void recordTurn(String userText, String assistantText, String mode, String outcome, Long totalMs,
                            Integer agentSteps, Integer agentReplans) {
        ConversationRecorder r = recorder;
        if (r == ConversationRecorder.NOOP) {
            return;
        }
        try {
            r.recordTurn(new TurnRecord(context.sessionId(), turnSeq.incrementAndGet(),
                    mode, userText, assistantText, Instant.now(), totalMs, outcome, agentSteps, agentReplans));
        } catch (Exception e) {
            log.debug("对话落库提交失败(忽略, 不影响对话): {}", e.toString());
        }
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
                : respond(text.trim(), false, false);
        return finishTurn(turn, interrupt);
    }

    /** 开启一轮: 建新的打断信号并转入 LISTENING */
    private Sinks.One<Void> beginTurn() {
        takeSpokenThisTurn();   // 上一回合若异常收尾有残留, 不能算到这一回合头上
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

    /** 三段式: ASR(中间结果透传给语义端点判定, 取 final) → 交给 {@link #respond} 走 LLM → 分句 → TTS */
    private Flux<AudioChunk> pipelineTurn(Flux<AudioFrame> userAudio) {
        return asr.transcribe(userAudio, context.asrConfig())
                // 旁路中间转写给接入层做语义端点判定(自适应断句); 不影响主链路
                .doOnNext(ev -> {
                    if (!ev.isFinal() && !ev.isBlank()) {
                        safeNotify(() -> listener.onAsrPartial(ev.text()));
                    }
                })
                .filter(AsrEvent::isFinal)
                .next()                                  // 取本轮最终识别结果
                .filter(ev -> !ev.isBlank())
                .flatMapMany(ev -> {
                    log.debug("ASR final: {}", ev.text());
                    return respond(ev.text(), true, true);
                });
    }

    /**
     * 拿到"本轮用户说了什么"之后的<b>统一</b>回复链路: 写历史 → (正则点歌快路径) → function-calling
     * 工具回合循环 → 分句 → TTS。语音与打字共用同一条逻辑, 仅出口不同:
     * <ul>
     *   <li>{@code speak=true}(语音): 文本经分句送 TTS, 返回可播放的音频块;</li>
     *   <li>{@code speak=false}(打字): 只把文本流式回传(经 listener), 不合成语音, 音频流恒空。</li>
     * </ul>
     *
     * @param notifyAsr 是否把 {@code userText} 当作识别结果回传前端(语音用; 打字前端已本地回显故关)
     */
    private Flux<AudioChunk> respond(String userText, boolean speak, boolean notifyAsr) {
        return Flux.defer(() -> {
            long startNanos = System.nanoTime();
            AtomicBoolean firstToken = new AtomicBoolean(false);
            AtomicBoolean firstAudio = new AtomicBoolean(false);
            // 终结回合时要落历史/念出的最终答复(普通文本答复); 动作型(点歌)不留历史, 不用它
            AtomicReference<String> reply = new AtomicReference<>(null);
            // 本回合是否触发了"动作型"工具(点歌等)。动作是副作用而非对话内容: 触发后本轮用户/助手都不进历史,
            // 否则模型会从历史里 few-shot 出"音乐请求→直接出确认文字"而跳过工具调用(已实测复现)。
            AtomicBoolean actionTurn = new AtomicBoolean(false);
            // 多步 Agent 运行态(预算+统计); 仅 agent 分支创建, 收尾时据它落 agentSteps/agentReplans
            AtomicReference<AgentRun> agentRunRef = new AtomicReference<>();

            // 控歌命令(下一首/上一首/暂停/继续/停止): 必须排在点歌解析之前 ——
            // "我想听下一首"两边都能沾边, 先到先得。句式命中后还要过状态门闸, 不成立就原样落到普通对话。
            Optional<String> control = musicIntent.parseControl(userText)
                    .filter(this::musicControlAllowed);
            if (control.isPresent()) {
                final String action = control.get();
                return silentActionTurn("控歌命令", action, userText, notifyAsr, startNanos,
                        () -> listener.onMusicRequest(action, ""));
            }

            // 音量命令: 不设状态门闸 —— 没在放歌时"声音大点"指的是助手说话的音量, 同样成立
            Optional<String> volume = VolumeIntent.parse(userText);
            if (volume.isPresent()) {
                final String direction = volume.get();
                return silentActionTurn("音量命令", direction, userText, notifyAsr, startNanos,
                        () -> listener.onVolumeRequest(direction));
            }

            // 取走待附加图片(有则本轮为带图回合); 点歌快路径不吃图, 留给下一个 LLM 回合
            Optional<String> song = musicIntent.parsePlay(userText);
            String image = song.isPresent() ? null : pendingImage.getAndSet(null);
            Message userMsg = Message.user(userText, image);
            appendHistory(userMsg);
            if (notifyAsr) {
                safeNotify(() -> listener.onAsrFinal(userText));
            }

            Flux<AudioChunk> body;
            // 正则点歌快路径: 明确点歌零延迟直达, 不经 LLM/工具往返(模糊表达才由模型调 play_music 技能)
            if (song.isPresent()) {
                body = musicTurn(song.get(), speak, actionTurn);
            } else {
                stateMachine.tryTransition(SessionState.THINKING);
                // 外部上下文(记忆/知识库/联网)三路并行装配, 且不在订阅线程上阻塞 —— 见 assembleContext。
                body = assembleContext(userText).flatMapMany(working -> {
                    // 视觉多模态: 只要上下文窗口内还有图片(本轮新图或此前几轮的图, 支持追问), 本轮就切视觉模型;
                    // 图片随历史滑窗滑出后自然回到普通文本模型。
                    boolean vision = working.stream().anyMatch(Message::hasImage);
                    LlmConfig turnCfg = llmConfigFor(vision);
                    if (vision) {
                        log.info("带图回合: 改用视觉模型 vendor={}, model={}, session={}",
                                turnCfg == null ? "-" : turnCfg.vendor(),
                                turnCfg == null ? "-" : turnCfg.model(), context.sessionId());
                    }
                    // 多步 Agent: 命中复杂任务先让模型出一份分步计划, 再逐步执行(步骤间口播进度)+反思补步+整合答复;
                    // 未开启/未命中/规划为空都按原路直接进 runLlmRound(零额外延迟)。带图回合不走 Agent
                    // (规划/反思用的常规模型读不了图, 视觉多步任务留作后续)。
                    if (!vision && agentEnabled && !skills.isEmpty() && AgentTriage.isComplex(userText)) {
                        return planner.plan(llm, working, activeLlmConfig)
                                .flatMapMany(plan -> {
                                    if (plan.isEmpty()) {
                                        return runLlmRound(working, 0, turnCfg, speak, reply, actionTurn,
                                                firstToken, firstAudio, startNanos);
                                    }
                                    log.info("Agent 规划: {} 步, session={}", plan.steps().size(), context.sessionId());
                                    safeNotify(() -> listener.onAgentPlan(plan.descriptions()));
                                    AgentRun run = new AgentRun(
                                            System.nanoTime() + Duration.ofMillis(AGENT_DEADLINE_MS).toNanos(),
                                            MAX_AGENT_TOOL_CALLS);
                                    agentRunRef.set(run);
                                    return runAgent(working, plan, run, speak, reply, firstToken, firstAudio,
                                            startNanos);
                                });
                    }
                    return runLlmRound(working, 0, turnCfg, speak, reply, actionTurn,
                            firstToken, firstAudio, startNanos);
                });
            }

            return body
                    .doOnComplete(() -> {
                        String r = reply.get();
                        if (r != null && !r.isBlank()) {
                            appendHistory(Message.assistant(r));
                            safeNotify(() -> listener.onAssistantText(r));
                        }
                    })
                    .doFinally(sig -> {
                        // 本回合助手说出口的内容; 取走即清空, 无论如何收尾都不能留给下一回合
                        String spoken = takeSpokenThisTurn();
                        String archived = reply.get();
                        // 动作型回合不留对话痕迹: 撤掉本轮先行写入的用户消息(助手侧本就没写)
                        if (actionTurn.get()) {
                            removeFromHistory(userMsg);
                        } else if (sig == reactor.core.publisher.SignalType.CANCEL) {
                            // 打断走 cancel, 上面的 doOnComplete 不触发 —— 不在这里补, 助手这半句会整段蒸发,
                            // 模型下一轮完全不知道自己开过口(用户说"你刚说的那个"就接不上)。
                            // 优先用实际逐字播出去的 spoken; 若答复是整段一次性合成的(终结型技能), 它才是用户听的内容。
                            String partial = !spoken.isBlank() ? spoken.strip()
                                    : (archived == null ? "" : archived.strip());
                            if (!partial.isEmpty()) {
                                archived = partial;
                                appendHistory(Message.assistant(partial + INTERRUPTED_SUFFIX));
                            }
                        }
                        userSpeechEndAtMs.set(0);   // 本轮没出音频也要清, 免得这次闭嘴被算进下一轮
                        Duration total = elapsed(startNanos);
                        metrics.recordTurnTotal(total);
                        metrics.countTurn(speak ? "voice" : "text", outcomeOf(sig));
                        // 落库: 动作型回合(点歌)reply 为空, 仍存用户那句作为档案; agent 回合附带步数/反思补步数
                        // 被打断的回合存的是"用户实际听到的那部分"(不含上面的标记 —— 标记是给模型的, 不是语料)
                        AgentRun run = agentRunRef.get();
                        Integer aSteps = run == null ? null : run.stepsExecuted();
                        Integer aReplans = run == null ? null : run.replans();
                        recordTurn(userText, archived, mode.name(), outcomeOf(sig),
                                total.toMillis(), aSteps, aReplans);
                    });
        });
    }

    /**
     * function-calling 工具回合循环的一轮。订阅一次 LLM: 文本增量实时回传/送 TTS(打字机/句子级流水线),
     * 同时收集本轮的工具调用。该轮流结束时:
     * <ul>
     *   <li>无工具调用 → 本轮文本即最终答复, 记入 {@code reply}, 结束;</li>
     *   <li>有工具调用 → 执行技能, 据结果决定终结(动作型)或回灌后再起下一轮(数据型)。</li>
     * </ul>
     * 工具回合(round 1)通常无文本, 故乐观地把本轮文本接进 TTS 不会误播; 真正的口语答复出现在
     * 工具执行后的下一轮。{@code depth} 超过 {@link #MAX_TOOL_ROUNDS} 即兜底终止。
     */
    private Flux<AudioChunk> runLlmRound(List<Message> working, int depth, LlmConfig cfg, boolean speak,
                                         AtomicReference<String> reply, AtomicBoolean actionTurn,
                                         AtomicBoolean firstToken, AtomicBoolean firstAudio, long startNanos) {
        if (depth >= MAX_TOOL_ROUNDS) {
            log.warn("工具回合超过上限 {}, 终止本轮, session={}", MAX_TOOL_ROUNDS, context.sessionId());
            return Flux.empty();
        }
        List<ToolCall> calls = Collections.synchronizedList(new ArrayList<>());
        StringBuilder roundText = new StringBuilder();

        if (depth == 0 && !skills.isEmpty()) {
            log.info("本回合下发工具 {} 个给模型, session={}", skills.toolSpecs().size(), context.sessionId());
        }
        // LLM 事件流: 文本增量累计成本轮文本并实时回传字幕; 工具调用收集起来留到流末处理。
        Flux<String> tokens = llm.chat(working, cfg, skills.toolSpecs())
                .concatMap(ev -> {
                    if (ev instanceof LlmEvent.TextDelta td && !td.text().isEmpty()) {
                        if (firstToken.compareAndSet(false, true)) {
                            Duration ttft = elapsed(startNanos);
                            metrics.recordLlmFirstToken(ttft);
                            logLlmFirstToken(speak ? "voice" : "text", cfg, ttft);
                        }
                        roundText.append(td.text());
                        emitAssistantDelta(td.text(), !speak);
                        return Flux.just(td.text());
                    }
                    if (ev instanceof LlmEvent.ToolCalls tc) {
                        calls.addAll(tc.calls());
                    }
                    return Flux.empty();
                });

        Flux<AudioChunk> speech = speak
                // doOnNext 挂在分句流上而不是 token 流上: 这里的每一句都是真的被送去合成了,
                // 是服务端能拿到的、离"用户听到"最近的一个信号(见 spokenThisTurn 的说明)。
                ? tts.synthesize(splitter.split(tokens).doOnNext(this::noteSpoken), activeTtsConfig)
                        .doOnNext(chunk -> {
                            if (firstAudio.compareAndSet(false, true)) {
                                Duration ttfa = elapsed(startNanos);
                                metrics.recordTtsFirstAudio(ttfa);
                                recordPerceivedFirstAudio();
                                log.info("TTS 首音频耗时: {} ms, session={}", ttfa.toMillis(), context.sessionId());
                                stateMachine.tryTransition(SessionState.SPEAKING);
                            }
                        })
                : tokens.then(Mono.<AudioChunk>empty()).flux();   // 打字: 仅消费 token 做字幕, 不出音频

        return speech.concatWith(Flux.defer(() -> {
            if (calls.isEmpty()) {
                if (roundText.length() > 0) {
                    reply.set(roundText.toString());   // 本轮无工具 → 这轮文本即最终答复
                }
                return Flux.empty();
            }
            return executeToolsAndContinue(working, List.copyOf(calls), depth, cfg, speak,
                    reply, actionTurn, firstToken, firstAudio, startNanos);
        }));
    }

    /**
     * 执行本轮模型发起的工具调用并决定走向。先把"模型发起调用"的 assistant 消息追加到工作列表(协议要求),
     * 再逐个执行技能:
     * <ul>
     *   <li>命中<b>动作型</b>(终结)结果: 下发客户端动作 + 念确认语, 结束本回合(不再问模型);</li>
     *   <li>全是<b>数据型</b>结果: 把各结果作为 tool 消息回灌, 起下一轮 LLM 让它据此作答。</li>
     * </ul>
     */
    private Flux<AudioChunk> executeToolsAndContinue(List<Message> working, List<ToolCall> calls, int depth,
                                                     LlmConfig cfg, boolean speak, AtomicReference<String> reply,
                                                     AtomicBoolean actionTurn, AtomicBoolean firstToken,
                                                     AtomicBoolean firstAudio, long startNanos) {
        working.add(Message.assistantToolCalls(calls));
        return Flux.fromIterable(calls)
                .concatMap(call -> runSkill(call).map(res -> new ToolOutcome(call, res)))
                .collectList()
                .flatMapMany(outcomes -> {
                    // 联网搜索等技能带回的来源: 透传给前端展示(在模型据此作答之前)。先于 terminal 分支处理,
                    // 避免同回合混有动作型工具时被提前 return 跳过。
                    for (ToolOutcome o : outcomes) {
                        List<WebSearchProvider.Result> src = o.result().sources();
                        if (src != null && !src.isEmpty()) {
                            safeNotify(() -> listener.onWebSearchSources(src));
                        }
                    }
                    for (ToolOutcome o : outcomes) {
                        SkillResult r = o.result();
                        if (r.terminal()) {
                            if (r.actionType() != null) {
                                // 动作型: 下发动作并念确认语(经 listener 显示字幕)。标记本轮为动作回合 →
                                // 用户/助手都不进历史(reply 不设), 杜绝模型从历史仿写确认语而跳过工具。
                                actionTurn.set(true);
                                dispatchAction(r.actionType(), r.actionPayload());
                                if (r.content() != null && !r.content().isBlank()) {
                                    safeNotify(() -> listener.onAssistantText(r.content()));
                                }
                            } else {
                                // 纯答复型: 正常进历史(经 reply, 由 doOnComplete 落历史 + 字幕)
                                reply.set(r.content());
                            }
                            if (speak && r.content() != null && !r.content().isBlank()) {
                                stateMachine.tryTransition(SessionState.THINKING);
                                return tts.synthesize(Flux.just(r.content()), activeTtsConfig)
                                        .doOnNext(chunk -> {
                                            if (firstAudio.compareAndSet(false, true)) {
                                                stateMachine.tryTransition(SessionState.SPEAKING);
                                            }
                                        });
                            }
                            return Flux.<AudioChunk>empty();
                        }
                    }
                    for (ToolOutcome o : outcomes) {
                        working.add(Message.tool(o.call().id(), o.result().content()));
                    }
                    return runLlmRound(working, depth + 1, cfg, speak, reply, actionTurn,
                            firstToken, firstAudio, startNanos);
                });
    }

    /**
     * 多步 Agent 执行(P2): 按计划<b>逐步推进</b> → 反思补步(有界) → 整合最终答复。每步独立一次 LLM(可调工具),
     * 结果累进 scratchpad 供后续步骤与整合引用; 步骤间口播过渡语(语音回合)让用户听到进度、不至于静默假死。
     * 整条流挂在 {@link #finishTurn} 的 {@code takeUntilOther} 下, 用户打断即整体取消。最终答复由整合环节
     * 经 {@code reply} 落历史(动作不在此终结), 故 Agent 回合在历史里就是普通的 user→assistant 一轮。
     */
    private Flux<AudioChunk> runAgent(List<Message> base, AgentPlan plan, AgentRun run, boolean speak,
                                     AtomicReference<String> reply,
                                     AtomicBoolean firstToken, AtomicBoolean firstAudio, long startNanos) {
        List<AgentStep> steps = plan.steps();
        int planned = Math.min(steps.size(), MAX_AGENT_STEPS);
        List<String> scratchpad = Collections.synchronizedList(new ArrayList<>());

        Flux<AudioChunk> plannedFlux = Flux.empty();
        for (int i = 0; i < planned; i++) {
            final int idx = i;
            final AgentStep step = steps.get(i);
            // 墙钟预算: 超时则跳过剩余步骤, 直接用已收集信息整合(绝不空手而归/卡死)
            plannedFlux = plannedFlux.concatWith(Flux.defer(() -> {
                if (run.outOfTime()) {
                    run.markCapped();
                    log.warn("Agent 墙钟超时, 跳过剩余步骤直接整合, session={}", context.sessionId());
                    return Flux.<AudioChunk>empty();
                }
                return runAgentStep(base, idx, planned, step.description(), scratchpad, run, speak);
            }));
        }

        // 反思补步(有界): 计划跑完后自检是否还差关键一步, 命中则补做, 最多 MAX_EXTRA_AGENT_STEPS 步。
        Flux<AudioChunk> reflectFlux = Flux.defer(() ->
                runAgentReflection(base, planned, scratchpad, run, speak, 0));

        // 整合: 据各步结果给用户最终口语答复(走普通 speaking 回合, 设 reply 落历史)。
        Flux<AudioChunk> synthFlux = Flux.defer(() -> {
            List<Message> sw = new ArrayList<>(base);
            sw.add(Message.system(AgentPrompts.synthesisInstruction(scratchpad)));
            return runLlmRound(sw, 0, activeLlmConfig, speak, reply, new AtomicBoolean(false),
                    firstToken, firstAudio, startNanos);
        });

        return plannedFlux.concatWith(reflectFlux).concatWith(synthFlux);
    }

    /**
     * 执行 Agent 的一步: 透传进度(onAgentStep)+ 语音回合口播过渡语 → 据当前步指令跑一次工具回合(静默, 不出音频)
     * → 把这一步结论累进 scratchpad。{@code index} 从 0 起; {@code total} 用于口播连接词("第一步/接下来/最后")。
     */
    private Flux<AudioChunk> runAgentStep(List<Message> base, int index, int total, String description,
                                         List<String> scratchpad, AgentRun run, boolean speak) {
        run.stepStarted();
        safeNotify(() -> listener.onAgentStep(index, description));
        Flux<AudioChunk> narration = !speak ? Flux.empty() : Flux.defer(() -> {
            stateMachine.tryTransition(SessionState.SPEAKING);
            return tts.synthesize(Flux.just(AgentPrompts.narration(index, total, description)), activeTtsConfig);
        });
        Mono<Void> exec = Mono.defer(() -> {
            List<Message> sw = new ArrayList<>(base);
            sw.add(Message.system(AgentPrompts.stepInstruction(
                    index + 1, total, description, new ArrayList<>(scratchpad))));
            return agentLlmRound(sw, 0, run)
                    .doOnNext(res -> scratchpad.add(AgentPrompts.scratchpadEntry(index + 1, description, res)))
                    .then();
        });
        return narration.concatWith(exec.thenMany(Flux.empty()));
    }

    /** Agent 反思: 自检 scratchpad 是否足够; 不足且未超额度/未超时则补做一步再递归自检, 否则结束(交给整合)。 */
    private Flux<AudioChunk> runAgentReflection(List<Message> base, int nextIndex, List<String> scratchpad,
                                                AgentRun run, boolean speak, int extraDone) {
        if (extraDone >= MAX_EXTRA_AGENT_STEPS || run.outOfTime()) {
            return Flux.empty();
        }
        List<Message> rw = new ArrayList<>(base);
        rw.add(Message.system(AgentPrompts.reflectInstruction(new ArrayList<>(scratchpad))));
        return collectText(llm.chat(rw, activeLlmConfig, List.of()))
                .map(AgentReflection::parse)
                .flatMapMany(r -> {
                    if (r.done()) {
                        return Flux.<AudioChunk>empty();
                    }
                    run.replanned();
                    int idx = nextIndex + extraDone;
                    log.info("Agent 反思补步 #{}: {}, session={}", extraDone + 1, r.next(), context.sessionId());
                    return runAgentStep(base, idx, idx + 1, r.next(), scratchpad, run, speak)
                            .concatWith(Flux.defer(() ->
                                    runAgentReflection(base, nextIndex, scratchpad, run, speak, extraDone + 1)));
                });
    }

    /**
     * Agent 的一轮工具回合(返回这一步的文本结论, 不出音频)。与 {@link #runLlmRound} 的差异: 不流式送 TTS、
     * 不区分动作/数据终结 —— 动作型工具照常下发动作、但结果一律当数据回灌, 让 Agent 继续推进而非中途终结。
     * 工具往返上限同 {@link #MAX_TOOL_ROUNDS}。
     */
    private Mono<String> agentLlmRound(List<Message> working, int depth, AgentRun run) {
        if (depth >= MAX_TOOL_ROUNDS) {
            return Mono.just("");
        }
        List<ToolCall> calls = Collections.synchronizedList(new ArrayList<>());
        StringBuilder text = new StringBuilder();
        return llm.chat(working, activeLlmConfig, skills.toolSpecs())
                .doOnNext(ev -> {
                    if (ev instanceof LlmEvent.TextDelta td) {
                        text.append(td.text());
                    } else if (ev instanceof LlmEvent.ToolCalls tc) {
                        calls.addAll(tc.calls());
                    }
                })
                .then(Mono.defer(() -> {
                    if (calls.isEmpty()) {
                        return Mono.just(text.toString());
                    }
                    // 工具调用总数预算: 额度不够本轮的工具就不执行了, 返回已有文本让模型用现有信息收尾
                    if (!run.tryUseToolCalls(calls.size())) {
                        log.warn("Agent 工具调用额度用尽, 跳过本步剩余工具, session={}", context.sessionId());
                        return Mono.just(text.toString());
                    }
                    working.add(Message.assistantToolCalls(List.copyOf(calls)));
                    return Flux.fromIterable(calls)
                            .concatMap(call -> runSkill(call).map(res -> new ToolOutcome(call, res)))
                            .doOnNext(o -> {
                                List<WebSearchProvider.Result> src = o.result().sources();
                                if (src != null && !src.isEmpty()) {
                                    safeNotify(() -> listener.onWebSearchSources(src));
                                }
                                if (o.result().terminal() && o.result().actionType() != null) {
                                    dispatchAction(o.result().actionType(), o.result().actionPayload());
                                }
                                working.add(Message.tool(o.call().id(), o.result().content()));
                            })
                            .then(Mono.defer(() -> agentLlmRound(working, depth + 1, run)));
                }));
    }

    /** 收集一条 LLM 事件流里的全部文本(忽略工具事件), 用于反思等只取文本的轻量调用。 */
    private Mono<String> collectText(Flux<LlmEvent> events) {
        return events.filter(ev -> ev instanceof LlmEvent.TextDelta)
                .map(ev -> ((LlmEvent.TextDelta) ev).text())
                .collect(StringBuilder::new, StringBuilder::append)
                .map(StringBuilder::toString);
    }

    /** 执行一次工具调用: 找技能 → 解析参数 → 执行; 未知工具/执行异常都兜成一条回灌结果, 不打断回合。 */
    private Mono<SkillResult> runSkill(ToolCall call) {
        log.info("工具调用: name={}, args={}, session={}", call.name(), call.arguments(), context.sessionId());
        Skill skill = skills.find(call.name()).orElse(null);
        if (skill == null) {
            return Mono.just(SkillResult.feedback("未知工具: " + call.name()));
        }
        Map<String, Object> args = new java.util.HashMap<>(parseArgs(call.arguments()));
        if (userId != null) {
            args.put(RememberSkill.USER_ID_ARG, userId);   // 注入当前用户, 供长期记忆等需用户态的技能用
        }
        return Mono.defer(() -> skill.execute(args))
                .onErrorResume(e -> {
                    log.warn("工具 {} 执行失败: {}", call.name(), e.toString());
                    return Mono.just(SkillResult.feedback("工具执行失败: " + e.getMessage()));
                });
    }

    /** 下发动作型技能产生的客户端动作。目前已知类型: 点歌(走与正则快路径相同的 onMusicRequest 通道)。 */
    private void dispatchAction(String type, Map<String, Object> payload) {
        Map<String, Object> p = payload == null ? Map.of() : payload;
        if (PlayMusicSkill.ACTION_TYPE.equals(type)) {
            String action = String.valueOf(p.getOrDefault("action", "play"));
            Object q = p.get("query");
            safeNotify(() -> listener.onMusicRequest(action, q == null ? "" : q.toString()));
        } else {
            log.warn("未知客户端动作类型: {}, session={}", type, context.sessionId());
        }
    }

    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> m = JSON.readValue(json, ARGS_TYPE);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            log.debug("工具参数解析失败, 当作空参: {}", json);
            return Map.of();
        }
    }

    /** 一次工具调用与其执行结果的配对(回合循环内部用)。 */
    private record ToolOutcome(ToolCall call, SkillResult result) {
    }

	    /** 自起点到现在的耗时 */
    /**
     * 记一次体感延迟: 用户闭嘴 → 第一帧音频。语音回合才有(打字回合 userSpeechEndAtMs 为 0)。
     * 取走即清零, 避免同一次闭嘴被后续回合重复计入。
     */
    private void recordPerceivedFirstAudio() {
        long endedAt = userSpeechEndAtMs.getAndSet(0);
        if (endedAt > 0) {
            metrics.recordPerceivedFirstAudio(
                    Duration.ofMillis(Math.max(0, System.currentTimeMillis() - endedAt)));
        }
    }

	    private static Duration elapsed(long startNanos) {
	        return Duration.ofNanos(System.nanoTime() - startNanos);
	    }

	    private void logLlmFirstToken(String mode, LlmConfig cfg, Duration ttft) {
	        String vendor = cfg == null || cfg.vendor() == null ? "-" : cfg.vendor().code();
	        String model = cfg == null || cfg.model() == null || cfg.model().isBlank() ? "-" : cfg.model();
	        log.info("LLM 首 token 耗时: {} ms, session={}, mode={}, vendor={}, model={}",
	                ttft.toMillis(), context.sessionId(), mode, vendor, model);
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
     * 点歌回合: 通知接入层让前端去 QQ 音乐播放, 并给一句确认。
     * 不走 LLM —— 点歌是确定性动作。{@code speak=true}(语音回合)时用 TTS 念确认语,
     * {@code speak=false}(文字回合)则只把动作交给前端(歌曲卡片即反馈), 不发声。
     *
     * @param query      想听的歌(歌名/歌手)
     * @param speak      是否合成语音确认
     * @param actionTurn 置位标记本轮为动作回合, 由调用方在收尾时撤掉本轮用户消息(动作不留对话历史)
     */
    /**
     * 静默动作回合(控歌、调音量): 只给前端下发一个动作, <b>不合成语音、不写历史</b>。
     *
     * <p>不念确认语是有意的: 这些命令的效果<b>本身就是反馈</b> —— 下一首起播、音量变大, 用户当场就听见了。
     * 而且歌还在响时 TTS 会盖在音乐上(两条播放通道各走各的), 再念一句"好的"只是噪音和延迟。
     *
     * <p>不写历史同 {@link #musicTurn}: 这是副作用而非对话内容, 留在历史里会诱导模型仿写确认语。
     */
    private Flux<AudioChunk> silentActionTurn(String label, String action, String userText,
                                              boolean notifyAsr, long startNanos, Runnable dispatch) {
        log.info("{}命中: action={}, 原句={}, session={}", label, action, userText, context.sessionId());
        if (notifyAsr) {
            safeNotify(() -> listener.onAsrFinal(userText));   // 字幕仍要显示用户这句
        }
        safeNotify(dispatch);
        return Flux.<AudioChunk>empty()
                .doFinally(sig -> {
                    Duration total = elapsed(startNanos);
                    metrics.recordTurnTotal(total);
                    metrics.countTurn("voice", outcomeOf(sig));
                    recordTurn(userText, null, mode.name(), outcomeOf(sig), total.toMillis());
                });
    }

    private Flux<AudioChunk> musicTurn(String query, boolean speak, AtomicBoolean actionTurn) {
        // 动作回合: 不写助手历史; 调用方据 actionTurn 撤掉用户那句, 整轮不留痕(避免模型仿写确认语跳过工具)
        actionTurn.set(true);
        String spoken = "好的，为您播放音乐" + query;
        // 动作: 让前端打开 QQ 音乐(具体 URL 由接入层按厂商拼装, 编排层不感知)
        safeNotify(() -> listener.onMusicRequest("play", query));
        if (!speak) {
            return Flux.empty();
        }
        stateMachine.tryTransition(SessionState.THINKING);
        return tts.synthesize(Flux.just(spoken), activeTtsConfig)
                .doOnNext(chunk -> stateMachine.tryTransition(SessionState.SPEAKING));
    }

    /** 每轮新鲜生成的"当前时间"上下文(注入 LLM 但不存历史)。让时间/日期问题直接答对, 不依赖工具调用。 */
    private static String currentTimeContext() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        String[] weekday = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
        return String.format(java.util.Locale.CHINA,
                "【实时信息】当前日期时间：%d年%d月%d日 %s %02d:%02d。"
                        + "涉及当前时间、几号、星期几等问题，一律以此为准直接作答，不要凭记忆推算或编造。",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
                weekday[now.getDayOfWeek().getValue() - 1], now.getHour(), now.getMinute());
    }

    /** 从历史里撤掉指定的那条消息(按实例匹配, 用于动作回合事后清痕)。 */
    private void removeFromHistory(Message message) {
        synchronized (history) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if (history.get(i) == message) {
                    history.remove(i);
                    return;
                }
            }
        }
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
        // 用 s2sConfigWithTime(): 与持久 S2S 一致地把当前时间 + 长期记忆注入人设
        return s2s.converse(userAudio, historySnapshot(), s2sConfigWithTime())
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
                // 用 doFinally 而不是 doOnComplete: 打断走的是 cancel, 只挂 onComplete 会让被打断的
                // 那半句整段丢失(三段式 respond 里是同一个坑)。被打断的消息带标记进历史, 落库仍存原文。
                .doFinally(sig -> {
                    if (assistant.isEmpty()) {
                        return;
                    }
                    String full = assistant.toString();
                    String outcome = outcomeOf(sig);
                    boolean interrupted = sig == reactor.core.publisher.SignalType.CANCEL;
                    appendHistory(Message.assistant(interrupted ? full + INTERRUPTED_SUFFIX : full));
                    safeNotify(() -> listener.onAssistantText(full));
                    recordTurn(lastUserText, full, "s2s", outcome, null);
                });
    }

    /** S2S 字幕分流: 用户转写 → onAsrFinal(显示"你说了什么"); 机器人回复转写 → 逐段 onAssistantDelta 并累计成全文。 */
    private void routeTranscript(AudioChunk chunk, StringBuilder assistant) {
        String text = chunk.text();
        if (text == null || text.isBlank()) {
            return;
        }
        if (chunk.textRole() == AudioChunk.TextRole.USER) {
            lastUserText = text;   // 暂存, 待本轮机器人回复收尾时配对落库
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
        // 把当前时间注入人设(端到端模型无 get_current_time 工具, 靠上下文答对时间), 并下发 function-calling 工具
        S2sSession session = s2s.open(historySnapshot(), skills.toolSpecs(), s2sConfigWithTime());
        stateMachine.tryTransition(SessionState.LISTENING);
        LiveResponse resp = new LiveResponse();
        Flux<AudioChunk> audioOut = session.events()
                .<AudioChunk>handle((ev, sink) -> onS2sLiveEvent(ev, resp, session, sink))
                .doFinally(sig -> {
                    if (!stateMachine.is(SessionState.CLOSED)) {
                        stateMachine.tryTransition(SessionState.IDLE);
                    }
                    log.debug("持久 S2S 会话结束, signal={}", sig);
                });
        return new S2sLiveSession(session, audioOut);
    }

    /** 端到端会话配置: 在人设后追加当前时间上下文(与三段式一致, 让时间问题答对)。 */
    private S2sConfig s2sConfigWithTime() {
        S2sConfig base = context.s2sConfig();
        if (base == null) {
            return null;
        }
        String persona = (base.systemPrompt() == null ? "" : base.systemPrompt()) + "\n\n" + currentTimeContext();
        String mem = memoryContext(null);   // S2S 会话级注入, 无当轮 query → 退回最近 N
        if (mem != null) {
            persona = persona + "\n\n" + mem;   // 长期记忆注入端到端人设(seedHistory 会跳过 system, 故走人设)
        }
        return new S2sConfig(base.vendor(), base.model(), base.voice(), persona, base.outputFormat());
    }

    /** 持久 S2S 下行事件 → 音频块 + 字幕(listener) + 历史 + 状态机。运行在单一订阅线程上, 串行。 */
    private void onS2sLiveEvent(S2sEvent ev, LiveResponse resp, S2sSession session,
                               reactor.core.publisher.SynchronousSink<AudioChunk> sink) {
        if (ev instanceof S2sEvent.UserTranscript u) {
            lastUserText = u.text();   // 暂存, 待本轮回复收尾(ResponseDone/被打断)时配对落库
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
        } else if (ev instanceof S2sEvent.FunctionCall fc) {
            handleS2sFunctionCall(fc, session);
        } else if (ev instanceof S2sEvent.UserSpeechStarted) {
            // 全双工打断: 落已说出的部分、回到聆听, 并通知接入层冲掉前端播放缓冲(止住已下发的音频)
            flushAssistant(resp, "interrupted");
            stateMachine.tryTransition(SessionState.INTERRUPTED);
            stateMachine.tryTransition(SessionState.LISTENING);
            safeNotify(listener::onUserSpeechStarted);
        } else if (ev instanceof S2sEvent.ResponseDone) {
            // 本次回复正常结束(会话不关): 落历史, 回到聆听等下一轮
            flushAssistant(resp, "complete");
            stateMachine.tryTransition(SessionState.LISTENING);
        }
    }

    /**
     * 持久 S2S 工具调用: 复用三段式同一套技能执行(runSkill)+ 动作下发(dispatchAction), 再把结果经
     * {@link S2sSession#submitToolResult} 回灌, 模型据此继续语音作答。动作型(点歌)同时触发前端动作。
     * 技能多为同步 Mono, 直接订阅(fire-and-forget); 失败也回灌一条提示, 不卡住会话。
     */
    private void handleS2sFunctionCall(S2sEvent.FunctionCall fc, S2sSession session) {
        runSkill(new ToolCall(fc.callId(), fc.name(), fc.arguments()))
                .subscribe(result -> {
                    if (result.actionType() != null) {
                        dispatchAction(result.actionType(), result.actionPayload());
                    }
                    List<WebSearchProvider.Result> src = result.sources();
                    if (src != null && !src.isEmpty()) {
                        safeNotify(() -> listener.onWebSearchSources(src));   // 联网来源透传前端展示
                    }
                    String output = result.content() == null || result.content().isBlank()
                            ? "已完成" : result.content();
                    session.submitToolResult(fc.callId(), output);
                }, err -> {
                    log.warn("持久 S2S 工具执行异常, call_id={}: {}", fc.callId(), err.toString());
                    session.submitToolResult(fc.callId(), "工具执行失败");
                });
    }

    /** 本次回复首次出现内容时迁入 THINKING(每次回复仅一次, 避免噪声日志)。 */
    private void markThinking(LiveResponse resp) {
        if (!resp.thinking) {
            resp.thinking = true;
            stateMachine.tryTransition(SessionState.THINKING);
        }
    }

    /** 把当前累计的机器人回复落历史并通知 listener, 然后复位本次回复态; 空则只复位。 */
    private void flushAssistant(LiveResponse resp, String outcome) {
        if (!resp.assistant.isEmpty()) {
            String full = resp.assistant.toString();
            // 全双工打断在这里是<b>常态</b>而非异常, 更要标注: 服务端 VAD 一听到用户开口就切,
            // 助手往往只说了几个字。不标, 模型会以为整段已经说完。
            boolean interrupted = "interrupted".equals(outcome);
            appendHistory(Message.assistant(interrupted ? full + INTERRUPTED_SUFFIX : full));
            safeNotify(() -> listener.onAssistantText(full));
            // 持久 S2S 每次回复结束/被打断算一轮; 用户那句来自之前的 UserTranscript 事件(lastUserText)
            recordTurn(lastUserText, full, "s2s-persistent", outcome, null);
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

    /**
     * 用一段外部历史重置会话上下文: <b>保留 system 提示</b>, 清掉旧的 user/assistant, 再按序注入给定历史。
     * 供接入层在"切换会话"(多会话/类 ChatGPT)时回灌该会话的上文 —— 之后的回合据此续聊。
     * 仅接收 user/assistant 文本消息; 持久 S2S 会在下次开连接(openS2sLive)时用上这段历史。
     */
    public void loadHistory(List<Message> messages) {
        synchronized (history) {
            history.removeIf(m -> m.role() != Message.Role.SYSTEM);
            if (messages != null) {
                for (Message m : messages) {
                    if (m == null || m.content() == null || m.content().isBlank()) {
                        continue;
                    }
                    if (m.role() == Message.Role.USER || m.role() == Message.Role.ASSISTANT) {
                        history.add(m);
                    }
                }
            }
            trimHistory();
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

    /**
     * 回传一段助手文本增量给接入层做字幕; {@code accumulate=true} 时同时计入 {@link #spokenThisTurn}。
     * 语音回合传 false —— 它的"说出口"以句子进 TTS 为准, 见 {@link #noteSpoken}。
     */
    private void emitAssistantDelta(String delta, boolean accumulate) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (accumulate) {
            noteSpoken(delta);
        }
        safeNotify(() -> listener.onAssistantDelta(delta));
    }

    /** 记下一段已经"说出口"的文本(语音回合: 一句刚被交给 TTS 合成)。 */
    private void noteSpoken(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        synchronized (spokenThisTurn) {
            spokenThisTurn.append(text);
        }
    }

    /** 取走本回合已说出口的文本并清空(回合收尾时调用一次, 不留给下一回合)。 */
    private String takeSpokenThisTurn() {
        synchronized (spokenThisTurn) {
            String s = spokenThisTurn.toString();
            spokenThisTurn.setLength(0);
            return s;
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
