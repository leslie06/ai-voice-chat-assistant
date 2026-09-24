package com.vca.store.merchant;

import com.vca.orchestrator.merchant.GreetingNotice;
import com.vca.orchestrator.merchant.Industry;
import com.vca.orchestrator.merchant.MerchantProfile;
import com.vca.orchestrator.merchant.MerchantRules;
import com.vca.orchestrator.merchant.MerchantStore;
import com.vca.store.account.AdminPolicy;
import com.vca.store.account.UserService;
import com.vca.store.entity.AppUser;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;

/**
 * 商家资料 REST。鉴权同 {@code KnowledgeRoutes}({@code Authorization: Bearer <token>} → userId)。
 *
 * <pre>
 *   GET    /api/merchants           → [profile]          我名下的门店(含停用); 管理员看全部
 *   POST   /api/merchants           {profile} → profile  新建 —— 仅管理员。ownerAccount(手机号/邮箱)指定归属账号
 *   GET    /api/merchants/{id}      → profile
 *   PUT    /api/merchants/{id}      {profile} → profile  整体覆盖(没传的字段按空处理)
 *   DELETE /api/merchants/{id}      → {ok}
 *   GET    /api/merchants/{id}/preview → {prompt}        看 AI 实际会拿到的机构资料文本(调试用)
 *   GET    /api/merchants/{id}/calls?days=30 → [call]     通话小结(摘要、意向、跟进建议、有没有录音)
 *   GET    /api/merchants/{id}/leads?days=90 → [lead]     AI 记下的线索(称呼、电话、意向、期望时间)
 *   GET    /api/merchants/{id}/calls/{callId}/recording → audio/wav   双声道录音(左 = 来电方, 右 = AI)
 *   GET    /api/merchants/industries   → [industry]       可选的行业及各字段在该行业里的叫法(网页表单用)
 * </pre>
 *
 * <p><b>两种角色</b>: 门店的所属账号只能改自己店的资料, 而接入号、转人工拨号串、热词表、音色这几项直通电话线路,
 * 只有运营管理员({@link AdminPolicy})能填 —— 注册是开放的, 让谁都能认领接入号, 等于谁都能把别家的来电接走。
 * 商家更新时这几项沿用库里的原值, 规则见 {@link MerchantRules}。
 *
 * <p>字段名与 {@link MerchantProfile} 一致(驼峰)。{@code ownerId}/{@code createdAt}/{@code updatedAt} 由服务端定,
 * 请求里传了也忽略。保存成功后存储层会通知电话注册表作废缓存, 下一通电话就用新资料。
 */
public final class MerchantRoutes {

    /** 一次请求的处理结果: 阻塞查库与鉴权判断都在同一个 callable 里做完, 再统一转成响应 */
    private record Outcome(int status, Object body) {
        static Outcome error(int status, String message) {
            return new Outcome(status, Map.of("error", message));
        }
    }

    private final UserService users;
    private final MerchantStore store;
    private final AdminPolicy admins;
    /** 通话/线索/录音; null = 没有这些表(只用门店资料的部署), 相关接口返回空 */
    private final MerchantActivity activity;
    /** 网关(商家版只看在线状态, 看不到密码); null = 没有网关功能 */
    private final com.vca.store.gateway.GatewayProvisioning gateways;

    private MerchantRoutes(UserService users, MerchantStore store, AdminPolicy admins, MerchantActivity activity,
                           com.vca.store.gateway.GatewayProvisioning gateways) {
        this.users = users;
        this.store = store;
        this.admins = admins == null ? AdminPolicy.NONE : admins;
        this.activity = activity;
        this.gateways = gateways;
    }

    public static RouterFunction<ServerResponse> create(UserService users, MerchantStore store, AdminPolicy admins) {
        return create(users, store, admins, null);
    }

    public static RouterFunction<ServerResponse> create(UserService users, MerchantStore store, AdminPolicy admins,
                                                        MerchantActivity activity) {
        return create(users, store, admins, activity, null);
    }

    public static RouterFunction<ServerResponse> create(UserService users, MerchantStore store, AdminPolicy admins,
                                                        MerchantActivity activity,
                                                        com.vca.store.gateway.GatewayProvisioning gateways) {
        MerchantRoutes r = new MerchantRoutes(users, store, admins, activity, gateways);
        return RouterFunctions.route(GET("/api/merchants"), r::list)
                .andRoute(POST("/api/merchants"), r::createOne)
                .andRoute(GET("/api/merchants/industries"), r::industries)
                .andRoute(GET("/api/merchants/{id}/preview"), r::preview)
                .andRoute(GET("/api/merchants/{id}/stats"), r::stats)
                .andRoute(GET("/api/merchants/{id}/gateway"), r::gatewayStatus)
                .andRoute(GET("/api/merchants/{id}/calls"), r::calls)
                .andRoute(GET("/api/merchants/{id}/leads"), r::leads)
                .andRoute(GET("/api/merchants/{id}/calls/{callId}/recording"), r::recording)
                .andRoute(GET("/api/merchants/{id}"), r::get)
                .andRoute(PUT("/api/merchants/{id}"), r::update)
                .andRoute(DELETE("/api/merchants/{id}"), r::delete);
    }

    private Mono<ServerResponse> list(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        return blocking(() -> admins.isAdmin(uid) ? store.listAll() : store.listByOwner(uid))
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
        return blocking(() -> store.findById(id).filter(p -> canSee(uid, p)))
                .flatMap(opt -> opt.map(p -> json(200, dto(p)))
                        .orElseGet(() -> json(404, Map.of("error", "商家不存在"))));
    }

    private Mono<ServerResponse> preview(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        long id = longOf(req.pathVariable("id"));
        return blocking(() -> store.findById(id).filter(p -> canSee(uid, p)))
                .flatMap(opt -> opt.map(p -> json(200, Map.of("prompt", p.renderProfile(), "greeting", spokenGreeting(p))))
                        .orElseGet(() -> json(404, Map.of("error", "商家不存在"))));
    }

    /** 电话里实际会播的开场白: 没写按店名生成, 再补上"智能助理接听、会录音"的告知(与电话侧同一套规则) */
    static String spokenGreeting(MerchantProfile p) {
        String g = p.greeting().isBlank() ? GreetingNotice.forShop(p.name().isBlank() ? p.number() : p.name(), true)
                : p.greeting();
        return GreetingNotice.apply(g);
    }

    /** 近 N 天(默认 30)按天的通话、意向、留资 */
    private Mono<ServerResponse> stats(ServerRequest req) {
        int days = req.queryParam("days").map(v -> {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                return 30;
            }
        }).orElse(30);
        return withShop(req, shop -> activity == null ? Map.of() : activity.stats(shop, Math.max(1, Math.min(days, 90))));
    }

    /** 这家店的网关在不在线(商家版概览用; 不含密码) */
    private Mono<ServerResponse> gatewayStatus(ServerRequest req) {
        return withShop(req, shop -> {
            Map<String, Object> m = new LinkedHashMap<>();
            var g = gateways == null ? java.util.Optional.<com.vca.orchestrator.merchant.GatewayAccount>empty()
                    : gateways.forMerchant(shop.id());
            m.put("opened", g.isPresent());
            g.ifPresent(a -> {
                var reg = gateways.control().status().registered();
                m.put("lineOnline", reg.contains(a.lineUser()));
                m.put("phoneOnline", reg.contains(a.phoneUser()));
                m.put("phoneExtension", a.phoneUser());
            });
            return m;
        });
    }

    // ---- 通话 / 线索 / 录音: 门店所属账号或管理员能看 ----

    private Mono<ServerResponse> calls(ServerRequest req) {
        return withShop(req, shop -> {
            if (activity == null) {
                return List.of();
            }
            LocalDateTime since = since(req, 30);
            return activity.calls(shop, since).stream().map(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("callId", c.getCallId());
                m.put("at", c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
                m.put("peerNumber", c.getPeerNumber());
                m.put("durationSec", c.getDurationSec());
                m.put("turns", c.getTurns());
                m.put("intent", c.getIntent());
                m.put("summary", c.getSummary());
                m.put("followUp", c.getFollowUp());
                m.put("hasRecording", activity.hasRecording(c.getCallId()));
                return m;
            }).toList();
        });
    }

    private Mono<ServerResponse> leads(ServerRequest req) {
        return withShop(req, shop -> activity == null ? List.of() : activity.leads(shop, since(req, 90)).stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("callId", l.getCallId());
            m.put("at", l.getCreatedAt() == null ? null : l.getCreatedAt().toString());
            m.put("name", l.getName());
            m.put("phone", l.getPhone());
            m.put("peerNumber", l.getPeerNumber());
            m.put("intent", l.getIntent());
            m.put("preferredTime", l.getPreferredTime());
            m.put("note", l.getNote());
            return m;
        }).toList());
    }

    /**
     * 录音文件。浏览器的 audio 标签带不了 Authorization 头, 所以网页是用 fetch 取回 blob 再播放 ——
     * 这里照常按 Bearer 鉴权, 不开"凭链接就能听"的口子(录音里是客户的声音和手机号)。
     */
    private Mono<ServerResponse> recording(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        if (activity == null) {
            return json(404, Map.of("error", "录音不存在"));
        }
        long id = longOf(req.pathVariable("id"));
        String callId = req.pathVariable("callId");
        return blocking(() -> store.findById(id).filter(p -> canSee(uid, p))
                        .flatMap(shop -> activity.recording(shop, callId)))
                .flatMap(opt -> opt.<Mono<ServerResponse>>map(file -> ServerResponse.ok()
                                .contentType(MediaType.parseMediaType("audio/wav"))
                                .header("Cache-Control", "private, no-store")
                                .body(BodyInserters.fromResource(new FileSystemResource(file))))
                        .orElseGet(() -> json(404, Map.of("error", "录音不存在或已过保留期"))));
    }

    /** 取门店(不是自己的当不存在), 再在阻塞线程上跑查询 */
    private Mono<ServerResponse> withShop(ServerRequest req,
                                          java.util.function.Function<MerchantProfile, Object> query) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        long id = longOf(req.pathVariable("id"));
        return blocking(() -> store.findById(id).filter(p -> canSee(uid, p))
                        .map(query))
                .flatMap(opt -> opt.map(body -> json(200, body))
                        .orElseGet(() -> json(404, Map.of("error", "商家不存在"))));
    }

    /** ?days=N, 默认 fallbackDays, 封顶一年 */
    private static LocalDateTime since(ServerRequest req, int fallbackDays) {
        int days = req.queryParam("days").map(v -> {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                return fallbackDays;
            }
        }).orElse(fallbackDays);
        return LocalDateTime.now().minusDays(Math.max(1, Math.min(days, 366)));
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
        return blocking(() -> id == null ? createAsAdmin(uid, body) : updateExisting(uid, id, body))
                .flatMap(o -> json(o.status(), o.body()));
    }

    /** 新建门店 = 分配接入号, 只有运营能做 */
    private Outcome createAsAdmin(long uid, Map<String, Object> body) {
        if (!admins.isAdmin(uid)) {
            return Outcome.error(403, "新建门店需要运营开通(接入号由运营分配)");
        }
        long ownerId = uid;
        String account = str(body, "ownerAccount").strip();
        if (!account.isEmpty()) {
            AppUser owner = users.findByUsernameOrEmail(account);
            if (owner == null) {
                return Outcome.error(400, "找不到账号 " + account + ", 请让商家先注册");
            }
            ownerId = owner.getId();
        }
        long owner = ownerId;
        return persist(() -> MerchantRules.checkedByAdmin(fromBody(owner, null, body)));
    }

    private Outcome updateExisting(long uid, long id, Map<String, Object> body) {
        MerchantProfile existing = store.findById(id).filter(p -> canSee(uid, p)).orElse(null);
        if (existing == null) {
            return Outcome.error(404, "商家不存在");
        }
        MerchantProfile incoming = fromBody(existing.ownerId(), id, body);
        return persist(() -> admins.isAdmin(uid)
                ? MerchantRules.checkedByAdmin(incoming)
                : MerchantRules.checkedByOwner(incoming, existing));
    }

    /** 校验不过是 400(填错了); 存储层拒绝是 409(号码被占) */
    private Outcome persist(java.util.function.Supplier<MerchantProfile> checked) {
        MerchantProfile profile;
        try {
            profile = checked.get();
        } catch (IllegalArgumentException e) {
            return Outcome.error(400, e.getMessage());
        }
        try {
            return new Outcome(200, dto(store.save(profile)));
        } catch (IllegalArgumentException e) {
            return Outcome.error(409, e.getMessage());
        }
    }

    private Mono<ServerResponse> delete(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        long id = longOf(req.pathVariable("id"));
        return blocking(() -> store.findById(id).filter(p -> canSee(uid, p))
                        .map(p -> store.delete(p.ownerId(), id)).orElse(false))
                .flatMap(ok -> ok ? json(200, Map.of("ok", true)) : json(404, Map.of("error", "商家不存在")));
    }

    /** 自己名下的店, 或者自己是运营管理员 */
    private boolean canSee(long uid, MerchantProfile p) {
        return p.ownerId() == uid || admins.isAdmin(uid);
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

    public static Map<String, Object> dto(MerchantProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("ownerId", p.ownerId());
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
