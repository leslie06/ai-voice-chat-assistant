package com.vca.store;

import com.vca.orchestrator.merchant.GatewayAccount;
import com.vca.orchestrator.merchant.GatewayControl;
import com.vca.orchestrator.merchant.GatewayStore;
import com.vca.orchestrator.merchant.MerchantProfile;
import com.vca.orchestrator.merchant.MerchantStore;
import com.vca.store.gateway.GatewayProvisioning;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 页面上开通/撤销网关: 分机分配、失败回滚、门店变更后跟着改。 */
class GatewayProvisioningTest {

    /** 扮演 FreeSWITCH: 记下写了哪些分机文件、重载了几次; existing = 目录里本来就有的分机 */
    static final class FakeControl implements GatewayControl {
        final Map<String, GatewayAccount> files = new LinkedHashMap<>();
        final Set<String> existing = new HashSet<>();
        int reloads;
        boolean failReload;

        @Override public boolean available() { return true; }
        @Override public String sipServer() { return "47.95.248.104"; }
        @Override public boolean userExists(String ext) { return existing.contains(ext); }
        @Override public void write(GatewayAccount a) { files.put(a.lineUser(), a); }
        @Override public void remove(GatewayAccount a) { files.remove(a.lineUser()); }
        @Override public void sync(Collection<GatewayAccount> all) { files.clear(); all.forEach(this::write); }
        @Override public void reload() {
            if (failReload) {
                throw new IllegalStateException("连不上 FreeSWITCH");
            }
            reloads++;
        }
        @Override public Status status() { return new Status(true, true, Set.of(), ""); }
    }

    static final class MemGateways implements GatewayStore {
        final Map<Long, GatewayAccount> rows = new LinkedHashMap<>();
        long seq;

        @Override public List<GatewayAccount> listAll() { return new ArrayList<>(rows.values()); }
        @Override public Optional<GatewayAccount> findById(long id) { return Optional.ofNullable(rows.get(id)); }
        @Override public Optional<GatewayAccount> findByMerchant(long m) {
            return rows.values().stream().filter(a -> a.merchantId() == m).findFirst();
        }
        @Override public GatewayAccount save(GatewayAccount a) {
            long id = a.id() == null ? ++seq : a.id();
            GatewayAccount s = new GatewayAccount(id, a.merchantId(), a.accessNumber(), a.label(), a.lineUser(),
                    a.linePassword(), a.phoneUser(), a.phonePassword(), a.createdAt());
            rows.put(id, s);
            return s;
        }
        @Override public boolean delete(long id) { return rows.remove(id) != null; }
    }

    static final class MemShops implements MerchantStore {
        final Map<Long, MerchantProfile> rows = new LinkedHashMap<>();

        @Override public Optional<MerchantProfile> findByNumber(String n) {
            return rows.values().stream().filter(p -> p.number().equals(n)).findFirst();
        }
        @Override public Optional<MerchantProfile> findById(long id) { return Optional.ofNullable(rows.get(id)); }
        @Override public List<MerchantProfile> listByOwner(long o) { return List.of(); }
        @Override public List<MerchantProfile> listEnabled() { return new ArrayList<>(rows.values()); }
        @Override public MerchantProfile save(MerchantProfile p) { rows.put(p.id(), p); return p; }
        @Override public boolean delete(long owner, long id) { return rows.remove(id) != null; }
    }

    private final FakeControl fs = new FakeControl();
    private final MemGateways gateways = new MemGateways();
    private final MemShops shops = new MemShops();
    private final GatewayProvisioning p = new GatewayProvisioning(gateways, shops, () -> fs);

    private MerchantProfile shop(long id, String number, String name, String transfer) {
        MerchantProfile s = new MerchantProfile(id, 21, number, name, true, "dental", "", "", transfer, "", "", "",
                "", "", "", "", "", "", "", "", null, null);
        shops.save(s);
        return s;
    }

    @Test
    void allocatesTheFirstFreePairAvoidingExtensionsFreeSwitchAlreadyHas() {
        fs.existing.add("8011");   // 比如脚本开过的网关
        GatewayAccount a = p.provision(shop(1, "5002", "阳光口腔", ""));

        assertThat(a.lineUser()).isEqualTo("8021");
        assertThat(a.phoneUser()).isEqualTo("8022");
        assertThat(a.linePassword()).matches("[0-9a-f]{24}");
        assertThat(fs.files).containsKey("8021");
        assertThat(fs.reloads).isEqualTo(1);
        assertThat(shops.findById(1).get().transferDialString()).as("门店没配转人工的, 配成 PHONE 口")
                .isEqualTo("user/8022@vca.local");

        GatewayAccount b = p.provision(shop(2, "5003", "启明英语", "user/9000@vca.local"));
        assertThat(b.lineUser()).isEqualTo("8031");
        assertThat(shops.findById(2).get().transferDialString()).as("已配过的不动").isEqualTo("user/9000@vca.local");
    }

    /** FreeSWITCH 重载失败: 库和文件都撤回, 不留"页面上有、注册不上"的半截网关 */
    @Test
    void rollsBackWhenFreeSwitchCannotReload() {
        fs.failReload = true;
        assertThatThrownBy(() -> p.provision(shop(1, "5002", "阳光口腔", ""))).hasMessageContaining("连不上");
        assertThat(gateways.rows).isEmpty();
        assertThat(fs.files).isEmpty();
        assertThat(shops.findById(1).get().transferDialString()).isEmpty();
    }

    @Test
    void oneGatewayPerShop() {
        MerchantProfile s = shop(1, "5002", "阳光口腔", "");
        p.provision(s);
        assertThatThrownBy(() -> p.provision(s)).hasMessageContaining("已经开通过");
    }

    @Test
    void revokeRemovesFileRowAndTheTransferThatPointedAtIt() {
        GatewayAccount a = p.provision(shop(1, "5002", "阳光口腔", ""));
        p.revoke(a.id());
        assertThat(fs.files).isEmpty();
        assertThat(gateways.rows).isEmpty();
        assertThat(shops.findById(1).get().transferDialString()).isEmpty();
    }

    @Test
    void refreshFollowsShopChanges() {
        GatewayAccount a = p.provision(shop(1, "5002", "阳光口腔", ""));
        shop(1, "5009", "阳光口腔二店", "user/8012@vca.local");   // 改了接入号和店名
        p.refresh();
        assertThat(fs.files.get(a.lineUser()).accessNumber()).as("LINE 口绑的接入号跟着改").isEqualTo("5009");
        assertThat(fs.files.get(a.lineUser()).label()).isEqualTo("阳光口腔二店");

        shops.rows.clear();   // 门店被删
        p.refresh();
        assertThat(fs.files).isEmpty();
        assertThat(gateways.rows).isEmpty();
    }
}
