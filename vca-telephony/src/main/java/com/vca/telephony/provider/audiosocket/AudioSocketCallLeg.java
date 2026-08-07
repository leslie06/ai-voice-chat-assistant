package com.vca.telephony.provider.audiosocket;

import com.vca.telephony.spi.CallEvent;
import com.vca.telephony.spi.CallLeg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一条 AudioSocket 连接 = 一路通话。
 *
 * <p><b>信令与媒体在这里是同一条 TCP</b>: Asterisk 的 dialplan 是 {@code Answer()} 之后才执行
 * {@code AudioSocket()}, 所以"连上来"本身就等于"已接通" —— {@link #pump()} 一开始就 emit
 * {@link CallEvent.Type#ANSWERED}, 不需要另外的信令通道。
 *
 * <p><b>两个必须知道的边界</b>:
 * <ol>
 *   <li><b>拿不到号码</b>。AudioSocket 只传 UUID 和音频, 被叫号码要由 originate 侧(AMI/ARI)持有,
 *       用 {@link #callId()} 这个 UUID 去对账。所以 {@link #peerNumber()} 由外部注入。</li>
 *   <li><b>拿不到 DTMF</b>。按键事件走 Asterisk 的 AMI/ARI 事件流, 不在这条 socket 上,
 *       需要时由上层把 {@link CallEvent.Type#DTMF} 喂进 {@link #injectEvent}。</li>
 * </ol>
 *
 * <p>上行与事件都是 <b>unicast</b> sink: 一路通话只有一个消费者({@code CallSession})。
 */
public final class AudioSocketCallLeg implements CallLeg {

    private static final Logger log = LoggerFactory.getLogger(AudioSocketCallLeg.class);

    private final Socket socket;
    private final AudioSocketConfig cfg;
    private final DataInputStream in;
    private final Sinks.Many<byte[]> inbound = Sinks.many().unicast().onBackpressureBuffer();
    private final Sinks.Many<CallEvent> events = Sinks.many().unicast().onBackpressureBuffer();
    private final AtomicBoolean answered = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    /** 出方向要串行: 节流线程逐帧写, hangup 可能并发插进来 */
    private final Object writeLock = new Object();

    private volatile String callId;
    private volatile String peerNumber;
    /** {@link #primeUuid} 误读到的非 UUID 帧, 留给 {@link #pump()} 先处理, 不能丢 */
    private AudioSocketCodec.Frame pending;

    public AudioSocketCallLeg(Socket socket, AudioSocketConfig cfg) throws IOException {
        this.socket = socket;
        this.cfg = cfg;
        this.in = new DataInputStream(socket.getInputStream());
        this.callId = "as-" + socket.getPort() + "-" + System.nanoTime();
    }

    // ---- CallLeg ----

    @Override
    public String callId() {
        return callId;
    }

    @Override
    public String peerNumber() {
        return peerNumber;
    }

    @Override
    public int sampleRate() {
        return cfg.sampleRate();
    }

    @Override
    public Flux<byte[]> inboundAudio() {
        return inbound.asFlux();
    }

    @Override
    public Flux<CallEvent> events() {
        return events.asFlux();
    }

    @Override
    public void writeAudio(byte[] pcm) {
        if (finished.get() || pcm == null || pcm.length == 0) {
            return;
        }
        byte[] payload = cfg.swapPayloadBytes() ? AudioSocketCodec.swap16(pcm) : pcm;
        try {
            synchronized (writeLock) {
                OutputStream out = socket.getOutputStream();
                out.write(AudioSocketCodec.audioFrame(payload));
                out.flush();
            }
        } catch (IOException e) {
            log.warn("[{}] 下行写失败, 判定断连: {}", callId, e.toString());
            finish("write-failed");
        }
    }

    @Override
    public void hangup(String reason) {
        if (finished.get()) {
            return;
        }
        try {
            synchronized (writeLock) {
                OutputStream out = socket.getOutputStream();
                out.write(AudioSocketCodec.terminateFrame());
                out.flush();
            }
        } catch (IOException e) {
            log.debug("[{}] 发挂机帧失败(对端可能已断): {}", callId, e.toString());
        }
        finish(reason);
    }

    // ---- 供上层补充 socket 上拿不到的信息 ----

    /** 由 originate 侧按 UUID 关联上被叫号码 —— AudioSocket 自己拿不到号码 */
    @Override
    public void attachPeerNumber(String number) {
        this.peerNumber = number;
    }

    /** 把 socket 之外的事件(如 AMI 来的 DTMF)喂进本路通话 */
    public void injectEvent(CallEvent event) {
        events.tryEmitNext(event);
    }

    // ---- 建连握手 ----

    /**
     * 建连后先把首帧 UUID 读出来。
     *
     * <p><b>为什么必须在建会话之前做</b>: {@code callId} 会被当成 sessionId 落库, 也是跟 originate 侧
     * 对账被叫号码的唯一键。等读泵跑起来再更新就晚了 —— 那时会话已经用占位 id 建好了。
     *
     * <p>超时或首帧不是 UUID 都不算错: 留着占位 id 继续跑, 误读的帧交回给 {@link #pump()} 处理。
     */
    void primeUuid(int timeoutMs) {
        if (timeoutMs <= 0) {
            return;
        }
        try {
            socket.setSoTimeout(timeoutMs);
            AudioSocketCodec.Frame frame = AudioSocketCodec.read(in);
            if (frame == null) {
                finish("peer-closed");
                return;
            }
            if (frame.type() == AudioSocketCodec.TYPE_UUID) {
                String uuid = AudioSocketCodec.parseUuid(frame.payload());
                if (!uuid.isEmpty()) {
                    this.callId = uuid;
                }
            } else {
                pending = frame;   // 该构建不发 UUID 帧, 这是正经数据, 不能吞
            }
        } catch (SocketTimeoutException e) {
            log.debug("等 UUID 帧超时({}ms), 沿用占位 callId={}", timeoutMs, callId);
        } catch (IOException e) {
            log.warn("[{}] 握手读失败: {}", callId, e.toString());
            finish("read-failed");
        } finally {
            resetTimeout();
        }
    }

    private void resetTimeout() {
        try {
            socket.setSoTimeout(0);   // 之后是阻塞读, 不设超时
        } catch (SocketException e) {
            log.debug("[{}] 复位 soTimeout 失败: {}", callId, e.toString());
        }
    }

    // ---- 读泵 ----

    /**
     * 阻塞读取直到断连。由 {@link AudioSocketServer} 在独立线程上调用,
     * <b>且必须在上层已经订阅之后再调</b> —— 这样 ANSWERED 与首个音频帧都落在订阅之后。
     */
    void pump() {
        if (finished.get()) {
            return;   // 握手阶段已经断了
        }
        markAnswered();   // 连接建立 = 已接通(dialplan 里 Answer() 先于 AudioSocket())
        try (DataInputStream stream = in) {
            if (pending != null && !handle(pending)) {
                return;
            }
            pending = null;
            AudioSocketCodec.Frame frame;
            while ((frame = AudioSocketCodec.read(stream)) != null) {
                if (!handle(frame)) {
                    return;
                }
            }
            finish("peer-closed");   // 干净 EOF
        } catch (EOFException e) {
            log.warn("[{}] 连接中断在帧中间: {}", callId, e.toString());
            finish("truncated");
        } catch (IOException e) {
            log.warn("[{}] 上行读失败: {}", callId, e.toString());
            finish("read-failed");
        }
    }

    /** @return false 表示应停止读取 */
    private boolean handle(AudioSocketCodec.Frame frame) {
        switch (frame.type()) {
            case AudioSocketCodec.TYPE_UUID -> {
                String uuid = AudioSocketCodec.parseUuid(frame.payload());
                if (!uuid.isEmpty()) {
                    this.callId = uuid;
                }
                log.info("AudioSocket 接入: uuid={}", callId);
            }
            case AudioSocketCodec.TYPE_AUDIO -> {
                byte[] pcm = cfg.swapPayloadBytes()
                        ? AudioSocketCodec.swap16(frame.payload()) : frame.payload();
                if (pcm.length > 0) {
                    inbound.tryEmitNext(pcm);
                }
            }
            case AudioSocketCodec.TYPE_TERMINATE -> {
                finish("peer-hangup");
                return false;
            }
            case AudioSocketCodec.TYPE_ERROR -> {
                int code = frame.payload().length > 0 ? frame.payload()[0] & 0xff : -1;
                log.warn("[{}] AudioSocket 报错, code={}", callId, code);
                finish("asterisk-error-" + code);
                return false;
            }
            default -> log.debug("[{}] 忽略未知帧类型 0x{}", callId, Integer.toHexString(frame.type()));
        }
        return true;
    }

    private void markAnswered() {
        if (answered.compareAndSet(false, true)) {
            events.tryEmitNext(CallEvent.of(CallEvent.Type.ANSWERED));
        }
    }

    /** 幂等收尾: 发 HANGUP、结束上行流、关 socket。 */
    private void finish(String reason) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        events.tryEmitNext(CallEvent.hangup(reason));
        events.tryEmitComplete();
        inbound.tryEmitComplete();
        try {
            socket.close();
        } catch (IOException e) {
            log.debug("[{}] 关闭 socket 异常: {}", callId, e.toString());
        }
    }
}
