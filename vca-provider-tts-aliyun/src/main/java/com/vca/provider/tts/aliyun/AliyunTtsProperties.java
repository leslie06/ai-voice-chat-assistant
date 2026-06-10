package com.vca.provider.tts.aliyun;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里云 DashScope 流式合成(CosyVoice)配置。
 *
 * <pre>
 * vca:
 *   providers:
 *     tts:
 *       aliyun:
 *         enabled: true
 *         api-key: ${DASHSCOPE_API_KEY}
 *         model: cosyvoice-v1
 * </pre>
 *
 * <p>输出固定为 PCM 24kHz 单声道 16bit(前端按此采样率播放)。
 */
@ConfigurationProperties(prefix = "vca.providers.tts.aliyun")
public class AliyunTtsProperties {

    private boolean enabled = false;
    private String apiKey = "";
    private String model = "cosyvoice-v1";

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
}
