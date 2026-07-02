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
 * <p>视觉多模态: {@code USER} 消息可携带一张图片({@link #imageUrl}, data URL 或 http URL)。
 * 带图消息会以 OpenAI 多模态 content(数组)形态发给视觉模型; 图片留在历史滑窗内, 支持后续追问
 * ("图里第二个人是谁"), 随窗口滑出自然失效。
 *
 * @param toolCalls  仅 ASSISTANT 发起工具调用时非空; 其余为空列表
 * @param toolCallId 仅 TOOL 消息非空: 指向它所回应的那次 {@link ToolCall#id()}
 * @param imageUrl   仅 USER 消息可非空: 本条消息附带的图片(data URL / http URL)
 */
public record Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId, String imageUrl) {

    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL
    }

    public Message {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, null, null, null);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, null, null, null);
    }

    /** 带图片的用户消息(视觉多模态); {@code imageUrl} 为 data URL 或 http URL */
    public static Message user(String content, String imageUrl) {
        return new Message(Role.USER, content, null, null,
                imageUrl == null || imageUrl.isBlank() ? null : imageUrl);
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, null, null, null);
    }

    /** 模型发起工具调用的 assistant 消息(content 留空, 调用放 toolCalls) */
    public static Message assistantToolCalls(List<ToolCall> calls) {
        return new Message(Role.ASSISTANT, "", calls, null, null);
    }

    /** 工具执行结果消息, 回灌给模型; {@code toolCallId} 须等于触发它的那次调用 id */
    public static Message tool(String toolCallId, String content) {
        return new Message(Role.TOOL, content, null, toolCallId, null);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /** 本条消息是否附带图片(视觉多模态) */
    public boolean hasImage() {
        return imageUrl != null && !imageUrl.isBlank();
    }
}
