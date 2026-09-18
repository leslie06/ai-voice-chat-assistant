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
}
