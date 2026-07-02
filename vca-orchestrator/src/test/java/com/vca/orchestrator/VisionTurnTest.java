package com.vca.orchestrator;

import com.vca.domain.enums.VendorType;
import com.vca.domain.model.AsrConfig;
import com.vca.domain.model.AsrEvent;
import com.vca.domain.model.AudioFrame;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.LlmEvent;
import com.vca.domain.model.Message;
import com.vca.domain.model.SessionContext;
import com.vca.domain.model.ToolSpec;
import com.vca.domain.spi.AsrProvider;
import com.vca.domain.spi.LlmProvider;
import com.vca.orchestrator.metrics.TurnMetrics;
import com.vca.orchestrator.pipeline.SentenceSplitter;
import com.vca.orchestrator.session.ConversationSession;
import com.vca.orchestrator.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 视觉多模态回合的编排逻辑: 附图 → 本轮用户消息带图 + 自动切视觉模型;
 * 图片留在历史窗口内支持追问; 无图回合用回普通模型; 图片一次附加只被一个回合消费。
 */
class VisionTurnTest {

    private static final String IMAGE = "data:image/jpeg;base64,QUJD";

    /** 记录每次调用收到的历史与 LLM 配置, 固定回一段文本。 */
    private static final class RecordingLlm implements LlmProvider {
        final List<List<Message>> seenHistories = new ArrayList<>();
        final List<LlmConfig> seenConfigs = new ArrayList<>();

        @Override
        public VendorType vendor() {
            return VendorType.DEEPSEEK;
        }

        @Override
        public Flux<String> chatStream(List<Message> history, LlmConfig cfg) {
            return chat(history, cfg, List.of())
                    .filter(e -> e instanceof LlmEvent.TextDelta)
                    .map(e -> ((LlmEvent.TextDelta) e).text());
        }

        @Override
        public Flux<LlmEvent> chat(List<Message> history, LlmConfig cfg, List<ToolSpec> tools) {
            seenHistories.add(List.copyOf(history));
            seenConfigs.add(cfg);
            return Flux.just(new LlmEvent.TextDelta("好的"));
        }
    }

    private static AsrProvider stubAsr() {
        return new AsrProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.ALIYUN;
            }

            @Override
            public Flux<AsrEvent> transcribe(Flux<AudioFrame> audio, AsrConfig cfg) {
                return Flux.just(AsrEvent.finalResult("stub", 100, 0.9));
            }
        };
    }

    private static ConversationSession session(RecordingLlm llm) {
        SessionContext ctx = SessionContext.pipeline(
                "s-vision", "u-1",
                AsrConfig.defaults(VendorType.ALIYUN),
                LlmConfig.defaults(VendorType.DEEPSEEK, "deepseek-chat"),
                com.vca.domain.model.TtsConfig.defaults(VendorType.ALIYUN, "v"));
        ConversationSession s = new ConversationSession(ctx, stubAsr(), llm, null, null,
                new SentenceSplitter(), 16, TurnMetrics.noop(), SkillRegistry.empty());
        s.setVisionModel(VendorType.QWEN, "qwen-vl-plus");
        return s;
    }

    @Test
    void imageTurnCarriesImageAndSwitchesToVisionModel() {
        RecordingLlm llm = new RecordingLlm();
        ConversationSession s = session(llm);

        s.attachImage(IMAGE);
        StepVerifier.create(s.handleTextTurn("图里有什么")).verifyComplete();

        // 本轮用户消息带图, 且改用视觉模型
        Message user = lastUser(llm.seenHistories.get(0));
        assertThat(user.imageUrl()).isEqualTo(IMAGE);
        assertThat(llm.seenConfigs.get(0).vendor()).isEqualTo(VendorType.QWEN);
        assertThat(llm.seenConfigs.get(0).model()).isEqualTo("qwen-vl-plus");
        // 人设/采样参数沿用原配置
        assertThat(llm.seenConfigs.get(0).systemPrompt())
                .isEqualTo(LlmConfig.defaults(VendorType.DEEPSEEK, "deepseek-chat").systemPrompt());
    }

    @Test
    void followUpKeepsVisionModelWhileImageInWindowAndImageConsumedOnce() {
        RecordingLlm llm = new RecordingLlm();
        ConversationSession s = session(llm);

        s.attachImage(IMAGE);
        StepVerifier.create(s.handleTextTurn("图里有什么")).verifyComplete();
        StepVerifier.create(s.handleTextTurn("第二个人是谁")).verifyComplete();

        // 追问轮: 图片仍在历史窗口内 → 继续用视觉模型; 但新用户消息本身不带图(一图一轮)
        assertThat(llm.seenConfigs.get(1).model()).isEqualTo("qwen-vl-plus");
        Message user2 = lastUser(llm.seenHistories.get(1));
        assertThat(user2.content()).isEqualTo("第二个人是谁");
        assertThat(user2.hasImage()).isFalse();
        // 历史里第一轮的带图消息仍在(支持追问)
        assertThat(llm.seenHistories.get(1).stream().anyMatch(Message::hasImage)).isTrue();
    }

    @Test
    void plainTurnUsesDefaultModel() {
        RecordingLlm llm = new RecordingLlm();
        ConversationSession s = session(llm);

        StepVerifier.create(s.handleTextTurn("你好")).verifyComplete();

        assertThat(llm.seenConfigs.get(0).vendor()).isEqualTo(VendorType.DEEPSEEK);
        assertThat(llm.seenConfigs.get(0).model()).isEqualTo("deepseek-chat");
        assertThat(llm.seenHistories.get(0).stream().anyMatch(Message::hasImage)).isFalse();
    }

    @Test
    void blankVisionModelKeepsCurrentModelForImageTurn() {
        RecordingLlm llm = new RecordingLlm();
        ConversationSession s = session(llm);
        s.setVisionModel(VendorType.QWEN, "");   // 未配置视觉模型 → 不切换

        s.attachImage(IMAGE);
        StepVerifier.create(s.handleTextTurn("图里有什么")).verifyComplete();

        assertThat(llm.seenConfigs.get(0).model()).isEqualTo("deepseek-chat");
        assertThat(lastUser(llm.seenHistories.get(0)).imageUrl()).isEqualTo(IMAGE);
    }

    private static Message lastUser(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).role() == Message.Role.USER) {
                return history.get(i);
            }
        }
        throw new AssertionError("历史里没有 user 消息");
    }
}
