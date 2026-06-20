package com.vca.domain.model;

/**
 * 大模型发起的一次工具调用(function call)。
 *
 * @param id        调用 id(厂商生成), 回传工具结果时需用它把结果与调用配对
 * @param name      工具名(对应某个 {@code Skill} 的 name)
 * @param arguments 调用参数, <b>原始 JSON 字符串</b>(如 {@code {"query":"晴天"}}); 由 Skill 自行解析
 */
public record ToolCall(String id, String name, String arguments) {
}
