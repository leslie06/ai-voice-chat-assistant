package com.vca.orchestrator.agent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次多步 Agent 运行的<b>预算 + 统计</b>。预算是"绝不空手而归"的安全网: 任一上限触顶就停止再扩张,
 * 用已收集的 scratchpad 直接整合答复(而非报错或卡死)。统计供数据飞轮落库(agent 回合执行了几步/反思补了几步)。
 *
 * <p>三重预算: <b>步数</b>(由编排层的 MAX_AGENT_STEPS/MAX_EXTRA_AGENT_STEPS 控, 不在此)、
 * <b>墙钟超时</b>({@link #outOfTime()})、<b>工具调用总数</b>({@link #tryUseToolCalls(int)})。
 * 非线程安全假设: Agent 各步串行执行(concat), 这里的原子量只为可见性与计数正确。
 */
public final class AgentRun {

    private final long deadlineNanos;
    private final AtomicInteger toolCallsLeft;
    private final AtomicInteger steps = new AtomicInteger();
    private final AtomicInteger replans = new AtomicInteger();
    private volatile boolean capped;

    /**
     * @param deadlineNanos 墙钟截止({@link System#nanoTime()} 基准); 过了就不再开新步
     * @param maxToolCalls  整段 Agent 允许的工具调用总数
     */
    public AgentRun(long deadlineNanos, int maxToolCalls) {
        this.deadlineNanos = deadlineNanos;
        this.toolCallsLeft = new AtomicInteger(Math.max(0, maxToolCalls));
    }

    /** 墙钟是否已超时(超时后编排层跳过剩余步骤/反思, 直接整合)。 */
    public boolean outOfTime() {
        return System.nanoTime() >= deadlineNanos;
    }

    /**
     * 申领 {@code n} 次工具调用额度: 够则扣减返回 true; 不够则不扣、标记 capped 返回 false
     * (调用方据此跳过本轮工具执行, 让模型用已有信息作答)。
     */
    public boolean tryUseToolCalls(int n) {
        if (n <= 0) {
            return true;
        }
        while (true) {
            int cur = toolCallsLeft.get();
            if (cur < n) {
                capped = true;
                return false;
            }
            if (toolCallsLeft.compareAndSet(cur, cur - n)) {
                return true;
            }
        }
    }

    /** 记一步已执行(计划步或反思补步均计入)。 */
    public void stepStarted() {
        steps.incrementAndGet();
    }

    /** 记一次反思补步(重规划)。 */
    public void replanned() {
        replans.incrementAndGet();
    }

    public int stepsExecuted() {
        return steps.get();
    }

    public int replans() {
        return replans.get();
    }

    /** 是否触发过任一预算上限(工具额度用尽; 墙钟超时另由 outOfTime 即时判)。 */
    public boolean capped() {
        return capped;
    }

    /** 标记触顶(墙钟超时跳步时由编排层调用, 便于诊断)。 */
    public void markCapped() {
        capped = true;
    }
}
