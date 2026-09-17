package com.vca.telephony.provider.freeswitch;

import com.vca.telephony.session.PendingCalls;
import com.vca.telephony.spi.CallEvent;
import com.vca.telephony.spi.CallLeg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 外呼: 对着假 FreeSWITCH 事件套接字跑真实 TCP, 验证认证、originate 命令内容、失败事件与媒体配对。 */
class FreeSwitchTelephonyProviderTest {

    private FakeEslServer server;
    private EslClient client;

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private EslConfig cfg(int port, int answerWaitMs) {
        return new EslConfig("127.0.0.1", port, "ClueCon", "sofia/gateway/cmcc/{number}",
                "ai-agent", "vca-outbound", 30_000, answerWaitMs, 3_000);
    }

    private FreeSwitchTelephonyProvider provider(PendingCalls pending, int answerWaitMs) throws IOException {
        server = new FakeEslServer("ClueCon");
        client = new EslClient(cfg(server.port(), answerWaitMs));
        client.connect();
        return new FreeSwitchTelephonyProvider(client, cfg(server.port(), answerWaitMs), pending);
    }

    @Test
    void wrongPasswordFailsFast() throws IOException {
        server = new FakeEslServer("ClueCon");
        client = new EslClient(new EslConfig("127.0.0.1", server.port(), "wrong", "user/{number}",
                "ai-agent", "vca-outbound", 30_000, 45_000, 3_000));

        assertThatThrownBy(() -> client.connect())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("认证被拒");
    }

    /** originate 的关键字段: 拨号串、身兼三职的 id、忽略早期媒体、锁 G.711、目标 extension */
    @Test
    void originateSendsCorrectCommand() throws Exception {
        FreeSwitchTelephonyProvider p = provider(new PendingCalls(), 45_000);

        p.originate("13800138000", "01088886666").subscribe(leg -> { }, err -> { });

        FakeEslServer.Received cmd = server.awaitBgapi(3_000);
        String id = cmd.jobUuid();
        assertThat(cmd.command())
                .startsWith("originate {")
                .contains("origination_uuid=" + id)
                .contains("ignore_early_media=true")
                .contains("absolute_codec_string=^^:PCMA:PCMU")
                .contains("origination_caller_id_number=01088886666")
                .contains("originate_timeout=30")
                .endsWith("}sofia/gateway/cmcc/13800138000 vca-outbound XML ai-agent");
    }

    /** 媒体连进来(通道 Unique-ID = origination_uuid) = 真接通, 号码回填 */
    @Test
    void originateCompletesWhenMediaArrives() throws Exception {
        PendingCalls pending = new PendingCalls();
        FreeSwitchTelephonyProvider p = provider(pending, 45_000);
        AtomicReference<CallLeg> got = new AtomicReference<>();

        p.originate("13800138000", null).subscribe(got::set, err -> { });
        FakeEslServer.Received cmd = server.awaitBgapi(3_000);
        assertThat(cmd.command()).doesNotContain("origination_caller_id_number");

        StubLeg media = new StubLeg(cmd.originationUuid());
        awaitUntil(() -> pending.pendingCount() == 1);
        assertThat(pending.attach(media)).isTrue();

        awaitUntil(() -> got.get() != null);
        assertThat(got.get()).isSameAs(media);
        assertThat(got.get().peerNumber()).isEqualTo("13800138000");
    }

    /** 空号/关机/拒接: BACKGROUND_JOB 带 -ERR, 立刻叫醒发起方(answerWait 设很长以证明没在干等) */
    @Test
    void backgroundJobFailureWakesCallerImmediately() throws Exception {
        PendingCalls pending = new PendingCalls();
        FreeSwitchTelephonyProvider p = provider(pending, 600_000);
        AtomicReference<Throwable> err = new AtomicReference<>();

        p.originate("13800138000", "1000").subscribe(leg -> { }, err::set);
        FakeEslServer.Received cmd = server.awaitBgapi(3_000);
        awaitUntil(() -> pending.pendingCount() == 1);

        server.pushBackgroundJob(cmd.jobUuid(), "-ERR NO_ANSWER\n");

        awaitUntil(() -> err.get() != null);
        assertThat(err.get()).hasMessageContaining("NO_ANSWER");
        assertThat(pending.pendingCount()).isZero();
    }

    /** 号码会被拼进命令: 逗号/空格/换行都能改写命令语义, 一律拒绝且不碰 FreeSWITCH */
    @Test
    void injectedNumbersAreRejectedWithoutTouchingFreeSwitch() throws Exception {
        FreeSwitchTelephonyProvider p = provider(new PendingCalls(), 45_000);

        for (String bad : new String[]{"  ", "138,origination_caller_id_number=110", "138 park XML default",
                "138\n\napi shutdown"}) {
            Mono<CallLeg> mono = p.originate(bad, "1000");
            assertThatThrownBy(mono::block).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> p.originate("13800138000", "10 00").block())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(server.bgapiCount()).isZero();
    }

    /**
     * FreeSWITCH 重启: 断开期间外呼立刻失败(不排队、不干等), 重连后自动恢复 —— 不需要跟着重启本进程。
     */
    @Test
    void reconnectsAfterFreeSwitchRestart() throws Exception {
        FreeSwitchTelephonyProvider p = provider(new PendingCalls(), 45_000);
        awaitUntil(() -> server.connectionCount() == 1);

        server.dropConnection();
        awaitUntil(() -> !client.isConnected());
        assertThatThrownBy(() -> p.originate("13800138000", null).block())
                .hasMessageContaining("未连接");

        awaitUntil(() -> client.isConnected());   // 首次退避 1s
        assertThat(server.connectionCount()).isEqualTo(2);
        p.originate("13800138000", null).subscribe(leg -> { }, err -> { });
        assertThat(server.awaitBgapi(3_000).command()).contains("sofia/gateway/cmcc/13800138000");
    }

    @Test
    void endpointTemplateMustContainNumberPlaceholder() {
        assertThatThrownBy(() -> new EslConfig("127.0.0.1", 8021, "x", "sofia/gateway/cmcc/",
                "ai-agent", "vca-outbound", 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{number}");
    }

    // ---- 假 FreeSWITCH 事件套接字 ----

    static final class FakeEslServer implements Closeable {

        record Received(String command, String jobUuid) {
            String originationUuid() {
                Matcher m = Pattern.compile("origination_uuid=([^,}]+)").matcher(command);
                return m.find() ? m.group(1) : null;
            }
        }

        private final ServerSocket server;
        private final String password;
        private final BlockingQueue<Received> bgapis = new LinkedBlockingQueue<>();
        private volatile OutputStream out;
        private volatile int bgapiCount;
        private volatile Socket current;
        private volatile int connections;

        FakeEslServer(String password) throws IOException {
            this.password = password;
            this.server = new ServerSocket(0);
            Thread t = new Thread(this::serve, "fake-esl");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return server.getLocalPort();
        }

        int bgapiCount() {
            return bgapiCount;
        }

        Received awaitBgapi(long timeoutMs) throws InterruptedException {
            Received r = bgapis.poll(timeoutMs, TimeUnit.MILLISECONDS);
            assertThat(r).as("没等到 bgapi").isNotNull();
            return r;
        }

        void pushBackgroundJob(String jobUuid, String result) throws IOException {
            String body = "Event-Name: BACKGROUND_JOB\nJob-UUID: " + jobUuid + "\nContent-Length: "
                    + result.getBytes(StandardCharsets.UTF_8).length + "\n\n" + result;
            write("Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                    + "\nContent-Type: text/event-plain\n\n" + body);
        }

        /** 模拟 FreeSWITCH 重启: 掐断当前连接, 之后照常接受新连接 */
        void dropConnection() throws IOException {
            Socket s = current;
            if (s != null) {
                s.close();
            }
        }

        int connectionCount() {
            return connections;
        }

        private void serve() {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    current = s;
                    connections++;
                    serveOne(s);
                } catch (IOException ignored) {
                    // 连接被掐断或服务端关闭, 继续等下一条
                }
            }
        }

        private void serveOne(Socket s) {
            try {
                out = s.getOutputStream();
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                write("Content-Type: auth/request\n\n");
                while (true) {
                    String first = in.readLine();
                    if (first == null) {
                        return;
                    }
                    if (first.isEmpty()) {
                        continue;
                    }
                    String jobUuid = null;
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        if (line.startsWith("Job-UUID: ")) {
                            jobUuid = line.substring("Job-UUID: ".length());
                        }
                    }
                    if (first.startsWith("auth ")) {
                        boolean ok = first.substring(5).equals(password);
                        reply(ok ? "+OK accepted" : "-ERR invalid");
                    } else if (first.startsWith("event ")) {
                        reply("+OK event listener enabled plain");
                    } else if (first.startsWith("bgapi ")) {
                        bgapiCount++;
                        reply("+OK Job-UUID: " + jobUuid);
                        bgapis.offer(new Received(first.substring(6), jobUuid));
                    } else {
                        reply("-ERR command not found");
                    }
                }
            } catch (IOException ignored) {
                // 连接被掐断
            }
        }

        private void reply(String text) throws IOException {
            write("Content-Type: command/reply\nReply-Text: " + text + "\n\n");
        }

        private synchronized void write(String raw) throws IOException {
            out.write(raw.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

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
