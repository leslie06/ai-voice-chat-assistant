package com.vca.web.music;

import com.vca.domain.model.MusicTrack;
import com.vca.domain.spi.MusicProvider;
import com.vca.orchestrator.auth.TokenAuthenticator;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

/** 为网页 KTV 面板提供完整曲库。只返回短期签名播放地址，不暴露 OSS 密钥。 */
public final class MusicCatalogRoute {

    private MusicCatalogRoute() {
    }

    public static RouterFunction<ServerResponse> create(
            MusicProvider provider, TokenAuthenticator authenticator, String sharedToken) {
        return RouterFunctions.route(GET("/api/music/catalog"), request -> {
            if (!authorized(request, authenticator, sharedToken)) {
                return ServerResponse.status(401).bodyValue(Map.of("error", "未登录或登录已失效"));
            }
            return provider.catalog()
                    .map(tracks -> tracks.stream().map(MusicCatalogRoute::dto).toList())
                    .flatMap(tracks -> ServerResponse.ok().bodyValue(Map.of("tracks", tracks)))
                    .onErrorResume(e -> ServerResponse.status(500)
                            .bodyValue(Map.of("error", "曲库加载失败")));
        });
    }

    private static boolean authorized(
            ServerRequest request, TokenAuthenticator authenticator, String sharedToken) {
        String header = request.headers().firstHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : header;
        if (authenticator != null) {
            return token != null && authenticator.authenticate(token) != null;
        }
        return sharedToken == null || sharedToken.isBlank() || sharedToken.equals(token);
    }

    private static Map<String, Object> dto(MusicTrack track) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("title", track.title());
        value.put("artist", track.artist());
        value.put("url", track.playUrl());
        value.put("cover", track.coverUrl());
        value.put("duration", track.durationSec());
        value.put("full", track.full());
        return value;
    }
}
