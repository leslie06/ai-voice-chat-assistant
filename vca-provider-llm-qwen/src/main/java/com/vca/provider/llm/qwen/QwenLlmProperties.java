package com.vca.provider.llm.qwen;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 通义千问(Qwen)文本 LLM 配置。走 DashScope 的 OpenAI 兼容接口
 * ({@code https://dashscope.aliyuncs.com/compatible-mode/v1}), 与 DeepSeek/OpenAI 同形,
 * 故复用同一套 SSE 流式解析。密钥即 DashScope 的 {@code DASHSCOPE_API_KEY}(与 ASR/TTS/S2S 共用)。
 *
 * <p>application.yml 示例:
 * <pre>
 * vca:
 *   providers:
 *     llm:
 *       qwen:
 *         enabled: true
 *         base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
 *         default-model: qwen3.7-plus
 *         keys: ["sk-xxx"]
 * </pre>
 */
@ConfigurationProperties(prefix = "vca.providers.llm.qwen")
public class QwenLlmProperties {

    /** 是否启用该厂商(可插拔开关) */
    private boolean enabled = false;

    /** API 基址: DashScope OpenAI 兼容模式 */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** 默认模型(会话未显式指定时使用) */
    private String defaultModel = "qwen3.7-plus";

    /** API Key 列表(DashScope key), 支持多 Key 轮询分摊并发配额 */
    private List<String> keys = new ArrayList<>();

    /** 建连超时 */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** 整体响应超时(流式下指首字节及空闲) */
    private Duration responseTimeout = Duration.ofSeconds(30);

    /**
     * 连接池空闲剔除时长。须短于对端 keep-alive 关闭时长, 否则会复用到已被对端关闭的死连接,
     * 发请求即断("Connection prematurely closed BEFORE response")。
     */
    private Duration maxIdleTime = Duration.ofSeconds(5);

    /** 连接最长存活时长, 到期强制重建, 兜底长连接老化 */
    private Duration maxLifeTime = Duration.ofSeconds(30);

    /**
     * "响应前断开"类请求异常的重试次数。此类错误发生在收到任何响应之前(一个 token 都没吐),
     * 重试幂等安全; 主要兜底复用死连接的瞬时失败。
     */
    private int maxRetries = 1;

    /**
     * 可选 HTTP 代理。DashScope 在国内, 一般无需代理(留空=直连); 仅作用于本客户端。
     */
    private String proxy;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public List<String> getKeys() {
        return keys;
    }

    public void setKeys(List<String> keys) {
        this.keys = keys;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    public void setResponseTimeout(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
    }

    public Duration getMaxIdleTime() {
        return maxIdleTime;
    }

    public void setMaxIdleTime(Duration maxIdleTime) {
        this.maxIdleTime = maxIdleTime;
    }

    public Duration getMaxLifeTime() {
        return maxLifeTime;
    }

    public void setMaxLifeTime(Duration maxLifeTime) {
        this.maxLifeTime = maxLifeTime;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public String getProxy() {
        return proxy;
    }

    public void setProxy(String proxy) {
        this.proxy = proxy;
    }
}
