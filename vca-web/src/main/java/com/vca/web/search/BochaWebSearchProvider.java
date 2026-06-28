package com.vca.web.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vca.orchestrator.search.WebSearchProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 博查 Bocha 联网搜索实现({@link WebSearchProvider})。POST {@code /v1/web-search}, 国内直连(同 DashScope, 无需代理)。
 * 返回 {@code data.webPages.value[]}: name/url/snippet/summary/datePublished —— 防御式解析, 字段缺失安全降级。
 *
 * <p>仅当配置了 {@code vca.web.bocha-key}(env {@code BOCHA_API_KEY})时才注册(见 WebAutoConfiguration)。
 * 阻塞 {@code .block()}: 调用发生在技能执行/编排自动注入链上(boundedElastic 或事件循环), 与 WeatherSkill 同模式。
 */
public class BochaWebSearchProvider implements WebSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(BochaWebSearchProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient client;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String freshness;

    public BochaWebSearchProvider(ObjectMapper mapper, String apiKey, String freshness) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.freshness = (freshness == null || freshness.isBlank()) ? "noLimit" : freshness.trim();
        this.client = WebClient.builder().baseUrl("https://api.bochaai.com").build();
    }

    @Override
    public List<Result> search(String query, int count) {
        if (apiKey.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        int n = count > 0 ? count : 5;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query.trim());
        body.put("summary", true);
        body.put("count", n);
        body.put("freshness", freshness);
        try {
            String json = client.post()
                    .uri("/v1/web-search")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(TIMEOUT)
                    .onErrorResume(e -> {
                        log.warn("博查搜索请求失败: {}", e.toString());
                        return Mono.empty();
                    })
                    .block();
            return parse(mapper, json, n);
        } catch (Exception e) {
            log.warn("博查搜索失败(忽略): {}", e.toString());
            return List.of();
        }
    }

    /** 解析博查响应 {@code data.webPages.value[]} → 结果列表。包私有静态, 便于用样例 JSON 单测。 */
    static List<Result> parse(ObjectMapper mapper, String json, int limit) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode value = mapper.readTree(json).path("data").path("webPages").path("value");
            if (!value.isArray() || value.isEmpty()) {
                return List.of();
            }
            List<Result> out = new ArrayList<>();
            for (JsonNode item : value) {
                String title = text(item, "name");
                String url = text(item, "url");
                // 优先用 summary(更完整), 没有再用 snippet
                String summary = text(item, "summary");
                String snippet = (summary != null && !summary.isBlank()) ? summary : text(item, "snippet");
                String date = text(item, "datePublished");
                if ((title != null && !title.isBlank()) || (snippet != null && !snippet.isBlank())) {
                    out.add(new Result(title, url, snippet, date));
                }
                if (out.size() >= limit) {
                    break;
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("博查响应解析失败: {}", e.toString());
            return List.of();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n != null && n.isTextual()) ? n.asText() : null;
    }
}
