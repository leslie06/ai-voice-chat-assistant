package com.vca.store.eval;

import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

/**
 * 把评测报告暴露成只读 HTTP 端点 {@code GET /eval/report?days=N}(数据飞轮 P2-A)。
 * 与 {@code LocalMusicRoute} 同款: 注册成 {@code RouterFunction} Bean 即被 WebFlux 接入。
 *
 * <p>查询是阻塞 JDBC, 故放 {@link Schedulers#boundedElastic()} 执行, 不占 Netty 事件循环。
 * {@code days} 缺省=全部历史; 例 {@code ?days=7} 看最近 7 天。
 *
 * <p><b>注意</b>: 报告只含计数/比率/延迟, 不含对话原文; 但仍属内部观测面, 公网部署应同 {@code /actuator}
 * 一样用反向代理限制访问。
 */
public final class EvaluationRoute {

    private EvaluationRoute() {
    }

    public static RouterFunction<ServerResponse> create(ConversationEvaluator evaluator) {
        return RouterFunctions.route(GET("/eval/report"), request -> {
            Instant since = request.queryParam("days")
                    .map(d -> Instant.now().minus(Duration.ofDays(Long.parseLong(d))))
                    .orElse(null);
            return Mono.fromCallable(() -> evaluator.report(since))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(report -> ServerResponse.ok().bodyValue(report));
        });
    }
}
