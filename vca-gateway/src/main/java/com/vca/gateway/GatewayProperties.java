package com.vca.gateway;

import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 治理层配置。按能力配置有序候选厂商(含并发配额)与熔断阈值。
 *
 * <pre>
 * vca:
 *   gateway:
 *     circuit:
 *       failure-threshold: 5
 *       open-duration: 10s
 *     llm:
 *       candidates:
 *         - { vendor: deepseek, model: deepseek-chat, max-concurrency: 50 }
 *         - { vendor: qwen,     model: qwen-plus,     max-concurrency: 50 }
 *     asr:
 *       candidates:
 *         - { vendor: aliyun, max-concurrency: 100 }
 *     tts:
 *       candidates:
 *         - { vendor: aliyun, voice: longxiaochun, max-concurrency: 100 }
 * </pre>
 */
@ConfigurationProperties(prefix = "vca.gateway")
public class GatewayProperties {

    private CapabilityProps asr = new CapabilityProps();
    private CapabilityProps llm = new CapabilityProps();
    private CapabilityProps tts = new CapabilityProps();
    private CapabilityProps s2s = new CapabilityProps();
    private CircuitProps circuit = new CircuitProps();

    /** 取某能力的候选列表(转为不可变的 Candidate 记录) */
    public List<Candidate> candidates(Capability capability) {
        CapabilityProps cap = switch (capability) {
            case ASR -> asr;
            case LLM -> llm;
            case TTS -> tts;
            case S2S -> s2s;
        };
        List<Candidate> result = new ArrayList<>(cap.candidates.size());
        for (CandidateProps c : cap.candidates) {
            result.add(new Candidate(c.vendor, c.model, c.voice, c.maxConcurrency));
        }
        return result;
    }

    public CapabilityProps getAsr() {
        return asr;
    }

    public void setAsr(CapabilityProps asr) {
        this.asr = asr;
    }

    public CapabilityProps getLlm() {
        return llm;
    }

    public void setLlm(CapabilityProps llm) {
        this.llm = llm;
    }

    public CapabilityProps getTts() {
        return tts;
    }

    public void setTts(CapabilityProps tts) {
        this.tts = tts;
    }

    public CapabilityProps getS2s() {
        return s2s;
    }

    public void setS2s(CapabilityProps s2s) {
        this.s2s = s2s;
    }

    public CircuitProps getCircuit() {
        return circuit;
    }

    public void setCircuit(CircuitProps circuit) {
        this.circuit = circuit;
    }

    /** 某能力的候选列表容器 */
    public static class CapabilityProps {
        private List<CandidateProps> candidates = new ArrayList<>();

        public List<CandidateProps> getCandidates() {
            return candidates;
        }

        public void setCandidates(List<CandidateProps> candidates) {
            this.candidates = candidates;
        }
    }

    /** 单个候选(可变 bean, 供属性绑定) */
    public static class CandidateProps {
        private VendorType vendor;
        private String model;
        private String voice;
        private int maxConcurrency = 50;

        public VendorType getVendor() {
            return vendor;
        }

        public void setVendor(VendorType vendor) {
            this.vendor = vendor;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getVoice() {
            return voice;
        }

        public void setVoice(String voice) {
            this.voice = voice;
        }

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }
    }

    /** 熔断参数 */
    public static class CircuitProps {
        /** 连续失败达到此阈值则熔断 */
        private int failureThreshold = 5;
        /** 熔断打开后多久进入半开试探 */
        private Duration openDuration = Duration.ofSeconds(10);

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public Duration getOpenDuration() {
            return openDuration;
        }

        public void setOpenDuration(Duration openDuration) {
            this.openDuration = openDuration;
        }
    }
}
