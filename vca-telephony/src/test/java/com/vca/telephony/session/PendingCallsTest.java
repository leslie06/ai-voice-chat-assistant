package com.vca.telephony.session;

import com.vca.telephony.spi.CallEvent;
import com.vca.telephony.spi.CallLeg;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 外呼接线台: 把 AMI 发起的呼叫和 AudioSocket 连进来的媒体按 id 对上。 */
class PendingCallsTest {

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

    /** 媒体连进来 → 唤醒发起方, 并把号码回填(AudioSocket 自己拿不到号码) */
    @Test
    void attachMatchesMediaToOriginateAndBackfillsNumber() {
        PendingCalls calls = new PendingCalls();
        StubLeg leg = new StubLeg("call-1");

        Mono<CallLeg> waiting = calls.register("call-1", "13800138000", Duration.ofSeconds(5));

        StepVerifier.create(waiting)
                .then(() -> assertThat(calls.attach(leg)).isTrue())
                .expectNext(leg)
                .verifyComplete();
        assertThat(leg.peerNumber()).isEqualTo("13800138000");
    }

    /** 没人在等的连接不是错误 —— 那是呼入, 照常建会话 */
    @Test
    void unmatchedLegIsInboundNotAnError() {
        PendingCalls calls = new PendingCalls();

        assertThat(calls.attach(new StubLeg("someone-called-in"))).isFalse();
    }

    /** 空号/关机/拒接要立刻报错, 不能让批量外呼干等到超时 */
    @Test
    void failWakesCallerImmediately() {
        PendingCalls calls = new PendingCalls();

        Mono<CallLeg> waiting = calls.register("call-2", "13800138000", Duration.ofSeconds(30));

        StepVerifier.create(waiting)
                .then(() -> calls.fail("call-2", "空号"))
                .verifyErrorMessage("外呼失败: 空号");
    }

    @Test
    void timesOutAndCleansUp() {
        PendingCalls calls = new PendingCalls();

        StepVerifier.create(calls.register("call-3", "138", Duration.ofMillis(80)))
                .verifyErrorSatisfies(e -> assertThat(e).hasMessageContaining("外呼等待媒体超时"));

        assertThat(calls.pendingCount()).isZero();
    }

    /** 已接通之后迟到的失败通知不该炸 */
    @Test
    void lateFailAfterAttachIsIgnored() {
        PendingCalls calls = new PendingCalls();
        StubLeg leg = new StubLeg("call-4");

        StepVerifier.create(calls.register("call-4", "138", Duration.ofSeconds(5)))
                .then(() -> calls.attach(leg))
                .expectNext(leg)
                .verifyComplete();

        calls.fail("call-4", "迟到的失败");   // 不抛异常即可
        assertThat(calls.pendingCount()).isZero();
    }
}
