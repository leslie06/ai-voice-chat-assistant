package com.vca.store.gateway;

import com.vca.orchestrator.merchant.GatewayAccount;
import com.vca.orchestrator.merchant.GatewayControl;
import com.vca.orchestrator.merchant.GatewayStore;
import com.vca.orchestrator.merchant.MerchantProfile;
import com.vca.orchestrator.merchant.MerchantRules;
import com.vca.orchestrator.merchant.MerchantStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 在页面上开通/撤销语音网关(代替服务器上的 add-gateway.sh)。
 *
 * <p>顺序: 分配分机 → 落库 → 写 FreeSWITCH 分机文件 → 重载。后两步任一失败就把前面的撤回,
 * 不留"库里有、FreeSWITCH 里没有"的半截网关 —— 运营照着页面上的账号去配 HT813, 却永远注册不上。
 *
 * <p>分机号 8NN1(LINE)/ 8NN2(PHONE), NN 从 01 起找第一个空位; 既避开库里已有的, 也问 FreeSWITCH
 * 避开它目录里已存在的(配置文件里的老网关 8001/8002、脚本开的网关、软电话)。
 */
public final class GatewayProvisioning {

    private static final Logger log = LoggerFactory.getLogger(GatewayProvisioning.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GatewayStore gateways;
    private final MerchantStore merchants;
    private final Supplier<GatewayControl> control;

    public GatewayProvisioning(GatewayStore gateways, MerchantStore merchants, Supplier<GatewayControl> control) {
        this.gateways = gateways;
        this.merchants = merchants;
        this.control = control;
    }

    public GatewayControl control() {
        GatewayControl c = control.get();
        return c == null ? GatewayControl.UNAVAILABLE : c;
    }

    public List<GatewayAccount> list() {
        return gateways.listAll();
    }

    public Optional<GatewayAccount> forMerchant(long merchantId) {
        return gateways.findByMerchant(merchantId);
    }

    public Optional<GatewayAccount> find(long id) {
        return gateways.findById(id);
    }

    /**
     * 给这家店开通网关。门店还没配转人工分机的, 顺带配成这台网关的 PHONE 口。
     *
     * @throws IllegalStateException    电话交换没接入 / 写文件或重载失败
     * @throws IllegalArgumentException 这家店已经开通过
     */
    public synchronized GatewayAccount provision(MerchantProfile shop) {
        GatewayControl c = control();
        if (!c.available()) {
            throw new IllegalStateException(c.status().message().isEmpty()
                    ? "电话交换未接入, 暂时不能开通网关" : c.status().message());
        }
        if (gateways.findByMerchant(shop.id()).isPresent()) {
            throw new IllegalArgumentException("这家店已经开通过网关");
        }
        String[] ext = allocate(c);
        GatewayAccount saved = gateways.save(new GatewayAccount(null, shop.id(), MerchantRules.number(shop.number()),
                shop.label(), ext[0], password(), ext[1], password(), null));
        try {
            c.write(saved);
            c.reload();
        } catch (RuntimeException e) {
            gateways.delete(saved.id());
            try {
                c.remove(saved);
            } catch (RuntimeException ignored) {
                // 已经在失败路径上
            }
            throw e;
        }
        if (shop.transferDialString().isBlank()) {
            merchants.save(MerchantRules.withTransferDialString(shop, saved.phoneDialString()));
        }
        log.info("已开通网关: 门店 {}({}), LINE {}, PHONE {}", shop.label(), shop.number(), saved.lineUser(), saved.phoneUser());
        return saved;
    }

    /** 撤销网关。门店的转人工正是这台网关的 PHONE 口的, 一并清掉 —— 否则转去一个已经不存在的分机 */
    public synchronized void revoke(long gatewayId) {
        GatewayAccount a = gateways.findById(gatewayId)
                .orElseThrow(() -> new IllegalArgumentException("网关不存在"));
        GatewayControl c = control();
        c.remove(a);
        gateways.delete(a.id());
        try {
            c.reload();
        } catch (RuntimeException e) {
            log.warn("撤销网关后重载失败(文件已删, FreeSWITCH 下次重载时生效): {}", e.getMessage());
        }
        merchants.findById(a.merchantId())
                .filter(shop -> shop.transferDialString().equals(a.phoneDialString()))
                .ifPresent(shop -> merchants.save(MerchantRules.withTransferDialString(shop, "")));
        log.info("已撤销网关: LINE {}, PHONE {}", a.lineUser(), a.phoneUser());
    }

    /**
     * 门店资料变了之后调用: 改了接入号或店名的, 重写分机文件(LINE 口绑的是接入号); 门店被删了的, 撤销它的网关。
     * 由门店存储的变更通知触发, 失败只记日志 —— 不能让保存资料因为 FreeSWITCH 暂时连不上而失败。
     */
    public synchronized void refresh() {
        GatewayControl c = control();
        if (!c.available()) {
            return;
        }
        boolean changed = false;
        for (GatewayAccount a : gateways.listAll()) {
            Optional<MerchantProfile> shop = merchants.findById(a.merchantId());
            if (shop.isEmpty()) {
                c.remove(a);
                gateways.delete(a.id());
                changed = true;
                log.info("门店已删除, 撤销它的网关: LINE {}", a.lineUser());
                continue;
            }
            MerchantProfile s = shop.get();
            if (!s.number().equals(a.accessNumber()) || !s.label().equals(a.label())) {
                GatewayAccount updated = gateways.save(new GatewayAccount(a.id(), a.merchantId(), s.number(), s.label(),
                        a.lineUser(), a.linePassword(), a.phoneUser(), a.phonePassword(), a.createdAt()));
                c.write(updated);
                changed = true;
            }
        }
        if (changed) {
            try {
                c.reload();
            } catch (RuntimeException e) {
                log.warn("网关分机已更新但重载失败: {}", e.getMessage());
            }
        }
    }

    /** 启动时按库重写全部分机文件: 容器重建、文件被误删都能自愈 */
    public void syncAll() {
        GatewayControl c = control();
        if (!c.available()) {
            return;
        }
        try {
            c.sync(gateways.listAll());
            c.reload();
        } catch (RuntimeException e) {
            log.warn("启动时同步网关分机失败(FreeSWITCH 没起?): {}", e.getMessage());
        }
    }

    /** 门店 id → 网关, 列表页用 */
    public Map<Long, GatewayAccount> byMerchant() {
        return gateways.listAll().stream().collect(Collectors.toMap(GatewayAccount::merchantId, Function.identity()));
    }

    private String[] allocate(GatewayControl c) {
        Set<String> used = new HashSet<>();
        for (GatewayAccount a : gateways.listAll()) {
            used.add(a.lineUser());
            used.add(a.phoneUser());
        }
        for (int nn = 1; nn <= 99; nn++) {
            String line = String.format("8%02d1", nn);
            String phone = String.format("8%02d2", nn);
            if (used.contains(line) || used.contains(phone)) {
                continue;
            }
            if (c.userExists(line) || c.userExists(phone)) {
                continue;
            }
            return new String[]{line, phone};
        }
        throw new IllegalStateException("分机号 8011~8992 都用完了");
    }

    private static String password() {
        byte[] b = new byte[12];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
