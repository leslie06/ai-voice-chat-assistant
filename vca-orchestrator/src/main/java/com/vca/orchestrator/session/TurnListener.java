package com.vca.orchestrator.session;

/**
 * 回合事件回调: 让接入层把识别结果/回复文本透传给前端做字幕。
 * 默认空实现, 不需要时无需关心。
 */
public interface TurnListener {

    TurnListener NOOP = new TurnListener() {
    };

    /** ASR 最终识别文本(本轮用户说了什么) */
    default void onAsrFinal(String text) {
    }

    /** LLM 回复的增量 token(逐段流式回调, 用于前端打字机式实时显示) */
    default void onAssistantDelta(String delta) {
    }

    /** 本轮 LLM 完整回复文本(在回复结束时回调) */
    default void onAssistantText(String fullText) {
    }

    /**
     * 命中点歌等"动作"意图: 让接入层把动作下发给前端执行(如打开 QQ 音乐)。
     *
     * @param action 动作, 目前为 "play"
     * @param query  歌曲查询词(歌名/歌手)
     */
    default void onMusicRequest(String action, String query) {
    }

    /**
     * 服务端 VAD 判定用户开口(持久 S2S 全双工打断)。接入层据此立即让前端冲掉播放缓冲、止住机器人当前回复。
     * 仅持久 S2S 模式产生; 三段式/每轮 S2S 的打断仍走原有 {@code interrupted} 通道。
     */
    default void onUserSpeechStarted() {
    }
}
