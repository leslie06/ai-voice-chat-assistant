package com.vca.gateway;

import com.vca.domain.spi.AsrProvider;
import com.vca.domain.spi.LlmProvider;
import com.vca.domain.spi.S2sProvider;
import com.vca.domain.spi.TtsProvider;
import com.vca.gateway.managed.ManagedProviders;
import com.vca.gateway.registry.ProviderRegistry;
import com.vca.gateway.router.GovernanceExecutor;

/**
 * 治理层对外门面。编排层(vca-orchestrator)从这里取受治理的 SPI provider:
 * 调用方式与裸 provider 一致, 但自动获得"按会话配置选厂商 + 熔断 + 配额 + 故障转移"。
 *
 * <p>构建一路会话时, 把这四个 provider 注入 {@code ConversationSession} 即可, 编排层完全
 * 不感知具体厂商与治理细节。
 */
public class ProviderGateway {

    private final AsrProvider asr;
    private final LlmProvider llm;
    private final TtsProvider tts;
    private final S2sProvider s2s;

    public ProviderGateway(ProviderRegistry registry, GovernanceExecutor executor) {
        this.asr = new ManagedProviders.ManagedAsr(registry, executor);
        this.llm = new ManagedProviders.ManagedLlm(registry, executor);
        this.tts = new ManagedProviders.ManagedTts(registry, executor);
        this.s2s = new ManagedProviders.ManagedS2s(registry, executor);
    }

    public AsrProvider asr() {
        return asr;
    }

    public LlmProvider llm() {
        return llm;
    }

    public TtsProvider tts() {
        return tts;
    }

    public S2sProvider s2s() {
        return s2s;
    }
}
