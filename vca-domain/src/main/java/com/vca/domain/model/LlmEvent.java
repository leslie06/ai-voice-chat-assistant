package com.vca.domain.model;

import java.util.List;

/**
 * 带工具通道的 LLM 流式事件。普通文本对话只会收到 {@link TextDelta};
 * 当模型决定调用工具时, 在该轮流结束处给出一次 {@link ToolCalls}(已把跨分片的
 * 参数拼装完整), 编排层据此执行技能、回灌结果并发起下一轮。
 *
 * <p>契约: 一轮里 {@code TextDelta} 与 {@code ToolCalls} 互斥居多 —— 模型要么直接答话,
 * 要么发起工具调用; 个别模型可能先说一句再调工具, 编排层按"出现 ToolCalls 即进入工具回合"处理。
 */
public sealed interface LlmEvent permits LlmEvent.TextDelta, LlmEvent.ToolCalls {

    /** 回复文本增量(与旧 {@code chatStream} 的 token 流等价) */
    record TextDelta(String text) implements LlmEvent {
    }

    /** 本轮模型发起的工具调用(参数已拼装完整), 一轮至多一次 */
    record ToolCalls(List<ToolCall> calls) implements LlmEvent {
    }
}
