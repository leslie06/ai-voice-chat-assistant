package com.vca.store.eval;

import java.time.Instant;
import java.util.List;

/**
 * 一次评测快照(数据飞轮 P2-A, <b>零标注</b>自动指标)。全部从 {@code conversation_turn} 表算得,
 * 不需人工标注 —— 落库即可看, 用来回答"机器人这阵子表现如何 / 改动让体验变好还是变差"。
 *
 * <p>口径:
 * <ul>
 *   <li>{@code emptyReplyRate}: {@code complete} 回合里助手文本为空的比例 —— <b>疑似内容审查命中或模型沉默</b>
 *       (与持久 S2S 的 data_inspection 降级相呼应), 偏高说明有效回复在丢;</li>
 *   <li>{@code suspectedFalseBargeRate}: {@code interrupted} 回合里助手刚开口就被打断(文本极短)的比例 ——
 *       <b>疑似回声自打断</b>, 偏高说明 barge 阈值/保护期或 AEC 需要调;</li>
 *   <li>{@code latency}: 仅三段式有逐轮 {@code total_ms}, S2S 路径为空不计入。</li>
 * </ul>
 */
public record EvaluationReport(
        Instant generatedAt,
        Instant since,                 // 统计窗口起点; null = 全部历史
        long totalTurns,
        long totalSessions,
        List<ModeStat> byMode,
        List<OutcomeStat> byOutcome,
        LatencyStat latency,
        double emptyReplyRate,
        double suspectedFalseBargeRate,
        AgentStat agent) {

    /** 按链路(pipeline/s2s/s2s-persistent)的回合数与失败率/打断率。 */
    public record ModeStat(String mode, long total, double errorRate, double interruptRate) {
    }

    /**
     * 多步 Agent 使用情况(P3): agent 回合占比 + 平均步数/反思补步数。
     * {@code agentTurnRate} 看复杂任务被路由进 Agent 的频度; {@code avgSteps}/{@code avgReplans} 看规划粒度
     * 与重规划频率 —— 平均补步数偏高说明初始规划常不到位。
     */
    public record AgentStat(long agentTurns, double agentTurnRate, double avgSteps, double avgReplans) {
    }

    /** 按结局(complete/interrupted/error)的回合数。 */
    public record OutcomeStat(String outcome, long count) {
    }

    /** 整轮耗时分位(毫秒); 取自有 total_ms 的回合(三段式)。 */
    public record LatencyStat(long count, long p50Ms, long p95Ms, long avgMs) {
    }
}
