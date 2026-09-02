package com.vca.orchestrator;

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
import com.vca.orchestrator.metrics.TurnMetrics;
import com.vca.orchestrator.pipeline.SentenceSplitter;
import com.vca.orchestrator.session.ConversationSession;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 体感延迟埋点。要钉住的是一个曾经被漏掉的事实: <b>用户感受到的延迟从他闭嘴那一刻起算</b>,
 * 而句尾判停要先等近 1 秒静音才轮到后端动手 —— 从 ASR final 起表的那几个内部指标全都不含这段,
 * 看板会系统性低估约 1~1.5 秒, 于是最该砍的那一段反而没人看见。
 */
class PerceivedLatencyTest {

    private static AsrProvider fakeAsr() {
        return new AsrProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.ALIYUN;
            }

            @Override
            public Flux<AsrEvent> transcribe(Flux<AudioFrame> audio, AsrConfig cfg) {
                return Flux.just(AsrEvent.finalResult("你好", 100, 0.95));
            }
        };
    }

    /** 一轮语音输入(VAD 判停后由接入层喂进来的帧)。 */
    private static Flux<AudioFrame> voiceTurn() {
        return Flux.just(AudioFrame.of(new byte[]{1, 2}, 0, 0), AudioFrame.endOfSpeech(1, 10));
    }

    private static LlmProvider fixedLlm() {
        return new LlmProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.DEEPSEEK;
            }

            @Override
            public Flux<String> chatStream(List<Message> history, LlmConfig cfg) {
                return Flux.just("好", "的", "。");
            }
        };
    }

    private static TtsProvider echoTts() {
        return new TtsProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.ALIYUN;
            }

            @Override
            public Flux<AudioChunk> synthesize(Flux<String> textSegments, TtsConfig cfg) {
                return textSegments.map(seg -> new AudioChunk(
                        seg.getBytes(StandardCharsets.UTF_8), AudioFormat.PCM, 0, seg, false));
            }
        };
    }

    private static ConversationSession session(TurnMetrics metrics) {
        SessionContext ctx = SessionContext.pipeline(
                "s-lat", "u-1",
                AsrConfig.defaults(VendorType.ALIYUN),
                LlmConfig.defaults(VendorType.DEEPSEEK, "deepseek-chat"),
                TtsConfig.defaults(VendorType.ALIYUN, "longxiaochun"));
        return new ConversationSession(ctx, fakeAsr(), fixedLlm(), echoTts(), null,
                new SentenceSplitter(), 16, metrics);
    }

    private static double totalMs(MeterRegistry reg, String name) {
        return reg.find(name).timer() == null ? -1 : reg.find(name).timer().totalTime(TimeUnit.MILLISECONDS);
    }

    private static long count(MeterRegistry reg, String name) {
        return reg.find(name).timer() == null ? 0 : reg.find(name).timer().count();
    }

    @Test
    void perceivedLatencyIncludesTheEndpointWaitThatInternalMetricsMiss() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ConversationSession session = session(new TurnMetrics(reg));

        // 模拟: 用户 1.2 秒前就闭嘴了, VAD 等满静音才判停, 现在才轮到后端
        long endedAt = System.currentTimeMillis() - 1200;
        session.markUserSpeechEnd(endedAt, 900, "neutral");

        session.handleUserTurn(voiceTurn()).collectList().block();

        double perceived = totalMs(reg, "vca.turn.perceived.first_audio");
        double internal = totalMs(reg, "vca.turn.tts.first_audio");
        assertThat(count(reg, "vca.turn.perceived.first_audio")).isEqualTo(1);
        // 体感必须把那 1.2 秒算进去; 内部指标则完全不含 —— 两者的差就是看板此前的盲区
        assertThat(perceived).isGreaterThanOrEqualTo(1200);
        assertThat(perceived - internal).isGreaterThanOrEqualTo(1100);

        // 判停时长与依据也要落到指标上, 才能判断语义端点判定是否真在起作用
        assertThat(totalMs(reg, "vca.turn.endpoint.silence")).isEqualTo(900);
        assertThat(reg.find("vca.turn.endpoint.reason").tag("reason", "neutral").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void textTurnHasNoPerceivedLatency() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ConversationSession session = session(new TurnMetrics(reg));

        session.handleTextTurn("你好").collectList().block();   // 打字: 没有"停止说话"这一刻, 也不产音频

        assertThat(count(reg, "vca.turn.perceived.first_audio")).isZero();
    }

    @Test
    void oneSpeechEndIsCountedAtMostOnce() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ConversationSession session = session(new TurnMetrics(reg));

        session.markUserSpeechEnd(System.currentTimeMillis() - 500, 500, "complete");
        session.handleUserTurn(voiceTurn()).collectList().block();
        session.handleUserTurn(voiceTurn()).collectList().block();   // 没有新的闭嘴时刻

        // 取走即清零: 同一次闭嘴不能被后续回合重复计入, 否则延迟看起来越来越长
        assertThat(count(reg, "vca.turn.perceived.first_audio")).isEqualTo(1);
    }
}
