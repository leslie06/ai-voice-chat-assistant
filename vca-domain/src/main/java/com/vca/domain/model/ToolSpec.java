package com.vca.domain.model;

import java.util.Map;

/**
 * 暴露给大模型的一个工具声明(OpenAI function-calling 的 {@code function} 部分)。
 * 由编排层据已注册的技能集生成, 随 {@code chat} 请求下发给厂商。
 *
 * @param name        工具名(英文蛇形, 如 {@code play_music}); 模型据此发起调用
 * @param description 给模型看的用途说明: 写清"什么时候该调它", 直接影响触发准确率
 * @param parameters  参数的 JSON Schema(object), 形如
 *                    {@code {"type":"object","properties":{...},"required":[...]}}; 无参时给空 properties
 */
public record ToolSpec(String name, String description, Map<String, Object> parameters) {
}
