package com.vca.telephony.session;

import com.vca.telephony.spi.CallLeg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外呼的"接线台": 把 <b>AMI 发起的呼叫</b> 和 <b>AudioSocket 连进来的媒体</b> 两条独立的路对上。
 *
 * <p>为什么需要这一层: 外呼是两条毫不相干的通道 ——
 * <pre>
 *   ① 我们经 AMI 让 Asterisk 拨号            (出方向, TCP 连 Asterisk)
 *   ② Asterisk 接通后回连 AudioSocket 送媒体  (入方向, Asterisk 连我们)
 * </pre>
 * 两条路唯一的共同信息是我们自己生成的那个 id: 它既当 AMI 的 {@code ActionID}, 又经
 * {@code Variable: CALLUUID=<id>} 传进 dialplan 成为 AudioSocket 的 UUID。媒体连进来时按这个 id
 * 一查, 就知道它属于哪一次外呼, 并把被叫号码回填进去(AudioSocket 自己拿不到号码)。
 *
 * <p>没配上的连接不是错误 —— 那是<b>呼入</b>, 照常建会话即可。
 */
public final class PendingCalls {

    private static final Logger log = LoggerFactory.getLogger(PendingCalls.class);

    private record Pending(Sinks.One<CallLeg> sink, String peerNumber) {
    }

    private final Map<String, Pending> waiting = new ConcurrentHashMap<>();

    /**
     * 登记一次外呼, 等媒体连进来。
     *
     * <p><b>必须先登记再发 Originate</b>: 反过来的话, 快线路上媒体可能比我们登记还早连进来, 那一路
     * 就会被当成呼入, 发起方则一直等到超时。
     *
     * @param timeout 等待上限(振铃 + 接通); 超时自动清理并以 error 结束
     */
    public Mono<CallLeg> register(String callId, String peerNumber, Duration timeout) {
        Sinks.One<CallLeg> sink = Sinks.one();
        waiting.put(callId, new Pending(sink, peerNumber));
        return sink.asMono()
                .timeout(timeout, Mono.error(() ->
                        new IllegalStateException("外呼等待媒体超时(" + timeout.toSeconds() + "s): " + callId)))
                .doFinally(sig -> waiting.remove(callId));
    }

    /**
     * 一路媒体连进来了。命中则回填号码并唤醒发起方。
     *
     * @return true = 这是外呼的媒体; false = 没人在等, 即呼入
     */
    public boolean attach(CallLeg leg) {
        Pending pending = waiting.remove(leg.callId());
        if (pending == null) {
            return false;
        }
        leg.attachPeerNumber(pending.peerNumber());
        pending.sink().tryEmitValue(leg);
        log.info("外呼接通: {} → {}", pending.peerNumber(), leg.callId());
        return true;
    }

    /** 呼叫失败(空号/关机/拒接/无人接听): 立刻让发起方拿到错误, 不必干等到超时。 */
    public void fail(String callId, String reason) {
        Pending pending = waiting.remove(callId);
        if (pending == null) {
            return;   // 已接通或已超时, 迟到的失败通知忽略
        }
        log.info("外呼失败: {} → {} ({})", pending.peerNumber(), callId, reason);
        pending.sink().tryEmitError(new IllegalStateException("外呼失败: " + reason));
    }

    /** 当前在等媒体的外呼数(诊断/并发控制用) */
    public int pendingCount() {
        return waiting.size();
    }
}
