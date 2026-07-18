package com.vca.store.account;

import com.vca.store.entity.ChatConversation;
import com.vca.store.entity.ChatMessage;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;

/**
 * 账号与会话的只读/读写 REST(数据飞轮之上的产品层)。注册成 {@code RouterFunction} Bean 即被 WebFlux 接入,
 * 与 {@code EvaluationRoute} 同款。鉴权: 请求头 {@code Authorization: Bearer <token>} → userId; 缺失/无效 401。
 * 所有会话操作按 userId 隔离, 越权当 404。阻塞 JDBC 放 {@link Schedulers#boundedElastic()}。
 *
 * <p>端点:
 * <pre>
 *   POST /api/register {username(手机号),email,password} → {token,username(脱敏显示名)}
 *   POST /api/login    {username(手机号),password}       → {token,username(脱敏显示名)}
 *   GET  /api/me                                         → {username(脱敏显示名)}
 *   POST /api/password/forgot {account}               → {ok} (dev 附 devToken)  发重置邮件
 *   POST /api/password/reset  {token,password}        → {ok}                    凭令牌设新密码
 *   POST /api/password/change-request                 → {ok} (dev 附 devToken)  已登录, 发重置邮件到本人邮箱
 *   GET    /api/conversations                         → [{id,title,updatedAt}]
 *   POST   /api/conversations {title?}                → {id,title}
 *   DELETE /api/conversations/{id}                    → {ok}
 *   GET    /api/conversations/{id}/messages           → [{role,content}]
 *   POST   /api/conversations/{id}/messages {role,content} → {ok}
 * </pre>
 */
public final class AccountRoutes {

    private final UserService users;
    private final ConversationService convs;
    private final PasswordResetService reset;

    private AccountRoutes(UserService users, ConversationService convs, PasswordResetService reset) {
        this.users = users;
        this.convs = convs;
        this.reset = reset;
    }

    public static RouterFunction<ServerResponse> create(UserService users, ConversationService convs,
                                                        PasswordResetService reset) {
        AccountRoutes r = new AccountRoutes(users, convs, reset);
        return RouterFunctions.route(POST("/api/register"), r::register)
                .andRoute(POST("/api/login"), r::login)
                .andRoute(GET("/api/me"), r::me)
                .andRoute(POST("/api/password/forgot"), r::forgot)
                .andRoute(POST("/api/password/reset"), r::resetPassword)
                .andRoute(POST("/api/password/change-request"), r::changeRequest)
                .andRoute(GET("/api/conversations"), r::listConvs)
                .andRoute(POST("/api/conversations"), r::createConv)
                .andRoute(DELETE("/api/conversations/{id}"), r::deleteConv)
                .andRoute(GET("/api/conversations/{id}/messages"), r::getMessages)
                .andRoute(POST("/api/conversations/{id}/messages"), r::appendMessage);
    }

    // ---- 认证(用户名 + 邮箱 + 密码) ----

    private Mono<ServerResponse> register(ServerRequest req) {
        return req.bodyToMono(Map.class).flatMap(body ->
                blocking(() -> users.register(str(body.get("username")), str(body.get("email")),
                        str(body.get("password")), clientIp(req)))
                        .flatMap(res -> res.error() != null
                                ? json(400, Map.of("error", res.error()))
                                : json(200, Map.of("token", res.token(), "username", res.username()))))
                .onErrorResume(e -> json(400, Map.of("error", "注册失败: 手机号或邮箱可能已被占用")))
                .switchIfEmpty(json(400, Map.of("error", "请求体缺失")));
    }

    // ---- 找回 / 修改密码(邮件) ----

    private Mono<ServerResponse> forgot(ServerRequest req) {
        return req.bodyToMono(Map.class).flatMap(body ->
                blocking(() -> reset.request(str(body.get("account")))).flatMap(this::resetRequestResponse))
                .switchIfEmpty(json(400, Map.of("error", "请求体缺失")));
    }

    /** 已登录用户请求修改密码: 发重置邮件到本人邮箱。 */
    private Mono<ServerResponse> changeRequest(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        return blocking(() -> reset.requestForUser(uid)).flatMap(this::resetRequestResponse);
    }

    private Mono<ServerResponse> resetRequestResponse(PasswordResetService.RequestResult res) {
        if (res.error() != null) {
            return json(400, Map.of("error", res.error()));
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        if (res.devToken() != null) {
            ok.put("devToken", res.devToken());   // 开发回显: 无真实邮件时方便联调
        }
        return json(200, ok);
    }

    private Mono<ServerResponse> resetPassword(ServerRequest req) {
        // reset() 成功返回 null → fromCallable 发空 → switchIfEmpty 出 200; 失败返回错误串 → flatMap 出 400
        return req.bodyToMono(Map.class).flatMap(body ->
                blocking(() -> reset.reset(str(body.get("token")), str(body.get("password"))))
                        .flatMap(err -> json(400, Map.of("error", err)))
                        .switchIfEmpty(json(200, Map.of("ok", true))))
                .switchIfEmpty(json(400, Map.of("error", "请求体缺失")));
    }

    private Mono<ServerResponse> login(ServerRequest req) {
        return req.bodyToMono(Map.class).flatMap(body ->
                blocking(() -> users.login(str(body.get("username")), str(body.get("password"))))
                        .flatMap(res -> res.error() != null
                                ? json(401, Map.of("error", res.error()))
                                : json(200, Map.of("token", res.token(), "username", res.username()))))
                .switchIfEmpty(json(400, Map.of("error", "请求体缺失")));
    }

    private Mono<ServerResponse> me(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        // 注意: Mono.fromCallable 对 null 结果会发空(不是 null 元素), 故 null 用户名走 switchIfEmpty
        return blocking(() -> users.usernameOf(uid))
                .flatMap(name -> json(200, Map.of("username", name)))
                .switchIfEmpty(unauthorized());
    }

    // ---- 会话(按 userId 隔离) ----

    private Mono<ServerResponse> listConvs(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        return blocking(() -> convs.list(uid))
                .flatMap(list -> json(200, list.stream().map(AccountRoutes::convDto).toList()));
    }

    private Mono<ServerResponse> createConv(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        return req.bodyToMono(Map.class).defaultIfEmpty(Map.of()).flatMap(body ->
                blocking(() -> convs.create(uid, str(body.get("title"))))
                        .flatMap(c -> json(200, convDto(c))));
    }

    private Mono<ServerResponse> deleteConv(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        long id = longOf(req.pathVariable("id"));
        return blocking(() -> convs.delete(uid, id))
                .flatMap(ok -> ok ? json(200, Map.of("ok", true)) : json(404, Map.of("error", "会话不存在")));
    }

    private Mono<ServerResponse> getMessages(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        long id = longOf(req.pathVariable("id"));
        // 越权/不存在时 messages() 返回 null → fromCallable 发空 → switchIfEmpty 出 404(空列表是非 null, 正常走 200)
        return blocking(() -> convs.messages(uid, id))
                .flatMap(list -> json(200, list.stream().map(AccountRoutes::msgDto).toList()))
                .switchIfEmpty(json(404, Map.of("error", "会话不存在")));
    }

    private Mono<ServerResponse> appendMessage(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        long id = longOf(req.pathVariable("id"));
        return req.bodyToMono(Map.class).flatMap(body ->
                blocking(() -> convs.appendMessage(uid, id, str(body.get("role")), str(body.get("content"))))
                        .flatMap(ok -> ok ? json(200, Map.of("ok", true)) : json(404, Map.of("error", "会话不存在"))))
                .switchIfEmpty(json(400, Map.of("error", "请求体缺失")));
    }

    // ---- 辅助 ----

    private Long userId(ServerRequest req) {
        String h = req.headers().firstHeader("Authorization");
        String token = (h != null && h.startsWith("Bearer ")) ? h.substring(7) : h;
        return token == null ? null : users.userIdOf(token);
    }

    /** 优先取 Caddy 转发的真实 IP，没有反向代理时取直连 IP。 */
    private static String clientIp(ServerRequest req) {
        String forwarded = req.headers().firstHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.remoteAddress()
                .map(address -> address.getAddress().getHostAddress())
                .orElse(null);
    }

    private static Map<String, Object> convDto(ChatConversation c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(c.getId()));
        m.put("title", c.getTitle());
        m.put("updatedAt", c.getUpdatedAt() == null ? 0 : c.getUpdatedAt().toInstant(ZoneOffset.UTC).toEpochMilli());
        return m;
    }

    private static Map<String, Object> msgDto(ChatMessage m) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("role", m.getRole());
        o.put("content", m.getContent());
        return o;
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

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long longOf(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return -1;
        }
    }
}
