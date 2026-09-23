package com.vca.store.merchant;

import com.vca.orchestrator.merchant.Industry;
import com.vca.orchestrator.merchant.MerchantProfile;
import com.vca.orchestrator.merchant.MerchantStore;
import com.vca.store.account.UserService;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;

/**
 * 商家资料 REST: 诊所用自己的账号登录, 维护自己名下的店。与 {@code KnowledgeRoutes} 同款鉴权
 * ({@code Authorization: Bearer <token>} → userId), 所有操作按账号隔离 —— 只能看和改自己名下的。
 *
 * <pre>
 *   GET    /api/merchants           → [profile]          我名下的全部商家(含停用)
 *   POST   /api/merchants           {profile} → profile  新建; number 必填且全局唯一
 *   GET    /api/merchants/{id}      → profile
 *   PUT    /api/merchants/{id}      {profile} → profile  整体覆盖(没传的字段按空处理)
 *   DELETE /api/merchants/{id}      → {ok}
 *   GET    /api/merchants/{id}/preview → {prompt}        看 AI 实际会拿到的机构资料文本(调试用)
 *   GET    /api/merchants/industries   → [industry]       可选的行业及各字段在该行业里的叫法(网页表单用)
 * </pre>
 *
 * <p>字段名与 {@link MerchantProfile} 一致(驼峰)。{@code ownerId}/{@code createdAt}/{@code updatedAt} 由服务端定,
 * 请求里传了也忽略。保存成功后存储层会通知电话注册表作废缓存, 下一通电话就用新资料。
 */
public final class MerchantRoutes {

    private final UserService users;
    private final MerchantStore store;

    private MerchantRoutes(UserService users, MerchantStore store) {
        this.users = users;
        this.store = store;
    }

    public static RouterFunction<ServerResponse> create(UserService users, MerchantStore store) {
        MerchantRoutes r = new MerchantRoutes(users, store);
        return RouterFunctions.route(GET("/api/merchants"), r::list)
                .andRoute(POST("/api/merchants"), r::createOne)
                .andRoute(GET("/api/merchants/industries"), r::industries)
                .andRoute(GET("/api/merchants/{id}/preview"), r::preview)
                .andRoute(GET("/api/merchants/{id}"), r::get)
                .andRoute(PUT("/api/merchants/{id}"), r::update)
                .andRoute(DELETE("/api/merchants/{id}"), r::delete);
    }

    private Mono<ServerResponse> list(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        return blocking(() -> store.listByOwner(uid))
                .flatMap(list -> json(200, list.stream().map(MerchantRoutes::dto).toList()));
    }

    /** 行业预设不需要登录: 只是字段叫法, 没有任何商家数据 */
    private Mono<ServerResponse> industries(ServerRequest req) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Industry i : Industry.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", i.code());
            m.put("label", i.label());
            m.put("servicesLabel", i.servicesLabel());
            m.put("staffLabel", i.staffLabel());
            m.put("bookingLabel", i.bookingLabel());
            out.add(m);
        }
        return json(200, out);
    }

    private Mono<ServerResponse> get(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        long id = longOf(req.pathVariable("id"));
        return blocking(() -> store.findById(id).filter(p -> p.ownerId() == uid))
                .flatMap(opt -> opt.map(p -> json(200, dto(p)))
                        .orElseGet(() -> json(404, Map.of("error", "商家不存在"))));
    }

    private Mono<ServerResponse> preview(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        long id = longOf(req.pathVariable("id"));
        return blocking(() -> store.findById(id).filter(p -> p.ownerId() == uid))
                .flatMap(opt -> opt.map(p -> json(200, Map.of("prompt", p.renderProfile())))
                        .orElseGet(() -> json(404, Map.of("error", "商家不存在"))));
    }

    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> createOne(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        return req.bodyToMono(Map.class)
                .flatMap(body -> save(uid, null, (Map<String, Object>) body));
    }

    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> update(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        long id = longOf(req.pathVariable("id"));
        return req.bodyToMono(Map.class)
                .flatMap(body -> save(uid, id, (Map<String, Object>) body));
    }

    private Mono<ServerResponse> save(long uid, Long id, Map<String, Object> body) {
        MerchantProfile profile = fromBody(uid, id, body);
        if (profile.number().isEmpty()) {
            return json(400, Map.of("error", "接入号(number)不能为空"));
        }
        return blocking(() -> store.save(profile))
                .flatMap(saved -> json(200, dto(saved)))
                .onErrorResume(IllegalArgumentException.class, e -> json(409, Map.of("error", e.getMessage())));
    }

    private Mono<ServerResponse> delete(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        long id = longOf(req.pathVariable("id"));
        return blocking(() -> store.delete(uid, id))
                .flatMap(ok -> ok ? json(200, Map.of("ok", true)) : json(404, Map.of("error", "商家不存在")));
    }

    // ---- 编解码 ----

    private static MerchantProfile fromBody(long uid, Long id, Map<String, Object> b) {
        return new MerchantProfile(
                id, uid,
                str(b, "number"), str(b, "name"),
                b.get("enabled") == null || Boolean.TRUE.equals(b.get("enabled")) || "true".equals(String.valueOf(b.get("enabled"))),
                str(b, "industry"),
                str(b, "greeting"), str(b, "systemPrompt"), str(b, "transferDialString"),
                str(b, "summaryWebhook"), str(b, "ttsVoice"), str(b, "asrVocabularyId"),
                str(b, "address"), str(b, "businessHours"), str(b, "phone"), str(b, "transport"),
                str(b, "services"), str(b, "staff"), str(b, "bookingRules"), str(b, "notes"),
                null, null);
    }

    static Map<String, Object> dto(MerchantProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("number", p.number());
        m.put("name", p.name());
        m.put("enabled", p.enabled());
        m.put("industry", p.industry());
        m.put("greeting", p.greeting());
        m.put("systemPrompt", p.systemPrompt());
        m.put("transferDialString", p.transferDialString());
        m.put("summaryWebhook", p.summaryWebhook());
        m.put("ttsVoice", p.ttsVoice());
        m.put("asrVocabularyId", p.asrVocabularyId());
        m.put("address", p.address());
        m.put("businessHours", p.businessHours());
        m.put("phone", p.phone());
        m.put("transport", p.transport());
        m.put("services", p.services());
        m.put("staff", p.staff());
        m.put("bookingRules", p.bookingRules());
        m.put("notes", p.notes());
        m.put("createdAt", p.createdAt() == null ? null : p.createdAt().toString());
        m.put("updatedAt", p.updatedAt() == null ? null : p.updatedAt().toString());
        return m;
    }

    private static String str(Map<String, Object> b, String key) {
        Object v = b.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    // ---- 与 KnowledgeRoutes 相同的小工具 ----

    private Long userId(ServerRequest req) {
        String h = req.headers().firstHeader("Authorization");
        String token = (h != null && h.startsWith("Bearer ")) ? h.substring(7) : h;
        return token == null ? null : users.userIdOf(token);
    }

    private static <T> Mono<T> blocking(Callable<T> c) {
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
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 供单测: 把资料列表转成 DTO */
    static List<Map<String, Object>> dtos(List<MerchantProfile> list) {
        return list.stream().map(MerchantRoutes::dto).toList();
    }
}
