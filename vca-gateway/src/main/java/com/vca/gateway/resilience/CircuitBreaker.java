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
 * <p><b>半开只放一个</b>: 冷却期一到, 若不限流, 期间攒下的全部并发调用会一起涌向那个刚被判定为"已挂"的
 * 厂商 —— 熔断本来要防的正是这件事。故 HALF_OPEN 下只放行一次试探, 其余照旧拒绝(转移到下一候选),
 * 等试探报回成功/失败再决定开合。
 *
 * <p>试探可能<b>永远不报回</b>: 打断(barge-in)会取消整条流, 而取消既不算成功也不算失败。若只用一个布尔
 * 标志把门锁上, 该厂商就再也不会被试探, 等于永久熔断。因此试探带时间戳, 超过一个 openDuration 仍无回音
 * 就允许下一次试探 —— 最坏情况退化成"每 openDuration 试一次", 不会卡死。
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
    /** 半开试探是否在途; 只有它为 false(或已过期)时才再放一个试探进去。 */
    private boolean probeInFlight = false;
    /** 在途试探的发起时刻; 用于识别"被取消而永不报回"的试探。 */
    private long probeStartedAtNanos = 0L;

    public CircuitBreaker(int failureThreshold, Duration openDuration) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDurationNanos = openDuration.toNanos();
    }

    /** 是否允许本次调用通过 */
    public synchronized boolean allowRequest() {
        long now = System.nanoTime();
        if (state == State.OPEN && now - openedAtNanos >= openDurationNanos) {
            state = State.HALF_OPEN;   // 冷却结束, 转入半开
            probeInFlight = false;
        }
        if (state == State.OPEN) {
            return false;
        }
        if (state == State.HALF_OPEN) {
            // 已有试探在途且还没超时 → 本次不放行, 让调用方转移到下一候选
            if (probeInFlight && now - probeStartedAtNanos < openDurationNanos) {
                return false;
            }
            probeInFlight = true;
            probeStartedAtNanos = now;
        }
        return true;
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        state = State.CLOSED;
        probeInFlight = false;
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
        probeInFlight = false;
    }
}
