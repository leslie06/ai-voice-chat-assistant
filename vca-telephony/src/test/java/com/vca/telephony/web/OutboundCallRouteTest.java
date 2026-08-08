package com.vca.telephony.web;

import com.vca.telephony.provider.ami.AmiClient;
import com.vca.telephony.provider.ami.AmiConfig;
import com.vca.telephony.provider.ami.AmiTelephonyProvider;
import com.vca.telephony.session.PendingCalls;
import com.vca.telephony.spi.CallEvent;
import com.vca.telephony.spi.CallLeg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;

/**
 * 单拨外呼端点。重点验鉴权与非法号码 —— 这个接口会真的打电话、真的花钱,
 * 它是整个系统里唯一一个"外部输入直接导致花钱"的入口。
 */
class OutboundCallRouteTest {

    private static final String TOKEN = "s3cr3t-token";

    private FakeAmi ami;
    private WebTestClient client;
    private final PendingCalls pending = new PendingCalls();

    /** 拿一个连上假 Asterisk 的真 provider: 号码校验发生在它内部, 必须走真实路径 */
    private void start() throws IOException {
        ami = new FakeAmi();
        AmiConfig cfg = new AmiConfig("127.0.0.1", ami.port(), "u", "p",
                "trunk", "ai-agent", "s", 1_000, 1_500, 2_000);
        AmiClient amiClient = new AmiClient(cfg);
        amiClient.connect();
        ami.setClient(amiClient);
        AmiTelephonyProvider provider = new AmiTelephonyProvider(amiClient, cfg, pending);
        client = WebTestClient.bindToRouterFunction(
                        OutboundCallRoute.create(provider, TOKEN, Duration.ofSeconds(3)))
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (ami != null) {
            ami.close();
        }
    }

    @Test
    void rejectsMissingToken() throws Exception {
        start();

        client.post().uri("/telephony/calls")
                .bodyValue(new OutboundCallRoute.CallRequest("13800138000", "1000"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsWrongToken() throws Exception {
        start();

        client.post().uri("/telephony/calls")
                .header("X-Telephony-Token", "guess")
                .bodyValue(new OutboundCallRoute.CallRequest("13800138000", "1000"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * <b>AMI 头注入</b>: 号码被拼进 {@code Channel: PJSIP/<号码>@<trunk>}, 而 AMI 是 CRLF 行协议。
     * 放进去一个带换行的号码就能往同一条连接里塞任意 manager action —— 必须在拨号前挡掉。
     */
    @Test
    void rejectsCrlfInjectionInNumber() throws Exception {
        start();

        client.post().uri("/telephony/calls")
                .header("X-Telephony-Token", TOKEN)
                .bodyValue(new OutboundCallRoute.CallRequest(
                        "13800138000\r\nAction: Command\r\nCommand: core show channels", "1000"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.outcome").isEqualTo("failed");
    }

    @Test
    void rejectsInjectionInCallerId() throws Exception {
        start();

        client.post().uri("/telephony/calls")
                .header("X-Telephony-Token", TOKEN)
                .bodyValue(new OutboundCallRoute.CallRequest("13800138000", "1000\r\nAction: Logoff"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void rejectsBlankNumber() throws Exception {
        start();

        client.post().uri("/telephony/calls")
                .header("X-Telephony-Token", TOKEN)
                .bodyValue(new OutboundCallRoute.CallRequest("  ", "1000"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    /** 拨出去了但没人接: 502 + 原因, 不能是 500 */
    @Test
    void unansweredCallReportsBadGateway() throws Exception {
        start();

        client.post().uri("/telephony/calls")
                .header("X-Telephony-Token", TOKEN)
                .bodyValue(new OutboundCallRoute.CallRequest("13800138000", "1000"))
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.outcome").isEqualTo("failed")
                .jsonPath("$.reason").exists();
    }

    /** 接通: 返回 callId 与回填的号码 */
    @Test
    void answeredCallReturnsCallId() throws Exception {
        start();
        ami.attachOnOriginate(pending);   // 收到 Originate 就模拟媒体连进来

        client.post().uri("/telephony/calls")
                .header("X-Telephony-Token", TOKEN)
                .bodyValue(new OutboundCallRoute.CallRequest("13800138000", "1000"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.outcome").isEqualTo("answered")
                .jsonPath("$.peerNumber").isEqualTo("13800138000")
                .jsonPath("$.callId").exists();
    }

    // ---- 假 Asterisk ----

    /** 复用 ami 包的假服务端, 外加"收到 Originate 就把媒体接上"的钩子 */
    private static final class FakeAmi implements java.io.Closeable {
        private final java.net.ServerSocket server;
        private final Thread thread;
        private volatile PendingCalls attachTo;
        private volatile AmiClient client;

        FakeAmi() throws IOException {
            this.server = new java.net.ServerSocket(0);
            this.thread = new Thread(this::serve, "fake-ami-route");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        void setClient(AmiClient c) {
            this.client = c;
        }

        void attachOnOriginate(PendingCalls pending) {
            this.attachTo = pending;
        }

        private void serve() {
            try (java.net.Socket socket = server.accept()) {
                var out = socket.getOutputStream();
                out.write("Asterisk Call Manager/7.0.0\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                var in = new java.io.BufferedReader(new java.io.InputStreamReader(
                        socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder buf = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    if (!line.isEmpty()) {
                        buf.append(line).append('\n');
                        continue;
                    }
                    if (buf.length() == 0) {
                        continue;
                    }
                    var packet = com.vca.telephony.provider.ami.AmiPacket.parse(buf.toString());
                    buf.setLength(0);
                    String id = packet.actionId();
                    if (id == null) {
                        continue;
                    }
                    out.write(("Response: Success\r\nActionID: " + id + "\r\n\r\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                    PendingCalls p = attachTo;
                    if (p != null && "Originate".equalsIgnoreCase(packet.getOrDefault("Action", ""))) {
                        attachWhenRegistered(p, id);
                    }
                }
            } catch (IOException e) {
                // 收尾关连接, 正常
            }
        }

        /** 等发起方登记完再接媒体, 模拟真实时序 */
        private void attachWhenRegistered(PendingCalls pending, String callId) {
            new Thread(() -> {
                for (int i = 0; i < 200 && pending.pendingCount() == 0; i++) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                pending.attach(new StubLeg(callId));
            }, "fake-media").start();
        }

        @Override
        public void close() throws IOException {
            if (client != null) {
                client.close();
            }
            server.close();
            thread.interrupt();
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
}
