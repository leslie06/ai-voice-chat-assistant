package com.vca.gateway;

import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.exception.ProviderException;
import com.vca.domain.spi.AsrProvider;
import com.vca.domain.spi.LlmProvider;
import com.vca.domain.spi.S2sProvider;
import com.vca.domain.spi.TtsProvider;
import com.vca.gateway.quota.ConcurrencyQuota;
import com.vca.gateway.registry.ProviderRegistry;
import com.vca.gateway.resilience.CircuitBreaker;
import com.vca.gateway.resilience.CircuitBreakers;
import com.vca.gateway.router.GovernanceExecutor;
import com.vca.gateway.router.VendorRouter;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 熔断记账: <b>吐过元素后被取消</b> 必须算成功。
 *
 * <p>线上事故: 电话通了一两轮之后 AI 就大部分回合不吭声。根因是 ASR 的消费方拿到 final 就
 * {@code next()} 取走并取消整条流 —— 永远不走 onComplete, 于是没有任何一次成功被记账。一阵真故障
 * (本机代理被关, DashScope 连不上)把熔断器打开后, 它再也关不回去: 每次半开试探都因"被取消"而
 * 无人报回, 状态长期停在半开, 每个 openDuration 只放行一次, 其余回合全被"熔断打开, 跳过候选"挡掉,
 * 最后报"所有候选厂商均不可用"。ASR 只有一家候选, 没有故障转移可言, 表现就是电话那头一片安静。
 */
class GovernanceCancelAccountingTest {

    /** 只放一个候选(ASR 的真实形态: 单厂商, 无转移), 让记账问题无处遁形。 */
    private static GatewayProperties singleAsrCandidate() {
        GatewayProperties props = new GatewayProperties();
        GatewayProperties.CandidateProps aliyun = new GatewayProperties.CandidateProps();
        aliyun.setVendor(VendorType.ALIYUN);
        aliyun.setMaxConcurrency(50);
        GatewayProperties.CapabilityProps asr = new GatewayProperties.CapabilityProps();
        asr.setCandidates(List.of(aliyun));
        props.setAsr(asr);
        return props;
    }

    private record Wired(GovernanceExecutor executor, CircuitBreakers circuits) {
    }

    private static Wired wire(int failureThreshold, Duration openDuration) {
        ProviderRegistry registry = new ProviderRegistry(
                List.<AsrProvider>of(), List.<LlmProvider>of(),
                List.<TtsProvider>of(), List.<S2sProvider>of());
        CircuitBreakers circuits = new CircuitBreakers(failureThreshold, openDuration);
        VendorRouter router = new VendorRouter(registry, singleAsrCandidate());
        return new Wired(new GovernanceExecutor(router, new ConcurrencyQuota(), circuits), circuits);
    }

    @Test
    void cancelAfterEmittingCountsAsSuccessAndClosesCircuit() throws InterruptedException {
        Wired w = wire(1, Duration.ofMillis(80));   // 一次失败即熔断
        AtomicInteger calls = new AtomicInteger();

        // 1) 一次真故障 → 熔断打开
        w.executor().execute(Capability.ASR, VendorType.ALIYUN, c -> {
            calls.incrementAndGet();
            return Flux.<String>error(ProviderException.retryable(
                    VendorType.ALIYUN, Capability.ASR, "连不上", null));
        }).onErrorResume(e -> Flux.empty()).blockLast();
        assertThat(w.circuits().state(Capability.ASR, VendorType.ALIYUN))
                .isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(120);   // 冷却结束, 下一次调用是半开试探

        // 2) 试探成功, 但消费方拿到结果就取消(ASR 取 final 的真实形态): 永远不会有 onComplete
        String first = w.executor().execute(Capability.ASR, VendorType.ALIYUN, c -> {
            calls.incrementAndGet();
            return Flux.just("识别结果").concatWith(Flux.never());   // 不结束, 只被取消
        }).next().block(Duration.ofSeconds(1));

        assertThat(first).isEqualTo("识别结果");
        assertThat(calls.get()).isEqualTo(2);
        // 修复前这里是 HALF_OPEN —— 熔断器就此锁死, 后面每轮都被跳过
        assertThat(w.circuits().state(Capability.ASR, VendorType.ALIYUN))
                .isEqualTo(CircuitBreaker.State.CLOSED);

        // 3) 恢复之后必须能连续放行, 而不是每个 openDuration 才放一次
        for (int i = 0; i < 3; i++) {
            assertThat(w.executor().execute(Capability.ASR, VendorType.ALIYUN,
                            c -> Flux.just("再来一句").concatWith(Flux.never()))
                    .next().block(Duration.ofSeconds(1))).isEqualTo("再来一句");
        }
    }

    @Test
    void cancelWithoutAnyElementIsNeitherSuccessNorFailure() {
        Wired w = wire(2, Duration.ofSeconds(10));

        // 一次失败(未达阈值 2)
        w.executor().execute(Capability.ASR, VendorType.ALIYUN,
                        c -> Flux.<String>error(ProviderException.retryable(
                                VendorType.ALIYUN, Capability.ASR, "连不上", null)))
                .onErrorResume(e -> Flux.empty()).blockLast();

        // 一句话没说就被取消(开口了又没声音, 或整通电话挂了): 不能当成功把失败计数清零
        w.executor().execute(Capability.ASR, VendorType.ALIYUN, c -> Flux.<String>never())
                .take(1).timeout(Duration.ofMillis(50))
                .onErrorResume(e -> Flux.empty()).blockLast();

        // 再失败一次就该达到阈值 —— 若空取消被误记为成功, 这里还是 CLOSED
        w.executor().execute(Capability.ASR, VendorType.ALIYUN,
                        c -> Flux.<String>error(ProviderException.retryable(
                                VendorType.ALIYUN, Capability.ASR, "连不上", null)))
                .onErrorResume(e -> Flux.empty()).blockLast();

        assertThat(w.circuits().state(Capability.ASR, VendorType.ALIYUN))
                .isEqualTo(CircuitBreaker.State.OPEN);
    }
}
