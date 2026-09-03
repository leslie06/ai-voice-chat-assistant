package com.vca.orchestrator;

import com.vca.domain.enums.VendorType;
import com.vca.domain.model.AsrConfig;
import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.AudioFrame;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.LlmEvent;
import com.vca.domain.model.Message;
import com.vca.domain.model.S2sConfig;
import com.vca.domain.model.S2sEvent;
import com.vca.domain.model.SessionContext;
import com.vca.domain.model.ToolSpec;
import com.vca.domain.model.TtsConfig;
import com.vca.domain.spi.LlmProvider;
import com.vca.domain.spi.S2sProvider;
import com.vca.domain.spi.S2sSession;
import com.vca.orchestrator.metrics.TurnMetrics;
import com.vca.orchestrator.pipeline.SentenceSplitter;
import com.vca.orchestrator.recorder.ConversationRecorder;
import com.vca.orchestrator.recorder.TurnRecord;
import com.vca.orchestrator.session.ConversationSession;
import com.vca.orchestrator.session.S2sLiveSession;
import com.vca.orchestrator.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 打断后的上下文完整性。
 *
 * <p>要守住的性质只有一条: <b>用户听到过的话, 模型必须知道自己说过, 并且知道那句没说完</b>。
 * 少了前半句, 用户说"你刚说的那个"模型接不上; 少了后半句, 模型以为已经完整表达过, 不会重述。
 *
 * <p>之所以容易漏: 正常收尾走 {@code doOnComplete}, 而打断走的是 {@code takeUntilOther} 的 cancel,
 * 两条路径完全不重合 —— 只挂 onComplete 的实现在正常测试里全绿, 一被打断就丢内容。
 */
class InterruptedHistoryTest {

    private static final String MARK = "被用户打断";

    /** 收集落库记录的假 recorder。 */
    private static final class CapturingRecorder implements ConversationRecorder {
        final List<TurnRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void recordTurn(TurnRecord record) {
            records.add(record);
        }
    }

    /**
     * 逐次可编排的假 LLM: 第 n 次调用返回 scripts[n]。同时记下每次拿到的历史,
     * 用来验证"下一轮模型确实看到了上一轮被打断的那句"。
     */
    private static final class ScriptedLlm implements LlmProvider {
        final List<List<Message>> seenHistories = new CopyOnWriteArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final List<Flux<LlmEvent>> scripts;

        @SafeVarargs
        ScriptedLlm(Flux<LlmEvent>... scripts) {
            this.scripts = List.of(scripts);
        }

        @Override
        public VendorType vendor() {
            return VendorType.DEEPSEEK;
        }

        @Override
        public Flux<String> chatStream(List<Message> history, LlmConfig cfg) {
            return Flux.empty();
        }

        @Override
        public Flux<LlmEvent> chat(List<Message> history, LlmConfig cfg, List<ToolSpec> tools) {
            seenHistories.add(List.copyOf(history));
            int i = calls.getAndIncrement();
            return i < scripts.size() ? scripts.get(i) : Flux.empty();
        }
    }

    /** 吐完给定文本就<b>挂住不结束</b>的脚本 —— 模拟"话说到一半, 模型还在生成"。 */
    private static Flux<LlmEvent> speaksThenHangs(String text) {
        return Flux.<LlmEvent>just(new LlmEvent.TextDelta(text)).concatWith(Flux.never());
    }

    private static Flux<LlmEvent> speaks(String text) {
        return Flux.just(new LlmEvent.TextDelta(text));
    }

    private static ConversationSession pipelineSession(LlmProvider llm) {
        SessionContext ctx = SessionContext.pipeline(
                "s-int", "u-1",
                AsrConfig.defaults(VendorType.ALIYUN),
                LlmConfig.defaults(VendorType.DEEPSEEK, "deepseek-chat"),
                TtsConfig.defaults(VendorType.ALIYUN, "v"));
        return new ConversationSession(ctx, null, llm, null, null,
                new SentenceSplitter(), 16, TurnMetrics.noop(), SkillRegistry.empty());
    }

    /** 历史里最后一条 assistant 消息。 */
    private static String lastAssistant(ConversationSession s) {
        return s.historyView().stream()
                .filter(m -> m.role() == Message.Role.ASSISTANT)
                .reduce((a, b) -> b)
                .map(Message::content)
                .orElse(null);
    }

    // ---- 三段式 ----

    @Test
    void interruptedPipelineTurnKeepsWhatWasSaidAndMarksItCutOff() {
        ScriptedLlm llm = new ScriptedLlm(speaksThenHangs("从前有座山，山里有座庙"));
        ConversationSession s = pipelineSession(llm);

        Disposable turn = s.handleTextTurn("讲个故事").subscribe();
        s.bargeIn();   // 说到一半用户插话
        turn.dispose();

        String said = lastAssistant(s);
        assertThat(said).isNotNull();
        assertThat(said).startsWith("从前有座山，山里有座庙");   // 说过的部分一个字不能少
        assertThat(said).contains(MARK);                        // 且必须标出"没说完"
    }

    @Test
    void completedTurnIsNotMarkedAsInterrupted() {
        ConversationSession s = pipelineSession(new ScriptedLlm(speaks("好的")));

        StepVerifier.create(s.handleTextTurn("在吗")).verifyComplete();

        assertThat(lastAssistant(s)).isEqualTo("好的");   // 正常收尾: 原样, 不带任何标记
    }

    @Test
    void nextTurnActuallySeesTheInterruptedMessage() {
        // 这条才是整件事的目的: 标记要真的进到下一轮喂给模型的历史里, 只落库不进上下文等于没做
        ScriptedLlm llm = new ScriptedLlm(speaksThenHangs("北京今天多云转晴，气温"), speaks("18 度。"));
        ConversationSession s = pipelineSession(llm);

        Disposable turn = s.handleTextTurn("北京天气").subscribe();
        s.bargeIn();
        turn.dispose();

        StepVerifier.create(s.handleTextTurn("多少度来着")).verifyComplete();

        assertThat(llm.seenHistories).hasSize(2);
        List<Message> secondTurn = llm.seenHistories.get(1);
        assertThat(secondTurn).anyMatch(m -> m.role() == Message.Role.ASSISTANT
                && m.content() != null
                && m.content().contains("北京今天多云转晴，气温")
                && m.content().contains(MARK));
    }

    @Test
    void archivedTurnKeepsRawTextWithoutTheMarker() {
        // 落库是语料(将来要拿去做评测/微调), 不该混进这条给模型看的注释
        ScriptedLlm llm = new ScriptedLlm(speaksThenHangs("我觉得这个方案"));
        ConversationSession s = pipelineSession(llm);
        CapturingRecorder rec = new CapturingRecorder();
        s.setRecorder(rec);

        Disposable turn = s.handleTextTurn("你怎么看").subscribe();
        s.bargeIn();
        turn.dispose();

        assertThat(rec.records).hasSize(1);
        TurnRecord r = rec.records.get(0);
        assertThat(r.outcome()).isEqualTo("interrupted");
        assertThat(r.assistantText()).isEqualTo("我觉得这个方案");
        assertThat(r.assistantText()).doesNotContain(MARK);
    }

    @Test
    void partialTextDoesNotLeakIntoTheNextTurn() {
        // 累计器是会话级的, 忘记清就会把上一轮被打断的半句拼到下一轮回复前面
        ScriptedLlm llm = new ScriptedLlm(speaksThenHangs("上一轮的半句"), speaks("下一轮的完整回复"));
        ConversationSession s = pipelineSession(llm);

        Disposable turn = s.handleTextTurn("第一句").subscribe();
        s.bargeIn();
        turn.dispose();

        StepVerifier.create(s.handleTextTurn("第二句")).verifyComplete();

        assertThat(lastAssistant(s)).isEqualTo("下一轮的完整回复");
    }

    // ---- 持久 S2S(全双工) ----

    private static S2sProvider fakeS2s(Flux<S2sEvent> script) {
        return new S2sProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.QWEN;
            }

            @Override
            public Flux<AudioChunk> converse(Flux<AudioFrame> audio, List<Message> history, S2sConfig cfg) {
                return Flux.empty();
            }

            @Override
            public S2sSession open(List<Message> history, S2sConfig cfg) {
                return new S2sSession() {
                    @Override
                    public void pushAudio(AudioFrame frame) {
                    }

                    @Override
                    public Flux<S2sEvent> events() {
                        return script;
                    }

                    @Override
                    public void cancelResponse() {
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
    }

    private static ConversationSession s2sSession(S2sProvider s2s) {
        SessionContext ctx = SessionContext.speechToSpeech(
                "s-live", "u-1", S2sConfig.defaults(VendorType.QWEN, "qwen-omni", "Chelsie"));
        return new ConversationSession(ctx, null, null, null, s2s,
                new SentenceSplitter(), 16, TurnMetrics.noop(), SkillRegistry.empty());
    }

    @Test
    void fullDuplexInterruptIsMarkedToo() {
        // 全双工里打断是常态: 服务端 VAD 一听见用户开口就切, 助手常常只说了几个字
        ConversationSession s = s2sSession(fakeS2s(Flux.just(
                new S2sEvent.UserTranscript("讲讲杭州"),
                new S2sEvent.AssistantText("杭州是浙江省"),
                new S2sEvent.UserSpeechStarted())));

        S2sLiveSession live = s.openS2sLive();
        StepVerifier.create(live.audioOut()).verifyComplete();

        String said = lastAssistant(s);
        assertThat(said).startsWith("杭州是浙江省");
        assertThat(said).contains(MARK);
    }

    @Test
    void fullDuplexNormalReplyIsNotMarked() {
        ConversationSession s = s2sSession(fakeS2s(Flux.just(
                new S2sEvent.UserTranscript("你好"),
                new S2sEvent.AssistantText("你好呀"),
                new S2sEvent.ResponseDone())));

        S2sLiveSession live = s.openS2sLive();
        StepVerifier.create(live.audioOut()).verifyComplete();

        assertThat(lastAssistant(s)).isEqualTo("你好呀");
    }
}
