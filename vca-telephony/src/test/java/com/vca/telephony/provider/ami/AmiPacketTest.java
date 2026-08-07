package com.vca.telephony.provider.ami;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AMI 报文格式。协议确定, 可先于 Asterisk 环境验完。 */
class AmiPacketTest {

    @Test
    void encodesAsCrlfLinesEndingWithBlankLine() {
        String wire = AmiPacket.action("Login", "Username", "vca", "Secret", "s3cr3t").encode();

        assertThat(wire).isEqualTo("Action: Login\r\nUsername: vca\r\nSecret: s3cr3t\r\n\r\n");
    }

    /** Originate 要带多条 Variable, 所以同名 key 必须能重复 —— 用 Map 存就废了 */
    @Test
    void keepsRepeatedKeys() {
        AmiPacket p = AmiPacket.action("Originate", "Channel", "PJSIP/123@trunk")
                .with("Variable", "CALLUUID=abc")
                .with("Variable", "__SIP_CODEC=alaw");

        assertThat(p.all("Variable")).containsExactly("CALLUUID=abc", "__SIP_CODEC=alaw");
        assertThat(p.encode()).contains("Variable: CALLUUID=abc\r\nVariable: __SIP_CODEC=alaw\r\n");
    }

    /** 空值直接不发: Asterisk 对空 CallerID 之类的字段反应各版本不一, 不如不带 */
    @Test
    void skipsBlankValues() {
        AmiPacket p = AmiPacket.action("Originate", "CallerID", "", "Channel", "PJSIP/1@t")
                .with("Variable", null);

        assertThat(p.get("CallerID")).isNull();
        assertThat(p.all("Variable")).isEmpty();
        assertThat(p.get("Channel")).isEqualTo("PJSIP/1@t");
    }

    /** AMI 各版本 key 大小写并不统一, 取值必须大小写不敏感 */
    @Test
    void lookupIsCaseInsensitive() {
        AmiPacket p = AmiPacket.parse("Response: Success\r\nActionID: abc-123\r\nMessage: ok");

        assertThat(p.get("actionid")).isEqualTo("abc-123");
        assertThat(p.get("RESPONSE")).isEqualTo("Success");
        assertThat(p.isSuccess()).isTrue();
        assertThat(p.isResponse()).isTrue();
        assertThat(p.event()).isNull();
    }

    @Test
    void parsesEventsAndValuesContainingColons() {
        AmiPacket p = AmiPacket.parse("Event: OriginateResponse\r\nResponse: Failure\r\nReason: 3\r\n"
                + "Channel: PJSIP/13800138000@trunk-0000001");

        assertThat(p.event()).isEqualTo("OriginateResponse");
        assertThat(p.isSuccess()).isFalse();
        assertThat(p.get("Channel")).isEqualTo("PJSIP/13800138000@trunk-0000001");
    }

    /** 畸形行(无冒号)丢掉即可, 不该拖累整个报文 */
    @Test
    void ignoresMalformedLines() {
        AmiPacket p = AmiPacket.parse("Response: Success\r\n随便一行没有冒号\r\nActionID: x");

        assertThat(p.isSuccess()).isTrue();
        assertThat(p.actionId()).isEqualTo("x");
    }

    @Test
    void rejectsUnpairedKeyValues() {
        assertThatThrownBy(() -> AmiPacket.action("Originate", "Channel"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
