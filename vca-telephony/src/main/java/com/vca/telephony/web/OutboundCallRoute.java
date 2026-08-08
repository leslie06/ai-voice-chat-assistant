package com.vca.telephony.web;

import com.vca.telephony.provider.ami.AmiTelephonyProvider;
import com.vca.telephony.spi.CallLeg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;

/**
 * 单拨外呼端点 {@code POST /telephony/calls} —— 打一通电话验证整条链路。
 *
 * <p><b>定位是冒烟工具, 不是批量任务入口。</b> 它会一直等到接通或失败才返回(便于人肉验证
 * "到底通没通"), 批量外呼需要的是异步发起 + 并发控制 + 重呼策略, 那是名单/任务系统的事。
 *
 * <pre>
 *   POST /telephony/calls
 *   X-Telephony-Token: &lt;token&gt;
 *   {"number":"13800138000","callerId":"01088886666"}
 *
 *   200 {"callId":"...","outcome":"answered","peerNumber":"13800138000","elapsedMs":8123}
 *   502 {"callId":null,"outcome":"failed","reason":"外呼失败: 空号","elapsedMs":3120}
 * </pre>
 *
 * <p><b>鉴权是强制的</b>: 这个端点会真的打电话、真的花钱, 未配令牌时
 * {@code TelephonyAutoConfiguration} 干脆不注册它(失败要失败在安全的那一侧)。
 */
public final class OutboundCallRoute {

    private static final Logger log = LoggerFactory.getLogger(OutboundCallRoute.class);

    private static final String TOKEN_HEADER = "X-Telephony-Token";

    private OutboundCallRoute() {
    }

    public static RouterFunction<ServerResponse> create(AmiTelephonyProvider provider,
                                                       String apiToken, Duration maxWait) {
        return RouterFunctions.route(POST("/telephony/calls"),
                request -> handle(request, provider, apiToken, maxWait));
    }

    private static Mono<ServerResponse> handle(ServerRequest request, AmiTelephonyProvider provider,
                                               String apiToken, Duration maxWait) {
        if (!authorized(request, apiToken)) {
            return ServerResponse.status(HttpStatus.UNAUTHORIZED).bodyValue(Map.of("error", "unauthorized"));
        }
        return request.bodyToMono(CallRequest.class)
                .defaultIfEmpty(new CallRequest(null, null))
                .flatMap(body -> place(provider, body, maxWait));
    }

    private static Mono<ServerResponse> place(AmiTelephonyProvider provider, CallRequest body, Duration maxWait) {
        long startedAt = System.currentTimeMillis();
        return provider.originate(body.number(), body.callerId())
                // 兜底: provider 内部已按 answerWaitMs 超时, 这里再夹一道, 免得 HTTP 连接被挂死
                .timeout(maxWait, Mono.error(() -> new IllegalStateException("等待接通超时")))
                .flatMap(leg -> ServerResponse.ok().bodyValue(answered(leg, startedAt)))
                .onErrorResume(IllegalArgumentException.class,
                        e -> ServerResponse.badRequest().bodyValue(failed(null, e.getMessage(), startedAt)))
                .onErrorResume(e -> {
                    log.info("外呼未接通: {} —— {}", body.number(), e.toString());
                    return ServerResponse.status(HttpStatus.BAD_GATEWAY)
                            .bodyValue(failed(null, e.getMessage(), startedAt));
                });
    }

    /** 令牌比对用定长算法, 不让响应时间泄漏前缀信息 */
    private static boolean authorized(ServerRequest request, String apiToken) {
        String provided = request.headers().firstHeader(TOKEN_HEADER);
        if (provided == null) {
            provided = request.queryParam("token").orElse("");
        }
        return java.security.MessageDigest.isEqual(
                provided.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                apiToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Map<String, Object> answered(CallLeg leg, long startedAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("callId", leg.callId());
        body.put("outcome", "answered");
        body.put("peerNumber", leg.peerNumber());
        body.put("elapsedMs", System.currentTimeMillis() - startedAt);
        return body;
    }

    private static Map<String, Object> failed(String callId, String reason, long startedAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("callId", callId);
        body.put("outcome", "failed");
        body.put("reason", reason == null ? "unknown" : reason);
        body.put("elapsedMs", System.currentTimeMillis() - startedAt);
        return body;
    }

    /** 请求体。号码的合法性由 {@code AmiTelephonyProvider} 把关(那里是安全边界)。 */
    public record CallRequest(String number, String callerId) {
    }
}
