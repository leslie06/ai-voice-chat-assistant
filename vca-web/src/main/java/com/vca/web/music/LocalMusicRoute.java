package com.vca.web.music;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地曲库文件流路由: 把 {@code GET /music/files/**} 映射到曲库目录里的音频文件。
 *
 * <p>自己实现而不用 {@code RouterFunctions.resources}, 是因为后者在解析<b>非 ASCII 文件名</b>
 * (中文歌名)时会 404。这里直接取已解码的路径变量、按曲库根目录解析, 并做<b>路径穿越校验</b>
 * (解析后必须仍在根目录内)。返回 {@link FileSystemResource}, 由 WebFlux 的资源写出器处理,
 * 天然支持 Range 请求(可拖动进度、边下边播)。
 */
public final class LocalMusicRoute {

    private LocalMusicRoute() {
    }

    /** 路径前缀, 与 {@link LocalMusicProvider#URL_PREFIX} 保持一致 */
    private static final String PATTERN = LocalMusicProvider.URL_PREFIX + "{*path}";

    public static RouterFunction<ServerResponse> create(String dir) {
        Path root = Paths.get(dir).toAbsolutePath().normalize();
        return RouterFunctions.route(RequestPredicates.GET(PATTERN), req -> serve(req, root));
    }

    private static Mono<ServerResponse> serve(ServerRequest req, Path root) {
        String rel = req.pathVariable("path");        // 已 URL 解码, 可能以 "/" 开头
        if (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        Path file = root.resolve(rel).normalize();
        // 路径穿越防护: 解析后必须仍落在曲库根目录内
        if (!file.startsWith(root) || !Files.isRegularFile(file) || !Files.isReadable(file)) {
            return ServerResponse.notFound().build();
        }
        FileSystemResource resource = new FileSystemResource(file);
        MediaType type = MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ServerResponse.ok().contentType(type).body(BodyInserters.fromResource(resource));
    }
}
