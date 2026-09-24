package com.vca.store.admin;

import com.vca.orchestrator.merchant.GatewayAccount;
import com.vca.orchestrator.merchant.GatewayControl;
import com.vca.orchestrator.merchant.Industry;
import com.vca.orchestrator.merchant.MerchantProfile;
import com.vca.orchestrator.merchant.MerchantRules;
import com.vca.orchestrator.merchant.MerchantStore;
import com.vca.store.account.AdminPolicy;
import com.vca.store.account.UserService;
import com.vca.store.entity.AppUser;
import com.vca.store.entity.PhoneCallSummary;
import com.vca.store.entity.PhoneLead;
import com.vca.store.gateway.GatewayProvisioning;
import com.vca.store.merchant.MerchantActivity;
import com.vca.store.merchant.MerchantRoutes;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;

/**
 * 运营后台的接口(/api/admin/**), 只给运营管理员。页面见 static/admin/。
 *
 * <pre>
 *   GET    /api/admin/overview                     总览: 门店/网关/来电/留资 + 系统状态 + 最近通话
 *   GET    /api/admin/merchants                    全部门店(含归属账号、网关状态、近 7 天来电) + 配置文件里的门店
 *   POST   /api/admin/merchants                    添加商家: 开账号(或用已有账号) + 建门店 + 可选开通网关
 *   GET    /api/admin/next-number                  建议的下一个接入号
 *   POST   /api/admin/merchants/{id}/gateway       给门店开通网关
 *   GET    /api/admin/gateways                     全部网关与在线状态
 *   GET    /api/admin/gateways/{id}                一台网关的完整配置(含密码, HT813 上要填的)
 *   DELETE /api/admin/gateways/{id}                撤销网关
 *   POST   /api/admin/accounts/{userId}/reset-password  重置商家账号的密码, 返回新密码
 *   GET    /api/admin/admins                       管理员列表
 *   POST   /api/admin/admins {phone}               授予管理员
 *   DELETE /api/admin/admins/{userId}              撤销管理员(配置文件里的超级管理员撤不掉)
 *   GET    /api/admin/calls?days=7&number=         跨门店的通话小结
 *   GET    /api/admin/leads?days=30&number=        跨门店的线索
 * </pre>
 *
 * 门店资料的编辑、删除、单店的通话/线索/录音走 {@code /api/merchants/**}(管理员可以操作任何门店)。
 */
public final class AdminRoutes {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 初始密码的字符: 去掉 0/o/1/l/i 这类念给商家听、抄下来容易错的 */
    private static final String PASSWORD_CHARS = "abcdefghjkmnpqrstuvwxyz23456789";

    private final UserService users;
    private final MerchantStore store;
    private final AdminPolicy admins;
    private final MerchantActivity activity;
    private final GatewayProvisioning gateways;
    /** 配置文件(vca.telephony.merchants)里的门店: 接入号 → 店名。库里没有它们, 但号码已被占用 */
    private final Map<String, String> configMerchants;
    private final Path recordingsDir;

    private AdminRoutes(UserService users, MerchantStore store, AdminPolicy admins, MerchantActivity activity,
                        GatewayProvisioning gateways, Map<String, String> configMerchants, Path recordingsDir) {
        this.users = users;
        this.store = store;
        this.admins = admins;
        this.activity = activity;
        this.gateways = gateways;
        this.configMerchants = configMerchants == null ? Map.of() : configMerchants;
        this.recordingsDir = recordingsDir;
    }

    public static RouterFunction<ServerResponse> create(UserService users, MerchantStore store, AdminPolicy admins,
                                                        MerchantActivity activity, GatewayProvisioning gateways,
                                                        Map<String, String> configMerchants, Path recordingsDir) {
        AdminRoutes r = new AdminRoutes(users, store, admins, activity, gateways, configMerchants, recordingsDir);
        return RouterFunctions.route(GET("/api/admin/overview"), req -> r.admin(req, uid -> r.overview()))
                .andRoute(GET("/api/admin/merchants"), req -> r.admin(req, uid -> r.merchants()))
                .andRoute(POST("/api/admin/merchants"), req -> r.adminWithBody(req, (uid, b) -> r.onboard(b)))
                .andRoute(GET("/api/admin/next-number"), req -> r.admin(req, uid -> Map.of("number", r.nextNumber())))
                .andRoute(POST("/api/admin/merchants/{id}/gateway"),
                        req -> r.admin(req, uid -> r.provision(longOf(req.pathVariable("id")))))
                .andRoute(GET("/api/admin/gateways"), req -> r.admin(req, uid -> r.gatewayList()))
                .andRoute(GET("/api/admin/gateways/{id}"),
                        req -> r.admin(req, uid -> r.gatewayConfig(longOf(req.pathVariable("id")))))
                .andRoute(DELETE("/api/admin/gateways/{id}"),
                        req -> r.admin(req, uid -> r.revoke(longOf(req.pathVariable("id")))))
                .andRoute(POST("/api/admin/accounts/{userId}/reset-password"),
                        req -> r.admin(req, uid -> r.resetPassword(longOf(req.pathVariable("userId")))))
                .andRoute(GET("/api/admin/admins"), req -> r.admin(req, uid -> r.adminList(uid)))
                .andRoute(POST("/api/admin/admins"), req -> r.adminWithBody(req, (uid, b) -> r.grant(b)))
                .andRoute(DELETE("/api/admin/admins/{userId}"),
                        req -> r.admin(req, uid -> r.revokeAdmin(uid, longOf(req.pathVariable("userId")))))
                .andRoute(GET("/api/admin/calls"), req -> r.admin(req, uid -> r.calls(req)))
                .andRoute(GET("/api/admin/leads"), req -> r.admin(req, uid -> r.leads(req)));
    }

    // ---- 总览 ----

    private Object overview() {
        List<MerchantProfile> shops = store.listAll();
        List<GatewayAccount> gws = gateways.list();
        GatewayControl.Status fs = gateways.control().status();
        MerchantActivity.Stats week = activity.stats(null, 7);
        MerchantActivity.Stats today = activity.stats(null, 1);
        Map<String, String> names = storeNames(shops);

        long online = gws.stream().filter(g -> fs.registered().contains(g.lineUser())).count();
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("freeswitchReachable", fs.reachable());
        system.put("freeswitchRunning", fs.running());
        system.put("freeswitchMessage", fs.message());
        system.put("gatewayProvisioning", gateways.control().available());
        system.put("sipServer", gateways.control().sipServer());
        system.put("recordingsDir", recordingsDir == null ? "" : recordingsDir.toString());
        if (recordingsDir != null) {
            File f = recordingsDir.toFile();
            system.put("diskFreeGb", Math.round(f.getUsableSpace() / 1e8) / 10.0);
            system.put("diskTotalGb", Math.round(f.getTotalSpace() / 1e8) / 10.0);
        }

        // 配置文件里的门店只算库里没有的: 库里建了同号门店就是接管了它
        long legacy = configMerchants.keySet().stream()
                .filter(n -> shops.stream().noneMatch(p -> p.number().equals(n))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stores", Map.of("total", shops.size() + legacy,
                "enabled", shops.stream().filter(MerchantProfile::enabled).count() + legacy));
        out.put("gateways", Map.of("total", gws.size(), "online", online));
        out.put("today", Map.of("calls", today.calls(), "leads", today.leads()));
        out.put("week", week);
        out.put("system", system);
        out.put("recent", activity.callsAll(LocalDateTime.now().minusDays(7)).stream().limit(8)
                .map(c -> callRow(c, names, shops)).toList());
        return out;
    }

    // ---- 门店 ----

    private Object merchants() {
        List<MerchantProfile> shops = store.listAll();
        Map<Long, GatewayAccount> byShop = gateways.byMerchant();
        Set<String> registered = gateways.control().status().registered();
        Map<String, Integer> weekCalls = activity.stats(null, 7).byNumber();
        Map<Long, AppUser> owners = new HashMap<>();
        for (AppUser u : users.findByIds(shops.stream().map(MerchantProfile::ownerId).distinct().toList())) {
            owners.put(u.getId(), u);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (MerchantProfile p : shops) {
            Map<String, Object> m = MerchantRoutes.dto(p);
            AppUser owner = owners.get(p.ownerId());
            m.put("ownerAccount", owner == null ? "" : owner.getUsername());
            m.put("gateway", gatewaySummary(byShop.get(p.id()), registered));
            m.put("calls7d", weekCalls.getOrDefault(p.number(), 0));
            list.add(m);
        }
        List<Map<String, Object>> legacy = new ArrayList<>();
        configMerchants.forEach((number, name) -> {
            if (shops.stream().noneMatch(p -> p.number().equals(number))) {
                legacy.add(Map.of("number", number, "name", name, "calls7d", weekCalls.getOrDefault(number, 0)));
            }
        });
        return Map.of("merchants", list, "configMerchants", legacy);
    }

    /**
     * 添加商家: 手机号没注册过就开账号(初始密码不填则生成), 注册过就把门店挂到这个账号下;
     * 然后建门店, 勾了"开通网关"再开通。网关开不了(电话交换暂时连不上)不影响前两步, 结果里说明。
     */
    private Object onboard(Map<String, Object> b) {
        String phone = str(b, "phone").strip();
        String name = str(b, "name").strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("请填写门店名称");
        }
        String number = str(b, "number").isBlank() ? nextNumber() : MerchantRules.number(str(b, "number"));
        if (store.listAll().stream().anyMatch(p -> p.number().equals(number))) {
            throw new Conflict("接入号 " + number + " 已被其他门店使用");
        }

        AppUser owner = users.findByPhone(phone);
        String initialPassword = null;
        if (owner == null) {
            initialPassword = str(b, "password").isBlank() ? newPassword() : str(b, "password");
            owner = users.createByAdmin(phone, str(b, "email"), initialPassword);
        }
        MerchantProfile shop = store.save(MerchantRules.checkedByAdmin(new MerchantProfile(null, owner.getId(), number,
                name, true, Industry.of(str(b, "industry")).code(), "", "", "", "", "", "",
                "", "", "", "", "", "", "", "", null, null)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("merchant", MerchantRoutes.dto(shop));
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("phone", owner.getUsername());
        account.put("created", initialPassword != null);
        if (initialPassword != null) {
            account.put("password", initialPassword);
        }
        out.put("account", account);
        if (Boolean.TRUE.equals(b.get("gateway")) || "true".equals(String.valueOf(b.get("gateway")))) {
            try {
                out.put("gateway", gatewayConfig(gateways.provision(shop)));
            } catch (RuntimeException e) {
                out.put("gatewayError", e.getMessage());
            }
        }
        return out;
    }

    /** 下一个空闲接入号: 库里与配置文件里用过的最大号 + 1, 从 5000 起 */
    String nextNumber() {
        Set<String> used = new LinkedHashSet<>(configMerchants.keySet());
        store.listAll().forEach(p -> used.add(p.number()));
        long max = 4999;
        for (String n : used) {
            if (n.length() <= 6 && n.chars().allMatch(Character::isDigit)) {
                max = Math.max(max, Long.parseLong(n));
            }
        }
        long next = max + 1;
        while (used.contains(String.valueOf(next))) {
            next++;
        }
        return String.valueOf(next);
    }

    // ---- 网关 ----

    private Object provision(long merchantId) {
        MerchantProfile shop = store.findById(merchantId)
                .orElseThrow(() -> new NotFound("门店不存在"));
        return gatewayConfig(gateways.provision(shop));
    }

    private Object gatewayList() {
        GatewayControl c = gateways.control();
        GatewayControl.Status fs = c.status();
        Map<Long, MerchantProfile> shops = new HashMap<>();
        store.listAll().forEach(p -> shops.put(p.id(), p));
        Set<String> known = new LinkedHashSet<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (GatewayAccount g : gateways.list()) {
            known.add(g.lineUser());
            known.add(g.phoneUser());
            Map<String, Object> m = gatewaySummary(g, fs.registered());
            MerchantProfile shop = shops.get(g.merchantId());
            m.put("merchantId", g.merchantId());
            m.put("store", shop == null ? g.label() : shop.label());
            m.put("accessNumber", g.accessNumber());
            m.put("createdAt", g.createdAt() == null ? null : g.createdAt().toString());
            list.add(m);
        }
        List<String> others = fs.registered().stream().filter(u -> !known.contains(u)).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gateways", list);
        out.put("otherRegistered", others);
        out.put("available", c.available());
        out.put("reachable", fs.reachable());
        out.put("running", fs.running());
        out.put("message", fs.message());
        out.put("sipServer", c.sipServer());
        return out;
    }

    private Map<String, Object> gatewayConfig(long id) {
        return gatewayConfig(gateways.find(id).orElseThrow(() -> new NotFound("网关不存在")));
    }

    /** HT813 上要填的全部项。含密码, 只给管理员 */
    private Map<String, Object> gatewayConfig(GatewayAccount g) {
        Map<String, Object> m = gatewaySummary(g, gateways.control().status().registered());
        m.put("merchantId", g.merchantId());
        m.put("store", g.label());
        m.put("accessNumber", g.accessNumber());
        m.put("linePassword", g.linePassword());
        m.put("phonePassword", g.phonePassword());
        m.put("sipServer", gateways.control().sipServer());
        return m;
    }

    private static Map<String, Object> gatewaySummary(GatewayAccount g, Set<String> registered) {
        if (g == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.id());
        m.put("lineUser", g.lineUser());
        m.put("phoneUser", g.phoneUser());
        m.put("lineOnline", registered.contains(g.lineUser()));
        m.put("phoneOnline", registered.contains(g.phoneUser()));
        return m;
    }

    private Object revoke(long id) {
        gateways.revoke(id);
        return Map.of("ok", true);
    }

    // ---- 账号与管理员 ----

    private Object resetPassword(long userId) {
        AppUser u = users.findById(userId);
        if (u == null) {
            throw new NotFound("账号不存在");
        }
        String pw = newPassword();
        users.updatePassword(userId, pw);
        return Map.of("phone", u.getUsername(), "password", pw);
    }

    private Object adminList(long self) {
        Set<Long> ids = new LinkedHashSet<>(admins.superAdmins());
        users.listByRole(UserService.ROLE_ADMIN).forEach(u -> ids.add(u.getId()));
        List<Map<String, Object>> list = new ArrayList<>();
        for (AppUser u : users.findByIds(ids)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", u.getId());
            m.put("phone", u.getUsername());
            m.put("super", admins.isSuperAdmin(u.getId()));
            m.put("self", u.getId() == self);
            m.put("lastLoginAt", u.getLastLoginAt() == null ? null : u.getLastLoginAt().toString());
            list.add(m);
        }
        return list;
    }

    private Object grant(Map<String, Object> b) {
        AppUser u = users.findByPhone(str(b, "phone"));
        if (u == null) {
            throw new NotFound("这个手机号还没有账号, 先让对方注册, 或在「添加商家」里开账号");
        }
        users.setRole(u.getId(), UserService.ROLE_ADMIN);
        return Map.of("ok", true);
    }

    private Object revokeAdmin(long self, long userId) {
        if (admins.isSuperAdmin(userId)) {
            throw new IllegalArgumentException("配置文件里的超级管理员不能在页面上撤销");
        }
        if (userId == self) {
            throw new IllegalArgumentException("不能撤销自己");
        }
        users.setRole(userId, UserService.ROLE_USER);
        return Map.of("ok", true);
    }

    // ---- 跨门店的通话与线索 ----

    private Object calls(ServerRequest req) {
        List<MerchantProfile> shops = store.listAll();
        Map<String, String> names = storeNames(shops);
        String number = req.queryParam("number").orElse("");
        return activity.callsAll(since(req, 7)).stream()
                .filter(c -> number.isEmpty() || number.equals(c.getCalledNumber()))
                .map(c -> callRow(c, names, shops)).toList();
    }

    private Object leads(ServerRequest req) {
        List<MerchantProfile> shops = store.listAll();
        Map<String, String> names = storeNames(shops);
        String number = req.queryParam("number").orElse("");
        return activity.leadsAll(since(req, 30)).stream()
                .filter(l -> number.isEmpty() || number.equals(l.getCalledNumber()))
                .map(l -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("callId", l.getCallId());
                    m.put("at", l.getCreatedAt() == null ? null : l.getCreatedAt().toString());
                    m.put("store", names.getOrDefault(l.getCalledNumber(), l.getCalledNumber()));
                    m.put("number", l.getCalledNumber());
                    m.put("name", l.getName());
                    m.put("phone", l.getPhone());
                    m.put("peerNumber", l.getPeerNumber());
                    m.put("intent", l.getIntent());
                    m.put("preferredTime", l.getPreferredTime());
                    m.put("note", l.getNote());
                    return m;
                }).toList();
    }

    private Map<String, Object> callRow(PhoneCallSummary c, Map<String, String> names, List<MerchantProfile> shops) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("callId", c.getCallId());
        m.put("at", c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
        m.put("store", names.getOrDefault(c.getCalledNumber(), c.getCalledNumber()));
        m.put("number", c.getCalledNumber());
        // 录音回放走 /api/merchants/{id}/calls/{callId}/recording, 要门店 id; 配置文件里的门店没有, 不给听
        shops.stream().filter(p -> p.number().equals(c.getCalledNumber()) && p.ownerId() == c.getOwnerId())
                .findFirst().ifPresent(p -> m.put("merchantId", p.id()));
        m.put("peerNumber", c.getPeerNumber());
        m.put("durationSec", c.getDurationSec());
        m.put("intent", c.getIntent());
        m.put("summary", c.getSummary());
        m.put("followUp", c.getFollowUp());
        m.put("hasRecording", m.containsKey("merchantId") && activity.hasRecording(c.getCallId()));
        return m;
    }

    private Map<String, String> storeNames(List<MerchantProfile> shops) {
        Map<String, String> names = new HashMap<>(configMerchants);
        shops.forEach(p -> names.put(p.number(), p.label()));
        return names;
    }

    // ---- 小工具 ----

    /** 找不到 → 404 */
    static final class NotFound extends RuntimeException {
        NotFound(String m) {
            super(m);
        }
    }

    /** 冲突 → 409 */
    static final class Conflict extends RuntimeException {
        Conflict(String m) {
            super(m);
        }
    }

    private Mono<ServerResponse> admin(ServerRequest req, Function<Long, Object> action) {
        Long uid = userId(req);
        if (uid == null) {
            return json(401, Map.of("error", "未登录或登录已失效"));
        }
        if (!admins.isAdmin(uid)) {
            return json(403, Map.of("error", "仅运营管理员可用"));
        }
        return Mono.fromCallable(() -> action.apply(uid))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(body -> json(200, body))
                .onErrorResume(AdminRoutes::error);
    }

    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> adminWithBody(ServerRequest req,
                                               java.util.function.BiFunction<Long, Map<String, Object>, Object> action) {
        return req.bodyToMono(Map.class).defaultIfEmpty(Map.of())
                .flatMap(body -> admin(req, uid -> action.apply(uid, (Map<String, Object>) body)));
    }

    private static Mono<ServerResponse> error(Throwable e) {
        int status = e instanceof NotFound ? 404
                : e instanceof Conflict ? 409
                : e instanceof IllegalArgumentException ? 400
                : e instanceof IllegalStateException ? 503
                : 500;
        String msg = e.getMessage() == null || status == 500 ? "服务器出错了, 请稍后再试" : e.getMessage();
        return json(status, Map.of("error", msg));
    }

    private Long userId(ServerRequest req) {
        String h = req.headers().firstHeader("Authorization");
        String token = (h != null && h.startsWith("Bearer ")) ? h.substring(7) : h;
        return token == null ? null : users.userIdOf(token);
    }

    private static Mono<ServerResponse> json(int status, Object body) {
        return ServerResponse.status(status).bodyValue(body);
    }

    private static LocalDateTime since(ServerRequest req, int fallbackDays) {
        int days = req.queryParam("days").map(v -> {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                return fallbackDays;
            }
        }).orElse(fallbackDays);
        return LocalDate.now().minusDays(Math.max(1, Math.min(days, 366)) - 1L).atStartOfDay();
    }

    private static String str(Map<String, Object> b, String key) {
        Object v = b.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static long longOf(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String newPassword() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(PASSWORD_CHARS.charAt(RANDOM.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
