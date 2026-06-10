package com.vca.domain.model;

/**
 * 会话上下文: 一路对话的不可变配置快照。会话内的对话历史(可变)由 session 模块单独管理,
 * 不放进这里, 以保持 domain 的契约层无状态。
 *
 * @param sessionId 会话唯一 id
 * @param userId    用户 id(可为 null)
 * @param mode      对话模式: 三段式 or 端到端
 * @param asrConfig 三段式下的 ASR 配置(端到端模式可为 null)
 * @param llmConfig LLM 配置: 三段式必填; 端到端模式可为 null, 但若想支持"打字走文字回复"则也需提供
 * @param ttsConfig 三段式下的 TTS 配置(端到端模式可为 null)
 * @param s2sConfig 端到端模式下的配置(三段式模式可为 null)
 */
public record SessionContext(
        String sessionId,
        String userId,
        Mode mode,
        AsrConfig asrConfig,
        LlmConfig llmConfig,
        TtsConfig ttsConfig,
        S2sConfig s2sConfig
) {
    /** 对话模式 */
    public enum Mode {
        /** 三段式: ASR -> LLM -> TTS, 自由组合厂商 */
        PIPELINE,
        /** 端到端语音大模型: 单厂商融合 */
        SPEECH_TO_SPEECH
    }

    /** 构造三段式会话 */
    public static SessionContext pipeline(String sessionId, String userId,
                                          AsrConfig asr, LlmConfig llm, TtsConfig tts) {
        return new SessionContext(sessionId, userId, Mode.PIPELINE, asr, llm, tts, null);
    }

    /** 构造端到端会话 */
    public static SessionContext speechToSpeech(String sessionId, String userId, S2sConfig s2s) {
        return new SessionContext(sessionId, userId, Mode.SPEECH_TO_SPEECH, null, null, null, s2s);
    }

    /**
     * 构造端到端会话, 并附带一份 LLM 配置 —— 仅供"打字输入"走文字进/文字出的回退链路:
     * 端到端语音模型只吃音频, 用户打字时改用普通 LLM 出一段文字回复(不合成语音);
     * 语音回合仍走 s2s。{@code llm} 为 null 时打字功能在 s2s 下不可用(与旧行为一致)。
     */
    public static SessionContext speechToSpeech(String sessionId, String userId, S2sConfig s2s, LlmConfig llm) {
        return new SessionContext(sessionId, userId, Mode.SPEECH_TO_SPEECH, null, llm, null, s2s);
    }

    public boolean isPipeline() {
        return mode == Mode.PIPELINE;
    }
}
