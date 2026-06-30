package com.vca.orchestrator.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次多步任务的执行计划: 由 {@link AgentPlanner} 让模型把用户请求拆成的有序步骤。
 *
 * <p>P1 阶段计划只作为<b>上下文注入</b>给现有工具回合循环({@code runLlmRound})作引导 —— 模型据此
 * 逐步推进、按需调工具。逐步执行器 + 反思/重规划 + 进度口播是 P2 的事, 那时会用到每步的状态/结果。
 * 故步骤目前只承载描述文本, 保持记录可向前扩展。
 */
public record AgentPlan(List<AgentStep> steps) {

    public AgentPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static AgentPlan empty() {
        return new AgentPlan(List.of());
    }

    /** 从步骤描述文本直接构造(规划器解析 JSON 后用)。 */
    public static AgentPlan of(List<String> descriptions) {
        List<AgentStep> ss = new ArrayList<>();
        if (descriptions != null) {
            for (String d : descriptions) {
                if (d != null && !d.isBlank()) {
                    ss.add(new AgentStep(d.strip()));
                }
            }
        }
        return new AgentPlan(ss);
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /** 步骤描述文本列表(透传给接入层展示进度用)。 */
    public List<String> descriptions() {
        List<String> ds = new ArrayList<>(steps.size());
        for (AgentStep s : steps) {
            ds.add(s.description());
        }
        return ds;
    }

    /**
     * 拼成一条 system 上下文注入工具回合循环: 把计划摆给模型, 引导它逐步推进、按需调工具,
     * 全部完成后再给最终答复。
     */
    public String toContextMessage() {
        StringBuilder sb = new StringBuilder(
                "为完成用户的请求, 已制定如下分步计划。请<b>严格按步骤逐步推进</b>(需要时调用工具获取信息或执行动作), "
                        + "每步基于上一步结果, 全部完成后再用自然口语给出<b>最终整合答复</b>(不要逐条复述步骤):");
        int i = 1;
        for (AgentStep s : steps) {
            sb.append("\n").append(i++).append(". ").append(s.description());
        }
        return sb.toString();
    }
}
