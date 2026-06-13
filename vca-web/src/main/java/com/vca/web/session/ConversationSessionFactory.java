package com.vca.web.session;

import com.vca.domain.enums.AudioFormat;
import com.vca.domain.model.AsrConfig;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.S2sConfig;
import com.vca.domain.model.SessionContext;
import com.vca.domain.model.TtsConfig;
import com.vca.gateway.ProviderGateway;
import com.vca.orchestrator.metrics.TurnMetrics;
import com.vca.orchestrator.pipeline.SentenceSplitter;
import com.vca.orchestrator.session.ConversationSession;
import com.vca.orchestrator.session.TurnListener;
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
    private final SentenceSplitter splitter = new SentenceSplitter();

    public ConversationSessionFactory(ProviderGateway gateway, WebProperties props, TurnMetrics metrics) {
        this.gateway = gateway;
        this.props = props;
        this.metrics = metrics;
    }

    public ConversationSession create(String sessionId, TurnListener listener) {
        SessionContext ctx = combinedContext(sessionId);

        ConversationSession session = new ConversationSession(
                ctx, gateway.asr(), gateway.llm(), gateway.tts(), gateway.s2s(), splitter,
                props.getHistoryMaxMessages(), metrics);
        session.setTurnListener(listener);
        return session;
    }

    /**
     * 双模式就绪: 同时备齐三段式(ASR→LLM→TTS)与端到端(s2s)两套配置, 让前端可在线热切换。
     * 初始模式取自 {@code vca.web.mode}; 两套都复用同一份 system prompt(人设), 切模式时人设不变。
     * 端到端模型只吃音频, 打字时回退到这份 LLM 出文字回复(不发声), s2s 下也能打字提问。
     */
    private SessionContext combinedContext(String sessionId) {
        AsrConfig asr = AsrConfig.defaults(props.getAsrVendor());
        LlmConfig llm = new LlmConfig(
                props.getLlmVendor(), props.getLlmModel(), props.getSystemPrompt(), 0.7, 1024);
        TtsConfig tts = TtsConfig.defaults(props.getTtsVendor(), props.getTtsVoice());
        S2sConfig s2s = new S2sConfig(
                props.getS2sVendor(), props.getS2sModel(), props.getS2sVoice(),
                props.getSystemPrompt(), AudioFormat.PCM);
        SessionContext.Mode initial = props.isS2sMode()
                ? SessionContext.Mode.SPEECH_TO_SPEECH : SessionContext.Mode.PIPELINE;
        return SessionContext.combined(sessionId, null, initial, asr, llm, tts, s2s);
    }
}
