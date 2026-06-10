package com.vca.provider.llm.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.exception.ProviderException;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.Message;
import com.vca.domain.spi.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容文本对话实现。适配 DeepSeek、DashScope/Qwen、Kimi 私有网关等同形接口:
 * POST /chat/completions, stream=true, SSE data 里读取 choices[0].delta.content。
 */
public class OpenAiCompatibleLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmProvider.class);
    private static final String DONE = "[DONE]";
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final String clientId;
    private final String providerName;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final OpenAiCompatibleLlmProperties.Client props;
    private final ApiKeyPool keyPool;

    public OpenAiCompatibleLlmProvider(String clientId, WebClient webClient, ObjectMapper objectMapper,
                                       OpenAiCompatibleLlmProperties.Client props) {
        if (props.getVendor() == null) {
            throw new IllegalArgumentException("OpenAI compatible LLM client " + clientId + " 未配置 vendor");
        }
        this.clientId = clientId;
        this.providerName = props.displayName(clientId);
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.props = props;
        this.keyPool = new ApiKeyPool(providerName, props.getKeys());
    }

    @Override
    public VendorType vendor() {
        return props.getVendor();
    }

    @Override
    public Flux<String> chatStream(List<Message> history, LlmConfig cfg) {
        final String apiKey = keyPool.next();
        final String model = (cfg.model() == null || cfg.model().isBlank())
                ? props.getDefaultModel() : cfg.model();
        final Map<String, Object> body = buildRequest(history, cfg, model);

        return webClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.ALL)
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.value() == 429, resp ->
                        resp.bodyToMono(String.class).defaultIfEmpty("")
                                .map(b -> ProviderException.quota(vendor(), Capability.LLM,
                                        providerName + " 限流(429): " + b)))
                .onStatus(s -> s.is5xxServerError(), resp ->
                        resp.bodyToMono(String.class).defaultIfEmpty("")
                                .map(b -> ProviderException.retryable(vendor(), Capability.LLM,
                                        providerName + " 服务端错误(" + resp.statusCode().value() + "): " + b, null)))
                .onStatus(s -> s.is4xxClientError(), resp ->
                        resp.bodyToMono(String.class).defaultIfEmpty("")
                                .map(b -> ProviderException.fatal(vendor(), Capability.LLM,
                                        providerName + " 请求错误(" + resp.statusCode().value() + "): " + b, null)))
                .bodyToFlux(SSE_TYPE)
                .takeUntil(sse -> DONE.equals(sse.data()))
                .mapNotNull(ServerSentEvent::data)
                .filter(data -> !DONE.equals(data) && !data.isBlank())
                .concatMap(this::extractContent)
                .retryWhen(Retry.fixedDelay(props.getMaxRetries(), Duration.ofMillis(200))
                        .filter(e -> e instanceof WebClientRequestException)
                        .doBeforeRetry(rs -> log.warn("{} 响应前断开, 重试第{}次: {}",
                                providerName, rs.totalRetries() + 1, rs.failure().toString())))
                .timeout(props.getResponseTimeout(),
                        Mono.error(() -> ProviderException.retryable(vendor(), Capability.LLM,
                                providerName + " 响应超时 " + props.getResponseTimeout(), null)))
                .doOnSubscribe(s -> log.debug("{} chatStream 开始, client={}, model={}, 历史{}条",
                        providerName, clientId, model, history.size()))
                .doOnError(e -> log.warn("{} chatStream 出错: {}", providerName, e.toString()));
    }

    private Flux<String> extractContent(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return Flux.empty();
            }
            String content = choices.get(0).path("delta").path("content").asText("");
            return content.isEmpty() ? Flux.empty() : Flux.just(content);
        } catch (Exception e) {
            log.debug("{} SSE 分片解析失败, 已跳过: {}", providerName, json);
            return Flux.empty();
        }
    }

    private Map<String, Object> buildRequest(List<Message> history, LlmConfig cfg, String model) {
        List<Map<String, String>> messages = new ArrayList<>(history.size());
        for (Message m : history) {
            Map<String, String> mm = new LinkedHashMap<>(2);
            mm.put("role", roleOf(m.role()));
            mm.put("content", m.content());
            messages.add(mm);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        if (props.isIncludeTemperature()) {
            body.put("temperature", cfg.temperature());
        }
        if (props.getMaxTokensField() != null && !props.getMaxTokensField().isBlank()) {
            body.put(props.getMaxTokensField(), cfg.maxTokens());
        }
        if (props.getExtraBody() != null && !props.getExtraBody().isEmpty()) {
            body.putAll(props.getExtraBody());
        }
        return body;
    }

    private static String roleOf(Message.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }
}
