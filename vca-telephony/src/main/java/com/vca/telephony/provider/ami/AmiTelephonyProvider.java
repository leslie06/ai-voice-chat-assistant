package com.vca.telephony.provider.ami;

import com.vca.telephony.session.PendingCalls;
import com.vca.telephony.spi.CallLeg;
import com.vca.telephony.spi.TelephonyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 用 AMI 发起外呼。
 *
 * <h2>一次外呼是两条独立的路</h2>
 * <pre>
 *   ①  本进程 ──AMI Originate──▶ Asterisk ──SIP──▶ 客户手机
 *   ②                            Asterisk ──AudioSocket──▶ 本进程(媒体)
 * </pre>
 * 两条路靠一个我们自己生成的 id 对上: 它<b>同时</b>当 AMI 的 {@code ActionID} 和
 * {@code Variable: CALLUUID}。dialplan 里写 {@code AudioSocket(${CALLUUID},host:port)},
 * 于是媒体连进来时带的 UUID 就是这个 id, {@link PendingCalls} 一查即可配对。
 * 用同一个 id 兼任两职是有意的 —— 失败事件({@code OriginateResponse})只带 ActionID,
 * 若两者是不同的 id, 拿到失败通知也不知道该叫醒谁。
 *
 * <h2>为什么天然不会对着彩铃说话</h2>
 * Originate 指定了 {@code Context/Exten}: Asterisk <b>只有在对端真正接听之后</b>才会把通道送进
 * dialplan, 也就是说 {@code AudioSocket()} 根本不会在彩铃/提示音阶段执行。这比任何音频特征判定都可靠,
 * 也是 {@link #ignoreEarlyMedia()} 恒为 true 的原因。
 */
public final class AmiTelephonyProvider implements TelephonyProvider {

    private static final Logger log = LoggerFactory.getLogger(AmiTelephonyProvider.class);

    /**
     * 号码白名单。<b>这是安全边界, 不是格式美化</b>: 号码会被拼进 AMI 报文
     * ({@code Channel: PJSIP/<号码>@<trunk>}), 而 AMI 是 CRLF 分隔的行协议 —— 号码里若带
     * {@code \r\n}, 攻击者就能往同一条连接里注入任意 manager action(挂断别人的通话、读配置、
     * 甚至 {@code Originate} 到自己的号码上刷话费)。只放行数字与拨号符, 一律不做"清洗后放行"。
     */
    private static final Pattern DIALABLE = Pattern.compile("[0-9+*#]{1,32}");

    private final AmiClient client;
    private final AmiConfig cfg;
    private final PendingCalls pending;

    public AmiTelephonyProvider(AmiClient client, AmiConfig cfg, PendingCalls pending) {
        this.client = client;
        this.cfg = cfg;
        this.pending = pending;
        client.onEvent(this::onEvent);
    }

    @Override
    public Mono<CallLeg> originate(String callee, String callerId) {
        if (callee == null || !DIALABLE.matcher(callee).matches()) {
            return Mono.error(new IllegalArgumentException("被叫号码非法: " + safeEcho(callee)));
        }
        if (callerId != null && !callerId.isBlank() && !DIALABLE.matcher(callerId).matches()) {
            return Mono.error(new IllegalArgumentException("主叫号显非法: " + safeEcho(callerId)));
        }
        String callId = UUID.randomUUID().toString();

        // 先登记再发起: 反过来的话, 快线路上媒体可能比登记还早连进来, 那一路会被当成呼入
        Mono<CallLeg> media = pending.register(callId, callee, Duration.ofMillis(cfg.answerWaitMs()));

        AmiPacket action = AmiPacket.action("Originate",
                        "ActionID", callId,
                        "Channel", "PJSIP/" + callee + "@" + cfg.trunk(),
                        "Context", cfg.context(),
                        "Exten", cfg.exten(),
                        "Priority", "1",
                        "CallerID", callerId,
                        "Timeout", String.valueOf(cfg.ringTimeoutMs()),
                        // 同步 Originate 会把 AMI 连接阻塞到通话结束, 几十路并发直接瘫掉
                        "Async", "true")
                .with("Variable", "CALLUUID=" + callId)
                // 锁死编码: 中继基本只给 G.711A, 协商到别的会让整条 8k 采样率假设错位
                .with("Variable", "__SIP_CODEC=alaw");

        return Mono.fromCallable(() -> client.send(action, callId, cfg.connectTimeoutMs()))
                .subscribeOn(Schedulers.boundedElastic())   // send 是阻塞的
                .flatMap(resp -> {
                    if (!resp.isSuccess()) {
                        String msg = resp.getOrDefault("Message", "(无消息)");
                        pending.fail(callId, msg);
                        return Mono.<CallLeg>error(new IllegalStateException("Originate 被拒: " + msg));
                    }
                    log.info("外呼已发起: {} (callId={})", callee, callId);
                    return media;   // 等媒体连进来 = 等真接通
                })
                .onErrorResume(e -> {
                    pending.fail(callId, e.toString());
                    return Mono.error(e);
                });
    }

    /**
     * {@code OriginateResponse} 是异步 Originate 的<b>最终</b>结果(前面那个 Response: Success 只表示
     * "指令已受理")。失败要立刻叫醒发起方, 否则一通空号也要干等到 {@code answerWaitMs} 才报错 ——
     * 批量外呼时这点等待会直接吃掉并发。
     */
    private void onEvent(AmiPacket packet) {
        if (!"OriginateResponse".equalsIgnoreCase(packet.getOrDefault("Event", ""))) {
            return;
        }
        String callId = packet.actionId();
        if (callId == null || packet.isSuccess()) {
            return;   // 成功由媒体连入来确认, 这里不用管
        }
        pending.fail(callId, packet.getOrDefault("Reason", packet.getOrDefault("Response", "unknown")));
    }

    /** 回显非法输入时先掐掉换行, 免得把注入内容原样写进日志(日志注入) */
    private static String safeEcho(String raw) {
        if (raw == null) {
            return "(空)";
        }
        String cleaned = raw.replaceAll("[\\r\\n]", "\\\\n");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) + "…" : cleaned;
    }

    /** 见类注释: 指定 Context/Exten 的 Originate 只在真接通后才进 dialplan。 */
    @Override
    public boolean ignoreEarlyMedia() {
        return true;
    }

    public boolean isReady() {
        return client.isConnected();
    }
}
