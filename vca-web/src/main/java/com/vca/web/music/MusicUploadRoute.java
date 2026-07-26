package com.vca.web.music;

import com.vca.domain.model.MusicTrack;
import com.vca.domain.spi.MusicUploadStore;
import com.vca.orchestrator.auth.TokenAuthenticator;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;

/** 用户歌曲上传：先审核，审核通过后进入所有用户都能播放的公共曲库。 */
public final class MusicUploadRoute {

    static final int MAX_AUDIO_BYTES = 25 * 1024 * 1024;
    static final int MAX_LYRICS_BYTES = 512 * 1024;

    private final OssMusicProvider oss;
    private final MusicUploadStore uploads;
    private final TokenAuthenticator authenticator;
    private final MusicModerationCoordinator moderation;

    private MusicUploadRoute(
            OssMusicProvider oss, MusicUploadStore uploads, TokenAuthenticator authenticator,
            MusicModerationCoordinator moderation) {
        this.oss = oss;
        this.uploads = uploads;
        this.authenticator = authenticator;
        this.moderation = moderation;
    }

    public static RouterFunction<ServerResponse> create(
            OssMusicProvider oss, MusicUploadStore uploads, TokenAuthenticator authenticator,
            MusicModerationCoordinator moderation) {
        MusicUploadRoute route = new MusicUploadRoute(oss, uploads, authenticator, moderation);
        return RouterFunctions.route(POST("/api/music/uploads"), route::upload)
                .andRoute(GET("/api/music/uploads"), route::list)
                .andRoute(GET("/api/music/uploads/lyrics"), route::lyrics);
    }

    private Mono<ServerResponse> upload(ServerRequest request) {
        Long userId = userId(request);
        if (userId == null) {
            return json(401, Map.of("error", "未登录或登录已失效"));
        }
        if (uploads == null) {
            return json(503, Map.of("error", "数据库未启用，暂时不能上传歌曲"));
        }
        if (moderation == null) {
            return json(503, Map.of("error", "内容安全审核未启用，暂时不能上传歌曲"));
        }
        return request.body(BodyExtractors.toMultipartData()).flatMap(parts -> {
            Map<String, Part> values = parts.toSingleValueMap();
            if (!(values.get("audio") instanceof FilePart audio)) {
                return json(400, Map.of("error", "请选择 MP3 文件"));
            }
            if (!audio.filename().toLowerCase(Locale.ROOT).endsWith(".mp3")) {
                return json(400, Map.of("error", "只支持 MP3 文件"));
            }
            FilePart lyrics = values.get("lyrics") instanceof FilePart file
                    && file.filename() != null && !file.filename().isBlank() ? file : null;
            if (lyrics != null && !lyrics.filename().toLowerCase(Locale.ROOT).endsWith(".lrc")) {
                return json(400, Map.of("error", "歌词只支持 LRC 文件"));
            }
            if (!"true".equalsIgnoreCase(field(values.get("rightsConfirmed")))) {
                return json(400, Map.of("error", "请确认你拥有歌曲版权或使用授权"));
            }
            String title = clean(field(values.get("title")), 255);
            String artist = clean(field(values.get("artist")), 255);
            if (title == null) {
                title = filenameStem(audio.filename());
            }
            if (artist == null) {
                artist = "未知歌手";
            }
            final String safeTitle = title;
            final String safeArtist = artist;
            Mono<byte[]> audioBytes = read(audio, MAX_AUDIO_BYTES);
            Mono<byte[]> lyricsBytes = lyrics == null
                    ? Mono.just(new byte[0]) : read(lyrics, MAX_LYRICS_BYTES);
            return Mono.zip(audioBytes, lyricsBytes).flatMap(tuple -> {
                byte[] mp3 = tuple.getT1();
                byte[] lrc = tuple.getT2();
                if (!looksLikeMp3(mp3)) {
                    return json(400, Map.of("error", "文件内容不是有效的 MP3"));
                }
                String id = UUID.randomUUID().toString();
                return Mono.fromCallable(() -> save(
                                userId, id, safeTitle, safeArtist,
                                clean(audio.filename(), 255),
                                mp3, lrc.length == 0 ? null : lrc))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(saved -> {
                            if (moderation != null) {
                                moderation.kick();
                            }
                            return json(200, Map.of(
                                "ok", true, "id", saved.id(), "title", saved.title(),
                                "artist", saved.artist(), "status", "pending"));
                        });
            });
        }).switchIfEmpty(json(400, Map.of("error", "请求体缺失")))
                .onErrorResume(error -> json(400, Map.of("error", uploadError(error))));
    }

    private MusicUploadStore.Upload save(
            long userId, String id, String title, String artist, String originalFilename,
            byte[] audio, byte[] lyrics) {
        OssMusicProvider.StoredUpload stored = oss.uploadForUser(
                userId, id, title, artist, originalFilename, audio, lyrics);
        MusicUploadStore.Upload record = new MusicUploadStore.Upload(
                stored.id(), userId, stored.title(), stored.artist(), stored.originalFilename(),
                stored.audioObjectKey(), stored.lyricsObjectKey(), stored.audioBytes(),
                stored.lyricsBytes(), true, "pending",
                null, null, null, null, null, null, null, Instant.now());
        try {
            uploads.save(record);
            return record;
        } catch (RuntimeException error) {
            oss.rollbackUpload(stored);
            throw error;
        }
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

    private static Mono<byte[]> read(FilePart file, int limit) {
        return DataBufferUtils.join(file.content(), limit).map(MusicUploadRoute::toBytes);
    }

    private static byte[] toBytes(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        return bytes;
    }

    static boolean looksLikeMp3(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return false;
        }
        if (bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return true;
        }
        for (int i = 0; i < Math.min(bytes.length - 1, 4096); i++) {
            if ((bytes[i] & 0xff) == 0xff && (bytes[i + 1] & 0xe0) == 0xe0) {
                return true;
            }
        }
        return false;
    }

    private static String field(Part part) {
        return part instanceof FormFieldPart field ? field.value() : null;
    }

    private static String filenameStem(String filename) {
        String value = filename == null ? "未命名歌曲" : filename;
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        value = value.substring(slash + 1);
        int dot = value.lastIndexOf('.');
        value = dot > 0 ? value.substring(0, dot) : value;
        String cleaned = clean(value, 255);
        return cleaned == null ? "未命名歌曲" : cleaned;
    }

    private static String clean(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.strip().replaceAll("[\\p{Cntrl}]", "");
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    private static String uploadError(Throwable error) {
        String value = error == null ? "" : String.valueOf(error.getMessage());
        if (value.contains("Exceeded limit")) {
            return "文件过大：MP3 上限 25MB，LRC 上限 512KB";
        }
        return "上传失败，请检查文件大小和 OSS 配置";
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
