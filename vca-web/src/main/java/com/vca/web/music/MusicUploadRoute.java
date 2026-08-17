package com.vca.web.music;

import com.vca.domain.model.MusicTrack;
import com.vca.domain.spi.MusicUploadStore;
import com.vca.orchestrator.auth.TokenAuthenticator;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

/**
 * 用户歌曲的只读接口：已审核通过的曲目列表与歌词。
 *
 * <p><b>上传入口（POST /api/music/uploads）已下线。</b>它依赖的内容审核队列每 15 秒
 * 重扫一次 {@code pending/reviewing} 记录，而阿里云语音审核提交失败时记录会原样留在队列里，
 * 于是每轮都重新调一次<b>按次计费</b>的文本审核 —— 一条卡住的记录一天约 5700 次调用。
 * 整条审核链路连同上传接口一并移除。历史已通过的歌曲不受影响，继续可听。
 */
public final class MusicUploadRoute {

    private final OssMusicProvider oss;
    private final MusicUploadStore uploads;
    private final TokenAuthenticator authenticator;

    private MusicUploadRoute(
            OssMusicProvider oss, MusicUploadStore uploads, TokenAuthenticator authenticator) {
        this.oss = oss;
        this.uploads = uploads;
        this.authenticator = authenticator;
    }

    public static RouterFunction<ServerResponse> create(
            OssMusicProvider oss, MusicUploadStore uploads, TokenAuthenticator authenticator) {
        MusicUploadRoute route = new MusicUploadRoute(oss, uploads, authenticator);
        return RouterFunctions.route(GET("/api/music/uploads"), route::list)
                .andRoute(GET("/api/music/uploads/lyrics"), route::lyrics);
    }

    private Mono<ServerResponse> list(ServerRequest request) {
        Long userId = userId(request);
        if (userId == null) {
            return json(401, Map.of("error", "未登录或登录已失效"));
        }
        if (uploads == null) {
            return json(200, Map.of("tracks", List.of()));
        }
        return Mono.fromCallable(() -> {
                    List<MusicUploadStore.Upload> approved = uploads.listApproved();
                    List<MusicTrack> tracks = oss.signedUserUploads(approved);
                    List<Map<String, Object>> mine = uploads.list(userId).stream()
                            .map(MusicUploadRoute::uploadDto).toList();
                    return Map.of(
                            "tracks", tracks.stream().map(MusicUploadRoute::trackDto).toList(),
                            "mine", mine);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(body -> json(200, body))
                .onErrorResume(error -> json(500, Map.of("error", "用户上传曲库加载失败")));
    }

    private Mono<ServerResponse> lyrics(ServerRequest request) {
        Long userId = userId(request);
        if (userId == null) {
            return json(401, Map.of("error", "未登录或登录已失效"));
        }
        if (uploads == null) {
            return ServerResponse.status(404).bodyValue("暂无歌词");
        }
        String id = request.queryParam("id").orElse("");
        return Mono.fromCallable(() -> uploads.isApprovedLyrics(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(approved -> {
                    if (!approved) {
                        return ServerResponse.status(404).bodyValue("暂无歌词");
                    }
                    return Mono.fromCallable(() -> oss.readUserLyrics(id))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(text -> ServerResponse.ok()
                                    .contentType(org.springframework.http.MediaType.parseMediaType(
                                            "text/plain;charset=UTF-8"))
                                    .bodyValue(text));
                })
                .onErrorResume(error -> ServerResponse.status(500).bodyValue("歌词加载失败"));
    }

    private Long userId(ServerRequest request) {
        if (authenticator == null) {
            return null;
        }
        String header = request.headers().firstHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : header;
        String value = token == null ? null : authenticator.authenticate(token);
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Map<String, Object> trackDto(MusicTrack track) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("title", track.title());
        value.put("artist", track.artist());
        value.put("url", track.playUrl());
        value.put("cover", track.coverUrl());
        value.put("lyricsId", track.lyricsId());
        value.put("duration", track.durationSec());
        value.put("full", track.full());
        value.put("uploaded", true);
        return value;
    }

    private static Map<String, Object> uploadDto(MusicUploadStore.Upload upload) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", upload.id());
        value.put("title", upload.title());
        value.put("artist", upload.artist());
        value.put("status", upload.status());
        value.put("reason", publicReason(upload));
        value.put("createdAt", upload.createdAt());
        return value;
    }

    private static String publicReason(MusicUploadStore.Upload upload) {
        if (!"rejected".equals(upload.status())) {
            return "";
        }
        return "歌曲名称或音频内容未通过平台安全审核";
    }

    private static Mono<ServerResponse> json(int status, Object body) {
        return ServerResponse.status(status).bodyValue(body);
    }
}
