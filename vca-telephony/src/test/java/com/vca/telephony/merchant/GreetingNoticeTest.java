package com.vca.telephony.merchant;

import com.vca.telephony.TelephonyProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 开场白必须让客户听到"接电话的是 AI"和"会录音"; 缺哪句补哪句, 已经说了的不重复。 */
class GreetingNoticeTest {

    @Test
    void addsOnlyWhatIsMissing() {
        assertThat(GreetingNotice.apply("您好，这里是美好口腔，请问有什么可以帮您？"))
                .isEqualTo("本通电话由智能助理接听，并会录音。您好，这里是美好口腔，请问有什么可以帮您？");
        assertThat(GreetingNotice.apply("您好，这里是美好口腔智能客服，请问有什么可以帮您？"))
                .as("说了是智能客服, 只补录音").startsWith("本通电话会录音。您好");
        assertThat(GreetingNotice.apply("您好，本通电话会录音，请问有什么可以帮您？"))
                .as("说了录音, 只补 AI 身份").startsWith("本通电话由智能助理接听。您好");
        String both = "您好，这里是 AI 助理，通话会录音，请问有什么可以帮您？";
        assertThat(GreetingNotice.apply(both)).isEqualTo(both);
    }

    /** 店名里的拼音字母不能被当成已经说了 AI */
    @Test
    void latinLettersInShopNamesDoNotCountAsDisclosure() {
        assertThat(GreetingNotice.apply("您好，这里是Taiyang口腔，通话会录音。"))
                .startsWith("本通电话由智能助理接听。");
    }

    @Test
    void emptyGreetingStaysEmpty() {
        assertThat(GreetingNotice.apply("")).isEmpty();
        assertThat(GreetingNotice.apply(null)).isNull();
    }

    /** 顶层开场白(默认商家)与预合成用的是同一段补过告知的文字, 否则接通时缓存不命中要现合成 */
    @Test
    void defaultMerchantAndPreloadUseTheSameText() {
        TelephonyProperties p = new TelephonyProperties();
        p.setGreeting("您好，这里是智能语音助手，请问有什么可以帮您的吗？");

        assertThat(p.effectiveGreeting()).isEqualTo("本通电话会录音。您好，这里是智能语音助手，请问有什么可以帮您的吗？");
        assertThat(p.toMerchantRegistry().resolve(null).greeting()).isEqualTo(p.effectiveGreeting());

        p.setGreetingNotice(false);
        assertThat(p.effectiveGreeting()).as("关掉只给内部联调用").isEqualTo(p.getGreeting());
    }
}
