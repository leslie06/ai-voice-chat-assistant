package com.vca.gateway.resilience;

import java.time.Duration;

/**
 * 极简熔断器(单个 厂商×能力)。三态:
 * <pre>
 *   CLOSED  正常放行; 连续失败达阈值 → OPEN
 *   OPEN    拒绝放行; 经过 openDuration → HALF_OPEN(放行一次试探)
 *   HALF_OPEN 试探: 成功 → CLOSED; 失败 → 重新 OPEN
 * </pre>
 *
 * <p>故意手写而非引入 Resilience4j: 逻辑可控、可确定性单测、零额外依赖。
 * 需要更丰富策略(滑动窗口/慢调用率)时可平替为 Resilience4j。线程安全(synchronized)。
 */
public class CircuitBreaker {

    public enum State {CLOSED, OPEN, HALF_OPEN}

    private final int failureThreshold;
    private final long openDurationNanos;

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAtNanos = 0L;

    public CircuitBreaker(int failureThreshold, Duration openDuration) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDurationNanos = openDuration.toNanos();
    }

    /** 是否允许本次调用通过 */
    public synchronized boolean allowRequest() {
        if (state == State.OPEN && System.nanoTime() - openedAtNanos >= openDurationNanos) {
            state = State.HALF_OPEN; // 冷却结束, 放行一次试探
        }
        return state != State.OPEN;
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        state = State.CLOSED;
    }

    public synchronized void recordFailure() {
        if (state == State.HALF_OPEN) {
            trip();
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            trip();
        }
    }

    public synchronized State state() {
        return state;
    }

    private void trip() {
        state = State.OPEN;
        openedAtNanos = System.nanoTime();
    }
}
