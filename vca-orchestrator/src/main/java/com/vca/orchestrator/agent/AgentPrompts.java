package com.vca.orchestrator.agent;

import java.util.List;

/**
 * Agent 逐步执行各环节的提示词与口播文案。全是纯函数, 便于单测、也把"措辞"从编排逻辑里拆出来集中维护。
 *
 * <p>约定: 总目标(用户请求)已在上文工作列表里; 这里的每条提示都作为一条额外 system 消息追加, 让模型聚焦当前环节。
 */
public final class AgentPrompts {

    private AgentPrompts() {
    }

    /** 步骤间口播过渡语(语音回合念出, 让用户听到进度、不至于静默假死)。{@code index} 从 0 起。 */
    public static String narration(int index, int total, String description) {
        String desc = description == null ? "" : description.strip();
        String connective;
        if (index <= 0) {
            connective = "好的，第一步，";
        } else if (index >= total - 1) {
            connective = "最后，";
        } else {
            connective = "接下来，";
        }
        return connective + desc + "。";
    }

    /** 执行第 {@code stepNo}/{@code total} 步的指令(stepNo 从 1 起), 带上已完成步骤的结果作上下文。 */
    public static String stepInstruction(int stepNo, int total, String description, List<String> scratchpad) {
        StringBuilder sb = new StringBuilder();
        sb.append("【多步执行】你正在按计划逐步完成用户上面的请求, 共 ").append(total).append(" 步。\n");
        sb.append("当前是第 ").append(stepNo).append(" 步：").append(description == null ? "" : description.strip()).append("\n");
        sb.append("已完成步骤的结果：\n").append(scratchpadOrNone(scratchpad)).append("\n");
        sb.append("请<b>只完成这一步</b>：需要外部信息或执行动作时调用相应工具；用简洁中文给出这一步的结论即可, "
                + "不要展开成给用户的最终答复, 也不要重复其他步骤。");
        return sb.toString();
    }

    /** 一条 scratchpad 记录(把某步结果存起来供后续步骤/整合引用)。 */
    public static String scratchpadEntry(int stepNo, String description, String result) {
        String r = result == null || result.isBlank() ? "(无输出)" : result.strip();
        return "第" + stepNo + "步(" + (description == null ? "" : description.strip()) + ")结果：" + r;
    }

    /** 反思自检指令: 让模型判断信息是否已足够, 还差则给出下一步。 */
    public static String reflectInstruction(List<String> scratchpad) {
        return "【自检】以下是为完成用户请求已做各步骤的结果：\n" + scratchpadOrNone(scratchpad) + "\n"
                + "判断这些信息是否已<b>足够</b>给出完整答复。<b>只输出 JSON</b>："
                + "足够则 {\"done\":true}；若还差关键的一步, 则 {\"done\":false,\"next\":\"下一步要做什么(一句话)\"}。"
                + "不要输出 JSON 以外的任何内容。";
    }

    /** 整合答复指令: 据各步结果给用户一个连贯的最终口语答复。 */
    public static String synthesisInstruction(List<String> scratchpad) {
        return "【整合答复】请基于以下各步骤的结果, 用自然、口语化的中文给用户一个完整、连贯的最终答复：\n"
                + scratchpadOrNone(scratchpad) + "\n"
                + "综合信息直接作答, 不要逐条罗列步骤、不要出现“第几步”或“步骤”等字样。";
    }

    private static String scratchpadOrNone(List<String> scratchpad) {
        if (scratchpad == null || scratchpad.isEmpty()) {
            return "（暂无）";
        }
        StringBuilder sb = new StringBuilder();
        for (String s : scratchpad) {
            if (s != null && !s.isBlank()) {
                sb.append("- ").append(s.strip()).append("\n");
            }
        }
        return sb.length() == 0 ? "（暂无）" : sb.toString().stripTrailing();
    }
}
