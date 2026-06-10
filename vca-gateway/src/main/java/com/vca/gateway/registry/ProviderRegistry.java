package com.vca.gateway.registry;

import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.spi.AsrProvider;
import com.vca.domain.spi.LlmProvider;
import com.vca.domain.spi.S2sProvider;
import com.vca.domain.spi.TtsProvider;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 厂商实现注册表: 收集所有 SPI provider bean, 按 (能力, 厂商) 建立索引。
 * 这是"多厂商可插拔"的运行期落点 —— 启用了哪些 provider 模块, 这里就有哪些可选项。
 */
public class ProviderRegistry {

    private final Map<VendorType, AsrProvider> asr = new EnumMap<>(VendorType.class);
    private final Map<VendorType, LlmProvider> llm = new EnumMap<>(VendorType.class);
    private final Map<VendorType, TtsProvider> tts = new EnumMap<>(VendorType.class);
    private final Map<VendorType, S2sProvider> s2s = new EnumMap<>(VendorType.class);

    public ProviderRegistry(Collection<AsrProvider> asrProviders,
                            Collection<LlmProvider> llmProviders,
                            Collection<TtsProvider> ttsProviders,
                            Collection<S2sProvider> s2sProviders) {
        asrProviders.forEach(p -> put(Capability.ASR, asr, p.vendor(), p));
        llmProviders.forEach(p -> put(Capability.LLM, llm, p.vendor(), p));
        ttsProviders.forEach(p -> put(Capability.TTS, tts, p.vendor(), p));
        s2sProviders.forEach(p -> put(Capability.S2S, s2s, p.vendor(), p));
    }

    /** 同一 (能力,厂商) 只能有一个实现; 重复(如桩与真实并存)直接 fail-fast, 避免静默覆盖 */
    private static <T> void put(Capability cap, Map<VendorType, T> map, VendorType vendor, T provider) {
        T existing = map.putIfAbsent(vendor, provider);
        if (existing != null) {
            throw new IllegalStateException(String.format(
                    "%s 厂商 %s 注册了多个实现: %s 与 %s。请确保同一能力下每个厂商只启用一个 provider"
                            + "(例如启用真实阿里云时关闭对应 dev 桩)。",
                    cap, vendor, existing.getClass().getSimpleName(), provider.getClass().getSimpleName()));
        }
    }

    public Optional<AsrProvider> asr(VendorType v) {
        return Optional.ofNullable(asr.get(v));
    }

    public Optional<LlmProvider> llm(VendorType v) {
        return Optional.ofNullable(llm.get(v));
    }

    public Optional<TtsProvider> tts(VendorType v) {
        return Optional.ofNullable(tts.get(v));
    }

    public Optional<S2sProvider> s2s(VendorType v) {
        return Optional.ofNullable(s2s.get(v));
    }

    /** 某能力下某厂商是否已注册(已启用) */
    public boolean has(Capability capability, VendorType vendor) {
        return switch (capability) {
            case ASR -> asr.containsKey(vendor);
            case LLM -> llm.containsKey(vendor);
            case TTS -> tts.containsKey(vendor);
            case S2S -> s2s.containsKey(vendor);
        };
    }

    /** 某能力下所有已注册厂商 */
    public Set<VendorType> vendors(Capability capability) {
        return switch (capability) {
            case ASR -> asr.keySet();
            case LLM -> llm.keySet();
            case TTS -> tts.keySet();
            case S2S -> s2s.keySet();
        };
    }
}
