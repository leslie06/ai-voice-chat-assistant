package com.vca.orchestrator.session;

import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.AudioFrame;
import com.vca.domain.spi.S2sSession;
import reactor.core.publisher.Flux;

/**
 * 持久 S2S 全双工会话的<b>编排层句柄</b>。把底层 {@link S2sSession}(厂商长连)包成接入层友好的形态:
 * 订阅 {@link #audioOut()} 即开始收下行音频/字幕, 持续 {@link #pushAudio} 喂麦克风音频, 服务端 VAD
 * 接管回合与打断 —— 不再有"轮"的概念。字幕与打断信号经 {@link TurnListener} 旁路透传(见
 * {@link ConversationSession#openS2sLive()}), 故本句柄只暴露音频下行 + 上行 + 关闭。
 */
public final class S2sLiveSession {

    private final S2sSession session;
    private final Flux<AudioChunk> audioOut;

    S2sLiveSession(S2sSession session, Flux<AudioChunk> audioOut) {
        this.session = session;
        this.audioOut = audioOut;
    }

    /** 下行音频流(含字幕块); <b>订阅即建连</b>, 全程只订阅一次。 */
    public Flux<AudioChunk> audioOut() {
        return audioOut;
    }

    /** 持续上行: 喂一帧麦克风音频(16k 单声道 PCM)。 */
    public void pushAudio(AudioFrame frame) {
        session.pushAudio(frame);
    }

    /** 主动打断(手动按钮): 截断机器人当前回复。 */
    public void cancelResponse() {
        session.cancelResponse();
    }

    /** 关闭持久会话并释放底层长连。幂等。 */
    public void close() {
        session.close();
    }
}
