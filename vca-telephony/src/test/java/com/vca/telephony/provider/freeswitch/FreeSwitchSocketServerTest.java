package com.vca.telephony.provider.freeswitch;

import com.vca.telephony.spi.CallEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用真实 TCP + UDP 跑通 FreeSWITCH 接入。测试里的 {@link FakeFreeSwitchChannel} 扮演 FreeSWITCH ——
 * 协议细节(应答格式、URL 编码、unicast 头部)按真机抓包写的。
 */
class FreeSwitchSocketServerTest {

    private static final String UUID = "32a14095-42fa-47a4-af82-96a968604539";

    private FreeSwitchSocketServer server;
    private FakeFreeSwitchChannel fs;

    private static final class Captured {
        volatile FreeSwitchCallLeg leg;
        volatile String callIdAtSetup;
        volatile String peerAtSetup;
        volatile String calledAtSetup;
        final BlockingQueue<byte[]> audio = new ArrayBlockingQueue<>(64);
        final BlockingQueue<CallEvent> events = new ArrayBlockingQueue<>(16);
        final BlockingQueue<String> completions = new ArrayBlockingQueue<>(4);
    }

    private final Captured captured = new Captured();

    @AfterEach
    void tearDown() throws Exception {
        if (fs != null) {
            fs.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private void start(FreeSwitchConfig cfg) throws Exception {
        server = new FreeSwitchSocketServer(cfg, leg -> {
            captured.leg = leg;
            captured.callIdAtSetup = leg.callId();
            captured.peerAtSetup = leg.peerNumber();
            captured.calledAtSetup = leg.calledNumber();
            // 模拟 CallSession: 在开泵之前订阅, 这正是服务端保证的顺序
            leg.inboundAudio().subscribe(captured.audio::offer,
                    err -> captured.completions.offer("error"),
                    () -> captured.completions.offer("complete"));
            leg.events().subscribe(captured.events::offer);
        });
        server.start();
        fs = new FakeFreeSwitchChannel(server.port());
    }

    private void startAndHandshake() throws Exception {
        start(FreeSwitchConfig.onPort(0));
        fs.answerHandshake(UUID, "13800138000", "01088886666");
    }

    /** 握手完成时 callId 和号码都已就位 —— 会话用 callId 当 sessionId 建, 号码进上下文 */
    @Test
    void handshakeProvidesCallIdAndNumbersBeforeSessionIsCreated() throws Exception {
        startAndHandshake();

        awaitUntil(() -> captured.callIdAtSetup != null);
        assertThat(captured.callIdAtSetup).isEqualTo(UUID);
        assertThat(captured.peerAtSetup).isEqualTo("13800138000");
        assertThat(captured.calledAtSetup).isEqualTo("01088886666");
    }

    /** unicast 命令: UDP、FreeSWITCH 侧端口交给系统分配、地址取自拨号计划的通道变量 */
    @Test
    void unicastCommandUsesDialplanAddressesAndEphemeralPort() throws Exception {
        start(FreeSwitchConfig.onPort(0));

        FakeFreeSwitchChannel.Command unicast = fs.answerHandshake(UUID, "1000", "5000");

        assertThat(unicast.headers())
                .containsEntry("call-command", "unicast")
                .containsEntry("transport", "udp")
                .containsEntry("local-ip", "127.0.0.1")
                .containsEntry("local-port", "0")
                .containsEntry("remote-ip", "127.0.0.1");
        assertThat(fs.remotePort()).isPositive();
    }

    /** 收到第一个媒体包才算接通: 开场白不能在媒体还没通时就开始"播" */
    @Test
    void answeredOnlyAfterFirstMediaPacket() throws Exception {
        startAndHandshake();
        awaitUntil(() -> captured.leg != null);

        assertThat(captured.events.poll(300, TimeUnit.MILLISECONDS)).isNull();

        byte[] pcm = pattern(320);
        fs.sendAudio(pcm);

        assertThat(take(captured.events).type()).isEqualTo(CallEvent.Type.ANSWERED);
        assertThat(take(captured.audio)).containsExactly(pcm);
    }

    /** 下行按首包来源回包: 这正是 FreeSWITCH 在 Docker 里也不用映射端口的原因 */
    @Test
    void outboundAudioGoesBackToTheFirstPacketSource() throws Exception {
        startAndHandshake();
        fs.sendAudio(pattern(320));
        take(captured.events);

        byte[] reply = pattern(320);
        reply[0] = 42;
        captured.leg.writeAudio(reply);

        assertThat(fs.receiveAudio()).containsExactly(reply);
    }

    /** 锁定来源后, 别处发来的包一律丢弃 —— 否则谁知道端口谁就能往通话里灌音频 */
    @Test
    void packetsFromOtherSourcesAreDropped() throws Exception {
        startAndHandshake();
        fs.sendAudio(pattern(320));
        take(captured.audio);

        try (DatagramSocket intruder = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            byte[] junk = new byte[320];
            intruder.send(new DatagramPacket(junk, junk.length, new InetSocketAddress("127.0.0.1", fs.remotePort())));
        }
        byte[] legit = pattern(320);
        legit[1] = 7;
        fs.sendAudio(legit);

        assertThat(take(captured.audio)).containsExactly(legit);   // 中间那包没进来
    }

    @Test
    void dtmfEventBecomesCallEvent() throws Exception {
        startAndHandshake();
        fs.sendAudio(pattern(320));
        take(captured.events);

        fs.event("Event-Name: DTMF\nDTMF-Digit: 5\nDTMF-Duration: 2000\n\n");

        CallEvent e = take(captured.events);
        assertThat(e.type()).isEqualTo(CallEvent.Type.DTMF);
        assertThat(e.detail()).isEqualTo("5");
    }

    /** 对端挂机: 带上挂机原因, 两条流都要结束, 否则 CallSession 会泄漏 */
    @Test
    void peerHangupEndsTheCallWithCause() throws Exception {
        startAndHandshake();
        fs.sendAudio(pattern(320));
        take(captured.events);

        fs.send("Content-Type: text/disconnect-notice\nContent-Disposition: linger\nContent-Length: 0\n\n");
        fs.event("Event-Name: CHANNEL_HANGUP\nHangup-Cause: NORMAL_CLEARING\n\n");

        CallEvent hangup = take(captured.events);
        assertThat(hangup.type()).isEqualTo(CallEvent.Type.HANGUP);
        assertThat(hangup.detail()).isEqualTo("hangup:NORMAL_CLEARING");
        assertThat(take(captured.completions)).isEqualTo("complete");
    }

    /** 连接被直接掐断也要收敛 */
    @Test
    void abruptDisconnectAlsoEndsTheCall() throws Exception {
        startAndHandshake();
        fs.sendAudio(pattern(320));
        take(captured.events);

        fs.close();
        fs = null;

        CallEvent hangup = take(captured.events);
        assertThat(hangup.type()).isEqualTo(CallEvent.Type.HANGUP);
    }

    /** 主动挂机: 必须给 FreeSWITCH 发 hangup 指令, 否则通道会一直停泊到超时 */
    @Test
    void hangupSendsHangupCommand() throws Exception {
        startAndHandshake();
        fs.sendAudio(pattern(320));
        take(captured.events);

        captured.leg.hangup("max-duration");

        FakeFreeSwitchChannel.Command cmd = fs.expect("sendmsg");
        assertThat(cmd.headers()).containsEntry("call-command", "hangup");
        assertThat(take(captured.events).detail()).isEqualTo("max-duration");
    }

    /** 媒体一直不来(拨号计划里的地址配错): 超时挂断, 不让客户对着静音干等 */
    @Test
    void noMediaWithinWaitHangsUp() throws Exception {
        start(FreeSwitchConfig.onPort(0).withMediaWaitMs(300));
        fs.answerHandshake(UUID, "1000", "5000");

        FakeFreeSwitchChannel.Command cmd = fs.expect("sendmsg");
        assertThat(cmd.headers()).containsEntry("call-command", "hangup");
        CallEvent hangup = take(captured.events);
        assertThat(hangup.type()).isEqualTo(CallEvent.Type.HANGUP);
        assertThat(hangup.detail()).isEqualTo("media-timeout");
    }

    /** FreeSWITCH 拒绝 unicast: 这路通话不该建会话 */
    @Test
    void rejectedUnicastAbortsBeforeSessionSetup() throws Exception {
        start(FreeSwitchConfig.onPort(0));
        fs.expect("connect");
        fs.send("Content-Type: command/reply\nReply-Text: %2BOK%0A\nUnique-ID: " + UUID + "\n\n");
        fs.expect("myevents");
        fs.reply("+OK Events Enabled");
        fs.readCommand();
        fs.reply("+OK will linger");
        fs.expect("sendmsg");
        fs.reply("-ERR invalid session");

        assertThat(fs.isClosedByPeer()).isTrue();
        assertThat(captured.leg).isNull();
    }

    /** 客户在握手期间就挂了: 事件穿插在应答之前到达, 同样不该建会话 */
    @Test
    void hangupDuringHandshakeAbortsBeforeSessionSetup() throws Exception {
        start(FreeSwitchConfig.onPort(0));
        fs.expect("connect");
        fs.send("Content-Type: command/reply\nReply-Text: %2BOK%0A\nUnique-ID: " + UUID + "\n\n");
        fs.expect("myevents");
        fs.event("Event-Name: CHANNEL_HANGUP\nHangup-Cause: ORIGINATOR_CANCEL\n\n");
        fs.reply("+OK Events Enabled");

        assertThat(fs.isClosedByPeer()).isTrue();
        assertThat(captured.leg).isNull();
    }

    // ---- 工具 ----

    private static byte[] pattern(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i % 251);
        }
        return b;
    }

    private static <T> T take(BlockingQueue<T> q) throws InterruptedException {
        T v = q.poll(3, TimeUnit.SECONDS);
        assertThat(v).as("等待队列元素超时").isNotNull();
        return v;
    }

    private static void awaitUntil(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("等待条件超时");
    }

    /**
     * 回归: 通话中途媒体断流时, 必须重新下发 unicast 把它接回来。
     *
     * <p>线上事故: 通话好好的, AI 忽然不出声、客户说话也不识别, 电话却不挂。FreeSWITCH 日志里是
     * "Asynchronous PTIME not supported, changing our end from 20 to 40" 紧跟着
     * "Shutting down unicast connection" —— 软电话中途发 re-INVITE 改了打包时长, FreeSWITCH
     * 重建编解码器时连带把 unicast 拆了, 而 SIP 信令毫发无损, 于是电话一直挂着、两头都哑。
     * 本机联调碰不到: 两端都是 20ms, 从不重协商。
     */
    @Test
    void reissuesUnicastWhenMediaStopsMidCall() throws Exception {
        start(FreeSwitchConfig.onPort(0).withMediaWaitMs(300));
        fs.answerHandshake(UUID, "13800138000", "01088886666");

        // 先让媒体真的通起来 —— 区分"一开始就没通"(该挂断)与"中途断了"(该重建)
        fs.sendAudio(new byte[320]);
        assertThat(captured.audio.poll(2, TimeUnit.SECONDS)).as("首包应被收到").isNotNull();

        // 之后一包不发, 模拟 FreeSWITCH 把 unicast 拆掉
        FakeFreeSwitchChannel.Command again = fs.expect("sendmsg");
        assertThat(again.headers().get("call-command")).isEqualTo("unicast");
        assertThat(again.headers().get("remote-port")).as("重建时仍用本进程同一个媒体口").isNotBlank();
        fs.reply("+OK");

        assertThat(captured.completions).as("重建期间不该结束这路通话").isEmpty();
    }

    /** 重建若干次仍然收不到媒体, 就挂断 —— 别让客户对着一部哑了的电话干等 */
    @Test
    void hangsUpWhenMediaNeverComesBack() throws Exception {
        start(FreeSwitchConfig.onPort(0).withMediaWaitMs(200));
        fs.answerHandshake(UUID, "13800138000", "01088886666");
        fs.sendAudio(new byte[320]);
        assertThat(captured.audio.poll(2, TimeUnit.SECONDS)).isNotNull();

        // 连着应答几次重建请求, 但始终不发音频
        for (int i = 0; i < 4; i++) {
            fs.expect("sendmsg");
            fs.reply("+OK");
        }
        assertThat(captured.completions.poll(3, TimeUnit.SECONDS))
                .as("重建无效后应结束这路通话").isNotNull();
    }

    // ---- 转人工 ----

    /** 发起转人工, 按顺序读掉三条命令, 返回坐席通道 uuid 与呼坐席的 Job-UUID */
    private String[] startTransfer() throws Exception {
        assertThat(captured.leg.transfer("user/8002@vca.local")).isTrue();
        assertThat(fs.readCommand().line()).isEqualTo("event plain BACKGROUND_JOB");
        assertThat(fs.readCommand().line()).isEqualTo("bgapi uuid_setvar " + UUID + " hangup_after_bridge true");
        FakeFreeSwitchChannel.Command originate = fs.readCommand();
        String line = originate.line();
        assertThat(line).startsWith("bgapi originate {origination_uuid=")
                .contains("originate_timeout=20").contains("origination_caller_id_number=13800138000")
                .endsWith("}user/8002@vca.local &park()");
        String agent = line.substring(line.indexOf("origination_uuid=") + 17, line.indexOf(','));
        return new String[]{agent, originate.headers().get("Job-UUID")};
    }

    private void backgroundJob(String jobUuid, String result) throws Exception {
        fs.event("Event-Name: BACKGROUND_JOB\nJob-UUID: " + jobUuid + "\nJob-Owner-UUID: " + UUID
                + "\nContent-Length: " + result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + "\n\n" + result);
    }

    /**
     * 坐席接了 → uuid_bridge 两路 → 通知上层已接通。接通后客户这一路的 unicast 被桥接拆掉, 没有媒体是正常的:
     * 媒体泵不能当成断流去重建、更不能挂机, 否则接起电话的前台聊不到十秒就被切断。
     */
    @Test
    void answeredAgentIsBridgedAndSilenceIsNotTreatedAsMediaLoss() throws Exception {
        start(FreeSwitchConfig.onPort(0).withMediaWaitMs(200));
        fs.answerHandshake(UUID, "13800138000", "5000");
        fs.sendAudio(new byte[320]);
        assertThat(take(captured.events).type()).isEqualTo(CallEvent.Type.ANSWERED);

        String[] t = startTransfer();
        backgroundJob(t[1], "+OK " + t[0] + "\n");

        FakeFreeSwitchChannel.Command bridge = fs.readCommand();
        assertThat(bridge.line()).isEqualTo("bgapi uuid_bridge " + UUID + " " + t[0]);
        backgroundJob(bridge.headers().get("Job-UUID"), "+OK\n");
        assertThat(take(captured.events).type()).isEqualTo(CallEvent.Type.TRANSFER_CONNECTED);

        // 桥接后一个媒体包都没有, 等过好几个 mediaWait 周期
        assertThat(fs.pollCommand(1200)).as("桥接中不该重建 unicast, 也不该挂机").isNull();
        assertThat(captured.completions).isEmpty();
        assertThat(captured.leg.isFinished()).isFalse();
    }

    /**
     * 前台没接: 客户这一路从头到尾没动过, 媒体照常 —— AI 接着聊。
     * (旧做法是在客户这一路上直接 bridge, 失败后 unicast 再也接不回来, 本机实测客户直接被挂断)
     */
    @Test
    void unansweredAgentLeavesTheCustomerLegUntouched() throws Exception {
        startAndHandshake();
        fs.sendAudio(pattern(320));
        assertThat(take(captured.events).type()).isEqualTo(CallEvent.Type.ANSWERED);
        take(captured.audio);

        String[] t = startTransfer();
        backgroundJob(t[1], "-ERR NO_ANSWER\n");

        CallEvent failed = take(captured.events);
        assertThat(failed.type()).isEqualTo(CallEvent.Type.TRANSFER_FAILED);
        assertThat(failed.detail()).isEqualTo("NO_ANSWER");
        assertThat(fs.pollCommand(300)).as("客户这一路不需要任何修补").isNull();
        byte[] later = pattern(320);
        fs.sendAudio(later);
        assertThat(take(captured.audio)).as("媒体一直通着").containsExactly(later);
    }

    /** 坐席还在振铃客户就挂了: 撤回对坐席的呼叫, 不让前台接起一个没人的电话 */
    @Test
    void customerHangingUpWhileAgentRingsCancelsTheAgentCall() throws Exception {
        startAndHandshake();
        fs.sendAudio(pattern(320));
        take(captured.events);
        String[] t = startTransfer();

        fs.send("Content-Type: text/disconnect-notice\nContent-Disposition: linger\nContent-Length: 0\n\n");
        fs.event("Event-Name: CHANNEL_HANGUP\nHangup-Cause: NORMAL_CLEARING\n\n");

        assertThat(fs.readCommand().line()).isEqualTo("bgapi uuid_kill " + t[0]);
        assertThat(take(captured.events).type()).isEqualTo(CallEvent.Type.HANGUP);
    }

    /** 坐席接起来的那一刻客户已经挂了: 同样把坐席那一路收掉 */
    @Test
    void agentAnsweringAfterTheCustomerLeftIsHungUp() throws Exception {
        startAndHandshake();
        fs.sendAudio(pattern(320));
        take(captured.events);
        String[] t = startTransfer();
        fs.event("Event-Name: CHANNEL_HANGUP\nHangup-Cause: NORMAL_CLEARING\n\n");
        assertThat(fs.readCommand().line()).isEqualTo("bgapi uuid_kill " + t[0]);
    }
}
