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
import com.vca.domain.model.ToolSpec;
import com.vca.domain.model.TtsConfig;
import com.vca.domain.spi.AsrProvider;
import com.vca.domain.spi.LlmProvider;
import com.vca.domain.spi.TtsProvider;
import com.vca.orchestrator.agent.AgentPlan;
import com.vca.orchestrator.agent.AgentPlanner;
import com.vca.orchestrator.agent.AgentTriage;
import com.vca.orchestrator.metrics.TurnMetrics;
import com.vca.orchestrator.pipeline.SentenceSplitter;
import com.vca.orchestrator.session.ConversationSession;
import com.vca.orchestrator.session.TurnListener;
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

/** 多步 Agent 规划(P1): 触发闸门 + 计划解析(纯) + 规划注入工具回合循环(编排, 假厂商)。 */
class AgentPlanningTest {

    // ---- 纯函数: 触发闸门 ----

    @Test
    void triageMatchesMultiStepRequestsOnly() {
        assertThat(AgentTriage.isComplex("帮我规划一下周末两天的行程")).isTrue();
        assertThat(AgentTriage.isComplex("对比一下骁龙和天玑哪个更省电")).isTrue();
        assertThat(AgentTriage.isComplex("先查下天气然后再帮我推荐穿搭")).isTrue();

        assertThat(AgentTriage.isComplex("今天几号")).isFalse();
        assertThat(AgentTriage.isComplex("放首歌")).isFalse();
        assertThat(AgentTriage.isComplex("你好呀")).isFalse();
        assertThat(AgentTriage.isComplex("")).isFalse();
        assertThat(AgentTriage.isComplex(null)).isFalse();
    }

    // ---- 纯函数: 计划解析 ----

    @Test
    void parsePlanHandlesObjectArrayFencesAndGarbage() {
        AgentPlan obj = AgentPlanner.parsePlan("{\"steps\":[\"查天气\",\"推荐穿搭\"]}");
        assertThat(obj.descriptions()).containsExactly("查天气", "推荐穿搭");

        // 裸数组 + 代码围栏 + 前后多余文字都应被容错截取
        AgentPlan fenced = AgentPlanner.parsePlan("好的:\n```json\n[\"第一步\", \"第二步\"]\n```");
        assertThat(fenced.descriptions()).containsExactly("第一步", "第二步");

        // 空步骤(模型判定无需规划)/无法解析 → 空计划
        assertThat(AgentPlanner.parsePlan("{\"steps\":[]}").isEmpty()).isTrue();
        assertThat(AgentPlanner.parsePlan("我觉得不需要分步").isEmpty()).isTrue();
        assertThat(AgentPlanner.parsePlan("").isEmpty()).isTrue();
    }

    @Test
    void parsePlanCapsStepCountAtSix() {
        AgentPlan p = AgentPlanner.parsePlan(
                "{\"steps\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\"]}");
        assertThat(p.steps()).hasSize(6);
    }

    // ---- 编排: 规划注入工具回合循环 ----

    @Test
    void complexTurnPlansFirstThenInjectsPlanIntoToolRound() {
        // 第1轮: 规划器收到的调用 → 返回计划 JSON; 第2轮: runLlmRound 据计划出最终答复
        ScriptedLlm llm = new ScriptedLlm(List.of(
                List.of(text("{\"steps\":[\"查询本周天气\",\"据天气推荐穿搭\"]}")),
                List.of(text("本周多云转晴，建议带件薄外套。"))));
        SkillRegistry skills = new SkillRegistry(List.of(dataSkill("get_weather", "x")));
        ConversationSession s = session(llm, skills);
        s.setAgentEnabled(true);
        PlanCaptor cap = new PlanCaptor();
        s.setTurnListener(cap);

        StepVerifier.create(s.handleTextTurn("先查下本周天气然后再帮我推荐穿搭"))
                .verifyComplete();

        // 规划器先被调用一次(不带工具), 再是真正的工具回合(带工具)
        assertThat(llm.seenTools.get(0)).isEmpty();
        assertThat(llm.seenTools.get(1)).anyMatch(t -> t.name().equals("get_weather"));
        // 第2轮(执行回合)的上下文里应注入了计划 system 消息
        List<Message> execRound = llm.seenHistories.get(1);
        assertThat(execRound).anyMatch(m -> m.role() == Message.Role.SYSTEM
                && m.content().contains("查询本周天气") && m.content().contains("据天气推荐穿搭"));
        // 计划经 listener 透传给前端
        assertThat(cap.planSteps).containsExactly("查询本周天气", "据天气推荐穿搭");
        // 最终答复正常产出
        assertThat(cap.fullReplies).containsExactly("本周多云转晴，建议带件薄外套。");
    }

    @Test
    void simpleTurnSkipsPlanningEvenWhenAgentEnabled() {
        // 非复杂请求: 即便开了 Agent 也不规划, 直接一轮出答复(只一次 LLM 调用)
        ScriptedLlm llm = new ScriptedLlm(List.of(List.of(text("你好，我在。"))));
        SkillRegistry skills = new SkillRegistry(List.of(dataSkill("get_weather", "x")));
        ConversationSession s = session(llm, skills);
        s.setAgentEnabled(true);
        PlanCaptor cap = new PlanCaptor();
        s.setTurnListener(cap);

        StepVerifier.create(s.handleTextTurn("在吗")).verifyComplete();

        assertThat(llm.seenHistories).hasSize(1);       // 无规划往返
        assertThat(cap.planSteps).isNull();             // 没有计划透传
        assertThat(cap.fullReplies).containsExactly("你好，我在。");
    }

    @Test
    void emptyPlanFallsBackToNormalRoundWithoutInjection() {
        // 命中闸门但模型判定无需分步(空计划): 应静默退回普通回合, 不注入计划
        ScriptedLlm llm = new ScriptedLlm(List.of(
                List.of(text("{\"steps\":[]}")),
                List.of(text("骁龙和天玑各有侧重。"))));
        SkillRegistry skills = new SkillRegistry(List.of(dataSkill("get_weather", "x")));
        ConversationSession s = session(llm, skills);
        s.setAgentEnabled(true);
        PlanCaptor cap = new PlanCaptor();
        s.setTurnListener(cap);

        StepVerifier.create(s.handleTextTurn("对比一下骁龙和天玑")).verifyComplete();

        assertThat(cap.planSteps).isNull();
        List<Message> execRound = llm.seenHistories.get(1);
        assertThat(execRound).noneMatch(m -> m.role() == Message.Role.SYSTEM
                && m.content().contains("分步计划"));
        assertThat(cap.fullReplies).containsExactly("骁龙和天玑各有侧重。");
    }

    // ---- 假厂商/技能(裁剪自 FunctionCallingTest) ----

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
            public Flux<AudioChunk> synthesize(Flux<String> textSegments, TtsConfig cfg) {
                return textSegments.map(seg -> new AudioChunk(
                        seg.getBytes(StandardCharsets.UTF_8), AudioFormat.MP3,
                        seq.getAndIncrement(), seg, false));
            }
        };
    }

    private static AsrProvider fakeAsr() {
        return new AsrProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.ALIYUN;
            }

            @Override
            public Flux<AsrEvent> transcribe(Flux<AudioFrame> audio, AsrConfig cfg) {
                return Flux.just(AsrEvent.finalResult("placeholder", 100, 0.9));
            }
        };
    }

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

    private static ConversationSession session(LlmProvider llm, SkillRegistry skills) {
        SessionContext ctx = SessionContext.pipeline(
                "s-agent", "u-1",
                AsrConfig.defaults(VendorType.ALIYUN),
                LlmConfig.defaults(VendorType.DEEPSEEK, "deepseek-chat"),
                TtsConfig.defaults(VendorType.ALIYUN, "v"));
        return new ConversationSession(ctx, fakeAsr(), llm, fakeTts(), null,
                new SentenceSplitter(), 16, TurnMetrics.noop(), skills);
    }

    private static final class PlanCaptor implements TurnListener {
        final List<String> fullReplies = new ArrayList<>();
        List<String> planSteps;

        @Override
        public void onAssistantText(String fullText) {
            fullReplies.add(fullText);
        }

        @Override
        public void onAgentPlan(List<String> steps) {
            planSteps = steps;
        }
    }
}
