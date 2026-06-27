package com.vca.orchestrator;

import com.vca.domain.enums.VendorType;
import com.vca.domain.model.AsrConfig;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.LlmEvent;
import com.vca.domain.model.Message;
import com.vca.domain.model.SessionContext;
import com.vca.domain.model.ToolSpec;
import com.vca.domain.model.TtsConfig;
import com.vca.domain.spi.LlmProvider;
import com.vca.orchestrator.metrics.TurnMetrics;
import com.vca.orchestrator.pipeline.SentenceSplitter;
import com.vca.orchestrator.recorder.ConversationRecorder;
import com.vca.orchestrator.recorder.TurnRecord;
import com.vca.orchestrator.session.ConversationSession;
import com.vca.orchestrator.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** 对话落库埋点: 普通回合应交给 recorder 一条配对好的 {@link TurnRecord}; 默认 NOOP 时不影响回合。 */
class ConversationRecorderTest {

    /** 收集落库记录的假 recorder。 */
    private static final class CapturingRecorder implements ConversationRecorder {
        final List<TurnRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void recordTurn(TurnRecord record) {
            records.add(record);
        }
    }

    /** 固定吐一段文本的假 LLM(打字回合不碰 ASR/TTS)。 */
    private static LlmProvider fakeLlm(String reply) {
        return new LlmProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.DEEPSEEK;
            }

            @Override
            public Flux<String> chatStream(List<Message> history, LlmConfig cfg) {
                return Flux.just(reply);
            }

            @Override
            public Flux<LlmEvent> chat(List<Message> history, LlmConfig cfg, List<ToolSpec> tools) {
                return Flux.just(new LlmEvent.TextDelta(reply));
            }
        };
    }

    private static ConversationSession session(LlmProvider llm) {
        SessionContext ctx = SessionContext.pipeline(
                "s-rec", "u-1",
                AsrConfig.defaults(VendorType.ALIYUN),
                LlmConfig.defaults(VendorType.DEEPSEEK, "deepseek-chat"),
                TtsConfig.defaults(VendorType.ALIYUN, "v"));
        return new ConversationSession(ctx, null, llm, null, null,
                new SentenceSplitter(), 16, TurnMetrics.noop(), SkillRegistry.empty());
    }

    @Test
    void normalTurnIsRecordedWithPairedUserAndAssistantText() {
        ConversationSession s = session(fakeLlm("你好呀"));
        CapturingRecorder rec = new CapturingRecorder();
        s.setRecorder(rec);

        StepVerifier.create(s.handleTextTurn("在吗")).verifyComplete();

        assertThat(rec.records).hasSize(1);
        TurnRecord r = rec.records.get(0);
        assertThat(r.sessionId()).isEqualTo("s-rec");
        assertThat(r.turnIndex()).isEqualTo(1);
        assertThat(r.userText()).isEqualTo("在吗");
        assertThat(r.assistantText()).isEqualTo("你好呀");
        assertThat(r.outcome()).isEqualTo("complete");
        assertThat(r.totalMs()).isNotNull();
        assertThat(r.at()).isNotNull();
    }

    @Test
    void turnIndexIncrementsAcrossTurns() {
        ConversationSession s = session(fakeLlm("嗯"));
        CapturingRecorder rec = new CapturingRecorder();
        s.setRecorder(rec);

        StepVerifier.create(s.handleTextTurn("第一句")).verifyComplete();
        StepVerifier.create(s.handleTextTurn("第二句")).verifyComplete();

        assertThat(rec.records).extracting(TurnRecord::turnIndex).containsExactly(1, 2);
        assertThat(rec.records).extracting(TurnRecord::userText).containsExactly("第一句", "第二句");
    }

    @Test
    void defaultRecorderIsNoopAndTurnStillCompletes() {
        // 不设 recorder: 默认 NOOP, 回合照常完成, 不抛异常
        ConversationSession s = session(fakeLlm("好的"));
        StepVerifier.create(s.handleTextTurn("测试")).verifyComplete();
    }
}
