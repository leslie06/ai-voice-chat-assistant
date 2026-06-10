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
}
