package com.vca.provider.llm.compat;

import com.vca.domain.enums.VendorType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容文本 LLM 配置。DeepSeek、DashScope/Qwen、Kimi 私有网关等只要遵循
 * /chat/completions + SSE delta.content, 就可以通过这里配置成一个 LlmProvider。
 */
@ConfigurationProperties(prefix = "vca.providers.llm.openai-compatible")
public class OpenAiCompatibleLlmProperties {

    private Map<String, Client> clients = new LinkedHashMap<>();

    public Map<String, Client> getClients() {
        return clients;
    }

    public void setClients(Map<String, Client> clients) {
        this.clients = clients;
    }

    public static class Client {
        private boolean enabled = false;
        private VendorType vendor;
        private String name = "";
        private String baseUrl = "";
        private String defaultModel = "";
        private List<String> keys = new ArrayList<>();
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration responseTimeout = Duration.ofSeconds(30);
        private Duration maxIdleTime = Duration.ofSeconds(5);
        private Duration maxLifeTime = Duration.ofSeconds(30);
        private int maxRetries = 1;
        private String proxy;
        private String maxTokensField = "max_tokens";
        private boolean includeTemperature = true;
        private Map<String, Object> extraBody = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public VendorType getVendor() {
            return vendor;
        }

        public void setVendor(VendorType vendor) {
            this.vendor = vendor;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
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

        public String getMaxTokensField() {
            return maxTokensField;
        }

        public void setMaxTokensField(String maxTokensField) {
            this.maxTokensField = maxTokensField;
        }

        public boolean isIncludeTemperature() {
            return includeTemperature;
        }

        public void setIncludeTemperature(boolean includeTemperature) {
            this.includeTemperature = includeTemperature;
        }

        public Map<String, Object> getExtraBody() {
            return extraBody;
        }

        public void setExtraBody(Map<String, Object> extraBody) {
            this.extraBody = extraBody;
        }

        String displayName(String id) {
            return name == null || name.isBlank() ? id : name;
        }
    }
}
