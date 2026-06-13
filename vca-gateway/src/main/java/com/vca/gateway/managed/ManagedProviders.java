package com.vca.gateway.managed;

import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.exception.ProviderException;
import com.vca.domain.model.AsrConfig;
import com.vca.domain.model.AsrEvent;
import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.AudioFrame;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.Message;
import com.vca.domain.model.S2sConfig;
import com.vca.domain.model.TtsConfig;
import com.vca.domain.spi.AsrProvider;
import com.vca.domain.spi.LlmProvider;
import com.vca.domain.spi.S2sProvider;
import com.vca.domain.spi.TtsProvider;
import com.vca.gateway.registry.ProviderRegistry;
import com.vca.gateway.router.GovernanceExecutor;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 受治理的 SPI 门面集合。编排层拿到的就是这些 —— 调用签名与原始 SPI 完全一致,
 * 但内部经过 {@link GovernanceExecutor} 做候选选择/熔断/配额/故障转移。
 *
 * <p>故障转移到备选厂商时, 会用候选自带的 model/voice 重建配置(厂商相关参数不能沿用主厂商)。
 * {@code vendor()} 对门面无意义(厂商在调用时按会话配置动态选择), 故抛异常以明确语义。
 */
public final class ManagedProviders {

    private ManagedProviders() {
    }

    private static UnsupportedOperationException noSingleVendor() {
        return new UnsupportedOperationException("受治理门面无单一厂商, 厂商在调用时由会话配置选择");
    }

    /** 受治理 ASR */
    public static final class ManagedAsr implements AsrProvider {
        private final ProviderRegistry registry;
        private final GovernanceExecutor executor;

        public ManagedAsr(ProviderRegistry registry, GovernanceExecutor executor) {
            this.registry = registry;
            this.executor = executor;
        }

        @Override
        public VendorType vendor() {
            throw noSingleVendor();
        }

        @Override
        public Flux<AsrEvent> transcribe(Flux<AudioFrame> audio, AsrConfig cfg) {
            return executor.execute(Capability.ASR, cfg.vendor(), cand -> {
                AsrProvider p = registry.asr(cand.vendor()).orElseThrow(
                        () -> ProviderException.fatal(cand.vendor(), Capability.ASR, "未注册的 ASR 厂商", null));
                AsrConfig vc = new AsrConfig(cand.vendor(), cfg.language(), cfg.sampleRate(),
                        cfg.hotWords(), cfg.enablePunctuation());
                return p.transcribe(audio, vc);
            });
        }
    }

    /** 受治理 LLM */
    public static final class ManagedLlm implements LlmProvider {
        private final ProviderRegistry registry;
        private final GovernanceExecutor executor;

        public ManagedLlm(ProviderRegistry registry, GovernanceExecutor executor) {
            this.registry = registry;
            this.executor = executor;
        }

        @Override
        public VendorType vendor() {
            throw noSingleVendor();
        }

        @Override
        public Flux<String> chatStream(List<Message> history, LlmConfig cfg) {
            return executor.execute(Capability.LLM, cfg.vendor(), cand -> {
                LlmProvider p = registry.llm(cand.vendor()).orElseThrow(
                        () -> ProviderException.fatal(cand.vendor(), Capability.LLM, "未注册的 LLM 厂商", null));
                // 模型选择: 候选就是会话请求的主厂商时, 优先用会话显式指定的 model(支持前端切模型);
                // 故障转移到别的厂商时, model 是厂商相关的, 必须用候选自带的(不能把主厂商的模型名塞给它)。
                boolean isPrimary = cand.vendor() == cfg.vendor();
                String model = (isPrimary && cfg.model() != null && !cfg.model().isBlank())
                        ? cfg.model()
                        : cand.model();
                LlmConfig vc = new LlmConfig(cand.vendor(), model, cfg.systemPrompt(),
                        cfg.temperature(), cfg.maxTokens());
                return p.chatStream(history, vc);
            });
        }
    }

    /** 受治理 TTS */
    public static final class ManagedTts implements TtsProvider {
        private final ProviderRegistry registry;
        private final GovernanceExecutor executor;

        public ManagedTts(ProviderRegistry registry, GovernanceExecutor executor) {
            this.registry = registry;
            this.executor = executor;
        }

        @Override
        public VendorType vendor() {
            throw noSingleVendor();
        }

        @Override
        public Flux<AudioChunk> synthesize(Flux<String> textSegments, TtsConfig cfg) {
            return executor.execute(Capability.TTS, cfg.vendor(), cand -> {
                TtsProvider p = registry.tts(cand.vendor()).orElseThrow(
                        () -> ProviderException.fatal(cand.vendor(), Capability.TTS, "未注册的 TTS 厂商", null));
                // 同厂商: 尊重会话(前端)选的音色 —— 音色是厂商相关的, 只在本厂商内有效。
                // 跨厂商故障转移: 会话音色对新厂商无效, 改用候选自带音色(缺省再退回会话音色)。
                boolean sameVendor = cand.vendor() == cfg.vendor();
                String voice = (sameVendor && cfg.voice() != null && !cfg.voice().isBlank())
                        ? cfg.voice()
                        : (cand.voice() != null ? cand.voice() : cfg.voice());
                TtsConfig vc = new TtsConfig(cand.vendor(), voice, cfg.format(),
                        cfg.sampleRate(), cfg.speed());
                return p.synthesize(textSegments, vc);
            });
        }
    }

    /** 受治理端到端 S2S */
    public static final class ManagedS2s implements S2sProvider {
        private final ProviderRegistry registry;
        private final GovernanceExecutor executor;

        public ManagedS2s(ProviderRegistry registry, GovernanceExecutor executor) {
            this.registry = registry;
            this.executor = executor;
        }

        @Override
        public VendorType vendor() {
            throw noSingleVendor();
        }

        @Override
        public Flux<AudioChunk> converse(Flux<AudioFrame> audio, List<Message> history, S2sConfig cfg) {
            return executor.execute(Capability.S2S, cfg.vendor(), cand -> {
                S2sProvider p = registry.s2s(cand.vendor()).orElseThrow(
                        () -> ProviderException.fatal(cand.vendor(), Capability.S2S, "未注册的 S2S 厂商", null));
                String model = cand.model() != null ? cand.model() : cfg.model();
                String voice = cand.voice() != null ? cand.voice() : cfg.voice();
                S2sConfig vc = new S2sConfig(cand.vendor(), model, voice,
                        cfg.systemPrompt(), cfg.outputFormat());
                return p.converse(audio, history, vc);
            });
        }
    }
}
