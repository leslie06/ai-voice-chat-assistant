package com.vca.telephony.merchant;

import com.vca.telephony.TelephonyProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 按被叫号码找商家; 没配/没命中一律回退默认, 单店部署不受影响。 */
class MerchantRegistryTest {

    private static TelephonyProperties.MerchantProps props(String number, String name, String owner) {
        TelephonyProperties.MerchantProps m = new TelephonyProperties.MerchantProps();
        m.setNumber(number);
        m.setName(name);
        m.setKnowledgeOwner(owner);
        return m;
    }

    private static TelephonyProperties baseProps() {
        TelephonyProperties p = new TelephonyProperties();
        p.setGreeting("您好，这里是智能助手");
        p.setKnowledgeOwner("1");
        p.setTransferDialString("user/9000");
        p.setTtsVoice("longanhuan_v3.6");
        p.getSummary().setWebhookUrl("https://hook/default");
        return p;
    }

    @Test
    void resolvesByCalledNumber() {
        TelephonyProperties p = baseProps();
        p.setMerchants(List.of(props("01088886666", "美好口腔", "11"),
                props("01099998888", "启明培训", "12")));

        MerchantRegistry registry = p.toMerchantRegistry();

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.resolve("01088886666").knowledgeOwner()).isEqualTo("11");
        assertThat(registry.resolve("01099998888").label()).isEqualTo("启明培训");
    }

    /** 每家没填的项回退到顶层, 免得为了改一句开场白把整套配置抄一遍 */
    @Test
    void unsetFieldsFallBackToTheTopLevelConfig() {
        TelephonyProperties p = baseProps();
        TelephonyProperties.MerchantProps m = props("01088886666", "美好口腔", "11");
        m.setGreeting("您好，这里是美好口腔");
        p.setMerchants(List.of(m));

        Merchant merchant = p.toMerchantRegistry().resolve("01088886666");

        assertThat(merchant.greeting()).as("自己配的, 前面补上 AI 与录音告知")
                .isEqualTo("本通电话由智能助理接听，并会录音。您好，这里是美好口腔");
        assertThat(merchant.transferDialString()).isEqualTo("user/9000");   // 顶层的
        assertThat(merchant.ttsVoice()).isEqualTo("longanhuan_v3.6");
        assertThat(merchant.summaryWebhook()).isEqualTo("https://hook/default");
    }

    /** 没登记的号码、线路没送号、根本没配多商家 —— 三种都走默认, 电话不能因为这个打不通 */
    @Test
    void anythingUnknownFallsBackToTheDefaultMerchant() {
        TelephonyProperties p = baseProps();
        p.setMerchants(List.of(props("01088886666", "美好口腔", "11")));
        MerchantRegistry registry = p.toMerchantRegistry();

        assertThat(registry.resolve("01066667777").label()).isEqualTo("默认");
        assertThat(registry.resolve(null).knowledgeOwner()).isEqualTo("1");
        assertThat(registry.resolve("").knowledgeOwner()).isEqualTo("1");
        assertThat(baseProps().toMerchantRegistry().resolve("01088886666").label()).isEqualTo("默认");
    }

    /** 配错(没填号码)不能让整套配置崩掉, 忽略这一条继续 */
    @Test
    void merchantsWithoutANumberAreIgnored() {
        TelephonyProperties p = baseProps();
        p.setMerchants(List.of(props("", "缺号码的店", "13"), props("01088886666", "美好口腔", "11")));

        MerchantRegistry registry = p.toMerchantRegistry();

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.resolve("01088886666").knowledgeOwner()).isEqualTo("11");
    }

    /**
     * 回归: 商家人设必须<b>追加</b>在电话人设之后, 不能把它替换掉。
     *
     * <p>线上事故: 给商家配了人设之后 AI 一口气说了近 30 秒, 撑爆下行缓冲, 后半句被丢弃,
     * 听感是"说一半突然没声音"。根因是电话人设里那些"必须简短、最多两句、完全口语化"的约束
     * 被商家人设顶掉了。那些是电话这个通道的硬要求, 任何商家都不该能去掉。
     */
    @Test
    void merchantPromptExtendsPhonePromptInsteadOfReplacingIt() {
        TelephonyProperties props = new TelephonyProperties();
        props.setSystemPrompt("你是电话客服助手。回答必须简短, 最多两句, 完全口语化。");
        TelephonyProperties.MerchantProps m = new TelephonyProperties.MerchantProps();
        m.setNumber("5001");
        m.setName("启明少儿英语");
        m.setSystemPrompt("你是启明少儿英语的电话客服, 我们做少儿英语培训。");
        props.setMerchants(java.util.List.of(m));

        String prompt = props.toMerchantRegistry().resolve("5001").systemPrompt();

        assertThat(prompt).contains("最多两句").contains("少儿英语培训");
        assertThat(prompt.indexOf("最多两句"))
                .as("电话人设要排在前面, 商家人设是补充")
                .isLessThan(prompt.indexOf("少儿英语培训"));
    }

    /** 商家没配人设时原样用电话人设, 不该多出空行或重复 */
    @Test
    void merchantWithoutPromptKeepsPhonePromptAsIs() {
        TelephonyProperties props = new TelephonyProperties();
        props.setSystemPrompt("你是电话客服助手。");
        TelephonyProperties.MerchantProps m = new TelephonyProperties.MerchantProps();
        m.setNumber("5000");
        m.setName("美好口腔");
        props.setMerchants(java.util.List.of(m));

        assertThat(props.toMerchantRegistry().resolve("5000").systemPrompt())
                .isEqualTo("你是电话客服助手。");
    }

    // ---- 接数据库: 库里优先、缓存、改动即时生效 ----

    /** 内存版存储: 记录查库次数, 能手动触发变更通知 */
    private static class MemStore implements com.vca.orchestrator.merchant.MerchantStore {
        final java.util.Map<String, com.vca.orchestrator.merchant.MerchantProfile> byNumber = new java.util.HashMap<>();
        final java.util.List<Runnable> listeners = new java.util.ArrayList<>();
        int lookups;

        void put(com.vca.orchestrator.merchant.MerchantProfile p) {
            byNumber.put(p.number(), p);
        }

        void changed() {
            listeners.forEach(Runnable::run);
        }

        @Override public java.util.Optional<com.vca.orchestrator.merchant.MerchantProfile> findByNumber(String number) {
            lookups++;
            return java.util.Optional.ofNullable(byNumber.get(number.trim())).filter(com.vca.orchestrator.merchant.MerchantProfile::enabled);
        }
        @Override public java.util.Optional<com.vca.orchestrator.merchant.MerchantProfile> findById(long id) { return java.util.Optional.empty(); }
        @Override public java.util.List<com.vca.orchestrator.merchant.MerchantProfile> listByOwner(long ownerId) { return java.util.List.of(); }
        @Override public java.util.List<com.vca.orchestrator.merchant.MerchantProfile> listEnabled() { return java.util.List.copyOf(byNumber.values()); }
        @Override public com.vca.orchestrator.merchant.MerchantProfile save(com.vca.orchestrator.merchant.MerchantProfile p) { put(p); changed(); return p; }
        @Override public boolean delete(long ownerId, long id) { return false; }
        @Override public void addChangeListener(Runnable l) { listeners.add(l); }
    }

    private static com.vca.orchestrator.merchant.MerchantProfile clinic(String number, String name, long owner, String hours) {
        return clinic(number, name, owner, hours, "您好，这里是" + name, "");
    }

    private static com.vca.orchestrator.merchant.MerchantProfile clinic(String number, String name, long owner, String hours,
                                                                         String greeting, String vocabularyId) {
        return new com.vca.orchestrator.merchant.MerchantProfile(1L, owner, number, name, true, "dental",
                greeting, "说话要热情", "user/8002@vca.local", "", "", vocabularyId,
                "东城区", hours, "", "", "洗牙 200-400 元", "", "", "", null, null);
    }

    @Test
    void industryAndVocabularyFlowFromProfileAndGreetingDefaultsToName() {
        TelephonyProperties p = baseProps();
        p.setAsrVocabularyId("vocab-global");
        MemStore store = new MemStore();
        store.put(clinic("5000", "美好口腔", 21, "9:00-18:00", "", ""));
        store.put(clinic("5002", "阳光口腔", 22, "9:00-18:00", "", "vocab-mine"));
        MerchantRegistry registry = p.toMerchantRegistry(store, m -> { });

        Merchant a = registry.resolve("5000");
        assertThat(a.industry()).isEqualTo(com.vca.orchestrator.merchant.Industry.DENTAL);
        assertThat(a.greeting()).as("没写开场白就按店名生成, 告知直接说进去")
                .isEqualTo("您好，这里是美好口腔的智能助理，本通电话会录音，请问有什么可以帮您？");
        assertThat(a.systemPrompt()).contains("你是「美好口腔」的电话客服").contains("口腔诊所");

        java.util.function.Function<com.vca.orchestrator.merchant.Industry, java.util.Optional<String>> none =
                i -> java.util.Optional.empty();
        java.util.function.Function<com.vca.orchestrator.merchant.Industry, java.util.Optional<String>> dentalTable =
                i -> i == com.vca.orchestrator.merchant.Industry.DENTAL ? java.util.Optional.of("vocab-dental") : java.util.Optional.empty();
        assertThat(p.vocabularyFor(a, none)).as("行业表还没建 → 全局").isEqualTo("vocab-global");
        assertThat(p.vocabularyFor(a, dentalTable)).as("行业表建好了 → 行业表").isEqualTo("vocab-dental");
        assertThat(p.vocabularyFor(registry.resolve("5002"), dentalTable)).as("店自己指定的最优先").isEqualTo("vocab-mine");
        assertThat(p.vocabularyFor(registry.resolve("9999"), dentalTable)).as("默认商家没登记行业 → 全局").isEqualTo("vocab-global");
        assertThat(registry.resolve("9999").industry()).isNull();
    }

    @Test
    void databaseMerchantWinsOverConfigAndRendersProfileIntoPrompt() {
        TelephonyProperties p = baseProps();
        p.setSystemPrompt("你是电话客服，回答必须简短。");
        p.setMerchants(List.of(props("5000", "配置文件里的旧店", "11")));
        MemStore store = new MemStore();
        store.put(clinic("5000", "美好口腔", 21, "每天 9:00-20:00"));
        List<Merchant> loaded = new java.util.ArrayList<>();
        MerchantRegistry registry = p.toMerchantRegistry(store, loaded::add);

        Merchant m = registry.resolve("5000");

        assertThat(m.label()).as("库里的优先于配置文件").isEqualTo("美好口腔");
        assertThat(m.knowledgeOwner()).as("知识库归属 = 资料所属账号").isEqualTo("21");
        assertThat(m.greeting()).isEqualTo("本通电话由智能助理接听，并会录音。您好，这里是美好口腔");
        assertThat(m.systemPrompt())
                .as("电话人设在前, 机构资料在中, 商家补充在后")
                .contains("回答必须简短").contains("营业时间: 每天 9:00-20:00").contains("说话要热情");
        assertThat(m.systemPrompt().indexOf("回答必须简短")).isLessThan(m.systemPrompt().indexOf("营业时间"));
        assertThat(m.systemPrompt().indexOf("营业时间")).isLessThan(m.systemPrompt().indexOf("说话要热情"));
        assertThat(loaded).as("新加载到的商家要回调一次(预合成开场白用)").extracting(Merchant::label).containsExactly("美好口腔");
    }

    @Test
    void cachesLookupsAndInvalidatesOnChange() {
        TelephonyProperties p = baseProps();
        MemStore store = new MemStore();
        store.put(clinic("5000", "美好口腔", 21, "9:00-18:00"));
        MerchantRegistry registry = p.toMerchantRegistry(store, m -> { });

        registry.resolve("5000");
        registry.resolve("5000");
        registry.resolve("5000");
        assertThat(store.lookups).as("同一个号反复来电只查一次库").isEqualTo(1);

        // 诊所在网页上改了营业时间 → 存储层通知 → 下一通电话就是新的
        store.save(clinic("5000", "美好口腔", 21, "9:00-21:00"));
        assertThat(registry.resolve("5000").systemPrompt()).contains("9:00-21:00");
        assertThat(store.lookups).isEqualTo(2);
    }

    @Test
    void unknownNumberIsNegativelyCachedAndFallsBackToConfigThenDefault() {
        TelephonyProperties p = baseProps();
        p.setMerchants(List.of(props("5001", "启明培训", "12")));
        MemStore store = new MemStore();
        MerchantRegistry registry = p.toMerchantRegistry(store, m -> { });

        assertThat(registry.resolve("5001").label()).as("库里没有 → 配置文件").isEqualTo("启明培训");
        assertThat(registry.resolve("9999").label()).as("哪都没有 → 默认").isEqualTo("默认");
        registry.resolve("9999");
        registry.resolve("9999");
        assertThat(store.lookups).as("扫号机器人拨的随机号不能每次都打库(负缓存)").isEqualTo(2);
    }

    @Test
    void storeFailureFallsBackInsteadOfBreakingTheCall() {
        TelephonyProperties p = baseProps();
        p.setMerchants(List.of(props("5000", "配置里的店", "11")));
        com.vca.orchestrator.merchant.MerchantStore broken = new MemStore() {
            @Override public java.util.Optional<com.vca.orchestrator.merchant.MerchantProfile> findByNumber(String n) {
                throw new IllegalStateException("数据库连不上");
            }
        };
        MerchantRegistry registry = p.toMerchantRegistry(broken, m -> { });
        assertThat(registry.resolve("5000").label()).as("查库失败退回配置文件, 电话照接").isEqualTo("配置里的店");
    }

    /**
     * 库里的门店没配转人工时不能回退到顶层那个分机 —— 那是另一家店的前台座机。
     * 库里存着不合规的拨号串/推送地址(校验上线前存进去的)时当作没填, 不能交给 FreeSWITCH。
     */
    @Test
    void storeMerchantNeverInheritsAnotherShopsDeskAndDropsUnsafeValues() {
        TelephonyProperties p = baseProps();   // 顶层 transfer = user/9000
        MemStore store = new MemStore();
        store.put(new com.vca.orchestrator.merchant.MerchantProfile(1L, 21, "5000", "美好口腔", true, "dental",
                "", "", "", "", "", "", "", "", "", "", "", "", "", "", null, null));
        store.put(new com.vca.orchestrator.merchant.MerchantProfile(2L, 22, "5001", "坏数据", true, "dental",
                "", "", "{api_on_answer=system reboot}user/8002@vca.local", "http://127.0.0.1:2019/load",
                "", "", "", "", "", "", "", "", "", "", null, null));
        store.put(new com.vca.orchestrator.merchant.MerchantProfile(3L, 23, "5002", "阳光口腔", true, "dental",
                "", "", "8003", "", "", "", "", "", "", "", "", "", "", "", null, null));
        MerchantRegistry registry = p.toMerchantRegistry(store, m -> { });

        assertThat(registry.resolve("5000").transferDialString()).as("没配 → 不转, 而不是转到别家").isEmpty();
        assertThat(registry.resolve("5001").transferDialString()).isEmpty();
        assertThat(registry.resolve("5001").summaryWebhook()).as("不合规的推送地址回退到顶层")
                .isEqualTo("https://hook/default");
        assertThat(registry.resolve("5002").transferDialString()).isEqualTo("user/8003@vca.local");
    }
}
