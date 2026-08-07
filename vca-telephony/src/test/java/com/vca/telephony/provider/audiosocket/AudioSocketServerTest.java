package com.vca.telephony.provider.audiosocket;

import com.vca.telephony.spi.CallEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用真实 TCP 跑通 AudioSocket 收发。测试里的"客户端"扮演 Asterisk ——
 * 因此这套用例能在没装 Asterisk 的机器上把协议层验完, 装好之后只剩配置问题。
 */
class AudioSocketServerTest {

    private AudioSocketServer server;
    private Socket client;

    /** 一路被接住的通话及其订阅出来的数据 */
    private static final class Captured {
        AudioSocketCallLeg leg;
        final BlockingQueue<byte[]> audio = new ArrayBlockingQueue<>(64);
        final BlockingQueue<CallEvent> events = new ArrayBlockingQueue<>(16);
        final BlockingQueue<String> completions = new ArrayBlockingQueue<>(4);
    }

    private final Captured captured = new Captured();

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private DataInputStream connect(boolean swapBytes) throws Exception {
        AudioSocketConfig cfg = new AudioSocketConfig(0, 8000, swapBytes, 16);
        server = new AudioSocketServer(cfg, leg -> {
            captured.leg = leg;
            // 模拟 CallSession: 在开泵之前订阅, 这正是 AudioSocketServer 保证的顺序
            leg.inboundAudio().subscribe(captured.audio::offer,
                    err -> captured.completions.offer("error"),
                    () -> captured.completions.offer("complete"));
            leg.events().subscribe(captured.events::offer);
        });
        server.start();
        client = new Socket("127.0.0.1", server.port());
        client.setTcpNoDelay(true);
        return new DataInputStream(client.getInputStream());
    }

    /** 连上来即已接通: dialplan 里 Answer() 先于 AudioSocket(), 所以首帧就该 emit ANSWERED */
    @Test
    void firstFrameMarksAnsweredAndUuidBecomesCallId() throws Exception {
        connect(false);
        OutputStream out = client.getOutputStream();

        out.write(AudioSocketCodec.encode(AudioSocketCodec.TYPE_UUID,
                "1f2e3d4c-aaaa-bbbb".getBytes(StandardCharsets.US_ASCII)));
        out.flush();

        assertThat(take(captured.events).type()).isEqualTo(CallEvent.Type.ANSWERED);
        // callId 换成 Asterisk 的 UUID —— 这是跟 originate 侧对账被叫号码的唯一键
        awaitUntil(() -> "1f2e3d4c-aaaa-bbbb".equals(captured.leg.callId()));
    }

    @Test
    void inboundAudioFramesReachTheLeg() throws Exception {
        connect(false);
        OutputStream out = client.getOutputStream();
        byte[] pcm = pattern(320);

        out.write(AudioSocketCodec.audioFrame(pcm));
        out.flush();

        assertThat(take(captured.audio)).containsExactly(pcm);
    }

    /** 下行: writeAudio 必须封成合法的 0x10 帧, 长度头对得上 */
    @Test
    void outboundAudioIsFramedCorrectly() throws Exception {
        DataInputStream in = connect(false);
        client.getOutputStream().write(AudioSocketCodec.audioFrame(new byte[2]));
        client.getOutputStream().flush();
        take(captured.audio);

        byte[] pcm = pattern(320);
        captured.leg.writeAudio(pcm);

        AudioSocketCodec.Frame frame = AudioSocketCodec.read(in);
        assertThat(frame.isAudio()).isTrue();
        assertThat(frame.payload()).containsExactly(pcm);
    }

    /** 对端挂机: 收到 0x00 → emit HANGUP 并结束上行流, CallSession 据此收尾 */
    @Test
    void peerTerminateEndsTheCall() throws Exception {
        connect(false);
        OutputStream out = client.getOutputStream();
        out.write(AudioSocketCodec.audioFrame(new byte[2]));
        out.write(AudioSocketCodec.terminateFrame());
        out.flush();

        assertThat(take(captured.events).type()).isEqualTo(CallEvent.Type.ANSWERED);
        CallEvent hangup = take(captured.events);
        assertThat(hangup.type()).isEqualTo(CallEvent.Type.HANGUP);
        assertThat(hangup.detail()).isEqualTo("peer-hangup");
        assertThat(take(captured.completions)).isEqualTo("complete");
    }

    /** 连接被直接掐断(非正常挂机帧)也要收敛, 否则会话会泄漏 */
    @Test
    void abruptDisconnectAlsoEndsTheCall() throws Exception {
        connect(false);
        client.getOutputStream().write(AudioSocketCodec.audioFrame(new byte[2]));
        client.getOutputStream().flush();
        take(captured.audio);

        client.close();

        CallEvent hangup = takeOfType(CallEvent.Type.HANGUP);
        assertThat(hangup.detail()).isEqualTo("peer-closed");
    }

    /** 主动挂机: 要给 Asterisk 发 0x00, 否则通道会挂在那里直到超时 */
    @Test
    void hangupSendsTerminateFrameToAsterisk() throws Exception {
        DataInputStream in = connect(false);
        client.getOutputStream().write(AudioSocketCodec.audioFrame(new byte[2]));
        client.getOutputStream().flush();
        take(captured.audio);

        captured.leg.hangup("max-duration");

        AudioSocketCodec.Frame frame = AudioSocketCodec.read(in);
        assertThat(frame.type()).isEqualTo(AudioSocketCodec.TYPE_TERMINATE);
    }

    /**
     * 字节序开关。联调时若听到的是刺耳噪声而不是人声, 打开它即可 —— 上下行都要翻,
     * 只翻一个方向会变成"能听懂对方但对方听不懂你"。
     */
    @Test
    void swapPayloadBytesFlipsBothDirections() throws Exception {
        DataInputStream in = connect(true);
        OutputStream out = client.getOutputStream();

        out.write(AudioSocketCodec.audioFrame(new byte[]{0x01, 0x02, 0x03, 0x04}));
        out.flush();
        assertThat(take(captured.audio)).containsExactly(0x02, 0x01, 0x04, 0x03);

        captured.leg.writeAudio(new byte[]{0x0a, 0x0b, 0x0c, 0x0d});
        assertThat(AudioSocketCodec.read(in).payload()).containsExactly(0x0b, 0x0a, 0x0d, 0x0c);
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

    private CallEvent takeOfType(CallEvent.Type type) throws InterruptedException {
        for (int i = 0; i < 8; i++) {
            CallEvent e = take(captured.events);
            if (e.type() == type) {
                return e;
            }
        }
        throw new AssertionError("没等到事件: " + type);
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
}
