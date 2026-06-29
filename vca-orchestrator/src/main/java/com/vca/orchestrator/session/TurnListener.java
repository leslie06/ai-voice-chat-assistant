package com.vca.orchestrator.session;

import com.vca.orchestrator.search.WebSearchProvider;

import java.util.List;

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

    /** ASR 中间转写(流式假设, 非最终)。仅三段式产生; 接入层用它做语义端点判定。 */
    default void onAsrPartial(String text) {
    }

    /** LLM 回复的增量 token(逐段流式回调, 用于前端打字机式实时显示) */
    default void onAssistantDelta(String delta) {
    }

    /** 本轮 LLM 完整回复文本(在回复结束时回调) */
    default void onAssistantText(String fullText) {
    }

    /**
     * 本轮命中时效启发式、自动联网检索到结果时回调(在 LLM 据此作答<b>之前</b>): 让接入层把来源
     * (标题/链接/时间)透传给前端展示, 用户能看到答案出处。无命中/未联网时不回调。
     */
    default void onWebSearchSources(List<WebSearchProvider.Result> sources) {
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
