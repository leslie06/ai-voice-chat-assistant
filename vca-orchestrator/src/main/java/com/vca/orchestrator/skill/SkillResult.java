package com.vca.orchestrator.skill;

import java.util.Map;

/**
 * 技能执行结果。决定工具回合循环的走向:
 * <ul>
 *   <li>{@code terminal=false}(数据型): {@link #content} 作为工具结果回灌给模型, 触发下一轮 LLM 出答复;</li>
 *   <li>{@code terminal=true}(动作型): 终结回合 —— {@link #content} 即直接念给用户的确认语,
 *       若 {@link #actionType} 非空则同时下发一个客户端动作(如点歌)。</li>
 * </ul>
 *
 * <p><b>动作型不留对话历史</b>: 点歌等动作是"副作用"而非对话内容; 若把确认语(甚至中性标记)写进历史,
 * 模型会从中 few-shot 出"音乐请求→直接出这段文字"的模式、跳过工具调用(已实测复现)。故编排层对动作型
 * 既不记用户那句、也不记助手回复 —— 每轮干净重判, 无可模仿。
 *
 * @param terminal      是否终结本回合(不再问模型)
 * @param content       数据型: 回灌模型的工具结果; 动作型: 直接念/显示的确认语
 * @param actionType    客户端动作类型(如 {@code "music"}); 仅动作型可非空
 * @param actionPayload 动作参数(如 {@code {action:"play", query:"晴天"}}); 随 actionType 一起下发
 */
public record SkillResult(boolean terminal, String content,
                          String actionType, Map<String, Object> actionPayload) {

    /** 数据型: 把 {@code content} 作为工具结果回灌模型, 由模型组织自然语言回答。 */
    public static SkillResult feedback(String content) {
        return new SkillResult(false, content, null, null);
    }

    /** 动作型(纯确认): 直接念 {@code spokenReply} 并终结, 无客户端动作。 */
    public static SkillResult reply(String spokenReply) {
        return new SkillResult(true, spokenReply, null, null);
    }

    /** 动作型: 下发一个客户端动作并念 {@code spokenReply} 确认, 终结回合(不留对话历史)。 */
    public static SkillResult action(String spokenReply, String actionType, Map<String, Object> actionPayload) {
        return new SkillResult(true, spokenReply, actionType, actionPayload);
    }
}
