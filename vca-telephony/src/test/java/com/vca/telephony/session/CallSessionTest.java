package com.vca.telephony.session;

import com.vca.domain.enums.AudioFormat;
import com.vca.domain.enums.VendorType;
import com.vca.domain.model.AsrConfig;
import com.vca.domain.model.AsrEvent;
import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.AudioFrame;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.Message;
import com.vca.domain.model.SessionContext;
import com.vca.domain.model.TtsConfig;
import com.vca.domain.spi.AsrProvider;
import com.vca.domain.spi.LlmProvider;
import com.vca.domain.spi.TtsProvider;
import com.vca.orchestrator.pipeline.SentenceSplitter;
import com.vca.orchestrator.session.ConversationSession;
import com.vca.orchestrator.vad.EnergyVad;
import com.vca.orchestrator.vad.VadConfig;
import com.vca.telephony.spi.CallEvent;
import com.vca.telephony.spi.CallLeg;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 电话链路的端到端编排(不含真实 FreeSWITCH): 用内存 {@link CallLeg} + 假厂商, 验证
 * 接通→开场白→实时节流出声→客户开口成轮→打断→回到聆听 这条闭环。
 *
 * <p>驱动方式: 用 {@link CallSession#attach()} 而非 {@code start()}, 自己手动 {@link CallSession#tick()},
 * 因此完全确定性, 不依赖 20ms 定时器。
 */
class CallSessionTest {

    private static final int MEDIA_RATE = 8000;
    private static final int TTS_RATE = 24000;
    /** 20ms @8k = 160 采样 = 320 字节, 与 RTP 包长一致 */
    private static final int FRAME_BYTES = 320;

    // ---- 内存 CallLeg ----

    private static final class FakeCallLeg implements CallLeg {
        final Sinks.Many<byte[]> inbound = Sinks.many().multicast().onBackpressureBuffer();
        final Sinks.Many<CallEvent> events = Sinks.many().multicast().onBackpressureBuffer();
        final List<byte[]> written = Collections.synchronizedList(new ArrayList<>());
        volatile String hangupReason;
        volatile boolean continuousMedia;

        @Override
        public boolean needsContinuousMedia() {
            return continuousMedia;
        }

        @Override
        public String callId() {
            return "call-1";
        }

        @Override
        public String peerNumber() {
            return "13800000000";
        }

        @Override
        public Flux<byte[]> inboundAudio() {
            return inbound.asFlux();
        }

        @Override
        public void writeAudio(byte[] pcm) {
            written.add(pcm);
        }

        @Override
        public Flux<CallEvent> events() {
            return events.asFlux();
        }

        @Override
        public void hangup(String reason) {
            hangupReason = reason;
        }
    }

    // ---- 假厂商 ----

    /** 真正等上行音频流结束才出 final —— 这样回合边界由 VAD 决定, 和线上一致 */
    private static AsrProvider fakeAsr(String text, AtomicInteger turns) {
        return new AsrProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.ALIYUN;
            }

            @Override
            public Flux<AsrEvent> transcribe(Flux<AudioFrame> audio, AsrConfig cfg) {
                turns.incrementAndGet();
                return audio.thenMany(Flux.just(AsrEvent.finalResult(text, 200, 0.95)));
            }
        };
    }

    private static LlmProvider fakeLlm(String reply) {
        return new LlmProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.DEEPSEEK;
            }

            @Override
            public Flux<String> chatStream(List<Message> history, LlmConfig cfg) {
                return Flux.fromArray(reply.split(""));
            }
        };
    }

    /** 每句合成 1 秒 24k PCM —— 要的是真实字节数, 好验证降采样与节流 */
    private static TtsProvider fakeTts() {
        return new TtsProvider() {
            final AtomicLong seq = new AtomicLong();

            @Override
            public VendorType vendor() {
                return VendorType.ALIYUN;
            }

            @Override
            public Flux<AudioChunk> synthesize(Flux<String> textSegments, TtsConfig cfg) {
                return textSegments.map(seg -> new AudioChunk(
                        new byte[TTS_RATE * 2], AudioFormat.PCM, seq.getAndIncrement(), seg, false));
            }
        };
    }

    private static ConversationSession conversation(AsrProvider asr) {
        SessionContext ctx = SessionContext.pipeline(
                "call-1", null,
                AsrConfig.defaults(VendorType.ALIYUN),
                LlmConfig.defaults(VendorType.DEEPSEEK, "deepseek-chat"),
                TtsConfig.defaults(VendorType.ALIYUN, "longxiaochun"));
        return new ConversationSession(ctx, asr, fakeLlm("您好，这边是贷款咨询。"), fakeTts(),
                null, new SentenceSplitter());
    }

    private static CallSession callSession(FakeCallLeg leg, AtomicInteger turns, byte[] greeting) {
        CallConfig cfg = new CallConfig(20, 30_000, TTS_RATE, 300, true);
        CallSession call = new CallSession(leg, conversation(fakeAsr("我想了解一下", turns)),
                VadConfig.defaults(), new EnergyVad(), cfg, greeting);
        call.attach();
        return call;
    }

    // ---- 用例 ----

    /** 早期媒体(彩铃)期间不能进对话: 人还没接, 跑 ASR/LLM/TTS 是纯烧钱 */
    @Test
    void earlyMediaDoesNotStartConversation() {
        FakeCallLeg leg = new FakeCallLeg();
        AtomicInteger turns = new AtomicInteger();
        CallSession call = callSession(leg, turns, null);

        leg.events.tryEmitNext(CallEvent.of(CallEvent.Type.EARLY_MEDIA));
        for (int i = 0; i < 40; i++) {
            leg.inbound.tryEmitNext(speech());   // 彩铃音乐, 电平很高
        }

        assertThat(call.isAnswered()).isFalse();
        assertThat(turns.get()).isZero();   // 一次 ASR 都不该起
    }

    /** 接通即出声: 开场白是预合成的, 直接进缓冲, 不走 LLM+TTS */
    @Test
    void greetingIsPlayedImmediatelyOnAnswerAndPacedIntoFrames() {
        FakeCallLeg leg = new FakeCallLeg();
        byte[] greeting = new byte[1000];   // 不是帧长整数倍, 用来验尾帧补齐
        CallSession call = callSession(leg, new AtomicInteger(), greeting);

        leg.events.tryEmitNext(CallEvent.of(CallEvent.Type.ANSWERED));
        assertThat(call.isAnswered()).isTrue();
        assertThat(call.pendingPlaybackMs()).isGreaterThan(0);

        for (int i = 0; i < 10; i++) {
            call.tick();
        }

        // 1000 字节 → 4 帧(最后一帧补静音), 之后缓冲空, 不再往线路写任何东西
        assertThat(leg.written).hasSize(4);
        assertThat(leg.written).allSatisfy(f -> assertThat(f).hasSize(FRAME_BYTES));
        assertThat(call.pendingPlaybackMs()).isZero();
    }

    /**
     * 需要连续媒体的接入层(FreeSWITCH): 开场白播完后每拍补一帧静音, 线路上 RTP 不断流;
     * 接通之前一帧都不写, 补的静音也不能让"机器人还在说话"的判断变成 true(否则打断窗口永远关不上)。
     */
    @Test
    void continuousMediaLegGetsSilenceWhenIdleButOnlyAfterAnswer() {
        FakeCallLeg leg = new FakeCallLeg();
        leg.continuousMedia = true;
        CallSession call = callSession(leg, new AtomicInteger(), new byte[640]);   // 2 帧开场白

        for (int i = 0; i < 5; i++) {
            call.tick();
        }
        assertThat(leg.written).isEmpty();   // 未接通

        leg.events.tryEmitNext(CallEvent.of(CallEvent.Type.ANSWERED));
        for (int i = 0; i < 10; i++) {
            call.tick();
        }

        assertThat(leg.written).hasSize(10);   // 2 帧开场白 + 8 帧静音, 一拍不落
        assertThat(leg.written).allSatisfy(f -> assertThat(f).hasSize(FRAME_BYTES));
        assertThat(leg.written.subList(2, 10)).allSatisfy(f -> assertThat(f).containsOnly(0));
        assertThat(call.pendingPlaybackMs()).isZero();
    }

    /**
     * end_call 工具置位后, 必须等缓冲里的告别语播完才挂 —— 直接挂客户只能听到半句。
     */
    @Test
    void hangupAfterPlaybackWaitsForTheFarewellToFinish() {
        FakeCallLeg leg = new FakeCallLeg();
        CallSession call = callSession(leg, new AtomicInteger(), new byte[640]);   // 2 帧告别语
        leg.events.tryEmitNext(CallEvent.of(CallEvent.Type.ANSWERED));

        call.hangupAfterPlayback();

        call.tick();                     // 第 1 帧
        assertThat(call.isClosed()).isFalse();
        call.tick();                     // 第 2 帧, 播完
        assertThat(call.isClosed()).isFalse();
        call.tick();                     // 缓冲已空 → 这时才挂
        assertThat(call.isClosed()).isTrue();
        assertThat(leg.hangupReason).isEqualTo("agent-ended");
        assertThat(leg.written).hasSize(2);
    }

    /**
     * 回归: 工具是在回合<b>进行中</b>置的位(告别语还没合成), 此刻缓冲本来就是空的。
     * 只看"缓冲空了"就挂, 客户一个字都听不到 —— 必须等本轮产完。
     */
    @Test
    void hangupAfterPlaybackDoesNotFireWhileTheTurnIsStillProducing() {
        FakeCallLeg leg = new FakeCallLeg();
        AtomicInteger turns = new AtomicInteger();
        CallSession call = callSession(leg, turns, null);
        leg.events.tryEmitNext(CallEvent.of(CallEvent.Type.ANSWERED));

        speakThenPause(leg);                      // 起一轮: 此时回合还在产音频
        call.hangupAfterPlayback();               // 工具在回合中途置位
        for (int i = 0; i < 3; i++) {
            call.tick();
        }
        assertThat(call.isClosed()).as("回合还没产完, 不能挂").isFalse();

        // 等这一轮把音频产完并播完
        awaitUntil(() -> call.pendingPlaybackMs() > 0, Duration.ofSeconds(3));
        for (int i = 0; i < 400 && !call.isClosed(); i++) {
            call.tick();
        }
        assertThat(call.isClosed()).isTrue();
        assertThat(leg.hangupReason).isEqualTo("agent-ended");
        assertThat(leg.written).isNotEmpty();     // 告别语确实播出去了
    }

    /** 客户开口 → 成一轮 → 回复经降采样进缓冲, 且只按实时节奏出去 */
    @Test
    void customerSpeechDrivesTurnAndReplyIsPaced() {
        FakeCallLeg leg = new FakeCallLeg();
        AtomicInteger turns = new AtomicInteger();
        CallSession call = callSession(leg, turns, null);
        leg.events.tryEmitNext(CallEvent.of(CallEvent.Type.ANSWERED));

        speakThenPause(leg);

        assertThat(turns.get()).isEqualTo(1);
        awaitUntil(() -> call.pendingPlaybackMs() > 0, Duration.ofSeconds(2));

        // TTS 出的是 24k, 线路是 8k: 1 秒 24k PCM(48000 字节) 应降成 1 秒 8k(16000 字节)
        assertThat(call.pendingPlaybackMs()).isEqualTo(1000);

        int before = leg.written.size();
        call.tick();
        // 一拍只出一帧 —— 绝不能因为缓冲里有 1 秒就一次性灌出去
        assertThat(leg.written).hasSize(before + 1);
        assertThat(leg.written.get(before)).hasSize(FRAME_BYTES);
    }

    /**
     * 打断窗口必须开到"真的播完"为止。
     *
     * <p>这条是回归测试: 回合流已经产完(后端不再有音频产出)但缓冲里还压着几秒没播,
     * 这段时间如果把 VAD 放回 AWAIT, 客户插话就会被当成新一轮而不是打断 —— 那正是
     * 浏览器版踩过的"说话打不断"的坑。电话版靠缓冲是否排空来判定, 不存在估算误差。
     */
    @Test
    void bargeInWorksWhileBufferedAudioStillPlaying() {
        FakeCallLeg leg = new FakeCallLeg();
        AtomicInteger turns = new AtomicInteger();
        CallSession call = callSession(leg, turns, null);
        leg.events.tryEmitNext(CallEvent.of(CallEvent.Type.ANSWERED));

        speakThenPause(leg);
        awaitUntil(() -> call.pendingPlaybackMs() > 0, Duration.ofSeconds(2));
        assertThat(call.pendingPlaybackMs()).isEqualTo(1000);   // 还有整整 1 秒没播

        // 客户在机器人说话中途插话(默认 bargeMs=250ms → 13 帧足够)
        for (int i = 0; i < 20; i++) {
            leg.inbound.tryEmitNext(speech());
        }

        // 打断 = 缓冲清空, 立刻停声
        assertThat(call.pendingPlaybackMs()).isZero();
        // 且这次插话被当作新一轮的开头, 不是被丢掉
        assertThat(turns.get()).isEqualTo(2);
    }

    /** 缓冲排空后才回到"等你开口": 之后再开口是新一轮, 不是打断 */
    @Test
    void returnsToListeningOnlyAfterBufferDrains() {
        FakeCallLeg leg = new FakeCallLeg();
        AtomicInteger turns = new AtomicInteger();
        CallSession call = callSession(leg, turns, null);
        leg.events.tryEmitNext(CallEvent.of(CallEvent.Type.ANSWERED));

        speakThenPause(leg);
        awaitUntil(() -> call.pendingPlaybackMs() > 0, Duration.ofSeconds(2));

        // 把 1 秒回复完整播完(50 帧), 再多 tick 一拍触发 resumeListening
        for (int i = 0; i < 60; i++) {
            call.tick();
        }
        assertThat(call.pendingPlaybackMs()).isZero();

        // 已回到 AWAIT: 再开口应当开启第二轮
        speakThenPause(leg);
        assertThat(turns.get()).isEqualTo(2);
    }

    @Test
    void hangupClosesSessionAndReleasesLeg() {
        FakeCallLeg leg = new FakeCallLeg();
        CallSession call = callSession(leg, new AtomicInteger(), null);
        leg.events.tryEmitNext(CallEvent.of(CallEvent.Type.ANSWERED));

        leg.events.tryEmitNext(CallEvent.hangup("peer-hangup"));

        assertThat(call.isClosed()).isTrue();
        assertThat(leg.hangupReason).isEqualTo("peer-hangup");
        // 关闭后节流器不再写线路
        int written = leg.written.size();
        call.tick();
        assertThat(leg.written).hasSize(written);
    }

    // ---- 工具 ----

    /** 说 160ms(过 onsetMs=150) + 静 800ms(过 silenceMs) → VAD 判定一轮说完 */
    private static void speakThenPause(FakeCallLeg leg) {
        for (int i = 0; i < 10; i++) {
            leg.inbound.tryEmitNext(speech());
        }
        for (int i = 0; i < 45; i++) {
            leg.inbound.tryEmitNext(silence());
        }
    }

    /** 20ms @8k 的 200Hz 方波, RMS 远高于 speechThreshold(0.015) */
    private static byte[] speech() {
        byte[] pcm = new byte[FRAME_BYTES];
        for (int i = 0; i < FRAME_BYTES / 2; i++) {
            short v = (short) ((i / 20) % 2 == 0 ? 6000 : -6000);
            pcm[2 * i] = (byte) (v & 0xff);
            pcm[2 * i + 1] = (byte) ((v >> 8) & 0xff);
        }
        return pcm;
    }

    private static byte[] silence() {
        return new byte[FRAME_BYTES];
    }

    private static void awaitUntil(BooleanSupplier cond, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("等待条件超时: " + timeout);
    }
}
