package com.vca.web.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vca.orchestrator.search.WebSearchProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * 用 JDK 阻塞式 {@link HttpClient}(非 Reactor): 自动注入路径在编排的事件循环线程上同步调用本方法,
 * 若用 WebClient 的 {@code .block()} 会被 Reactor 的"事件循环禁阻塞"守卫拒绝并抛异常; 改用 JDK HttpClient
 * 与同路径上的记忆/知识库检索一致(普通阻塞调用, 不触发守卫)。工具路径已在 boundedElastic 线程上跑, 同样兼容。
 */
public class BochaWebSearchProvider implements WebSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(BochaWebSearchProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final URI ENDPOINT = URI.create("https://api.bochaai.com/v1/web-search");

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String freshness;

    public BochaWebSearchProvider(ObjectMapper mapper, String apiKey, String freshness) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.freshness = (freshness == null || freshness.isBlank()) ? "noLimit" : freshness.trim();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        log.info("博查 provider 初始化, key 指纹={}", fingerprint(this.apiKey));
    }

    /** key 指纹: 仅首4/尾4/长度, 用于和 .env 对账, 不泄露全量。 */
    private static String fingerprint(String k) {
        if (k == null || k.isEmpty()) {
            return "<空>";
        }
        if (k.length() <= 8) {
            return "len=" + k.length();
        }
        return k.substring(0, 4) + "..." + k.substring(k.length() - 4) + " len=" + k.length();
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
            HttpRequest req = HttpRequest.newBuilder(ENDPOINT)
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                log.warn("博查搜索 HTTP {} (实际用的 key 指纹={}): {}", resp.statusCode(), fingerprint(apiKey),
                        resp.body() == null ? "" : resp.body().substring(0, Math.min(200, resp.body().length())));
                return List.of();
            }
            return parse(mapper, resp.body(), n);
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
