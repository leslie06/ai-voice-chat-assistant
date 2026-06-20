package com.vca.orchestrator.skill;

import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 一个可被大模型经 function-calling 调用的技能。把"确定性能力"(点歌、查时间、查天气……)
 * 从大模型里解耦出来: 由模型判断<b>何时</b>调、传<b>什么</b>参数, 由技能执行<b>怎么</b>做。
 *
 * <p>两类典型形态(由 {@link SkillResult} 表达):
 * <ul>
 *   <li><b>数据型</b>(查时间/天气): 执行后把结果<b>回灌</b>给模型({@link SkillResult#feedback}),
 *       让模型据此用自然口语组织回答 —— 多一次 LLM 往返;</li>
 *   <li><b>动作型</b>(点歌): 直接产生一个客户端动作并给一句确认语({@link SkillResult#action}),
 *       <b>终结</b>本回合, 不再问模型。</li>
 * </ul>
 *
 * <p>实现应是无状态、可并发的(同一实例服务所有会话)。
 */
public interface Skill {

    /** 工具名(英文蛇形, 全局唯一), 对应模型发起调用时的 function name。 */
    String name();

    /** 给模型看的用途说明: 写清"什么时候该调用我", 直接影响触发准确率。 */
    String description();

    /**
     * 参数的 JSON Schema(object), 形如
     * {@code {"type":"object","properties":{"query":{"type":"string","description":"…"}},"required":["query"]}}。
     * 无参技能返回空 properties 的 object。
     */
    Map<String, Object> parameters();

    /**
     * 执行技能。
     *
     * @param args 模型给的参数(已从 JSON 解析为 Map); 缺参/类型异常由实现自行兜底
     * @return 执行结果; 出错可直接抛异常, 编排层会兜成一条"工具失败"回灌给模型
     */
    Mono<SkillResult> execute(Map<String, Object> args);
}
