package com.vca.telephony.provider.ami;

import com.vca.telephony.session.PendingCalls;
import com.vca.telephony.spi.CallEvent;
import com.vca.telephony.spi.CallLeg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 外呼: 对着假 Asterisk 跑真实 TCP, 验证 AMI 登录握手、Originate 指令内容、
 * 以及"AMI 呼叫"与"AudioSocket 媒体"两条独立通道的配对。
 */
class AmiTelephonyProviderTest {

    private FakeAmiServer server;
    private AmiClient client;

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private AmiTelephonyProvider provider(PendingCalls pending, int answerWaitMs) throws IOException {
        server = new FakeAmiServer();
        AmiConfig cfg = new AmiConfig("127.0.0.1", server.port(), "vca", "s3cr3t",
                "trunk-cmcc", "ai-agent", "s", 30_000, answerWaitMs, 3_000);
        client = new AmiClient(cfg);
        client.connect();
        return new AmiTelephonyProvider(client, cfg, pending);
    }

    /** 登录失败必须抛, 不能静默地拨不出去 */
    @Test
    void loginRejectionFailsFast() throws IOException {
        server = new FakeAmiServer();
        server.rejectLogin();
        AmiConfig cfg = new AmiConfig("127.0.0.1", server.port(), "vca", "wrong",
                "t", "ai-agent", "s", 30_000, 45_000, 3_000);
        client = new AmiClient(cfg);

        assertThatThrownBy(() -> client.connect())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("登录被拒");
    }

    /** Originate 指令的关键字段: 拨号串、异步、编码锁死、以及那个身兼两职的 id */
    @Test
    void originateSendsCorrectAction() throws Exception {
        AmiTelephonyProvider p = provider(new PendingCalls(), 45_000);

        p.originate("13800138000", "01088886666").subscribe(leg -> { }, err -> { });

        AmiPacket action = server.awaitAction("Originate", 3_000);
        assertThat(action.get("Channel")).isEqualTo("PJSIP/13800138000@trunk-cmcc");
        assertThat(action.get("Context")).isEqualTo("ai-agent");
        assertThat(action.get("CallerID")).isEqualTo("01088886666");
        // 同步 Originate 会把 AMI 连接阻塞到通话结束, 几十路并发直接瘫掉
        assertThat(action.get("Async")).isEqualTo("true");
        // 中继基本只给 G.711A, 协商到别的会让 8k 采样率假设错位
        assertThat(action.all("Variable")).contains("__SIP_CODEC=alaw");
        // ActionID 和 CALLUUID 必须是同一个值 —— 失败事件只带 ActionID, 不同则叫不醒发起方
        assertThat(action.all("Variable")).contains("CALLUUID=" + action.actionId());
    }

    /** 媒体连进来 = 真接通, 此时 originate 才算完成, 并且号码已回填 */
    @Test
    void originateCompletesWhenMediaArrives() throws Exception {
        PendingCalls pending = new PendingCalls();
        AmiTelephonyProvider p = provider(pending, 45_000);
        AtomicReference<CallLeg> got = new AtomicReference<>();

        p.originate("13800138000", "1000").subscribe(got::set, err -> { });
        AmiPacket action = server.awaitAction("Originate", 3_000);

        // Asterisk 接通后回连 AudioSocket, UUID 就是 dialplan 里的 CALLUUID
        StubLeg media = new StubLeg(action.actionId());
        awaitUntil(() -> pending.pendingCount() == 1);
        assertThat(pending.attach(media)).isTrue();

        awaitUntil(() -> got.get() != null);
        assertThat(got.get()).isSameAs(media);
        assertThat(got.get().peerNumber()).isEqualTo("13800138000");
    }

    /**
     * 空号/关机/拒接: {@code OriginateResponse} 事件带 Failure, 应当立刻报错。
     * 用一个很长的 answerWait 来证明它没有在干等超时。
     */
    @Test
    void originateResponseFailureWakesCallerImmediately() throws Exception {
        PendingCalls pending = new PendingCalls();
        AmiTelephonyProvider p = provider(pending, 600_000);
        AtomicReference<Throwable> err = new AtomicReference<>();

        p.originate("13800138000", "1000").subscribe(leg -> { }, err::set);
        AmiPacket action = server.awaitAction("Originate", 3_000);
        awaitUntil(() -> pending.pendingCount() == 1);

        server.pushEvent("Event: OriginateResponse\r\nActionID: " + action.actionId()
                + "\r\nResponse: Failure\r\nReason: 3");

        awaitUntil(() -> err.get() != null);
        assertThat(err.get()).hasMessageContaining("外呼失败");
        assertThat(pending.pendingCount()).isZero();
    }

    /**
     * 回归: {@code OriginateResponse} <b>同时带 Event 和 Response 两个字段</b>。
     * 早先 AmiClient 只看 "有没有 Response" 来判断是不是某个 Action 的应答, 于是当这个事件赶在
     * Response 之前到达(ActionID 还挂在等待表里)时, 它会被当成应答吞掉、永远不走事件回调 ——
     * 结果就是空号/关机叫不醒发起方, 一直干等到 answerWait。判据必须是 "有没有 Event 字段"。
     */
    @Test
    void originateFailureEventIsNotSwallowedWhenItBeatsTheResponse() throws Exception {
        PendingCalls pending = new PendingCalls();
        server = new FakeAmiServer();
        server.sendOriginateFailureBeforeResponse();
        AmiConfig cfg = new AmiConfig("127.0.0.1", server.port(), "vca", "s3cr3t",
                "trunk-cmcc", "ai-agent", "s", 30_000, 600_000, 3_000);
        client = new AmiClient(cfg);
        client.connect();
        AmiTelephonyProvider p = new AmiTelephonyProvider(client, cfg, pending);
        AtomicReference<Throwable> err = new AtomicReference<>();

        p.originate("13800138000", "1000").subscribe(leg -> { }, err::set);

        awaitUntil(() -> err.get() != null);
        assertThat(err.get()).hasMessageContaining("外呼失败");
    }

    @Test
    void blankCalleeIsRejectedWithoutTouchingAmi() throws Exception {
        AmiTelephonyProvider p = provider(new PendingCalls(), 45_000);

        Mono<CallLeg> mono = p.originate("  ", "1000");

        assertThatThrownBy(mono::block).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 工具 ----

    private static final class StubLeg implements CallLeg {
        private final String id;
        private String peer;

        StubLeg(String id) {
            this.id = id;
        }

        @Override
        public String callId() {
            return id;
        }

        @Override
        public String peerNumber() {
            return peer;
        }

        @Override
        public void attachPeerNumber(String number) {
            this.peer = number;
        }

        @Override
        public Flux<byte[]> inboundAudio() {
            return Flux.never();
        }

        @Override
        public void writeAudio(byte[] pcm) {
        }

        @Override
        public Flux<CallEvent> events() {
            return Flux.never();
        }

        @Override
        public void hangup(String reason) {
        }
    }

    private static void awaitUntil(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("等待条件超时");
    }
}
