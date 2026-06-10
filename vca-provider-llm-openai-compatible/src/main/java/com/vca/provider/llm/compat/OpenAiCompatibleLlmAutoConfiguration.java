package com.vca.provider.llm.compat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vca.domain.spi.LlmProvider;
import io.netty.channel.ChannelOption;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.ProxyProvider;

import java.net.URI;

/**
 * 注册 OpenAI 兼容 LLM provider。每个 enabled client 对应一个 LlmProvider bean,
 * 再由 gateway 按 vendor 做治理与切换。
 */
@AutoConfiguration
@EnableConfigurationProperties(OpenAiCompatibleLlmProperties.class)
public class OpenAiCompatibleLlmAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmAutoConfiguration.class);

    @Bean
    LlmProvider deepseekOpenAiCompatibleLlmProvider(OpenAiCompatibleLlmProperties props, Environment env) {
        return providerIfEnabled("deepseek", props, env);
    }

    @Bean
    LlmProvider qwenOpenAiCompatibleLlmProvider(OpenAiCompatibleLlmProperties props, Environment env) {
        return providerIfEnabled("qwen", props, env);
    }

    @Bean
    LlmProvider kimiOpenAiCompatibleLlmProvider(OpenAiCompatibleLlmProperties props, Environment env) {
        return providerIfEnabled("kimi", props, env);
    }

    private LlmProvider providerIfEnabled(String clientId, OpenAiCompatibleLlmProperties props, Environment env) {
        OpenAiCompatibleLlmProperties.Client client = props.getClients().get(clientId);
        if (client == null) {
            return null;
        }
        boolean disabled = explicitDisabled(clientId, env);
        if (disabled || (!client.isEnabled() && !hasKeys(client))) {
            log.info("{} LLM 未启用", client.displayName(clientId));
            return null;
        }
        return provider(clientId, props);
    }

    private LlmProvider provider(String clientId, OpenAiCompatibleLlmProperties props) {
        OpenAiCompatibleLlmProperties.Client client = props.getClients().get(clientId);
        if (client == null) {
            throw new IllegalArgumentException("未配置 OpenAI compatible LLM client: " + clientId);
        }
        WebClient webClient = WebClient.builder()
                .baseUrl(client.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient(clientId, client)))
                .build();
        return new OpenAiCompatibleLlmProvider(clientId, webClient, new ObjectMapper(), client);
    }

    private boolean explicitDisabled(String clientId, Environment env) {
        String key = switch (clientId) {
            case "deepseek" -> "DEEPSEEK_ENABLED";
            case "qwen" -> "QWEN_LLM_ENABLED";
            case "kimi" -> "KIMI_ENABLED";
            default -> null;
        };
        return key != null && "false".equalsIgnoreCase(env.getProperty(key));
    }

    private boolean hasKeys(OpenAiCompatibleLlmProperties.Client client) {
        return client.getKeys() != null
                && client.getKeys().stream().anyMatch(k -> k != null && !k.isBlank());
    }

    private HttpClient httpClient(String clientId, OpenAiCompatibleLlmProperties.Client client) {
        ConnectionProvider pool = ConnectionProvider.builder("llm-" + clientId)
                .maxIdleTime(client.getMaxIdleTime())
                .maxLifeTime(client.getMaxLifeTime())
                .evictInBackground(client.getMaxIdleTime())
                .build();
        HttpClient httpClient = HttpClient.create(pool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) client.getConnectTimeout().toMillis())
                .resolver(DefaultAddressResolverGroup.INSTANCE);

        if (client.getProxy() != null && !client.getProxy().isBlank()) {
            URI p = URI.create(client.getProxy().trim());
            int port = p.getPort() > 0 ? p.getPort() : 7890;
            httpClient = httpClient.proxy(spec -> spec
                    .type(ProxyProvider.Proxy.HTTP)
                    .host(p.getHost())
                    .port(port));
            log.info("{} 走代理: {}:{}", client.displayName(clientId), p.getHost(), port);
        }
        return httpClient;
    }
}
