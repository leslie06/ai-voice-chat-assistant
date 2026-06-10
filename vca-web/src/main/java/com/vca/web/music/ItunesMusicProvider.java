package com.vca.web.music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vca.domain.model.MusicTrack;
import com.vca.domain.spi.MusicProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 基于 iTunes Search API 的音乐检索: 合法、免密钥、CORS 友好。
 *
 * <p>返回 Apple 官方的 ~30 秒预览片段({@code previewUrl}, m4a), 浏览器可直接播放。
 * 限制: 只是预览片段, 非整曲 —— 这是免费合法方案的取舍。换成有完整播放权的曲库时,
 * 只需另写一个 {@link MusicProvider} 实现并替换装配, 接入层与前端无需改动。
 *
 * @see <a href="https://performance-partners.apple.com/search-api">iTunes Search API</a>
 */
public class ItunesMusicProvider implements MusicProvider {

    private static final Logger log = LoggerFactory.getLogger(ItunesMusicProvider.class);

    private final WebClient client;
    private final ObjectMapper mapper;

    public ItunesMusicProvider(ObjectMapper mapper) {
        this.mapper = mapper;
        this.client = WebClient.builder().baseUrl("https://itunes.apple.com").build();
    }

    @Override
    public Mono<MusicTrack> search(String query) {
        return client.get()
                .uri(b -> b.path("/search")
                        .queryParam("term", query)
                        .queryParam("media", "music")
                        .queryParam("limit", 1)
                        .queryParam("country", "CN")   // 偏向中文曲库, 找不到再由 Apple 回退
                        .build())
                .retrieve()
                // iTunes 返回 Content-Type: text/javascript, Jackson 解码器不认; 故按文本取再自行解析
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(5))
                .flatMap(this::toTrack)
                .onErrorResume(e -> {
                    log.warn("iTunes 检索失败: {} ({})", query, e.toString());
                    return Mono.empty();
                });
    }

    /** 取首条结果转 {@link MusicTrack}; 无结果或无可播放地址时返回空 Mono */
    private Mono<MusicTrack> toTrack(String json) {
        JsonNode body;
        try {
            body = mapper.readTree(json);
        } catch (Exception e) {
            log.warn("iTunes 响应解析失败: {}", e.toString());
            return Mono.empty();
        }
        JsonNode results = body.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return Mono.empty();
        }
        JsonNode t = results.get(0);
        String url = t.path("previewUrl").asText(null);
        if (url == null || url.isBlank()) {
            return Mono.empty();
        }
        return Mono.just(new MusicTrack(
                t.path("trackName").asText(""),
                t.path("artistName").asText(""),
                url,
                t.path("artworkUrl100").asText(null),
                (int) (t.path("trackTimeMillis").asLong(0) / 1000),
                false));   // iTunes 只是 30 秒试听
    }
}
