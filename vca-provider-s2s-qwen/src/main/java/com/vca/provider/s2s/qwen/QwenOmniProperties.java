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
}
