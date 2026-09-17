package com.vca.telephony.provider.freeswitch;

import com.vca.telephony.session.PendingCalls;
import com.vca.telephony.spi.CallLeg;
import com.vca.telephony.spi.TelephonyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 经 FreeSWITCH 事件套接字发起外呼。
 *
 * <h2>一次外呼是两条独立的路</h2>
 * <pre>
 *   ①  本进程 ──ESL bgapi originate──▶ FreeSWITCH ──SIP──▶ 客户手机
 *   ②  客户接听后通道进拨号计划(vca-outbound) ──socket 应用──▶ 本进程(信令 + unicast 媒体)
 * </pre>
 * 两条路靠一个我们生成的 id 对上, 它<b>身兼三职</b>: {@code origination_uuid}(于是 ② 连进来时通道的
 * Unique-ID 就是它, {@link PendingCalls} 一查即配对)、{@code Job-UUID}(失败时 BACKGROUND_JOB 事件带着它,
 * 能叫醒发起方)、以及落库的 sessionId。
 *
 * <h2>为什么天然不会对着彩铃说话</h2>
 * originate 的目标是拨号计划里的 extension: FreeSWITCH <b>只有在对端真正接听之后</b>才把通道送进拨号计划,
 * socket 应用根本不会在彩铃阶段执行。另加 {@code ignore_early_media=true}, 连早期媒体都不桥接。
 */
public final class FreeSwitchTelephonyProvider implements TelephonyProvider {

    private static final Logger log = LoggerFactory.getLogger(FreeSwitchTelephonyProvider.class);

    /**
     * 号码白名单。<b>这是安全边界, 不是格式美化</b>: 号码会被拼进 originate 命令和通道变量,
     * 逗号能多塞一个变量、空格能改掉目标 extension、换行能多塞一条命令。只放行数字与拨号符。
     */
    private static final Pattern DIALABLE = Pattern.compile("[0-9+*#]{1,32}");

    private final EslClient client;
    private final EslConfig cfg;
    private final PendingCalls pending;

    public FreeSwitchTelephonyProvider(EslClient client, EslConfig cfg, PendingCalls pending) {
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
        boolean hasCallerId = callerId != null && !callerId.isBlank();
        if (hasCallerId && !DIALABLE.matcher(callerId).matches()) {
            return Mono.error(new IllegalArgumentException("主叫号显非法: " + safeEcho(callerId)));
        }
        String callId = UUID.randomUUID().toString();

        // 先登记再发起: 反过来的话, 快线路上媒体可能比登记还早连进来, 那一路会被当成呼入
        Mono<CallLeg> media = pending.register(callId, callee, Duration.ofMillis(cfg.answerWaitMs()));
        String command = originateCommand(callId, callee, hasCallerId ? callerId : null);

        return Mono.fromCallable(() -> client.bgapi(command, callId, cfg.connectTimeoutMs()))
                .subscribeOn(Schedulers.boundedElastic())   // bgapi 等应答是阻塞的
                .flatMap(reply -> {
                    if (!reply.isOk()) {
                        pending.fail(callId, reply.replyText());
                        return Mono.<CallLeg>error(new IllegalStateException("originate 被拒: " + reply.replyText()));
                    }
                    log.info("外呼已发起: {} (callId={})", callee, callId);
                    return media;   // 等媒体连进来 = 等真接通
                })
                .onErrorResume(e -> {
                    pending.fail(callId, e.toString());
                    return Mono.error(e);
                });
    }

    /** 包可见: 单测直接断言命令内容 */
    String originateCommand(String callId, String callee, String callerId) {
        List<String> vars = new ArrayList<>();
        vars.add("origination_uuid=" + callId);
        vars.add("originate_timeout=" + Math.max(1, cfg.ringTimeoutMs() / 1000));
        vars.add("ignore_early_media=true");
        // 锁死 G.711: 本进程按 8k 解释 unicast 音频, 协商到宽带编码会整段变速。
        // 变量值里的逗号会被当成变量分隔符, 所以用 FreeSWITCH 的 ^^: 语法把分隔符换成冒号
        vars.add("absolute_codec_string=^^:PCMA:PCMU");
        if (callerId != null) {
            vars.add("origination_caller_id_number=" + callerId);
        }
        String dial = cfg.endpoint().replace("{number}", callee);
        return "originate {" + String.join(",", vars) + "}" + dial + " " + cfg.exten() + " XML " + cfg.context();
    }

    /**
     * BACKGROUND_JOB 是 originate 的<b>最终</b>结果。失败(空号/关机/拒接/无人接听)要立刻叫醒发起方,
     * 否则一通空号也要干等到 {@code answerWaitMs} —— 批量外呼时这点等待会直接吃掉并发。
     * 成功不用管: 媒体连进来时由 {@link PendingCalls#attach} 确认。
     */
    private void onEvent(EslMessage event) {
        if (!"BACKGROUND_JOB".equals(event.eventName())) {
            return;
        }
        String jobId = event.get("Job-UUID");
        String result = event.body().strip();
        if (jobId == null || result.startsWith("+OK")) {
            return;
        }
        pending.fail(jobId, result.startsWith("-ERR") ? result.substring(4).strip() : result);
    }

    private static String safeEcho(String raw) {
        if (raw == null) {
            return "(空)";
        }
        String cleaned = raw.replaceAll("[\\r\\n]", "\\\\n");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) + "…" : cleaned;
    }

    /** 见类注释 */
    @Override
    public boolean ignoreEarlyMedia() {
        return true;
    }

    public boolean isReady() {
        return client.isConnected();
    }
}
