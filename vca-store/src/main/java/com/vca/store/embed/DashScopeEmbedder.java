package com.vca.store.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope 兼容模式 embedding 实现: POST {base}/embeddings(OpenAI 兼容), 复用 {@code DASHSCOPE_API_KEY}。
 * 默认 {@code text-embedding-v4} / 1024 维。批量一次最多 {@link #MAX_BATCH} 条, 超出自动分批。
 *
 * <p><b>用 JDK {@link HttpClient} 同步发请求</b>(而非 WebClient.block()): 召回/检索发生在编排的
 * reactor 事件循环线程上, Reactor 会拒绝在非阻塞线程上 {@code block()}; JDK 同步 {@code send} 是普通阻塞调用,
 * 与该路径上原本就阻塞的 JDBC 查询一致。失败吞掉返回空, 上层降级为不带向量。
 */
public class DashScopeEmbedder implements Embedder {

    private static final Logger log = LoggerFactory.getLogger(DashScopeEmbedder.class);
    /** DashScope 单次 embedding 输入条数上限(兼容接口为 10)。 */
    private static final int MAX_BATCH = 10;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient http;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final int dimensions;

    public DashScopeEmbedder(String baseUrl, String apiKey, String model, int dimensions, String proxy) {
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.endpoint = base + "/embeddings";
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (proxy != null && !proxy.isBlank()) {
            try {
                URI p = URI.create(proxy.trim());
                int port = p.getPort() > 0 ? p.getPort() : 7890;
                b.proxy(ProxySelector.of(new InetSocketAddress(p.getHost(), port)));
            } catch (Exception e) {
                log.warn("embedding 代理配置无效, 忽略: {}", proxy);
            }
        }
        this.http = b.build();
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        List<float[]> out = call(List.of(text));
        return out.isEmpty() ? null : out.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += MAX_BATCH) {
            List<String> slice = texts.subList(i, Math.min(i + MAX_BATCH, texts.size()));
            List<float[]> part = call(slice);
            // call() 失败返回空 → 补等长 null, 保持与输入对齐
            if (part.size() == slice.size()) {
                result.addAll(part);
            } else {
                result.addAll(Arrays.asList(new float[slice.size()][]));
            }
        }
        return result;
    }

    /** 调一次 embeddings 接口, 返回与输入等长的向量列表; 任何异常返回空列表。 */
    private List<float[]> call(List<String> inputs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", inputs);
        body.put("encoding_format", "float");
        if (dimensions > 0) {
            body.put("dimensions", dimensions);
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("embedding 请求失败({}): {}", resp.statusCode(), resp.body());
                return List.of();
            }
            return parse(resp.body(), inputs.size());
        } catch (Exception e) {
            log.warn("embedding 调用失败(降级为无向量): {}", e.toString());
            return List.of();
        }
    }

    /** 解析 {@code {data:[{index,embedding:[...]}...]}}, 按 index 回填到正确位置。 */
    private List<float[]> parse(String json, int expected) {
        try {
            JsonNode data = objectMapper.readTree(json).path("data");
            if (!data.isArray() || data.isEmpty()) {
                return List.of();
            }
            float[][] arr = new float[expected][];
            for (JsonNode item : data) {
                int idx = item.path("index").asInt(0);
                JsonNode emb = item.path("embedding");
                if (idx < 0 || idx >= expected || !emb.isArray()) {
                    continue;
                }
                float[] v = new float[emb.size()];
                for (int i = 0; i < v.length; i++) {
                    v[i] = (float) emb.get(i).asDouble();
                }
                arr[idx] = v;
            }
            return new ArrayList<>(Arrays.asList(arr));
        } catch (Exception e) {
            log.warn("embedding 响应解析失败: {}", e.toString());
            return List.of();
        }
    }
}
