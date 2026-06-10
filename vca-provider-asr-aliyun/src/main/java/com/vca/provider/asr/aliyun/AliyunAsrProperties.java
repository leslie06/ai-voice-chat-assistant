package com.vca.provider.asr.aliyun;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里云 DashScope 实时识别配置。
 *
 * <pre>
 * vca:
 *   providers:
 *     asr:
 *       aliyun:
 *         enabled: true
 *         api-key: ${DASHSCOPE_API_KEY}
 *         model: paraformer-realtime-v2
 * </pre>
 */
@ConfigurationProperties(prefix = "vca.providers.asr.aliyun")
public class AliyunAsrProperties {

    private boolean enabled = false;
    private String apiKey = "";
    /** 实时识别模型 */
    private String model = "paraformer-realtime-v2";

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
