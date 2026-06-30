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
import com.vca.orchestrator.agent.AgentPrompts;
import com.vca.orchestrator.agent.AgentReflection;
import com.vca.orchestrator.agent.AgentRun;
import com.vca.orchestrator.agent.AgentTriage;
import com.vca.orchestrator.recorder.ConversationRecorder;
import com.vca.orchestrator.recorder.TurnRecord;
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

    @Test
    void parseReflectionHandlesDoneNextAndGarbage() {
        assertThat(AgentReflection.parse("{\"done\":true}").done()).isTrue();

        AgentReflection more = AgentReflection.parse("{\"done\":false,\"next\":\"再查一下汇率\"}");
        assertThat(more.done()).isFalse();
        assertThat(more.next()).isEqualTo("再查一下汇率");

        // done=false 却没给 next、或根本解析不出 → 保守当作"足够", 不平白补步
        assertThat(AgentReflection.parse("{\"done\":false}").done()).isTrue();
        assertThat(AgentReflection.parse("说不清").done()).isTrue();
    }

    @Test
    void narrationUsesPositionAwareConnectives() {
        assertThat(AgentPrompts.narration(0, 3, "查天气")).startsWith("好的，第一步，").contains("查天气");
        assertThat(AgentPrompts.narration(1, 3, "找景点")).startsWith("接下来，");
        assertThat(AgentPrompts.narration(2, 3, "排行程")).startsWith("最后，");
    }

    @Test
    void agentRunEnforcesBudgetsAndCountsStats() {
        AgentRun run = new AgentRun(System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos(), 3);
        assertThat(run.outOfTime()).isFalse();
        assertThat(run.tryUseToolCalls(2)).isTrue();    // 额度 3→1
        assertThat(run.tryUseToolCalls(2)).isFalse();   // 不够 2, 不扣, 标记 capped
        assertThat(run.capped()).isTrue();
        assertThat(run.tryUseToolCalls(1)).isTrue();    // 1→0
        run.stepStarted();
        run.stepStarted();
        run.replanned();
        assertThat(run.stepsExecuted()).isEqualTo(2);
        assertThat(run.replans()).isEqualTo(1);

        // 截止时刻已过 → 立即超时
        assertThat(new AgentRun(System.nanoTime() - 1, 5).outOfTime()).isTrue();
    }

    // ---- 编排: 触发与退回 ----

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

    // ---- 编排: 逐步执行 + 反思补步(P2) ----

    @Test
    void twoStepAgentExecutesSequentiallyThenSynthesizes() {
        ScriptedLlm llm = new ScriptedLlm(List.of(
                List.of(text("{\"steps\":[\"查A\",\"查B\"]}")),   // 0 规划
                List.of(text("A的结果是甲")),                      // 1 第一步
                List.of(text("B的结果是乙")),                      // 2 第二步
                List.of(text("{\"done\":true}")),                  // 3 反思: 足够
                List.of(text("综合来看，甲和乙。"))));              // 4 整合答复
        SkillRegistry skills = new SkillRegistry(List.of(dataSkill("get_weather", "x")));
        ConversationSession s = session(llm, skills);
        s.setAgentEnabled(true);
        PlanCaptor cap = new PlanCaptor();
        s.setTurnListener(cap);
        CapturingRecorder rec = new CapturingRecorder();
        s.setRecorder(rec);

        StepVerifier.create(s.handleTextTurn("帮我对比一下A和B")).verifyComplete();

        // 落库带上 agent 统计: 执行 2 步、无反思补步
        assertThat(rec.records).hasSize(1);
        assertThat(rec.records.get(0).agentSteps()).isEqualTo(2);
        assertThat(rec.records.get(0).agentReplans()).isEqualTo(0);

        // 计划 + 逐步进度透传
        assertThat(cap.planSteps).containsExactly("查A", "查B");
        assertThat(cap.stepIdx).containsExactly(0, 1);
        // 共 5 次 LLM: 规划/步1/步2/反思/整合; 规划环节不下发工具(纯文本出计划)
        assertThat(llm.seenHistories).hasSize(5);
        assertThat(llm.seenTools.get(0)).isEmpty();
        // 第二步的上下文里带上了第一步的结果(scratchpad 累进)
        assertThat(llm.seenHistories.get(2)).anyMatch(m -> m.role() == Message.Role.SYSTEM
                && m.content().contains("甲"));
        // 整合环节看得到两步结果
        assertThat(llm.seenHistories.get(4)).anyMatch(m -> m.role() == Message.Role.SYSTEM
                && m.content().contains("甲") && m.content().contains("乙"));
        // 最终答复正常落定; 历史里是普通的一轮 user→assistant
        assertThat(cap.fullReplies).containsExactly("综合来看，甲和乙。");
        List<Message> h = s.historyView();
        assertThat(h).anyMatch(m -> m.role() == Message.Role.USER && m.content().equals("帮我对比一下A和B"));
        assertThat(h).anyMatch(m -> m.role() == Message.Role.ASSISTANT && m.content().equals("综合来看，甲和乙。"));
    }

    @Test
    void reflectionAddsBoundedExtraStep() {
        ScriptedLlm llm = new ScriptedLlm(List.of(
                List.of(text("{\"steps\":[\"查A\"]}")),                 // 0 规划(1 步)
                List.of(text("甲")),                                    // 1 第一步
                List.of(text("{\"done\":false,\"next\":\"再查C\"}")),   // 2 反思: 还差一步
                List.of(text("丙")),                                    // 3 补做的额外步
                List.of(text("{\"done\":true}")),                       // 4 反思: 足够
                List.of(text("甲和丙。"))));                            // 5 整合
        SkillRegistry skills = new SkillRegistry(List.of(dataSkill("get_weather", "x")));
        ConversationSession s = session(llm, skills);
        s.setAgentEnabled(true);
        PlanCaptor cap = new PlanCaptor();
        s.setTurnListener(cap);

        CapturingRecorder rec = new CapturingRecorder();
        s.setRecorder(rec);

        StepVerifier.create(s.handleTextTurn("先查A然后再查别的")).verifyComplete();

        // 计划 1 步 + 反思补 1 步 = 执行了 2 步(下标 0、1)
        assertThat(cap.stepIdx).containsExactly(0, 1);
        assertThat(llm.seenHistories).hasSize(6);
        assertThat(cap.fullReplies).containsExactly("甲和丙。");
        // 落库统计: 2 步, 其中 1 步是反思补做
        assertThat(rec.records.get(0).agentSteps()).isEqualTo(2);
        assertThat(rec.records.get(0).agentReplans()).isEqualTo(1);
    }

    @Test
    void reflectionExtraStepsAreCapped() {
        // 反思每次都说"还差一步": 应被 MAX_EXTRA_AGENT_STEPS(2) 截住, 不会无限补步
        ScriptedLlm llm = new ScriptedLlm(List.of(
                List.of(text("{\"steps\":[\"s1\"]}")),                  // 0 规划
                List.of(text("r1")),                                    // 1 第一步
                List.of(text("{\"done\":false,\"next\":\"x1\"}")),      // 2 反思→补步
                List.of(text("r2")),                                    // 3 额外步1
                List.of(text("{\"done\":false,\"next\":\"x2\"}")),      // 4 反思→补步
                List.of(text("r3")),                                    // 5 额外步2(到额度)
                List.of(text("最终。"))));                              // 6 整合(不再反思)
        SkillRegistry skills = new SkillRegistry(List.of(dataSkill("get_weather", "x")));
        ConversationSession s = session(llm, skills);
        s.setAgentEnabled(true);
        PlanCaptor cap = new PlanCaptor();
        s.setTurnListener(cap);

        StepVerifier.create(s.handleTextTurn("帮我规划一个复杂的多步任务")).verifyComplete();

        // 计划 1 步 + 最多补 2 步 = 执行 3 步; 反思只在每次补步前调, 第三次额度耗尽不再反思
        assertThat(cap.stepIdx).containsExactly(0, 1, 2);
        assertThat(llm.seenHistories).hasSize(7);
        assertThat(cap.fullReplies).containsExactly("最终。");
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
        final List<Integer> stepIdx = new ArrayList<>();
        List<String> planSteps;

        @Override
        public void onAssistantText(String fullText) {
            fullReplies.add(fullText);
        }

        @Override
        public void onAgentPlan(List<String> steps) {
            planSteps = steps;
        }

        @Override
        public void onAgentStep(int index, String description) {
            stepIdx.add(index);
        }
    }

    /** 捕获落库的 TurnRecord, 验证 agent 统计字段。 */
    private static final class CapturingRecorder implements ConversationRecorder {
        final List<TurnRecord> records = new ArrayList<>();

        @Override
        public void recordTurn(TurnRecord record) {
            records.add(record);
        }
    }
}
