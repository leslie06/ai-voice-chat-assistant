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
 *         vocabulary-id: vocab-xxxx   # 可选: 热词表 id, 见下
 * </pre>
 */
@ConfigurationProperties(prefix = "vca.providers.asr.aliyun")
public class AliyunAsrProperties {

    private boolean enabled = false;
    private String apiKey = "";
    /** 实时识别模型 */
    private String model = "paraformer-realtime-v2";
    /**
     * 热词表 id(可选)。DashScope 的 v2 系列<b>不接受随请求内联的热词数组</b> ——
     * 热词要先经它的 vocabulary 接口注册成一张表, 拿到 id 后在识别时引用。
     * 所以 {@code AsrConfig.hotWords()} 那个 List 在本厂商这里传不进去, 只能走这个 id;
     * 配了 hotWords 却没配它, provider 会打一次 warn 而不是默默忽略。
     */
    private String vocabularyId = "";

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

    public String getVocabularyId() {
        return vocabularyId;
    }

    public void setVocabularyId(String vocabularyId) {
        this.vocabularyId = vocabularyId;
    }
}
