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
    /**
     * 语音网关 LINE 分机在 FreeSWITCH 目录里绑定的接入号(见 deploy/freeswitch/add-gateway.sh)。
     * 分机认证后目录变量会落到通道上, 有它就以它为准认领门店: 网关上"转 VoIP"那个框是装机时手填的,
     * 填错了会把 A 店的来电送成 B 店; 而分机账号是开通时按店生成的, 不会错。
     */
    static final String VAR_ACCESS_NUMBER = "variable_vca_access_number";

    /** 挂机后连接最多再留多久(秒)。只为把挂机事件送到, 不需要长 */
    private static final int LINGER_SECONDS = 10;
    /**
     * 媒体中途断流后最多重建几次。每次间隔一个 {@code mediaWaitMs}(默认 3s), 所以默认最多扛约 9 秒。
     * 再多就别耗着了: 客户对着一部哑了的电话等十几秒, 体验比直接挂断还差, 而且通道会一直占到停泊超时。
     */
    private static final int MEDIA_REBUILD_TRIES = 3;
    /** 主动挂机后等 FreeSWITCH 自己断开的上限: 超过就强关, 不留半开连接 */
    private static final long CLOSE_GRACE_MS = 3_000;
    /**
     * 转人工时坐席振铃多久没接算失败(秒)。前台可能正在招呼别的客人, 20 秒约响五六声;
     * 再长客户就在听回铃音干等了, 不如回到 AI 这边留个回电。
     */
    static final int TRANSFER_RING_SECONDS = 20;
    /** 坐席号码显示用的来电号码: 只放纯号码, 其余不带(它会拼进 originate 命令) */
    private static final java.util.regex.Pattern CALLER_ID = java.util.regex.Pattern.compile("^\\+?[0-9]{3,20}$");

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
    /** FreeSWITCH 侧的 unicast 地址, 中途重建时要原样再用一次 */
    /** 本通电话是否收到过媒体包: 区分"从一开始就没通"(配置错)与"中途断了"(该重建) */
    private volatile boolean everHadMedia;
    private volatile String fsLocalIp = "127.0.0.1";
    private volatile String fsRemoteHost = "127.0.0.1";
    /** 锁定的 FreeSWITCH 媒体地址(首包来源); null = 还没收到媒体 */
    private volatile SocketAddress mediaPeer;
    private volatile boolean strayLogged;
    /**
     * 已经和坐席接上(下发了 uuid_bridge)。桥接会重置客户这一路的媒体, unicast 随之拆掉、不再送音频 ——
     * 这是正常现象, 媒体泵不能当成断流去重建、更不能挂机, 否则接起电话的前台聊不到十秒就被切断。
     */
    private volatile boolean bridging;
    /** 正在呼叫的坐席那一路(我们指定的 uuid); null = 没有在转接 */
    private volatile String agentUuid;
    /** 呼坐席、接通两路这两个后台任务的 Job-UUID, 用来认领 BACKGROUND_JOB 结果 */
    private volatile String originateJob;
    private volatile String bridgeJob;
    private volatile boolean jobEventsSubscribed;

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
        if (finished.get() || bridging || peer == null || sock == null || pcm == null || pcm.length == 0) {
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

    @Override
    public boolean supportsTransfer() {
        return true;
    }

    /**
     * 转人工: 先<b>单独</b>呼坐席, 坐席接起来再把两路接上。
     *
     * <p>不能直接在客户这一路上执行 {@code bridge}: 桥接(以及任何让通道重置媒体的操作)会拆掉 unicast,
     * 而 FreeSWITCH 的停泊循环把 unicast 连接缓存在局部变量里, 同一次停泊内重新下发的 unicast 会被它当成旧连接
     * 立刻拆掉(读过 switch_ivr.c 的 switch_ivr_park 确认, 本机实测也是"Created unicast"紧跟着被拆)。
     * 所以前台没接的话, 这通电话的媒体就再也接不回来, 客户只能被挂断。
     *
     * <p>现在的做法: 客户这一路一直停泊着不动, AI 这边继续出声(放回铃音); 另起一路
     * {@code bgapi originate … &park()} 呼坐席:
     * <ul>
     *   <li>坐席接了 → {@code uuid_bridge} 两路接上, 此后对话归坐席;</li>
     *   <li>坐席没接 / 忙 / 没注册 → 客户这一路从头到尾没动过, 发 {@link CallEvent.Type#TRANSFER_FAILED}, AI 接着聊;</li>
     *   <li>振铃期间客户挂了 → 把坐席那一路也杀掉, 不让前台接起一个没人的电话。</li>
     * </ul>
     * 结果都经 BACKGROUND_JOB 事件回来: 套接字连接开了 myevents 之后, 本连接发起的后台任务结果仍会投递过来
     * (mod_event_socket 按 Job-Owner-UUID 放行)。
     *
     * @return true = 已开始呼坐席; 结果异步到达
     */
    @Override
    public boolean transfer(String dialString) {
        if (finished.get() || dialString == null || dialString.isBlank() || agentUuid != null) {
            return false;
        }
        String dial = dialString.strip();
        if (dial.chars().anyMatch(Character::isWhitespace)) {
            log.warn("[{}] 转人工拨号串含空白, 拒绝: {}", callId, dial);
            return false;
        }
        String agent = java.util.UUID.randomUUID().toString();
        String job = java.util.UUID.randomUUID().toString();
        agentUuid = agent;
        originateJob = job;
        try {
            synchronized (writeLock) {
                if (!jobEventsSubscribed) {
                    out.write(EslMessage.command("event plain BACKGROUND_JOB"));
                    jobEventsSubscribed = true;
                }
                // 坐席聊完挂机, 客户这边跟着挂; 不设的话客户会回到拨号计划
                out.write(EslMessage.command("bgapi uuid_setvar " + callId + " hangup_after_bridge true"));
                out.write(EslMessage.command("bgapi " + originateCommand(agent, dial), "Job-UUID", job));
                out.flush();
            }
            log.info("[{}] 转人工: 呼叫坐席 {}(坐席通道 {})", callId, dial, agent);
            return true;
        } catch (IOException | RuntimeException e) {
            agentUuid = null;
            originateJob = null;
            log.warn("[{}] 转人工失败: {}", callId, e.toString());
            return false;
        }
    }

    /** 呼坐席的 originate: 坐席接起后先停泊, 等我们 uuid_bridge。来电显示为客户号码, 前台一看就知道是谁 */
    String originateCommand(String agent, String dial) {
        StringBuilder vars = new StringBuilder("origination_uuid=").append(agent)
                .append(",originate_timeout=").append(TRANSFER_RING_SECONDS)
                .append(",ignore_early_media=true");
        String peer = peerNumber;
        if (peer != null && CALLER_ID.matcher(peer).matches()) {
            vars.append(",origination_caller_id_number=").append(peer);
        }
        // 拨号串自带 {变量} 时并进同一个块: 两个块连写 FreeSWITCH 不认
        String target = dial.startsWith("{") ? "{" + vars + "," + dial.substring(1) : "{" + vars + "}" + dial;
        return "originate " + target + " &park()";
    }

    /** 呼坐席 / 接通两路 的后台任务结果 */
    private void onBackgroundJob(EslMessage event) {
        String job = event.get("Job-UUID");
        String result = event.body().strip();
        boolean ok = result.startsWith("+OK");
        String cause = ok ? "" : (result.startsWith("-ERR") ? result.substring(4).strip() : result);
        if (job != null && job.equals(originateJob)) {
            originateJob = null;
            String agent = agentUuid;
            if (!ok) {
                agentUuid = null;
                log.warn("[{}] 转人工没接通({}), 通话继续由 AI 接待", callId, cause.isEmpty() ? "UNKNOWN" : cause);
                emitEvent(new CallEvent(CallEvent.Type.TRANSFER_FAILED, cause.isEmpty() ? "UNKNOWN" : cause));
                return;
            }
            if (finished.get() || agent == null) {
                killAgent(agent);   // 坐席接起来时客户已经挂了
                return;
            }
            String bj = java.util.UUID.randomUUID().toString();
            bridgeJob = bj;
            bridging = true;   // 先置位: 两路一接上客户这边的 unicast 就停了
            try {
                synchronized (writeLock) {
                    out.write(EslMessage.command("bgapi uuid_bridge " + callId + " " + agent, "Job-UUID", bj));
                    out.flush();
                }
                log.info("[{}] 坐席已接听, 接通两路", callId);
            } catch (IOException e) {
                log.warn("[{}] 接通坐席失败: {}", callId, e.toString());
            }
            return;
        }
        if (job != null && job.equals(bridgeJob)) {
            bridgeJob = null;
            if (ok) {
                log.info("[{}] 已转给坐席", callId);
                emitEvent(CallEvent.of(CallEvent.Type.TRANSFER_CONNECTED));
                return;
            }
            // 没接上(多半是客户刚好挂了): 坐席那一路别留着空响
            String agent = agentUuid;
            agentUuid = null;
            bridging = false;
            killAgent(agent);
            if (!finished.get()) {
                log.warn("[{}] 接通坐席失败({}), 通话继续由 AI 接待", callId, cause);
                emitEvent(new CallEvent(CallEvent.Type.TRANSFER_FAILED, cause.isEmpty() ? "BRIDGE_FAILED" : cause));
            }
        }
    }

    /** 杀掉坐席那一路(还在振铃或接起来没人)。尽力而为: 连接已断就算了, 它会按 originate_timeout 自己结束 */
    private void killAgent(String agent) {
        if (agent == null) {
            return;
        }
        try {
            synchronized (writeLock) {
                out.write(EslMessage.command("bgapi uuid_kill " + agent));
                out.flush();
            }
            log.info("[{}] 已撤回对坐席的呼叫", callId);
        } catch (IOException | RuntimeException e) {
            log.debug("[{}] 撤回坐席呼叫失败: {}", callId, e.toString());
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
        if (agentUuid != null && !bridging) {
            killAgent(agentUuid);   // 必须在半关连接之前发, 之后就写不出去了
            agentUuid = null;
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
            String dialed = blankToNull(data.get("Caller-Destination-Number"));
            String bound = blankToNull(data.get(VAR_ACCESS_NUMBER));
            calledNumber = bound != null ? bound : dialed;
            if (bound != null && dialed != null && !bound.equals(dialed)) {
                log.info("[{}] 网关分机绑定接入号 {}, 按它认领门店(网关送来的号码是 {}, 忽略)", callId, bound, dialed);
            }
            checkCodec(data);

            request(EslMessage.command("myevents"), "myevents");
            request(EslMessage.command("linger " + LINGER_SECONDS), "linger");

            // FreeSWITCH 侧的地址由它的拨号计划告诉我们; 没设就按同机部署处理
            fsLocalIp = data.getOrDefault(VAR_MEDIA_LOCAL_IP, "127.0.0.1");
            fsRemoteHost = data.getOrDefault(VAR_MEDIA_REMOTE_HOST, "127.0.0.1");
            media = new DatagramSocket(new InetSocketAddress(cfg.mediaBindAddress(), 0));
            sendUnicast(true);
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
     * 下发一次 {@code unicast}, 让 FreeSWITCH 把通话音频往本进程的 UDP 口送。
     *
     * <p>握手时发一次, <b>通话中途还可能要再发</b> —— 见 {@link #pumpMedia()} 里的重建逻辑。
     */
    private void sendUnicast(boolean awaitReply) throws IOException {
        DatagramSocket sock = media;
        if (sock == null) {
            return;
        }
        byte[] cmd = EslMessage.command("sendmsg",
                "call-command", "unicast",
                "local-ip", fsLocalIp,
                "local-port", "0",          // 让系统分配, 回包地址按首包来源锁定
                "remote-ip", fsRemoteHost,
                "remote-port", String.valueOf(sock.getLocalPort()),
                "transport", "udp");
        if (awaitReply) {
            request(cmd, "unicast");        // 握手阶段: 信令泵还没起, 由本线程读应答
            return;
        }
        // 通话中途: 信令泵正独占着输入流, 这里只写不读, 应答交给它吞掉(与 hangup 同一套路)
        synchronized (writeLock) {
            out.write(cmd);
            out.flush();
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
            case "BACKGROUND_JOB" -> {
                onBackgroundJob(event);
                return true;
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
        int silentRounds = 0;
        try {
            sock.setSoTimeout(Math.max(1, cfg.mediaWaitMs()));
            while (!finished.get()) {
                try {
                    sock.receive(packet);
                } catch (SocketTimeoutException e) {
                    if (mediaPeer == null && !everHadMedia) {
                        log.error("[{}] {}ms 内没收到 FreeSWITCH 的媒体包, 挂断。排查: ① 拨号计划里 vca_media_remote_host "
                                        + "是不是 FreeSWITCH 能访问到本进程的地址; ② 容器里 vca_media_local_ip 必须是 0.0.0.0; "
                                        + "③ 本进程媒体绑定地址 {} 能否收到来自 FreeSWITCH 的 UDP",
                                callId, cfg.mediaWaitMs(), cfg.mediaBindAddress());
                        hangup("media-timeout");
                        return;
                    }
                    if (bridging) {
                        silentRounds = 0;   // 桥接中本来就没有媒体, 不是断流; 通话结束靠信令上的挂机事件
                        continue;
                    }
                    if (everHadMedia && !rebuildMedia(++silentRounds)) {
                        return;
                    }
                    continue;
                }
                silentRounds = 0;
                onMediaPacket(packet, buf, sock);
            }
        } catch (IOException e) {
            if (!finished.get()) {
                log.warn("[{}] 上行媒体读失败: {}", callId, e.toString());
                hangup("media-read-failed");
            }
        }
    }

    /**
     * 通话进行中媒体突然断流时, 重新下发一次 {@code unicast}。
     *
     * <p>线上事故: 通话好好的, AI 忽然不出声、客户说话也不识别, 电话却不挂。FreeSWITCH 日志里是
     * <pre>
     *   [WARNING] [CBR]: Asynchronous PTIME not supported, changing our end from 20 to 40
     *   [DEBUG]   Shutting down unicast connection
     * </pre>
     * 软电话中途发了个 re-INVITE 把打包时长从 20ms 改成 40ms, FreeSWITCH 重建编解码器时<b>连带把
     * unicast 拆了</b> —— unicast 是挂在媒体通道上的。拆完没人重建, 媒体这条路就永久断了,
     * 而 SIP 信令毫发无损, 于是电话一直挂着, 两头都是哑的。
     *
     * <p>本机联调永远碰不到: 两端都是 20ms, 从不重协商。走公网之后软电话会按网络状况调打包时长。
     *
     * <p>重建时必须把 {@code mediaPeer} 清掉: 新的 unicast 会从 FreeSWITCH 的另一个端口发过来,
     * 不清就会被"非 FreeSWITCH 来源"那条规则全部丢弃。
     *
     * <p><b>已知局限(2026-09-24 读源码并本机实测确认)</b>: 重建在同一次停泊里<b>救不回来</b>。
     * {@code switch_ivr_park} 把 unicast 连接缓存在局部变量里, 只在进入停泊时取一次; 通道重置媒体
     * ({@code switch_core_session_reset}, 重协商打包时长、桥接都会触发)拆掉旧连接后, 新下发的 unicast
     * 会被循环拿着旧连接发包失败而立刻拆掉(日志 "Created unicast connection" 紧跟
     * "Attempting to join thread that does not exist")。所以这里实际起的作用是: 三次之后挂断, 别让客户对着哑电话等。
     * 根治靠不让媒体重置发生 —— 网关打包时长固定 20ms; 转人工不在本通道上 bridge(见 {@link #transfer})。
     *
     * @return false 表示已经放弃并挂断, 调用方应结束媒体泵
     */
    private boolean rebuildMedia(int silentRounds) {
        if (silentRounds > MEDIA_REBUILD_TRIES) {
            log.error("[{}] 媒体断流且重建 {} 次无效, 挂断", callId, MEDIA_REBUILD_TRIES);
            hangup("media-lost");
            return false;
        }
        log.warn("[{}] 媒体断流约 {}ms, 重新下发 unicast(第 {} 次)",
                callId, (long) silentRounds * cfg.mediaWaitMs(), silentRounds);
        mediaPeer = null;   // 新连接的源端口会变, 不清会被当成外来包丢掉
        try {
            sendUnicast(false);
        } catch (IOException e) {
            log.warn("[{}] 重建媒体失败: {}", callId, e.toString());
        }
        return true;
    }

    private void onMediaPacket(DatagramPacket packet, byte[] buf, DatagramSocket sock) throws IOException {
        SocketAddress src = packet.getSocketAddress();
        if (mediaPeer == null) {
            mediaPeer = src;
            everHadMedia = true;
            // 超时<b>不能</b>取消。原来这里设了 0(永久阻塞), 依据是"客户沉默时 FreeSWITCH 照样按节奏发静音包"
            // —— 这句只在 unicast 还活着时成立。线上 FreeSWITCH 因为 ptime 重协商把 unicast 拆了之后,
            // 一个包都不会再来, 这个线程就永久睡死, 通话僵在那里两头都哑。留着超时才能察觉并重建。
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
        if (agentUuid != null && !bridging) {
            killAgent(agentUuid);   // 客户在坐席振铃时挂了: 别让前台接起一个没人的电话
            agentUuid = null;
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
