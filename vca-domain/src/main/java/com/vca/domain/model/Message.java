package com.vca.domain.model;

import java.util.List;

/**
 * 一条对话消息。LLM 调用时按顺序传入历史(system + 多轮 user/assistant)。
 *
 * <p>function-calling 引入两类特殊消息, <b>仅在单回合的工具调用循环内临时使用</b>,
 * 不进长期对话历史(故 s2s/历史滑窗不受影响):
 * <ul>
 *   <li>{@code ASSISTANT} 且 {@link #toolCalls} 非空: 模型发起的工具调用(content 通常为空);</li>
 *   <li>{@code TOOL}: 工具执行结果, 用 {@link #toolCallId} 与对应调用配对回灌给模型。</li>
 * </ul>
 *
 * @param toolCalls  仅 ASSISTANT 发起工具调用时非空; 其余为空列表
 * @param toolCallId 仅 TOOL 消息非空: 指向它所回应的那次 {@link ToolCall#id()}
 */
public record Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL
    }

    public Message {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, null, null);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, null, null);
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, null, null);
    }

    /** 模型发起工具调用的 assistant 消息(content 留空, 调用放 toolCalls) */
    public static Message assistantToolCalls(List<ToolCall> calls) {
        return new Message(Role.ASSISTANT, "", calls, null);
    }

    /** 工具执行结果消息, 回灌给模型; {@code toolCallId} 须等于触发它的那次调用 id */
    public static Message tool(String toolCallId, String content) {
        return new Message(Role.TOOL, content, null, toolCallId);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
