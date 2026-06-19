package com.vca.provider.s2s.qwen;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通义千问 Qwen-Omni 实时端到端语音配置。
 *
 * <pre>
 * vca:
 *   providers:
 *     s2s:
 *       qwen:
 *         enabled: true
 *         api-key: ${DASHSCOPE_API_KEY}
 *         model: qwen3-omni-flash-realtime
 *         voice: Chelsie
 * </pre>
 *
 * <p>复用阿里云 DashScope 的同一把 {@code DASHSCOPE_API_KEY}(与 ASR/TTS 一致)。
 */
@ConfigurationProperties(prefix = "vca.providers.s2s.qwen")
public class QwenOmniProperties {

    private boolean enabled = false;
    private String apiKey = "";
    /** Qwen-Omni 实时语音模型 */
    private String model = "qwen3-omni-flash-realtime";
    /** 默认回复音色; 可被会话配置/治理候选覆盖 */
    private String voice = "Chelsie";

    /**
     * 持久会话({@code open})用<b>服务端 VAD</b>接管回合切分与打断, 以下为其调参(每轮 {@code converse} 不用):
     * 阈值越高越不易被噪声/回声误触发开口; 静音时长决定多久算"说完"; prefix-padding 是开口前回补的音频。
     */
    private float turnDetectionThreshold = 0.5f;
    /** 服务端 VAD 判停所需的尾部静音时长(ms): 越大越不容易抢话、但回复起步越慢 */
    private int turnDetectionSilenceMs = 800;
    /** 服务端 VAD 在开口点前回补的音频(ms), 避免切掉第一个字 */
    private int turnDetectionPrefixPaddingMs = 300;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
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

    public float getTurnDetectionThreshold() {
        return turnDetectionThreshold;
    }

    public void setTurnDetectionThreshold(float turnDetectionThreshold) {
        this.turnDetectionThreshold = turnDetectionThreshold;
    }

    public int getTurnDetectionSilenceMs() {
        return turnDetectionSilenceMs;
    }

    public void setTurnDetectionSilenceMs(int turnDetectionSilenceMs) {
        this.turnDetectionSilenceMs = turnDetectionSilenceMs;
    }

    public int getTurnDetectionPrefixPaddingMs() {
        return turnDetectionPrefixPaddingMs;
    }

    public void setTurnDetectionPrefixPaddingMs(int turnDetectionPrefixPaddingMs) {
        this.turnDetectionPrefixPaddingMs = turnDetectionPrefixPaddingMs;
    }
}
