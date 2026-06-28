package com.vca.store.knowledge;

import com.vca.store.account.UserService;
import com.vca.store.entity.KnowledgeDoc;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;

/**
 * RAG 知识库 REST。注册成 {@code RouterFunction} Bean 即被 WebFlux 接入, 与 {@code AccountRoutes} 同款。
 * 鉴权: {@code Authorization: Bearer <token>} → userId; 缺失/无效 401。所有操作按 userId 隔离。
 *
 * <pre>
 *   POST   /api/knowledge        multipart(file) → {ok,chunks}   上传文档(txt/md/pdf), 切块入库
 *   GET    /api/knowledge                        → [{id,title,createdAt}]
 *   DELETE /api/knowledge/{id}                    → {ok}
 * </pre>
 */
public final class KnowledgeRoutes {

    /** 上传文件读入内存的上限(5MB), 防 OOM。 */
    private static final int MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

    private final UserService users;
    private final KnowledgeService knowledge;

    private KnowledgeRoutes(UserService users, KnowledgeService knowledge) {
        this.users = users;
        this.knowledge = knowledge;
    }

    public static RouterFunction<ServerResponse> create(UserService users, KnowledgeService knowledge) {
        KnowledgeRoutes r = new KnowledgeRoutes(users, knowledge);
        return RouterFunctions.route(POST("/api/knowledge"), r::upload)
                .andRoute(GET("/api/knowledge"), r::list)
                .andRoute(DELETE("/api/knowledge/{id}"), r::delete);
    }

    private Mono<ServerResponse> upload(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        return req.body(BodyExtractors.toMultipartData()).flatMap(parts -> {
            Part p = parts.toSingleValueMap().get("file");
            if (!(p instanceof FilePart fp)) {
                return json(400, Map.of("error", "缺少文件(form 字段名应为 file)"));
            }
            String filename = fp.filename();
            return DataBufferUtils.join(fp.content(), MAX_UPLOAD_BYTES).flatMap(buf -> {
                byte[] bytes = toBytes(buf);
                return blocking(() -> knowledge.ingest(uid, filename, TextExtractor.extract(filename, bytes)))
                        .flatMap(count -> count > 0
                                ? json(200, Map.of("ok", true, "chunks", count))
                                : json(400, Map.of("error", "无法解析文件或内容为空")));
            }).onErrorResume(e -> json(400, Map.of("error", "文件过大或读取失败(上限 5MB)")));
        }).switchIfEmpty(json(400, Map.of("error", "请求体缺失")));
    }

    private Mono<ServerResponse> list(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        return blocking(() -> knowledge.list(uid))
                .flatMap(docs -> json(200, docs.stream().map(KnowledgeRoutes::docDto).toList()));
    }

    private Mono<ServerResponse> delete(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        long id = longOf(req.pathVariable("id"));
        return blocking(() -> knowledge.delete(uid, id))
                .flatMap(ok -> ok ? json(200, Map.of("ok", true)) : json(404, Map.of("error", "文档不存在")));
    }

    // ---- 辅助 ----

    private Long userId(ServerRequest req) {
        String h = req.headers().firstHeader("Authorization");
        String token = (h != null && h.startsWith("Bearer ")) ? h.substring(7) : h;
        return token == null ? null : users.userIdOf(token);
    }

    private static byte[] toBytes(DataBuffer buf) {
        byte[] bytes = new byte[buf.readableByteCount()];
        buf.read(bytes);
        DataBufferUtils.release(buf);
        return bytes;
    }

    private static Map<String, Object> docDto(KnowledgeDoc d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(d.getId()));
        m.put("title", d.getTitle());
        m.put("createdAt", d.getCreatedAt() == null ? 0 : d.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli());
        return m;
    }

    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> c) {
        return Mono.fromCallable(c).subscribeOn(Schedulers.boundedElastic());
    }

    private static Mono<ServerResponse> json(int status, Object body) {
        return ServerResponse.status(status).bodyValue(body);
    }

    private static Mono<ServerResponse> unauthorized() {
        return json(401, Map.of("error", "未登录或登录已失效"));
    }

    private static long longOf(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return -1;
        }
    }
}
