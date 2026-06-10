package com.vca.provider.llm.deepseek;

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
 * DeepSeek 对话实现。DeepSeek 的接口与 OpenAI 兼容: POST /chat/completions, stream=true,
 * 返回 text/event-stream, 每个事件是一段 JSON, 末尾为 "data: [DONE]"。
 *
 * <p>本实现严格遵守 SPI 契约: 逐 token 流式返回, 订阅取消(打断)时 WebClient 会自动
 * 断开底层连接。
 */
public class DeepSeekLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekLlmProvider.class);
    private static final String DONE = "[DONE]";
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final DeepSeekProperties props;
    private final ApiKeyPool keyPool;

    public DeepSeekLlmProvider(WebClient webClient, ObjectMapper objectMapper, DeepSeekProperties props) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.props = props;
        this.keyPool = new ApiKeyPool(props.getKeys());
    }

    @Override
    public VendorType vendor() {
        return VendorType.DEEPSEEK;
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
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.value() == 429, resp ->
                        resp.bodyToMono(String.class).defaultIfEmpty("")
                                .map(b -> ProviderException.quota(VendorType.DEEPSEEK, Capability.LLM,
                                        "DeepSeek 限流(429): " + b)))
                .onStatus(s -> s.is5xxServerError(), resp ->
                        resp.bodyToMono(String.class).defaultIfEmpty("")
                                .map(b -> ProviderException.retryable(VendorType.DEEPSEEK, Capability.LLM,
                                        "DeepSeek 服务端错误(" + resp.statusCode().value() + "): " + b, null)))
                .onStatus(s -> s.is4xxClientError(), resp ->
                        resp.bodyToMono(String.class).defaultIfEmpty("")
                                .map(b -> ProviderException.fatal(VendorType.DEEPSEEK, Capability.LLM,
                                        "DeepSeek 请求错误(" + resp.statusCode().value() + "): " + b, null)))
                .bodyToFlux(SSE_TYPE)
                .takeUntil(sse -> DONE.equals(sse.data()))
                .mapNotNull(ServerSentEvent::data)
                .filter(data -> !DONE.equals(data) && !data.isBlank())
                .concatMap(this::extractContent)
                // 仅对"响应前断开"(请求阶段异常, 尚未吐出任何 token)做重试: 兜底复用死连接的
                // 瞬时失败, 重试幂等安全。其余错误(超时/限流/4xx/5xx)交给上层治理处理。
                .retryWhen(Retry.fixedDelay(props.getMaxRetries(), Duration.ofMillis(200))
                        .filter(e -> e instanceof WebClientRequestException)
                        .doBeforeRetry(rs -> log.warn("DeepSeek 响应前断开, 重试第{}次: {}",
                                rs.totalRetries() + 1, rs.failure().toString())))
                .timeout(props.getResponseTimeout(),
                        Mono.error(() -> ProviderException.retryable(VendorType.DEEPSEEK, Capability.LLM,
                                "DeepSeek 响应超时 " + props.getResponseTimeout(), null)))
                .doOnSubscribe(s -> log.debug("DeepSeek chatStream 开始, model={}, 历史{}条", model, history.size()))
                .doOnError(e -> log.warn("DeepSeek chatStream 出错: {}", e.toString()));
    }

    /** 解析单个 SSE data(JSON) -> delta.content; 无内容则发空流 */
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
            // 单个分片解析失败不应中断整条流, 记录并跳过
            log.debug("DeepSeek SSE 分片解析失败, 已跳过: {}", json);
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
        body.put("temperature", cfg.temperature());
        body.put("max_tokens", cfg.maxTokens());
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
