package com.vca.provider.llm.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.exception.ProviderException;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.LlmEvent;
import com.vca.domain.model.Message;
import com.vca.domain.model.ToolCall;
import com.vca.domain.model.ToolSpec;
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
 *
 * <p>支持 function-calling: 传入 {@code tools} 时, 在请求体带 {@code tools}/{@code tool_choice=auto},
 * 并把流式 {@code delta.tool_calls}(参数按 index 跨分片累积)在流末尾拼成一次
 * {@link LlmEvent.ToolCalls} 事件。{@link #chat} 是主路径, {@link #chatStream} 退化为只取其文本增量。
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
        // 不带工具的纯文本路径: 复用 chat() 同一条 SSE 实现, 只取文本增量。
        return chat(history, cfg, List.of())
                .filter(ev -> ev instanceof LlmEvent.TextDelta)
                .map(ev -> ((LlmEvent.TextDelta) ev).text());
    }

    @Override
    public Flux<LlmEvent> chat(List<Message> history, LlmConfig cfg, List<ToolSpec> tools) {
        return Flux.defer(() -> {
            final String apiKey = keyPool.next();
            final String model = (cfg.model() == null || cfg.model().isBlank())
                    ? props.getDefaultModel() : cfg.model();
            final Map<String, Object> body = buildRequest(history, cfg, model, tools);
            // 工具调用分片累积器: 每次订阅独立一份(在 defer 内创建), 按 tool_calls[i].index 归集
            final Map<Integer, ToolCallBuf> toolBufs = new LinkedHashMap<>();

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
                    .concatMap(data -> parseChunk(data, toolBufs))
                    // 流自然结束时, 若累积到工具调用则补发一次拼装完整的 ToolCalls
                    .concatWith(Flux.defer(() -> finishToolCalls(toolBufs)))
                    .retryWhen(Retry.fixedDelay(props.getMaxRetries(), Duration.ofMillis(200))
                            .filter(e -> e instanceof WebClientRequestException)
                            .doBeforeRetry(rs -> log.warn("{} 响应前断开, 重试第{}次: {}",
                                    providerName, rs.totalRetries() + 1, rs.failure().toString())))
                    .timeout(props.getResponseTimeout(),
                            Mono.error(() -> ProviderException.retryable(vendor(), Capability.LLM,
                                    providerName + " 响应超时 " + props.getResponseTimeout(), null)))
                    .doOnSubscribe(s -> log.debug("{} chat 开始, client={}, model={}, 历史{}条, 工具{}个",
                            providerName, clientId, model, history.size(), tools == null ? 0 : tools.size()))
                    .doOnError(e -> log.warn("{} chat 出错: {}", providerName, e.toString()));
        });
    }

    /** 解析一个 SSE data 分片: 累积工具调用; 有文本增量则发 {@link LlmEvent.TextDelta}。 */
    private Flux<LlmEvent> parseChunk(String json, Map<Integer, ToolCallBuf> toolBufs) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return Flux.empty();
            }
            JsonNode delta = choices.get(0).path("delta");
            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    int idx = tc.path("index").asInt(0);
                    ToolCallBuf buf = toolBufs.computeIfAbsent(idx, k -> new ToolCallBuf());
                    if (tc.hasNonNull("id")) {
                        buf.id = tc.get("id").asText();
                    }
                    JsonNode fn = tc.path("function");
                    if (fn.hasNonNull("name")) {
                        buf.name = fn.get("name").asText();
                    }
                    if (fn.has("arguments")) {
                        buf.args.append(fn.path("arguments").asText(""));
                    }
                }
            }
            // content 在工具调用分片里常为 JSON null, 只取真正的字符串(避免 NullNode 被读成 "null")
            JsonNode contentNode = delta.get("content");
            String content = (contentNode != null && contentNode.isTextual()) ? contentNode.asText() : "";
            return content.isEmpty() ? Flux.empty() : Flux.just(new LlmEvent.TextDelta(content));
        } catch (Exception e) {
            log.debug("{} SSE 分片解析失败, 已跳过: {}", providerName, json);
            return Flux.empty();
        }
    }

    /** 把累积的工具调用拼成一次 ToolCalls 事件; 无调用则空。 */
    private Flux<LlmEvent> finishToolCalls(Map<Integer, ToolCallBuf> toolBufs) {
        if (toolBufs.isEmpty()) {
            return Flux.empty();
        }
        List<ToolCall> calls = new ArrayList<>(toolBufs.size());
        for (ToolCallBuf b : toolBufs.values()) {
            if (b.name == null || b.name.isBlank()) {
                continue;
            }
            String id = (b.id == null || b.id.isBlank()) ? "call_" + b.name : b.id;
            String args = b.args.length() == 0 ? "{}" : b.args.toString();
            calls.add(new ToolCall(id, b.name, args));
        }
        return calls.isEmpty() ? Flux.empty() : Flux.just(new LlmEvent.ToolCalls(calls));
    }

    private Map<String, Object> buildRequest(List<Message> history, LlmConfig cfg, String model, List<ToolSpec> tools) {
        List<Map<String, Object>> messages = new ArrayList<>(history.size());
        for (Message m : history) {
            messages.add(messageOf(m));
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
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", toolsPayload(tools));
            body.put("tool_choice", "auto");
        }
        return body;
    }

    /** 把一条历史消息转成 OpenAI messages 元素, 含 tool 结果与 assistant 工具调用两种特殊形态。 */
    private static Map<String, Object> messageOf(Message m) {
        Map<String, Object> mm = new LinkedHashMap<>(3);
        mm.put("role", roleOf(m.role()));
        if (m.role() == Message.Role.TOOL) {
            mm.put("tool_call_id", m.toolCallId());
            mm.put("content", m.content() == null ? "" : m.content());
            return mm;
        }
        if (m.hasToolCalls()) {
            mm.put("content", m.content() == null ? "" : m.content());
            List<Map<String, Object>> tcs = new ArrayList<>(m.toolCalls().size());
            for (ToolCall c : m.toolCalls()) {
                Map<String, Object> fn = new LinkedHashMap<>(2);
                fn.put("name", c.name());
                fn.put("arguments", c.arguments() == null ? "{}" : c.arguments());
                Map<String, Object> tc = new LinkedHashMap<>(3);
                tc.put("id", c.id());
                tc.put("type", "function");
                tc.put("function", fn);
                tcs.add(tc);
            }
            mm.put("tool_calls", tcs);
            return mm;
        }
        mm.put("content", m.content());
        return mm;
    }

    private static List<Map<String, Object>> toolsPayload(List<ToolSpec> tools) {
        List<Map<String, Object>> arr = new ArrayList<>(tools.size());
        for (ToolSpec t : tools) {
            Map<String, Object> fn = new LinkedHashMap<>(3);
            fn.put("name", t.name());
            fn.put("description", t.description());
            fn.put("parameters", t.parameters() == null
                    ? Map.of("type", "object", "properties", Map.of()) : t.parameters());
            Map<String, Object> wrap = new LinkedHashMap<>(2);
            wrap.put("type", "function");
            wrap.put("function", fn);
            arr.add(wrap);
        }
        return arr;
    }

    private static String roleOf(Message.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
    }

    /** 流式工具调用分片的累积态(按 index 一份)。 */
    private static final class ToolCallBuf {
        String id;
        String name;
        final StringBuilder args = new StringBuilder();
    }
}
