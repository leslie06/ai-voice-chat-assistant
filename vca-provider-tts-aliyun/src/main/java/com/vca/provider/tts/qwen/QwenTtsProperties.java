package com.vca.provider.tts.qwen;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里云 Qwen-TTS(通义千问语音, Realtime 流式)配置。与 CosyVoice 是不同的模型与接口。
 *
 * <pre>
 * vca:
 *   providers:
 *     tts:
 *       qwen:
 *         enabled: true
 *         api-key: ${DASHSCOPE_API_KEY}
 *         model: qwen3-tts-flash-realtime   # 流式 realtime 模型, 以 DashScope 文档为准
 * </pre>
 *
 * <p>音色(voice)由会话/网关候选传入(如 Cherry/Ethan/Chelsie...)。输出 PCM 24kHz 单声道 16bit。
 */
@ConfigurationProperties(prefix = "vca.providers.tts.qwen")
public class QwenTtsProperties {

    private boolean enabled = false;
    private String apiKey = "";
    /** Realtime 流式模型 id, 以 DashScope 文档为准 */
    private String model = "qwen3-tts-flash-realtime";

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
