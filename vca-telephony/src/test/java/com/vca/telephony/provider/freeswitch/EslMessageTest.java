package com.vca.telephony.provider.freeswitch;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EslMessageTest {

    private static ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    /** connect 的应答: 没有 Content-Length, 通道数据平铺在头部里, 值是 URL 编码的(真机抓到的样子) */
    @Test
    void connectReplyCarriesUrlEncodedChannelData() throws Exception {
        EslMessage msg = EslMessage.read(stream(
                "Event-Name: CHANNEL_DATA\n"
                        + "Content-Type: command/reply\n"
                        + "Reply-Text: %2BOK%0A\n"
                        + "Unique-ID: 32a14095-42fa-47a4-af82-96a968604539\n"
                        + "Caller-Caller-ID-Number: 1000\n"
                        + "Caller-Destination-Number: 5000\n"
                        + "variable_vca_media_remote_host: host.docker.internal\n"
                        + "\n"));

        assertThat(msg.contentType()).isEqualTo(EslMessage.CT_REPLY);
        assertThat(msg.isOk()).isTrue();
        assertThat(msg.replyText()).isEqualTo("+OK");
        assertThat(msg.get("Unique-ID")).isEqualTo("32a14095-42fa-47a4-af82-96a968604539");
        assertThat(msg.get("variable_vca_media_remote_host")).isEqualTo("host.docker.internal");
    }

    /** 事件: 外层头部带 Content-Length(字节数), 正文本身又是一段头部 */
    @Test
    void eventBodyIsParsedAsNestedHeaders() throws Exception {
        String body = "Event-Name: DTMF\nDTMF-Digit: 5\nCaller-Caller-ID-Name: %E5%BC%A0%E4%B8%89\n\n";
        int len = body.getBytes(StandardCharsets.UTF_8).length;
        EslMessage msg = EslMessage.read(stream(
                "Content-Length: " + len + "\nContent-Type: text/event-plain\n\n" + body));

        EslMessage event = msg.event();
        assertThat(event.eventName()).isEqualTo("DTMF");
        assertThat(event.get("DTMF-Digit")).isEqualTo("5");
        assertThat(event.get("Caller-Caller-ID-Name")).isEqualTo("张三");
    }

    /** BACKGROUND_JOB 的执行结果在事件自己的正文里 */
    @Test
    void eventWithOwnBody() throws Exception {
        String inner = "-ERR NO_ANSWER\n";
        String body = "Event-Name: BACKGROUND_JOB\nJob-UUID: abc\nContent-Length: "
                + inner.length() + "\n\n" + inner;
        int len = body.getBytes(StandardCharsets.UTF_8).length;
        EslMessage event = EslMessage.read(stream(
                "Content-Length: " + len + "\nContent-Type: text/event-plain\n\n" + body)).event();

        assertThat(event.get("Job-UUID")).isEqualTo("abc");
        assertThat(event.body()).isEqualTo("-ERR NO_ANSWER\n");
    }

    /** Content-Length 是字节数: 正文含中文时按字符数截会错位, 下一条报文整条读坏 */
    @Test
    void contentLengthCountsBytesNotChars() throws Exception {
        String body = "你好";
        int len = body.getBytes(StandardCharsets.UTF_8).length;   // 6, 不是 2
        ByteArrayInputStream in = stream("Content-Length: " + len + "\nContent-Type: api/response\n\n" + body
                + "Content-Type: command/reply\nReply-Text: +OK\n\n");

        assertThat(EslMessage.read(in).body()).isEqualTo("你好");
        assertThat(EslMessage.read(in).replyText()).isEqualTo("+OK");
    }

    /** 不能把 + 解成空格: 号码 +8613800138000 原样保留 */
    @Test
    void plusSignIsNotTurnedIntoSpace() {
        assertThat(EslMessage.percentDecode("+8613800138000")).isEqualTo("+8613800138000");
        assertThat(EslMessage.percentDecode("a%20b%2Bc")).isEqualTo("a b+c");
        assertThat(EslMessage.percentDecode("100%")).isEqualTo("100%");   // 非法转义原样保留
    }

    @Test
    void cleanEofReturnsNullButTruncationThrows() throws Exception {
        assertThat(EslMessage.read(stream(""))).isNull();
        assertThat(EslMessage.read(stream("\n\n"))).isNull();
        assertThatThrownBy(() -> EslMessage.read(stream("Content-Length: 10\n\nabc")))
                .isInstanceOf(EOFException.class);
    }

    /** 命令组包是注入防线: 任何字段带换行都拒绝, 否则能在同一条连接上多塞一条命令 */
    @Test
    void commandRejectsNewlinesInAnyField() {
        assertThat(new String(EslMessage.command("sendmsg", "call-command", "hangup"), StandardCharsets.UTF_8))
                .isEqualTo("sendmsg\ncall-command: hangup\n\n");

        assertThatThrownBy(() -> EslMessage.command("bgapi originate x\n\napi shutdown"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EslMessage.command("sendmsg", "hangup-cause", "X\nfoo: bar"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
