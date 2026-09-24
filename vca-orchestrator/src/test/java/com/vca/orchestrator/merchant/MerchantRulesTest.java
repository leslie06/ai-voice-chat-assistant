package com.vca.orchestrator.merchant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 门店资料里直通电话线路的几项: 谁能改、能填成什么样。 */
class MerchantRulesTest {

    private static MerchantProfile profile(String number, String transfer, String webhook, String voice) {
        return new MerchantProfile(7L, 21, number, "美好口腔", true, "dental", "", "", transfer, webhook,
                voice, "vocab-x", "", "", "", "", "", "", "", "", null, null);
    }

    @Test
    void numberMustBePlainDigits() {
        assertThat(MerchantRules.number(" 5000 ")).isEqualTo("5000");
        assertThatThrownBy(() -> MerchantRules.number("50")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MerchantRules.number("5000,5001")).isInstanceOf(IllegalArgumentException.class);
    }

    /** 分机号补成拨号串; 只放行 user/ 和 sofia/gateway/ 两种形状 */
    @Test
    void dialStringAcceptsOnlyKnownShapes() {
        assertThat(MerchantRules.dialString("")).isEmpty();
        assertThat(MerchantRules.dialString("8002")).isEqualTo("user/8002@vca.local");
        assertThat(MerchantRules.dialString("user/8002@vca.local")).isEqualTo("user/8002@vca.local");
        assertThat(MerchantRules.dialString("sofia/gateway/trunk/13800138000"))
                .isEqualTo("sofia/gateway/trunk/13800138000");
    }

    /** 变量块、多目标、任意 SIP 地址都能让 FreeSWITCH 干坏事, 一律拒绝 */
    @Test
    void dialStringRejectsVariablesAndArbitraryTargets() {
        for (String bad : new String[]{
                "{api_on_answer=system reboot}user/8002@vca.local",
                "user/8002@vca.local,user/8003@vca.local",
                "sofia/internal/sip:x@203.0.113.9",
                "user/8002@vca.local\napi shutdown",
                "loopback/9999"}) {
            assertThatThrownBy(() -> MerchantRules.dialString(bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** 推送只发企业微信/钉钉机器人: 否则服务器会替人往任意地址(包括本机管理口)发请求 */
    @Test
    void webhookOnlyAllowsGroupBotHosts() {
        assertThat(MerchantRules.webhook("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc"))
                .startsWith("https://qyapi.weixin.qq.com/");
        assertThat(MerchantRules.webhook("https://oapi.dingtalk.com/robot/send?access_token=x")).isNotEmpty();
        for (String bad : new String[]{
                "http://qyapi.weixin.qq.com/cgi-bin/webhook/send",
                "https://127.0.0.1:2019/load",
                "https://qyapi.weixin.qq.com.evil.com/x",
                "https://user@qyapi.weixin.qq.com/x",
                "https://qyapi.weixin.qq.com:8443/x"}) {
            assertThatThrownBy(() -> MerchantRules.webhook(bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** 商家更新: 运营字段一律沿用库里的值, 请求里改了也不算 */
    @Test
    void ownerCannotChangeOperatorFields() {
        MerchantProfile existing = profile("5000", "user/8002@vca.local", "", "longanhuan_v3.6");
        MerchantProfile attempt = new MerchantProfile(7L, 21, "5001", "美好口腔(改名)", true, "dental", "", "",
                "{x=y}user/8003@vca.local", "https://oapi.dingtalk.com/robot/send?access_token=x",
                "someone-elses-clone", "vocab-evil", "新地址", "", "", "", "", "", "", "", null, null);

        MerchantProfile saved = MerchantRules.checkedByOwner(attempt, existing);

        assertThat(saved.number()).isEqualTo("5000");
        assertThat(saved.transferDialString()).isEqualTo("user/8002@vca.local");
        assertThat(saved.ttsVoice()).isEqualTo("longanhuan_v3.6");
        assertThat(saved.asrVocabularyId()).isEqualTo("vocab-x");
        assertThat(saved.name()).as("资料字段照常更新").isEqualTo("美好口腔(改名)");
        assertThat(saved.address()).isEqualTo("新地址");
        assertThat(saved.summaryWebhook()).startsWith("https://oapi.dingtalk.com/");
    }

    @Test
    void adminEditsAreStillValidated() {
        assertThat(MerchantRules.checkedByAdmin(profile("5000", "8002", "", "")).transferDialString())
                .isEqualTo("user/8002@vca.local");
        assertThatThrownBy(() -> MerchantRules.checkedByAdmin(profile("5000", "{a=b}user/1", "", "")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
