package com.vca.orchestrator;

import com.vca.domain.enums.AudioFormat;
import com.vca.domain.enums.SessionState;
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
import com.vca.domain.spi.S2sProvider;
import com.vca.domain.spi.TtsProvider;
import com.vca.orchestrator.pipeline.SentenceSplitter;
import com.vca.orchestrator.session.ConversationSession;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationSessionTest {

    // ---- 假厂商实现(只依赖 domain SPI, 验证编排逻辑) ----

    /** 固定返回一个 partial + 一个 final 的 ASR */
    private static AsrProvider fakeAsr(String finalText) {
        return new AsrProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.ALIYUN;
            }

            @Override
            public Flux<AsrEvent> transcribe(Flux<AudioFrame> audio, AsrConfig cfg) {
                return Flux.just(
                        AsrEvent.partial(finalText.substring(0, 1), 50),
                        AsrEvent.finalResult(finalText, 200, 0.95));
            }
        };
    }

    /** 把整段回复按字符逐个吐出, 模拟 LLM token 流 */
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

    /** 每个句子片段映射成一个携带文本的音频块; delayPerChunk>0 时模拟逐块播放耗时 */
    private static TtsProvider fakeTts(Duration delayPerChunk) {
        return new TtsProvider() {
            final AtomicLong seq = new AtomicLong();

            @Override
            public VendorType vendor() {
                return VendorType.ALIYUN;
            }

            @Override
            public Flux<AudioChunk> synthesize(Flux<String> textSegments, TtsConfig cfg) {
                Flux<AudioChunk> out = textSegments.map(seg -> new AudioChunk(
                        seg.getBytes(StandardCharsets.UTF_8), AudioFormat.MP3,
                        seq.getAndIncrement(), seg, false));
                return delayPerChunk.isZero() ? out : out.delayElements(delayPerChunk);
            }
        };
    }

    private static ConversationSession pipelineSession(AsrProvider asr, LlmProvider llm, TtsProvider tts) {
        SessionContext ctx = SessionContext.pipeline(
                "s-1", "u-1",
                AsrConfig.defaults(VendorType.ALIYUN),
                LlmConfig.defaults(VendorType.DEEPSEEK, "deepseek-chat"),
                TtsConfig.defaults(VendorType.ALIYUN, "longxiaochun"));
        return new ConversationSession(ctx, asr, llm, tts, null, new SentenceSplitter());
    }

    private static Flux<AudioFrame> dummyAudio() {
        return Flux.just(AudioFrame.of(new byte[]{1, 2}, 0, 0), AudioFrame.endOfSpeech(1, 10));
    }

    @Test
    void pipelineProducesPerSentenceAudioAndRecordsHistory() {
        ConversationSession session = pipelineSession(
                fakeAsr("今天天气怎么样"),
                fakeLlm("今天天气不错，适合出去散步。你想去哪？"),
                fakeTts(Duration.ZERO));

        // 句子级流水线: 两句 → 两个音频块, 文本随块带出
        StepVerifier.create(session.handleUserTurn(dummyAudio()))
                .expectNextMatches(c -> c.text().equals("今天天气不错，适合出去散步。"))
                .expectNextMatches(c -> c.text().equals("你想去哪？"))
                .verifyComplete();

        // 回合结束应回到 IDLE
        assertThat(session.state()).isEqualTo(SessionState.IDLE);

        // 历史: system + user + assistant(完整回复)
        List<Message> h = session.historyView();
        assertThat(h).hasSize(3);
        assertThat(h.get(0).role()).isEqualTo(Message.Role.SYSTEM);
        assertThat(h.get(1)).isEqualTo(Message.user("今天天气怎么样"));
        assertThat(h.get(2).role()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(h.get(2).content()).isEqualTo("今天天气不错，适合出去散步。你想去哪？");
    }

    @Test
    void bargeInCancelsTurnMidStream() {
        ConversationSession session = pipelineSession(
                fakeAsr("讲个长故事"),
                fakeLlm("第一句。第二句。第三句。第四句。第五句。"),
                fakeTts(Duration.ofMillis(80)));  // 逐块延迟, 给打断留出时机

        // 收到第一句后立即打断, 整条流应被取消并完成
        StepVerifier.create(session.handleUserTurn(dummyAudio()))
                .expectNextCount(1)
                .then(session::bargeIn)
                .verifyComplete();

        awaitState(session, SessionState.IDLE, Duration.ofSeconds(1));
        assertThat(session.state()).isEqualTo(SessionState.IDLE);

        // 被打断的回合不应把(不完整的)assistant 回复写进历史
        List<Message> h = session.historyView();
        assertThat(h).hasSize(2); // system + user
        assertThat(h.get(1).role()).isEqualTo(Message.Role.USER);
    }

    private static void awaitState(ConversationSession session, SessionState target, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (session.state() != target && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
