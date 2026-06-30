package com.vca.store.eval;

import com.vca.store.mapper.EvaluationMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 评测报告计算器(数据飞轮 P2-A)。从 {@link EvaluationMapper} 取聚合, 在 Java 侧算分位数与各比率,
 * 组装成 {@link EvaluationReport}。无状态, 线程安全。
 */
public class ConversationEvaluator {

    /** 助手文本短于这个字符数即视作"刚开口就被打断"(疑似回声误打断)。 */
    private static final int SHORT_REPLY_CHARS = 8;

    private final EvaluationMapper mapper;

    public ConversationEvaluator(EvaluationMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 生成评测报告。
     *
     * @param since 统计窗口起点; null 表示全部历史
     */
    public EvaluationReport report(Instant since) {
        LocalDateTime from = since == null
                ? LocalDateTime.of(1970, 1, 1, 0, 0)
                : LocalDateTime.ofInstant(since, ZoneId.systemDefault());

        long totalTurns = mapper.totalTurns(from);
        long totalSessions = mapper.totalSessions(from);

        List<EvaluationReport.ModeStat> byMode = new ArrayList<>();
        for (ModeAggRow r : mapper.aggByMode(from)) {
            byMode.add(new EvaluationReport.ModeStat(
                    r.getMode(), r.getTotal(),
                    rate(r.getErrors(), r.getTotal()),
                    rate(r.getInterrupts(), r.getTotal())));
        }

        List<EvaluationReport.OutcomeStat> byOutcome = new ArrayList<>();
        for (OutcomeRow r : mapper.aggByOutcome(from)) {
            byOutcome.add(new EvaluationReport.OutcomeStat(r.getOutcome(), r.getCnt()));
        }

        EvaluationReport.LatencyStat latency = latencyOf(mapper.latencies(from));

        double emptyReplyRate = rate(mapper.emptyCompleteTurns(from), mapper.completeTurns(from));
        long interrupted = mapper.interruptedTurns(from);
        double falseBargeRate = rate(mapper.shortInterruptedTurns(from, SHORT_REPLY_CHARS), interrupted);

        long agentTurns = mapper.agentTurns(from);
        EvaluationReport.AgentStat agent = new EvaluationReport.AgentStat(
                agentTurns,
                rate(agentTurns, totalTurns),
                avg(mapper.agentStepsSum(from), agentTurns),
                avg(mapper.agentReplansSum(from), agentTurns));

        return new EvaluationReport(Instant.now(), since, totalTurns, totalSessions,
                byMode, byOutcome, latency, emptyReplyRate, falseBargeRate, agent);
    }

    /** 比率, 保留 4 位小数; 分母为 0 返回 0。 */
    static double rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return Math.round((double) numerator / denominator * 10000.0) / 10000.0;
    }

    /** 均值, 保留 2 位小数; 分母为 0 返回 0。 */
    static double avg(long sum, long count) {
        if (count <= 0) {
            return 0.0;
        }
        return Math.round((double) sum / count * 100.0) / 100.0;
    }

    /** 在 Java 侧算 p50/p95(最近秩法)与均值, 避开 MySQL 8 没有的 PERCENTILE 函数。 */
    public static EvaluationReport.LatencyStat latencyOf(List<Long> valuesIn) {
        if (valuesIn == null || valuesIn.isEmpty()) {
            return new EvaluationReport.LatencyStat(0, 0, 0, 0);
        }
        List<Long> values = new ArrayList<>(valuesIn);
        Collections.sort(values);
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        long avg = sum / values.size();
        return new EvaluationReport.LatencyStat(values.size(),
                percentile(values, 0.50), percentile(values, 0.95), avg);
    }

    /** 最近秩: 升序数据里取第 ceil(p*n) 个(1 基), 即下标 clamp(ceil(p*n)-1)。values 须已升序。 */
    private static long percentile(List<Long> sorted, double p) {
        int n = sorted.size();
        int idx = (int) Math.ceil(p * n) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= n) {
            idx = n - 1;
        }
        return sorted.get(idx);
    }
}
