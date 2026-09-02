package com.vca.gateway;

import com.vca.gateway.resilience.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    @Test
    void opensAfterThresholdAndBlocks() {
        CircuitBreaker cb = new CircuitBreaker(2, Duration.ofSeconds(10));
        assertThat(cb.allowRequest()).isTrue();

        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        cb.recordFailure();                       // 达到阈值 2
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allowRequest()).isFalse();  // 熔断, 拒绝
    }

    @Test
    void halfOpensAfterCooldownThenClosesOnSuccess() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofMillis(80));
        cb.recordFailure();                       // 立即 OPEN
        assertThat(cb.allowRequest()).isFalse();

        Thread.sleep(120);                        // 冷却结束
        assertThat(cb.allowRequest()).isTrue();   // 半开放行试探
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        cb.recordSuccess();                       // 试探成功 → 恢复
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void halfOpenFailureReopens() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofMillis(80));
        cb.recordFailure();
        Thread.sleep(120);
        assertThat(cb.allowRequest()).isTrue();   // 进入半开
        cb.recordFailure();                       // 试探失败 → 重新熔断
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allowRequest()).isFalse();
    }

    @Test
    void halfOpenAdmitsOnlyOneProbe() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofMillis(50));
        cb.recordFailure();
        Thread.sleep(80);

        assertThat(cb.allowRequest()).isTrue();    // 第一个试探放行
        assertThat(cb.allowRequest()).isFalse();   // 其余照旧拒绝, 由调用方转移到下一候选
        assertThat(cb.allowRequest()).isFalse();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        cb.recordSuccess();
        assertThat(cb.allowRequest()).isTrue();    // 试探成功 → 闭合, 恢复正常放行
        assertThat(cb.allowRequest()).isTrue();
    }

    @Test
    void abandonedProbeDoesNotLockTheBreakerForever() throws InterruptedException {
        // 打断会取消整条流, 取消既不算成功也不算失败 —— 试探可能永远不报回。
        // 若只用布尔标志锁门, 该厂商就再也不会被试探, 等于永久熔断。
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofMillis(50));
        cb.recordFailure();
        Thread.sleep(80);

        assertThat(cb.allowRequest()).isTrue();    // 试探发出后就此失联
        assertThat(cb.allowRequest()).isFalse();

        Thread.sleep(80);                          // 超过一个 openDuration 仍无回音
        assertThat(cb.allowRequest()).isTrue();    // 允许下一次试探, 不会卡死
    }
}
