package com.vca.web.session;

import com.vca.domain.enums.AudioFormat;
import com.vca.domain.model.AsrConfig;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.S2sConfig;
import com.vca.domain.model.SessionContext;
import com.vca.domain.model.TtsConfig;
import com.vca.gateway.ProviderGateway;
import com.vca.orchestrator.knowledge.KnowledgeStore;
import com.vca.orchestrator.memory.MemoryStore;
import com.vca.orchestrator.search.WebSearchProvider;
import com.vca.orchestrator.metrics.TurnMetrics;
import com.vca.orchestrator.pipeline.SentenceSplitter;
import com.vca.orchestrator.recorder.ConversationRecorder;
import com.vca.orchestrator.session.ConversationSession;
import com.vca.orchestrator.session.TurnListener;
import com.vca.orchestrator.skill.SkillRegistry;
import com.vca.web.WebProperties;

/**
 * 为每条 WebSocket 连接创建一路 {@link ConversationSession}。
 * 关键: 注入的是 {@link ProviderGateway} 的<b>受治理</b> provider, 因此编排会话自动获得
 * 选厂商/熔断/配额/故障转移, 而它本身对此无感。
 */
public class ConversationSessionFactory {

    private final ProviderGateway gateway;
    private final WebProperties props;
    private final TurnMetrics metrics;
    private final SkillRegistry skills;
    /** 对话存档端口(数据飞轮); 未启用落库时为 NOOP, 编排会话照常工作 */
    private final ConversationRecorder recorder;
    /** 长期记忆端口(跨会话个性化); 未启用落库时为 NOOP */
    private final MemoryStore memory;
    /** 知识库检索端口(RAG, 自动注入); 未启用时为 NOOP */
    private final KnowledgeStore knowledge;
    /** 联网搜索端口(自动注入 + 工具); 未配 key 时为 NOOP */
    private final WebSearchProvider webSearch;
    private final SentenceSplitter splitter = new SentenceSplitter();

    public ConversationSessionFactory(ProviderGateway gateway, WebProperties props, TurnMetrics metrics,
                                      SkillRegistry skills) {
        this(gateway, props, metrics, skills, ConversationRecorder.NOOP, MemoryStore.NOOP, KnowledgeStore.NOOP,
                WebSearchProvider.NOOP);
    }

    public ConversationSessionFactory(ProviderGateway gateway, WebProperties props, TurnMetrics metrics,
                                      SkillRegistry skills, ConversationRecorder recorder, MemoryStore memory,
                                      KnowledgeStore knowledge, WebSearchProvider webSearch) {
        this.gateway = gateway;
        this.props = props;
        this.metrics = metrics;
        this.skills = skills == null ? SkillRegistry.empty() : skills;
        this.recorder = recorder == null ? ConversationRecorder.NOOP : recorder;
        this.memory = memory == null ? MemoryStore.NOOP : memory;
        this.knowledge = knowledge == null ? KnowledgeStore.NOOP : knowledge;
        this.webSearch = webSearch == null ? WebSearchProvider.NOOP : webSearch;
    }

    /**
     * 接入层对本路会话的覆盖项。浏览器传 {@link #none()}; 电话接入用它砍掉用不上的工具、换电话专用音色与人设。
     *
     * @param skills        本路会话下发给模型的工具集; null = 用全局注册表
     * @param ttsVoice      本路会话的合成音色; 留空 = 用 {@code vca.web.tts-voice}
     * @param systemPrompt  本路会话的人设; 留空 = 用 {@code vca.web.system-prompt}
     * @param llmModel      本路会话的对话模型; 留空 = 用 {@code vca.web.llm-model}
     * @param webSearchAuto 是否允许自动联网注入; null = 用 {@code vca.web.web-search-auto}。
     *                      电话侧关掉它 —— 实测一次注入要 2.3 秒, 是体感延迟里最大的一块
     * @param knowledgeOwner 按谁的知识库检索(账号 id)。浏览器留空(按登录用户); 电话填商家账号 ——
     *                       对端是外部客户、没有登录身份, 要查的是商家上传的项目/价格/营业时间
     */
    public record Overrides(SkillRegistry skills, String ttsVoice, String systemPrompt, String llmModel,
                           Boolean webSearchAuto, String knowledgeOwner) {
        private static final Overrides NONE = new Overrides(null, null, null, null, null, null);

        public static Overrides none() {
            return NONE;
        }
    }

    public ConversationSession create(String sessionId, TurnListener listener) {
        return create(sessionId, null, listener);
    }

    public ConversationSession create(String sessionId, String userId, TurnListener listener) {
        return create(sessionId, userId, listener, Overrides.none());
    }

    /**
     * 建一路会话。{@code userId} 非空时启用长期记忆(回灌该用户的跨会话记忆, 并允许 remember 工具写入);
     * 为空(未登录/账号系统未启用)时记忆为 NOOP。
     */
    public ConversationSession create(String sessionId, String userId, TurnListener listener, Overrides overrides) {
        Overrides ov = overrides == null ? Overrides.none() : overrides;
        SessionContext ctx = combinedContext(sessionId, ov.ttsVoice(), ov.systemPrompt(), ov.llmModel());

        ConversationSession session = new ConversationSession(
                ctx, gateway.asr(), gateway.llm(), gateway.tts(), gateway.s2s(), splitter,
                props.getHistoryMaxMessages(), metrics,
                ov.skills() == null ? skills : ov.skills());
        session.setTurnListener(listener);
        session.setRecorder(recorder);
        // 联网搜索不分用户(实时信息非个人数据), 无条件启用; 自动注入可由接入层关掉(电话默认关)
        boolean searchAuto = ov.webSearchAuto() == null ? props.isWebSearchAuto() : ov.webSearchAuto();
        session.setWebSearch(webSearch, searchAuto, props.getWebSearchCount());
        // 多步 Agent 规划: 命中复杂回合先规划再执行(需配合工具, 故技能为空时该开关在会话内自然失效)
        session.setAgentEnabled(props.isAgentEnabled());
        // 视觉模型: 带图回合自动切换(留空则沿用当前对话模型, 需其自身支持视觉)
        session.setVisionModel(props.getVisionVendor(), props.getVisionModel());
        if (userId != null && !userId.isBlank()) {
            session.setMemory(memory, userId);
            session.setKnowledge(knowledge);   // RAG 自动注入按同一登录用户隔离
        }
        // 接入层指定了知识库归属(电话=商家账号): 查商家的资料, 但不启用个人记忆 —— 对端不是本系统用户
        if (ov.knowledgeOwner() != null && !ov.knowledgeOwner().isBlank()) {
            session.setKnowledge(knowledge, ov.knowledgeOwner());
        }
        return session;
    }

    /**
     * 双模式就绪: 同时备齐三段式(ASR→LLM→TTS)与端到端(s2s)两套配置, 让前端可在线热切换。
     * 初始模式取自 {@code vca.web.mode}; 两套都复用同一份 system prompt(人设), 切模式时人设不变。
     * 端到端模型只吃音频, 打字时回退到这份 LLM 出文字回复(不发声), s2s 下也能打字提问。
     */
    private SessionContext combinedContext(String sessionId, String ttsVoiceOverride, String promptOverride,
                                           String llmModelOverride) {
        AsrConfig asr = new AsrConfig(props.getAsrVendor(), props.getAsrLanguage(),
                16000, java.util.List.of(), true);
        String prompt = promptOverride == null || promptOverride.isBlank()
                ? props.getSystemPrompt() : promptOverride;
        String llmModel = llmModelOverride == null || llmModelOverride.isBlank()
                ? props.getLlmModel() : llmModelOverride;
        LlmConfig llm = new LlmConfig(props.getLlmVendor(), llmModel, prompt, 0.7, 1024);
        String voice = ttsVoiceOverride == null || ttsVoiceOverride.isBlank()
                ? props.getTtsVoice() : ttsVoiceOverride;
        TtsConfig tts = TtsConfig.defaults(props.getTtsVendor(), voice);
        S2sConfig s2s = new S2sConfig(
                props.getS2sVendor(), props.getS2sModel(), props.getS2sVoice(),
                prompt, AudioFormat.PCM);
        SessionContext.Mode initial = props.isS2sMode()
                ? SessionContext.Mode.SPEECH_TO_SPEECH : SessionContext.Mode.PIPELINE;
        return SessionContext.combined(sessionId, null, initial, asr, llm, tts, s2s);
    }
}
