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

        assertThat(merchant.greeting()).isEqualTo("您好，这里是美好口腔");   // 自己配的
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
}
