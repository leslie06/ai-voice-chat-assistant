package com.vca.gateway;

import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.exception.ProviderException;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.Message;
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
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GovernanceFailoverTest {

    private final AtomicInteger deepseekCalls = new AtomicInteger();

    /** DEEPSEEK: 调用即报可重试错(模拟厂商故障), 并计数被调用次数 */
    private LlmProvider failingDeepseek() {
        return new LlmProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.DEEPSEEK;
            }

            @Override
            public Flux<String> chatStream(List<Message> history, LlmConfig cfg) {
                deepseekCalls.incrementAndGet();
                return Flux.error(ProviderException.retryable(
                        VendorType.DEEPSEEK, Capability.LLM, "模拟故障", null));
            }
        };
    }

    /** QWEN: 正常流式返回, 校验拿到的是 qwen 的 model */
    private LlmProvider goodQwen() {
        return new LlmProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.QWEN;
            }

            @Override
            public Flux<String> chatStream(List<Message> history, LlmConfig cfg) {
                assertThat(cfg.vendor()).isEqualTo(VendorType.QWEN);
                assertThat(cfg.model()).isEqualTo("qwen-plus"); // 用候选自带 model, 非主厂商的
                return Flux.just("你好", "世界");
            }
        };
    }

    private GatewayProperties propsWith(int deepseekConcurrency, int failureThreshold) {
        GatewayProperties props = new GatewayProperties();
        GatewayProperties.CandidateProps d = new GatewayProperties.CandidateProps();
        d.setVendor(VendorType.DEEPSEEK);
        d.setModel("deepseek-chat");
        d.setMaxConcurrency(deepseekConcurrency);
        GatewayProperties.CandidateProps q = new GatewayProperties.CandidateProps();
        q.setVendor(VendorType.QWEN);
        q.setModel("qwen-plus");
        q.setMaxConcurrency(50);
        GatewayProperties.CapabilityProps llm = new GatewayProperties.CapabilityProps();
        llm.setCandidates(List.of(d, q));
        props.setLlm(llm);
        props.getCircuit().setFailureThreshold(failureThreshold);
        props.getCircuit().setOpenDuration(Duration.ofSeconds(10));
        return props;
    }

    private record Wired(ProviderGateway gateway, ConcurrencyQuota quota, CircuitBreakers circuits) {
    }

    private Wired wire(GatewayProperties props) {
        ProviderRegistry registry = new ProviderRegistry(
                List.<AsrProvider>of(),
                List.of(failingDeepseek(), goodQwen()),
                List.<TtsProvider>of(),
                List.<S2sProvider>of());
        ConcurrencyQuota quota = new ConcurrencyQuota();
        CircuitBreakers circuits = new CircuitBreakers(
                props.getCircuit().getFailureThreshold(), props.getCircuit().getOpenDuration());
        VendorRouter router = new VendorRouter(registry, props);
        GovernanceExecutor executor = new GovernanceExecutor(router, quota, circuits);
        return new Wired(new ProviderGateway(registry, executor), quota, circuits);
    }

    @Test
    void failsOverToNextVendorOnError() {
        Wired w = wire(propsWith(50, 5));

        // 主厂商 DEEPSEEK 报错(尚未吐字) → 自动转移到 QWEN
        StepVerifier.create(w.gateway().llm().chatStream(
                        List.of(Message.user("hi")),
                        LlmConfig.defaults(VendorType.DEEPSEEK, "deepseek-chat")))
                .expectNext("你好")
                .expectNext("世界")
                .verifyComplete();

        assertThat(deepseekCalls.get()).isEqualTo(1);
    }

    @Test
    void explicitUnregisteredVendorDoesNotSilentlyFallBack() {
        Wired w = wire(propsWith(50, 5));

        StepVerifier.create(w.gateway().llm().chatStream(
                        List.of(Message.user("hi")),
                        LlmConfig.defaults(VendorType.MOONSHOT, "kimi-k2.6")))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ProviderException.class);
                    ProviderException pe = (ProviderException) e;
                    assertThat(pe.vendor()).isEqualTo(VendorType.MOONSHOT);
                    assertThat(pe.getMessage()).contains("未注册的 LLM 厂商");
                })
                .verify();
    }

    @Test
    void skipsCircuitOpenPrimaryWithoutCallingIt() {
        Wired w = wire(propsWith(50, 1)); // 阈值 1: 一次失败即熔断 DEEPSEEK
        LlmConfig cfg = LlmConfig.defaults(VendorType.DEEPSEEK, "deepseek-chat");

        // 第一次: DEEPSEEK 失败一次 → 熔断打开, 转移 QWEN 成功
        StepVerifier.create(w.gateway().llm().chatStream(List.of(Message.user("a")), cfg))
                .expectNext("你好").expectNext("世界").verifyComplete();
        assertThat(w.circuits().state(Capability.LLM, VendorType.DEEPSEEK))
                .isEqualTo(CircuitBreaker.State.OPEN);

        // 第二次: DEEPSEEK 熔断打开 → 订阅前直接跳过, 不再调用它
        StepVerifier.create(w.gateway().llm().chatStream(List.of(Message.user("b")), cfg))
                .expectNext("你好").expectNext("世界").verifyComplete();

        assertThat(deepseekCalls.get()).isEqualTo(1); // 只在第一次被调用过
    }

    @Test
    void quotaFullFailsOverWithoutTrippingCircuit() {
        Wired w = wire(propsWith(1, 5)); // DEEPSEEK 并发上限 1

        // 先占满 DEEPSEEK 的唯一许可
        var holder = w.quota().gate(Capability.LLM, VendorType.DEEPSEEK, 1,
                Flux.just("x").concatWith(Flux.never())).subscribe();

        // 主厂商配额满 → 转移 QWEN; 且配额满不应算作熔断失败
        StepVerifier.create(w.gateway().llm().chatStream(
                        List.of(Message.user("hi")),
                        LlmConfig.defaults(VendorType.DEEPSEEK, "deepseek-chat")))
                .expectNext("你好").expectNext("世界").verifyComplete();

        assertThat(deepseekCalls.get()).isEqualTo(0); // 配额拦在调用之前
        assertThat(w.circuits().state(Capability.LLM, VendorType.DEEPSEEK))
                .isEqualTo(CircuitBreaker.State.CLOSED); // 容量问题, 熔断器不受影响
        holder.dispose();
    }
}
