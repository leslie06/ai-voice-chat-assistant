package com.vca.gateway;

import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.exception.ProviderException;
import com.vca.gateway.quota.ConcurrencyQuota;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyQuotaTest {

    @Test
    void gateLimitsConcurrencyAndRejectsWhenFull() {
        ConcurrencyQuota quota = new ConcurrencyQuota();

        // 占满唯一的许可: 一条"开了但不结束"的流
        Flux<Integer> longRunning = Flux.just(1).concatWith(Flux.never());
        Disposable holder = quota.gate(Capability.LLM, VendorType.DEEPSEEK, 1, longRunning).subscribe();

        assertThat(quota.availablePermits(Capability.LLM, VendorType.DEEPSEEK)).isEqualTo(0);

        // 配额已满: 第二次应立即以配额异常失败(触发转移)
        StepVerifier.create(quota.gate(Capability.LLM, VendorType.DEEPSEEK, 1, Flux.just(99)))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ProviderException.class);
                    assertThat(((ProviderException) e).isQuotaExceeded()).isTrue();
                })
                .verify();

        // 释放后许可归还
        holder.dispose();
        assertThat(quota.availablePermits(Capability.LLM, VendorType.DEEPSEEK)).isEqualTo(1);
    }
}
