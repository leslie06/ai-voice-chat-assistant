package com.vca.provider.llm.deepseek;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek 厂商配置。密钥(多 Key)放在这里由厂商模块自管 —— 治理层(gateway)落地前,
 * 先用模块内简单轮询; gateway 上线后可改为由其统一注入。
 *
 * <p>application.yml 示例:
 * <pre>
 * vca:
 *   providers:
 *     llm:
 *       deepseek:
 *         enabled: true
 *         base-url: https://api.deepseek.com
 *         default-model: deepseek-chat
 *         keys: ["sk-xxx", "sk-yyy"]   # 多 Key 横向扩并发配额
 * </pre>
 */
@ConfigurationProperties(prefix = "vca.providers.llm.deepseek")
public class DeepSeekProperties {

    /** 是否启用该厂商(可插拔开关) */
    private boolean enabled = false;

    /** API 基址 */
    private String baseUrl = "https://api.deepseek.com";

    /** 默认模型(会话未显式指定时使用) */
    private String defaultModel = "deepseek-chat";

    /** API Key 列表, 支持多 Key 轮询分摊并发配额 */
    private List<String> keys = new ArrayList<>();

    /** 建连超时 */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** 整体响应超时(流式下指首字节及空闲) */
    private Duration responseTimeout = Duration.ofSeconds(30);

    /**
     * 连接池空闲剔除时长。须短于对端(DeepSeek/网关)keep-alive 关闭时长, 否则会复用到
     * 已被对端关闭的死连接, 发请求即断("Connection prematurely closed BEFORE response")。
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
     * 可选 HTTP 代理(如 {@code http://127.0.0.1:7890})。仅作用于本 DeepSeek 客户端,
     * 不影响其它厂商(避免把国内的阿里云 DashScope 也塞进代理)。留空=直连。
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
