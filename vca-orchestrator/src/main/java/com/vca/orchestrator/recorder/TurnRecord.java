package com.vca.orchestrator.recorder;

import java.time.Instant;

/**
 * 一轮对话的存档记录: "用户说了什么 / 机器人答了什么" + 元数据。落库后构成数据飞轮的原料 ——
 * 既是可回溯的对话档案, 也是离线评测(WER 抽样、误打断率、回合失败率、工具命中)的数据源。
 *
 * <p>设计取舍(P1 最小闭环):
 * <ul>
 *   <li>只记文本与回合元数据, <b>不记音频</b>(音频体量大, 留作 WER 阶段再以对象存储引用补);</li>
 *   <li>{@code totalMs} 可空 —— 仅三段式/文本回合有逐轮计时, S2S(每轮/持久)路径无单轮起止点;</li>
 *   <li>逐轮延迟 SLO(首 token/首音频)仍在 Micrometer/Prometheus 聚合, 此处不重复存。</li>
 * </ul>
 *
 * @param sessionId     会话 id(同一条 WebSocket 长连一个)
 * @param turnIndex     本会话内的回合序号(从 1 递增)
 * @param mode          回合所走链路: {@code pipeline}(三段式) / {@code s2s}(每轮端到端) / {@code s2s-persistent}(持久全双工)
 * @param userText      本轮用户说了什么(可空: 极少数无识别结果的边界回合)
 * @param assistantText 机器人本轮回复文本(可空: 动作型回合如点歌不产生口语答复)
 * @param at            回合落库时刻
 * @param totalMs       整轮耗时(毫秒), 可空
 * @param outcome       回合结局: {@code complete} / {@code interrupted} / {@code error}
 */
public record TurnRecord(
        String sessionId,
        int turnIndex,
        String mode,
        String userText,
        String assistantText,
        Instant at,
        Long totalMs,
        String outcome) {
}
