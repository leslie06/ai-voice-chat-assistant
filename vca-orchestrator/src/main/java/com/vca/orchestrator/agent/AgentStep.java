package com.vca.orchestrator.agent;

/**
 * 计划中的一个步骤。P1 仅承载步骤描述; P2 逐步执行器将在此基础上扩展执行状态/结果(故单列成记录便于演进)。
 *
 * @param description 这一步要做什么(模型生成的自然语言描述)
 */
public record AgentStep(String description) {
}
