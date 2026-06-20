package com.vca.orchestrator;

import com.vca.domain.enums.AudioFormat;
import com.vca.domain.enums.VendorType;
import com.vca.domain.model.AsrConfig;
import com.vca.domain.model.AsrEvent;
import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.AudioFrame;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.LlmEvent;
import com.vca.domain.model.Message;
import com.vca.domain.model.SessionContext;
import com.vca.domain.model.ToolCall;
import com.vca.domain.model.ToolSpec;
import com.vca.domain.spi.AsrProvider;
import com.vca.domain.spi.LlmProvider;
import com.vca.domain.spi.TtsProvider;
import com.vca.orchestrator.metrics.TurnMetrics;
import com.vca.orchestrator.pipeline.SentenceSplitter;
import com.vca.orchestrator.session.ConversationSession;
import com.vca.orchestrator.session.TurnListener;
import com.vca.orchestrator.skill.PlayMusicSkill;
import com.vca.orchestrator.skill.Skill;
import com.vca.orchestrator.skill.SkillRegistry;
import com.vca.orchestrator.skill.SkillResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** function-calling 工具回合循环的编排逻辑(只依赖 domain SPI 的假厂商 + 假技能)。 */
class FunctionCallingTest {

    // ---- 假厂商/技能 ----

    /** 按脚本逐轮回放 LLM 事件; 记录每轮收到的历史与工具声明, 供断言"工具结果回灌"。 */
    private static final class ScriptedLlm implements LlmProvider {
        private final Deque<List<LlmEvent>> rounds;
        final List<List<Message>> seenHistories = new ArrayList<>();
        final List<List<ToolSpec>> seenTools = new ArrayList<>();

        ScriptedLlm(List<List<LlmEvent>> rounds) {
            this.rounds = new ArrayDeque<>(rounds);
        }

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
            seenTools.add(List.copyOf(tools));
            List<LlmEvent> evs = rounds.poll();
            return evs == null ? Flux.empty() : Flux.fromIterable(evs);
        }
    }

    private static TtsProvider fakeTts() {
        return new TtsProvider() {
            final AtomicLong seq = new AtomicLong();

            @Override
            public VendorType vendor() {
                return VendorType.ALIYUN;
            }

            @Override
            public Flux<AudioChunk> synthesize(Flux<String> textSegments, com.vca.domain.model.TtsConfig cfg) {
                return textSegments.map(seg -> new AudioChunk(
                        seg.getBytes(StandardCharsets.UTF_8), AudioFormat.MP3,
                        seq.getAndIncrement(), seg, false));
            }
        };
    }

    private static AsrProvider fakeAsr(String text) {
        return new AsrProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.ALIYUN;
            }

            @Override
            public Flux<AsrEvent> transcribe(Flux<AudioFrame> audio, AsrConfig cfg) {
                return Flux.just(AsrEvent.finalResult(text, 100, 0.9));
            }
        };
    }

    /** 数据型假技能: 固定回灌一段结果文本。 */
    private static Skill dataSkill(String name, String result) {
        return new Skill() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "测试数据技能";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public Mono<SkillResult> execute(Map<String, Object> args) {
                return Mono.just(SkillResult.feedback(result));
            }
        };
    }

    private static LlmEvent.TextDelta text(String s) {
        return new LlmEvent.TextDelta(s);
    }

    private static LlmEvent.ToolCalls call(String id, String name, String argsJson) {
        return new LlmEvent.ToolCalls(List.of(new ToolCall(id, name, argsJson)));
    }

    private static ConversationSession session(LlmProvider llm, TtsProvider tts, SkillRegistry skills) {
        SessionContext ctx = SessionContext.pipeline(
                "s-fc", "u-1",
                AsrConfig.defaults(VendorType.ALIYUN),
                LlmConfig.defaults(VendorType.DEEPSEEK, "deepseek-chat"),
                com.vca.domain.model.TtsConfig.defaults(VendorType.ALIYUN, "v"));
        return new ConversationSession(ctx, fakeAsr("placeholder"), llm, tts, null,
                new SentenceSplitter(), 16, TurnMetrics.noop(), skills);
    }

    private static Flux<AudioFrame> dummyAudio() {
        return Flux.just(AudioFrame.of(new byte[]{1}, 0, 0), AudioFrame.endOfSpeech(1, 10));
    }

    private static final class Captor implements TurnListener {
        final StringBuilder deltas = new StringBuilder();
        final List<String> fullReplies = new ArrayList<>();
        final List<String> music = new ArrayList<>();

        @Override
        public void onAssistantDelta(String delta) {
            deltas.append(delta);
        }

        @Override
        public void onAssistantText(String fullText) {
            fullReplies.add(fullText);
        }

        @Override
        public void onMusicRequest(String action, String query) {
            music.add(action + ":" + query);
        }
    }

    // ---- 测试 ----

    @Test
    void dataToolResultIsFedBackAndModelComposesAnswer() {
        // 第1轮: 模型发起 get_weather 调用(无文本); 第2轮: 据回灌结果出口语答复
        ScriptedLlm llm = new ScriptedLlm(List.of(
                List.of(call("c1", "get_weather", "{\"city\":\"杭州\"}")),
                List.of(text("杭州现在晴，二十五度。"))));
        SkillRegistry skills = new SkillRegistry(List.of(dataSkill("get_weather", "晴, 25℃")));
        ConversationSession s = session(llm, fakeTts(), skills);
        Captor cap = new Captor();
        s.setTurnListener(cap);

        // 走打字回合(speak=false): 验证回灌循环与历史, 不涉及 TTS
        StepVerifier.create(s.handleTextTurn("杭州天气怎么样"))
                .verifyComplete();

        assertThat(cap.fullReplies).containsExactly("杭州现在晴，二十五度。");
        // 第2轮请求里应包含: assistant 工具调用消息 + tool 结果消息(内容为技能回灌)
        List<Message> round2 = llm.seenHistories.get(1);
        assertThat(round2).anyMatch(Message::hasToolCalls);
        assertThat(round2).anyMatch(m -> m.role() == Message.Role.TOOL && m.content().equals("晴, 25℃"));
        // 工具声明确实下发给了模型
        assertThat(llm.seenTools.get(0)).anyMatch(t -> t.name().equals("get_weather"));

        // 长期历史保持干净: 只有 system + user + 最终 assistant 文本(无工具中间消息)
        List<Message> h = s.historyView();
        assertThat(h).hasSize(3);
        assertThat(h.get(1)).isEqualTo(Message.user("杭州天气怎么样"));
        assertThat(h.get(2)).isEqualTo(Message.assistant("杭州现在晴，二十五度。"));
        assertThat(h).noneMatch(m -> m.role() == Message.Role.TOOL);
    }

    @Test
    void actionToolDispatchesAndSpeaksConfirmationWithoutSecondRound() {
        // 模型理解模糊点歌 → 调 play_music; 动作型技能终结回合, 不再问模型
        ScriptedLlm llm = new ScriptedLlm(List.of(
                List.of(call("c1", PlayMusicSkill.NAME, "{\"query\":\"轻音乐\"}"))));
        SkillRegistry skills = new SkillRegistry(List.of(new PlayMusicSkill()));
        ConversationSession voice = session(llm, fakeTts(), skills);
        Captor cap = new Captor();
        voice.setTurnListener(cap);

        // 语音回合: 会话内置 ASR 给出占位文本(不触发正则点歌), 故走 LLM → 模型调 play_music; 确认语经 TTS 念出
        StepVerifier.create(voice.handleUserTurn(dummyAudio()))
                .expectNextMatches(c -> c.text().equals("好的，为您播放音乐轻音乐"))
                .verifyComplete();

        assertThat(cap.music).containsExactly("play:轻音乐");
        assertThat(cap.fullReplies).containsExactly("好的，为您播放音乐轻音乐");   // 口播/字幕仍是确认语
        // 只问了模型一轮(动作型终结, 无回灌)
        assertThat(llm.seenHistories).hasSize(1);

        // 动作回合不留对话痕迹: 既无用户那句、也无助手确认语(防模型从历史仿写文字而跳过工具)
        List<Message> h = voice.historyView();
        assertThat(h).noneMatch(m -> m.role() == Message.Role.USER);
        assertThat(h).noneMatch(m -> m.role() == Message.Role.ASSISTANT);
    }

    @Test
    void actionTurnLeavesNoTraceSoNextTurnHistoryStaysClean() {
        // 两次连续点歌: 第一次动作回合不该在历史里留下"音乐请求→确认语", 否则第二次模型会照着仿写文字、跳过工具。
        ScriptedLlm llm = new ScriptedLlm(List.of(
                List.of(call("c1", PlayMusicSkill.NAME, "{\"query\":\"晴天\"}")),
                List.of(call("c2", PlayMusicSkill.NAME, "{\"query\":\"七里香\"}"))));
        SkillRegistry skills = new SkillRegistry(List.of(new PlayMusicSkill()));
        ConversationSession s = session(llm, fakeTts(), skills);
        s.setTurnListener(new Captor());

        StepVerifier.create(s.handleTextTurn("来首伤感的歌")).verifyComplete();
        StepVerifier.create(s.handleTextTurn("再来首安静点的")).verifyComplete();

        // 第二轮发给模型的历史里, 不能出现第一轮点歌留下的任何 user/assistant 痕迹(只该有 system + 本轮 user)
        List<Message> round2History = llm.seenHistories.get(1);
        assertThat(round2History).noneMatch(m -> m.role() == Message.Role.ASSISTANT);
        assertThat(round2History.stream().filter(m -> m.role() == Message.Role.USER).count()).isEqualTo(1);
        assertThat(round2History).anyMatch(m -> m.role() == Message.Role.USER && m.content().equals("再来首安静点的"));
        // 会话长期历史也干净(动作不是对话内容)
        assertThat(s.historyView()).noneMatch(m -> m.role() == Message.Role.USER || m.role() == Message.Role.ASSISTANT);
    }

    @Test
    void plainTextAnswerStillWorksWithToolsRegistered() {
        // 注册了工具但模型直接答话(无 ToolCalls): 退化为普通文本回合
        ScriptedLlm llm = new ScriptedLlm(List.of(
                List.of(text("你好呀，"), text("有什么可以帮你？"))));
        SkillRegistry skills = new SkillRegistry(List.of(dataSkill("get_weather", "x")));
        ConversationSession s = session(llm, fakeTts(), skills);
        Captor cap = new Captor();
        s.setTurnListener(cap);

        StepVerifier.create(s.handleTextTurn("在吗"))
                .verifyComplete();

        assertThat(cap.fullReplies).containsExactly("你好呀，有什么可以帮你？");
        assertThat(llm.seenHistories).hasSize(1);   // 无工具往返
    }
}
