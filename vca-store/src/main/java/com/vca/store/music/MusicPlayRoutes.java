package com.vca.store.music;

import com.vca.store.account.UserService;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;

/** 登录用户听歌统计接口。 */
public final class MusicPlayRoutes {

    private final UserService users;
    private final MusicPlayService plays;

    private MusicPlayRoutes(UserService users, MusicPlayService plays) {
        this.users = users;
        this.plays = plays;
    }

    public static RouterFunction<ServerResponse> create(UserService users, MusicPlayService plays) {
        MusicPlayRoutes route = new MusicPlayRoutes(users, plays);
        return RouterFunctions.route(POST("/api/music/plays"), route::record);
    }

    private Mono<ServerResponse> record(ServerRequest request) {
        Long userId = userId(request);
        if (userId == null) {
            return ServerResponse.status(401).bodyValue(Map.of("error", "未登录或登录已失效"));
        }
        return request.bodyToMono(Map.class)
                .flatMap(body -> Mono.fromCallable(() -> plays.record(
                                userId,
                                string(body.get("title")),
                                string(body.get("artist")),
                                integer(body.get("duration"))))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(ok -> ok
                        ? ServerResponse.ok().bodyValue(Map.of("ok", true))
                        : ServerResponse.badRequest().bodyValue(Map.of("error", "歌曲信息不完整")))
                .switchIfEmpty(ServerResponse.badRequest().bodyValue(Map.of("error", "请求体缺失")));
    }

    private Long userId(ServerRequest request) {
        String header = request.headers().firstHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : header;
        return token == null ? null : users.userIdOf(token);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }
}
