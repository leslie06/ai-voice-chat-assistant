package com.vca.orchestrator.recorder;

/**
 * 语音通话双轨录音端口。用户上行 PCM 与客服下行 PCM 分开写入，避免混音后无法区分双方。
 *
 * <p>这是旁路能力：实现必须快速返回、自行吞掉异常，录音失败不能影响 WebSocket 语音链路。
 */
public interface AudioRecordingService {

    AudioRecordingService NOOP = (userId, sessionId) -> Session.NOOP;

    /** 登录用户建立 WebSocket 后开启一条录音。 */
    Session start(String userId, String sessionId);

    interface Session extends AutoCloseable {

        Session NOOP = new Session() {
            @Override public void setConversationId(Long conversationId) { }
            @Override public void appendUserAudio(byte[] pcm16le, int sampleRate) { }
            @Override public void appendAssistantAudio(byte[] pcm16le, int sampleRate) { }
            @Override public void close() { }
        };

        /** 前端切换会话后补充业务会话 id；可为空。 */
        void setConversationId(Long conversationId);

        /** 追加用户麦克风 PCM16LE、单声道音频。 */
        void appendUserAudio(byte[] pcm16le, int sampleRate);

        /** 追加客服 TTS PCM16LE、单声道音频。 */
        void appendAssistantAudio(byte[] pcm16le, int sampleRate);

        /** 异步结束并封装 WAV；必须幂等。 */
        @Override
        void close();
    }
}
