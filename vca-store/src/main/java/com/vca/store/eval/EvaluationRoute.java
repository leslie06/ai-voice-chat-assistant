package com.vca.store.eval;

import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

/**
 * 把评测报告暴露成只读 HTTP 端点 {@code GET /eval/report?days=N}(数据飞轮 P2-A)。
 * 与 {@code LocalMusicRoute} 同款: 注册成 {@code RouterFunction} Bean 即被 WebFlux 接入。
 *
 * <p>查询是阻塞 JDBC, 故放 {@link Schedulers#boundedElastic()} 执行, 不占 Netty 事件循环。
 * {@code days} 缺省=全部历史; 例 {@code ?days=7} 看最近 7 天。
 *
 * <p>报告只含计数/比率/延迟, 不含对话原文, 但仍是内部观测面(全站用量、失败率): 只给运营管理员看,
 * 请求头 {@code Authorization: Bearer <token>}, 非管理员一律 403。
 */
public final class EvaluationRoute {

    private EvaluationRoute() {
    }

    /**
     * @param userIdOfToken 登录令牌 → 账号 id; 无效返回 null
     * @param isAdmin       该账号是不是运营管理员
     */
    public static RouterFunction<ServerResponse> create(ConversationEvaluator evaluator,
                                                        Function<String, Long> userIdOfToken,
                                                        Predicate<Long> isAdmin) {
        return RouterFunctions.route(GET("/eval/report"), request -> {
            String h = request.headers().firstHeader("Authorization");
            String token = (h != null && h.startsWith("Bearer ")) ? h.substring(7) : h;
            Long uid = token == null ? null : userIdOfToken.apply(token);
            if (uid == null) {
                return ServerResponse.status(401).bodyValue(Map.of("error", "未登录或登录已失效"));
            }
            if (!isAdmin.test(uid)) {
                return ServerResponse.status(403).bodyValue(Map.of("error", "仅运营管理员可查看"));
            }
            Instant since = request.queryParam("days")
                    .map(d -> Instant.now().minus(Duration.ofDays(Long.parseLong(d))))
                    .orElse(null);
            return Mono.fromCallable(() -> evaluator.report(since))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(report -> ServerResponse.ok().bodyValue(report));
        });
    }
}
