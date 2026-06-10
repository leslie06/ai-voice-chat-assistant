package com.vca.gateway;

import com.vca.domain.spi.AsrProvider;
import com.vca.domain.spi.LlmProvider;
import com.vca.domain.spi.S2sProvider;
import com.vca.domain.spi.TtsProvider;
import com.vca.gateway.quota.ConcurrencyQuota;
import com.vca.gateway.registry.ProviderRegistry;
import com.vca.gateway.resilience.CircuitBreakers;
import com.vca.gateway.router.GovernanceExecutor;
import com.vca.gateway.router.VendorRouter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 治理层自动装配。把当前启用的所有 provider 模块收集进注册表, 组装出 {@link ProviderGateway}。
 * 用 {@link ObjectProvider} 容忍"某能力一个厂商都没有"的情况(返回空列表)。
 */
@AutoConfiguration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ProviderRegistry providerRegistry(ObjectProvider<AsrProvider> asr,
                                      ObjectProvider<LlmProvider> llm,
                                      ObjectProvider<TtsProvider> tts,
                                      ObjectProvider<S2sProvider> s2s) {
        return new ProviderRegistry(
                asr.stream().toList(), llm.stream().toList(),
                tts.stream().toList(), s2s.stream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    ConcurrencyQuota concurrencyQuota() {
        return new ConcurrencyQuota();
    }

    @Bean
    @ConditionalOnMissingBean
    CircuitBreakers circuitBreakers(GatewayProperties props) {
        return new CircuitBreakers(props.getCircuit().getFailureThreshold(),
                props.getCircuit().getOpenDuration());
    }

    @Bean
    @ConditionalOnMissingBean
    VendorRouter vendorRouter(ProviderRegistry registry, GatewayProperties props) {
        return new VendorRouter(registry, props);
    }

    @Bean
    @ConditionalOnMissingBean
    GovernanceExecutor governanceExecutor(VendorRouter router, ConcurrencyQuota quota, CircuitBreakers circuits) {
        return new GovernanceExecutor(router, quota, circuits);
    }

    @Bean
    @ConditionalOnMissingBean
    ProviderGateway providerGateway(ProviderRegistry registry, GovernanceExecutor executor) {
        return new ProviderGateway(registry, executor);
    }
}
