package com.vca.orchestrator.merchant;

import com.vca.domain.spi.VocabularyClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按行业自动维护热词表: 每个行业一张, 词 = 基础词 + 该行业所有启用门店的店名与项目名;
 * 重启后按前缀找回旧表不重建; 词变了才改; 厂商接口坏了不影响电话。
 */
class HotWordSyncTest {

    private static final String MODEL = "paraformer-realtime-8k-v2";

    /** 内存版厂商热词接口: 记录每次调用, 可注入故障 */
    static final class FakeClient implements VocabularyClient {
        final Map<String, Snapshot> tables = new LinkedHashMap<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        boolean broken;
        int seq;

        @Override
        public List<String> list(String prefix) {
            fail();
            calls.add("list:" + prefix);
            List<String> ids = new ArrayList<>();
            for (String id : tables.keySet()) {
                if (id.startsWith("vocab-" + prefix + "-")) {
                    ids.add(0, id);   // 新的在前
                }
            }
            return ids;
        }

        @Override
        public Snapshot query(String id) {
            fail();
            calls.add("query:" + id);
            Snapshot s = tables.get(id);
            if (s == null) {
                throw new IllegalStateException("no such table " + id);
            }
            return s;
        }

        @Override
        public String create(String prefix, String targetModel, List<String> words) {
            fail();
            String id = "vocab-" + prefix + "-" + (++seq);
            calls.add("create:" + id);
            tables.put(id, new Snapshot(id, targetModel, List.copyOf(words)));
            return id;
        }

        @Override
        public void update(String id, List<String> words) {
            fail();
            calls.add("update:" + id);
            tables.put(id, new Snapshot(id, tables.get(id).targetModel(), List.copyOf(words)));
        }

        @Override
        public boolean delete(String id) {
            calls.add("delete:" + id);
            return tables.remove(id) != null;
        }

        private void fail() {
            if (broken) {
                throw new IllegalStateException("vendor down");
            }
        }
    }

    /** 内存版商家库, 带变更通知 */
    static class MemStore implements MerchantStore {
        final List<MerchantProfile> rows = new ArrayList<>();
        final List<Runnable> listeners = new ArrayList<>();

        @Override
        public Optional<MerchantProfile> findByNumber(String number) {
            return rows.stream().filter(p -> p.enabled() && p.number().equals(number)).findFirst();
        }

        @Override
        public Optional<MerchantProfile> findById(long id) {
            return rows.stream().filter(p -> p.id() == id).findFirst();
        }

        @Override
        public List<MerchantProfile> listByOwner(long ownerId) {
            return rows.stream().filter(p -> p.ownerId() == ownerId).toList();
        }

        @Override
        public List<MerchantProfile> listEnabled() {
            return rows.stream().filter(MerchantProfile::enabled).toList();
        }

        @Override
        public MerchantProfile save(MerchantProfile p) {
            rows.removeIf(r -> r.id() != null && r.id().equals(p.id()));
            rows.add(p);
            listeners.forEach(Runnable::run);
            return p;
        }

        @Override
        public boolean delete(long ownerId, long id) {
            boolean removed = rows.removeIf(r -> r.id() == id && r.ownerId() == ownerId);
            listeners.forEach(Runnable::run);
            return removed;
        }

        @Override
        public void addChangeListener(Runnable listener) {
            listeners.add(listener);
        }
    }

    private static MerchantProfile merchant(long id, String number, String name, String industry, String services) {
        return new MerchantProfile(id, 11, number, name, true, industry, "", "", "", "", "", "",
                "", "", "", "", services, "", "", "", null, null);
    }

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private HotWordSync sync;

    private HotWordSync sync(MemStore store, FakeClient client) {
        sync = new HotWordSync(store, client, MODEL, scheduler, Duration.ofMillis(50));
        return sync;
    }

    @AfterEach
    void tearDown() {
        if (sync != null) {
            sync.close();
        }
    }

    @Test
    void createsOneTablePerIndustryWithBaseWordsPlusMerchantWords() {
        MemStore store = new MemStore();
        store.rows.add(merchant(1, "5000", "美好口腔", "dental", "洗牙 200 元\n种植牙 6800 元起"));
        store.rows.add(merchant(2, "5001", "启明少儿英语", "education", "雅思冲刺班 12000 元"));
        store.rows.add(merchant(3, "5002", "阳光口腔", "dental", "正畸 12000 元"));
        FakeClient client = new FakeClient();

        Map<Industry, String> ids = sync(store, client).syncNow();

        assertThat(ids).containsOnlyKeys(Industry.DENTAL, Industry.EDUCATION);
        List<String> dental = client.tables.get(ids.get(Industry.DENTAL)).words();
        assertThat(dental).as("行业基础词 + 两家店的店名与项目名, 去重")
                .contains("洗牙", "种植牙", "预约", "美好口腔", "阳光口腔", "正畸")
                .doesNotHaveDuplicates()
                .doesNotContain("启明少儿英语", "雅思冲刺班");
        assertThat(client.tables.get(ids.get(Industry.EDUCATION)).words())
                .contains("试听", "启明少儿英语", "雅思冲刺班").doesNotContain("洗牙");
        assertThat(client.tables.get(ids.get(Industry.DENTAL)).targetModel()).isEqualTo(MODEL);
        assertThat(sync.vocabularyIdFor(Industry.DENTAL)).contains(ids.get(Industry.DENTAL));
        assertThat(sync.vocabularyIdFor(Industry.GENERIC)).as("没有这个行业的店就没有表").isEmpty();
    }

    @Test
    void reusesExistingTableAfterRestartWhenWordsUnchanged() {
        MemStore store = new MemStore();
        store.rows.add(merchant(1, "5000", "美好口腔", "dental", "洗牙 200 元"));
        FakeClient client = new FakeClient();
        String id = sync(store, client).syncNow().get(Industry.DENTAL);
        sync.close();
        client.calls.clear();

        // "重启": 新的同步器, 内存里没有 id
        HotWordSync restarted = new HotWordSync(store, client, MODEL, Executors.newSingleThreadScheduledExecutor(),
                Duration.ofMillis(50));
        try {
            assertThat(restarted.syncNow().get(Industry.DENTAL)).isEqualTo(id);
            assertThat(client.calls).as("按前缀找回旧表, 查一下内容, 不建不改")
                    .containsExactly("list:vcadental", "query:" + id);
        } finally {
            restarted.close();
        }
    }

    @Test
    void updatesInsteadOfCreatingWhenWordsChange() {
        MemStore store = new MemStore();
        store.rows.add(merchant(1, "5000", "美好口腔", "dental", "洗牙 200 元"));
        FakeClient client = new FakeClient();
        String id = sync(store, client).syncNow().get(Industry.DENTAL);
        client.calls.clear();

        store.rows.add(merchant(2, "5002", "阳光口腔", "dental", "正畸 12000 元"));
        assertThat(sync.syncNow().get(Industry.DENTAL)).isEqualTo(id);
        assertThat(client.calls).containsExactly("query:" + id, "update:" + id);
        assertThat(client.tables.get(id).words()).contains("阳光口腔", "正畸");
        assertThat(client.tables).as("没有多建表").hasSize(1);
    }

    @Test
    void rebuildsWhenExistingTableIsBoundToAnotherModel() {
        MemStore store = new MemStore();
        store.rows.add(merchant(1, "5000", "美好口腔", "dental", ""));
        FakeClient client = new FakeClient();
        client.tables.put("vocab-vcadental-old", new VocabularyClient.Snapshot("vocab-vcadental-old",
                "paraformer-realtime-v2", List.of("洗牙")));

        String id = sync(store, client).syncNow().get(Industry.DENTAL);

        assertThat(id).isNotEqualTo("vocab-vcadental-old");
        assertThat(client.tables).as("绑错模型的旧表删掉, 免得占配额").doesNotContainKey("vocab-vcadental-old");
        assertThat(client.tables.get(id).targetModel()).isEqualTo(MODEL);
    }

    @Test
    void vendorFailureKeepsPreviousTableAndNeverThrows() {
        MemStore store = new MemStore();
        store.rows.add(merchant(1, "5000", "美好口腔", "dental", "洗牙 200 元"));
        FakeClient client = new FakeClient();
        String id = sync(store, client).syncNow().get(Industry.DENTAL);

        client.broken = true;
        store.rows.add(merchant(2, "5002", "阳光口腔", "dental", ""));
        Map<Industry, String> ids = sync.syncNow();

        assertThat(ids).as("这一轮没成功的行业不在结果里").isEmpty();
        assertThat(sync.vocabularyIdFor(Industry.DENTAL)).as("旧表继续用").contains(id);
    }

    @Test
    void storeChangesTriggerADebouncedSync() throws Exception {
        MemStore store = new MemStore();
        FakeClient client = new FakeClient();
        sync(store, client).start();

        // 连续改三次, 只该同步一次
        store.save(merchant(1, "5000", "美好口腔", "dental", "洗牙 200 元"));
        store.save(merchant(1, "5000", "美好口腔", "dental", "洗牙 200 元\n补牙 300 元"));
        store.save(merchant(1, "5000", "美好口腔", "dental", "洗牙 200 元\n补牙 300 元\n拔牙 500 元"));

        long deadline = System.currentTimeMillis() + 3000;
        while (sync.vocabularyIdFor(Industry.DENTAL).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(150);   // 再等一会, 确认没有多余的同步
        assertThat(sync.vocabularyIdFor(Industry.DENTAL)).isPresent();
        assertThat(client.calls.stream().filter(c -> c.startsWith("create:")).count()).isEqualTo(1);
        assertThat(client.calls.stream().filter(c -> c.startsWith("update:")).count()).as("三次改动合并成一次").isZero();
        assertThat(client.tables.values().iterator().next().words()).contains("拔牙");
    }

    @Test
    void wordListIsCappedAtVendorLimit() {
        MemStore store = new MemStore();
        StringBuilder services = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            services.append("项目").append(toHan(i)).append(" 100 元\n");
        }
        store.rows.add(merchant(1, "5000", "大而全", "generic", services.toString()));
        FakeClient client = new FakeClient();

        String id = sync(store, client).syncNow().get(Industry.GENERIC);

        assertThat(client.tables.get(id).words()).hasSize(HotWordSync.MAX_WORDS);
        assertThat(client.tables.get(id).words()).as("基础词优先, 门店词按顺序截断").startsWith("预约");
    }

    /** 数字 → 汉字数字, 让每行的项目名都是不同的汉字串 */
    private static String toHan(int n) {
        String digits = "零一二三四五六七八九";
        StringBuilder sb = new StringBuilder();
        for (char c : String.valueOf(n).toCharArray()) {
            sb.append(digits.charAt(c - '0'));
        }
        return sb.toString();
    }
}
