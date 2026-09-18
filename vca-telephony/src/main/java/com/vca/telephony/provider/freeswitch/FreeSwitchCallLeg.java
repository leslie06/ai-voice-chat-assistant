package com.vca.telephony.provider.freeswitch;

import com.vca.telephony.spi.CallEvent;
import com.vca.telephony.spi.CallLeg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FreeSWITCH 的一路通话。<b>信令和媒体是两条路</b>:
 * <pre>
 *   信令: FreeSWITCH ──TCP(ESL, socket 应用 async full)──▶ 本进程   主叫/被叫、按键、挂机; 我们下发 unicast / hangup
 *   媒体: FreeSWITCH ◀──────────UDP(L16 裸 PCM, 20ms 一包)──────▶ 本进程   双向音频
 * </pre>
 *
 * <h2>握手</h2>
 * <ol>
 *   <li>{@code connect} —— 应答里平铺着整份通道数据: Unique-ID(当 callId)、主叫、被叫、编码,
 *       以及拨号计划 set 的变量。<b>号码直接就有</b>, 不像 AudioSocket 要靠外部回填。</li>
 *   <li>{@code myevents} —— 订阅本通道事件(按键、挂机)。</li>
 *   <li>{@code linger 10} —— 挂机后连接多留 10 秒, 否则挂机事件来不及送到连接就断了, 拿不到挂机原因。</li>
 *   <li>本地开一个 UDP 口, 下发 {@code sendmsg / call-command: unicast}。FreeSWITCH 内核随即开始把通话音频
 *       按 20ms 一包发过来, 并把我们发回去的包写进通话。</li>
 * </ol>
 *
 * <h2>为什么媒体用 unicast</h2>
 * 它是 FreeSWITCH 内核自带的(switch_ivr.c), 不需要任何第三方模块。社区常用的 mod_audio_stream
 * 开源版只能单向推流, 实时回放在它的闭源商业版里。unicast 的代价是只有 UDP(transport 参数写 tcp 也会建 UDP 套接字),
 * 所以本类自己处理两件 UDP 的事:
 * <ul>
 *   <li><b>回包地址按首包来源锁定</b>(同对称 RTP)。FreeSWITCH 侧端口让系统随机分配, 不用维护端口池;
 *       在 Docker 里, 首包来源是端口转发后的地址, 按它回包正好能原路回到容器 —— 不需要映射任何端口。
 *       锁定之后其他来源的包一律丢弃, 防止有人往通话里灌音频。</li>
 *   <li><b>"收到第一个媒体包"才算接通</b>, 此时才 emit {@link CallEvent.Type#ANSWERED}。开场白因此不会在媒体
 *       还没通的时候就开始"播", 结果客户听到的是被截掉开头的半句话。</li>
 * </ul>
 *
 * <p>上行与事件都是 unicast sink(一路通话只有一个消费者 {@code CallSession})。unicast sink 不允许并发 emit,
 * 而这里会从信令线程、媒体线程、挂机调用方三处 emit, 所以各自加锁串行化。
 */
public final class FreeSwitchCallLeg implements CallLeg {

    private static final Logger log = LoggerFactory.getLogger(FreeSwitchCallLeg.class);

    /** 拨号计划里约定的通道变量名(见 deploy/freeswitch/conf/dialplan.xml) */
    static final String VAR_MEDIA_LOCAL_IP = "variable_vca_media_local_ip";
    static final String VAR_MEDIA_REMOTE_HOST = "variable_vca_media_remote_host";

    /** 挂机后连接最多再留多久(秒)。只为把挂机事件送到, 不需要长 */
    private static final int LINGER_SECONDS = 10;
    /** 主动挂机后等 FreeSWITCH 自己断开的上限: 超过就强关, 不留半开连接 */
    private static final long CLOSE_GRACE_MS = 3_000;

    private final Socket socket;
    private final FreeSwitchConfig cfg;
    private final InputStream in;
    private final OutputStream out;
    private final Sinks.Many<byte[]> inbound = Sinks.many().unicast().onBackpressureBuffer();
    private final Sinks.Many<CallEvent> events = Sinks.many().unicast().onBackpressureBuffer();
    private final Object inboundLock = new Object();
    private final Object eventLock = new Object();
    private final Object writeLock = new Object();
    private final AtomicBoolean answered = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();

    private volatile String callId;
    private volatile String peerNumber;
    private volatile String calledNumber;
    private volatile DatagramSocket media;
    /** 锁定的 FreeSWITCH 媒体地址(首包来源); null = 还没收到媒体 */
    private volatile SocketAddress mediaPeer;
    private volatile boolean strayLogged;

    public FreeSwitchCallLeg(Socket socket, FreeSwitchConfig cfg) throws IOException {
        this.socket = socket;
        this.cfg = cfg;
        this.in = new BufferedInputStream(socket.getInputStream());   // ESL 按字节解析, 不缓冲就是每字节一次系统调用
        this.out = socket.getOutputStream();
        this.callId = "fs-" + socket.getPort() + "-" + System.nanoTime();
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
    public String calledNumber() {
        return calledNumber;
    }

    /**
     * 外呼时由接线台回填客户号码。FreeSWITCH 的通道数据里, 外呼腿的"主叫"是我们的号显、
     * "被叫"是拨号计划里的 {@code vca-outbound}, 都不是客户 —— 以发起方登记的号码为准。
     */
    @Override
    public void attachPeerNumber(String number) {
        this.peerNumber = number;
        this.calledNumber = number;
    }

    @Override
    public int sampleRate() {
        return cfg.sampleRate();
    }

    /**
     * 需要。unicast 写进通道的帧才会变成 RTP, 不写就断流(见接口注释)。
     * 不能改用 FreeSWITCH 的 {@code send_silence_when_idle}: 停泊循环会<b>无条件</b>每 20ms 写一帧静音,
     * 和 unicast 线程写入的语音叠在一起, 发包速率翻倍, 客户听到的是被搅乱的声音。
     */
    @Override
    public boolean needsContinuousMedia() {
        return true;
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
        SocketAddress peer = mediaPeer;
        DatagramSocket sock = media;
        if (finished.get() || peer == null || sock == null || pcm == null || pcm.length == 0) {
            return;
        }
        try {
            sock.send(new DatagramPacket(pcm, pcm.length, peer));
        } catch (IOException e) {
            if (!finished.get()) {
                log.warn("[{}] 下行媒体发送失败, 判定断连: {}", callId, e.toString());
                finish("media-write-failed", true);
            }
        }
    }

    /**
     * 转人工: 让 FreeSWITCH 把这一路桥接到坐席。
     *
     * <p>用 {@code sendmsg execute bridge} 而不是 {@code uuid_transfer}: bridge 直接在本通道上执行,
     * 用的就是我们这条已经建好的连接, 不需要另开 ESL(呼入场景可能根本没开外呼那条连接)。
     *
     * <p>桥接之后通道离开停泊状态, unicast 随之停止, 本进程不再收发音频 —— 这正是我们要的:
     * 剩下的对话归坐席。挂机事件仍会从这条信令连接上来, 会话照常收尾、照常落库。
     */
    @Override
    public boolean transfer(String dialString) {
        if (finished.get() || dialString == null || dialString.isBlank()) {
            return false;
        }
        try {
            synchronized (writeLock) {
                out.write(EslMessage.command("sendmsg",
                        "call-command", "execute",
                        "execute-app-name", "bridge",
                        "execute-app-arg", dialString));
                out.flush();
            }
            log.info("[{}] 转人工: bridge {}", callId, dialString);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("[{}] 转人工失败: {}", callId, e.toString());
            return false;
        }
    }

    /**
     * 主动挂机: 让 FreeSWITCH 挂断通道, 然后半关连接等它自己断开。
     * <b>不能发完就 close</b>: 我们这边接收缓冲里往往还有没读的事件, 此时 close 会发 RST,
     * 对端可能在读到挂机指令之前就先收到 RST, 通道就挂在那里直到停泊超时。
     */
    @Override
    public void hangup(String reason) {
        if (finished.get()) {
            return;
        }
        boolean sent = false;
        try {
            synchronized (writeLock) {
                out.write(EslMessage.command("sendmsg",
                        "call-command", "hangup",
                        "hangup-cause", "NORMAL_CLEARING"));
                out.flush();
                socket.shutdownOutput();
            }
            sent = true;
        } catch (IOException e) {
            log.debug("[{}] 发挂机指令失败(对端可能已断): {}", callId, e.toString());
        }
        finish(reason, !sent);
    }

    // ---- 握手 ----

    /**
     * 建连后同步完成握手(见类注释)。在建会话<b>之前</b>调用: callId 要当 sessionId 落库, 号码要进上下文。
     *
     * @throws IOException 握手失败或通道在握手期间就挂了; 调用方应放弃这路通话
     */
    void handshake() throws IOException {
        socket.setSoTimeout(cfg.handshakeTimeoutMs());
        try {
            EslMessage data = request(EslMessage.command("connect"), "connect");
            String uuid = data.get("Unique-ID");
            if (uuid != null && !uuid.isBlank()) {
                callId = uuid;
            }
            peerNumber = blankToNull(data.get("Caller-Caller-ID-Number"));
            calledNumber = blankToNull(data.get("Caller-Destination-Number"));
            checkCodec(data);

            request(EslMessage.command("myevents"), "myevents");
            request(EslMessage.command("linger " + LINGER_SECONDS), "linger");

            // FreeSWITCH 侧的地址由它的拨号计划告诉我们; 没设就按同机部署处理
            String fsLocalIp = data.getOrDefault(VAR_MEDIA_LOCAL_IP, "127.0.0.1");
            String fsRemoteHost = data.getOrDefault(VAR_MEDIA_REMOTE_HOST, "127.0.0.1");
            media = new DatagramSocket(new InetSocketAddress(cfg.mediaBindAddress(), 0));
            request(EslMessage.command("sendmsg",
                    "call-command", "unicast",
                    "local-ip", fsLocalIp,
                    "local-port", "0",          // 让系统分配, 回包地址按首包来源锁定
                    "remote-ip", fsRemoteHost,
                    "remote-port", String.valueOf(media.getLocalPort()),
                    "transport", "udp"), "unicast");
            log.info("[{}] FreeSWITCH 接入: 主叫={}, 被叫={}, 媒体 {}:{} ⇄ {}:0",
                    callId, peerNumber, calledNumber, fsRemoteHost, media.getLocalPort(), fsLocalIp);
        } catch (IOException | RuntimeException e) {
            finish("handshake-failed", true);
            throw e instanceof IOException io ? io : new IOException(e);
        } finally {
            if (!finished.get()) {
                socket.setSoTimeout(0);
            }
        }
    }

    /**
     * 发一条命令并等它的应答。async 模式下 myevents 之后事件会穿插着到, 等应答期间收到的事件照常处理
     * (握手期间客户就挂了也要能感知到)。
     */
    private EslMessage request(byte[] command, String what) throws IOException {
        synchronized (writeLock) {
            out.write(command);
            out.flush();
        }
        while (true) {
            EslMessage msg;
            try {
                msg = EslMessage.read(in);
            } catch (SocketTimeoutException e) {
                throw new IOException("等 FreeSWITCH 应答 " + what + " 超时", e);
            }
            if (msg == null) {
                throw new IOException("FreeSWITCH 在应答 " + what + " 之前断开");
            }
            if (EslMessage.CT_REPLY.equals(msg.contentType())) {
                if (!msg.isOk()) {
                    throw new IOException("FreeSWITCH 拒绝 " + what + ": " + msg.replyText());
                }
                return msg;
            }
            if (!handle(msg)) {
                throw new IOException("通道在握手阶段就结束了(" + what + ")");
            }
        }
    }

    private void checkCodec(EslMessage data) {
        String rate = data.get("Channel-Read-Codec-Rate");
        if (rate != null && !rate.equals(String.valueOf(cfg.sampleRate()))) {
            // unicast 按通道读编码的采样率送 L16; 对不上时音频会整段变速, 识别全错
            log.warn("[{}] 通道编码 {}@{}Hz 与配置的线路采样率 {}Hz 不一致 —— 请在 FreeSWITCH 的 codec-prefs 里只留 PCMA/PCMU",
                    callId, data.get("Channel-Read-Codec-Name"), rate, cfg.sampleRate());
        }
    }

    // ---- 信令泵 ----

    /** 阻塞读信令直到断连。<b>必须在上层订阅之后调用</b>。 */
    void pumpSignaling() {
        try {
            EslMessage msg;
            while ((msg = EslMessage.read(in)) != null) {
                if (!handle(msg)) {
                    return;
                }
            }
            finish("peer-closed", true);
        } catch (IOException e) {
            if (!finished.get()) {
                log.warn("[{}] 信令读失败: {}", callId, e.toString());
            }
            finish("signaling-failed", true);
        } finally {
            closeSignalingQuietly();   // 走到这里说明对端已断或我们已收尾, 连接没有继续留着的理由
        }
    }

    /** @return false 表示通话已结束, 停止读取 */
    private boolean handle(EslMessage msg) {
        switch (msg.contentType()) {
            case EslMessage.CT_EVENT_PLAIN -> {
                return handleEvent(msg.event());
            }
            case EslMessage.CT_DISCONNECT -> {
                // linger 模式下挂机时先来一条带 Content-Disposition: linger 的通知, 之后挂机事件还会继续到
                if (!"linger".equalsIgnoreCase(msg.getOrDefault("Content-Disposition", ""))) {
                    finish("peer-disconnected", true);
                    return false;
                }
                return true;
            }
            case EslMessage.CT_REPLY -> {
                if (!msg.isOk()) {
                    log.warn("[{}] FreeSWITCH 命令失败: {}", callId, msg.replyText());
                }
                return true;
            }
            default -> {
                return true;
            }
        }
    }

    private boolean handleEvent(EslMessage event) {
        switch (event.eventName()) {
            case "DTMF" -> {
                String digit = event.get("DTMF-Digit");
                if (digit != null && !digit.isEmpty()) {
                    emitEvent(CallEvent.dtmf(digit));
                }
                return true;
            }
            case "CHANNEL_HANGUP", "CHANNEL_HANGUP_COMPLETE" -> {
                finish("hangup:" + event.getOrDefault("Hangup-Cause", "UNKNOWN"), true);
                return false;
            }
            default -> {
                return true;
            }
        }
    }

    // ---- 媒体泵 ----

    /** 阻塞收 UDP 直到通话结束。<b>必须在上层订阅之后调用</b>, 由服务端放到独立线程。 */
    void pumpMedia() {
        DatagramSocket sock = media;
        if (sock == null || finished.get()) {
            return;
        }
        byte[] buf = new byte[4096];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        try {
            sock.setSoTimeout(Math.max(1, cfg.mediaWaitMs()));
            while (!finished.get()) {
                try {
                    sock.receive(packet);
                } catch (SocketTimeoutException e) {
                    if (mediaPeer == null) {
                        log.error("[{}] {}ms 内没收到 FreeSWITCH 的媒体包, 挂断。排查: ① 拨号计划里 vca_media_remote_host "
                                        + "是不是 FreeSWITCH 能访问到本进程的地址; ② 容器里 vca_media_local_ip 必须是 0.0.0.0; "
                                        + "③ 本进程媒体绑定地址 {} 能否收到来自 FreeSWITCH 的 UDP",
                                callId, cfg.mediaWaitMs(), cfg.mediaBindAddress());
                        hangup("media-timeout");
                        return;
                    }
                    continue;
                }
                onMediaPacket(packet, buf, sock);
            }
        } catch (IOException e) {
            if (!finished.get()) {
                log.warn("[{}] 上行媒体读失败: {}", callId, e.toString());
                hangup("media-read-failed");
            }
        }
    }

    private void onMediaPacket(DatagramPacket packet, byte[] buf, DatagramSocket sock) throws IOException {
        SocketAddress src = packet.getSocketAddress();
        if (mediaPeer == null) {
            mediaPeer = src;
            sock.setSoTimeout(0);   // 通了之后就不需要超时了; 客户沉默时 FreeSWITCH 照样按节奏发静音包
            markAnswered();
        } else if (!mediaPeer.equals(src)) {
            if (!strayLogged) {
                strayLogged = true;
                log.warn("[{}] 丢弃非 FreeSWITCH 来源的媒体包: {} (已锁定 {})", callId, src, mediaPeer);
            }
            return;
        }
        int len = packet.getLength();
        if (len > 0) {
            byte[] pcm = Arrays.copyOf(buf, len);
            synchronized (inboundLock) {
                if (!finished.get()) {
                    inbound.tryEmitNext(pcm);
                }
            }
        }
    }

    private void markAnswered() {
        if (answered.compareAndSet(false, true)) {
            emitEvent(CallEvent.of(CallEvent.Type.ANSWERED));
        }
    }

    // ---- 收尾 ----

    private void emitEvent(CallEvent event) {
        synchronized (eventLock) {
            events.tryEmitNext(event);
        }
    }

    /**
     * 幂等收尾: emit HANGUP、结束两条流、关媒体口。
     *
     * @param closeNow true = 立刻关信令连接; false = 已发出挂机指令, 给 FreeSWITCH 一点时间读到再断
     */
    private void finish(String reason, boolean closeNow) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        synchronized (eventLock) {
            events.tryEmitNext(CallEvent.hangup(reason));
            events.tryEmitComplete();
        }
        synchronized (inboundLock) {
            inbound.tryEmitComplete();
        }
        DatagramSocket sock = media;
        if (sock != null) {
            sock.close();
        }
        if (closeNow) {
            closeSignalingQuietly();
        } else {
            CompletableFuture.delayedExecutor(CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)
                    .execute(this::closeSignalingQuietly);
        }
    }

    private void closeSignalingQuietly() {
        try {
            socket.close();
        } catch (IOException e) {
            log.debug("[{}] 关闭信令连接异常: {}", callId, e.toString());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // ---- 诊断 ----

    boolean isFinished() {
        return finished.get();
    }

    /** 本进程 UDP 媒体口(单测用: 扮演 FreeSWITCH 往这里发包) */
    int mediaPort() {
        DatagramSocket sock = media;
        return sock == null ? -1 : sock.getLocalPort();
    }
}
