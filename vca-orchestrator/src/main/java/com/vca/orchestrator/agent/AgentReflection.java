package com.vca.orchestrator.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 反思自检结果: 已完成的步骤是否足够回答用户; 不足则给出补充的下一步。用于 Agent 逐步执行后的<b>有界</b>重规划
 * —— 命中 {@code !done && next} 时再补做一步(编排层限额, 防无限自我延长)。
 *
 * @param done 信息是否已足够、可以整合答复
 * @param next 不足时补做的下一步描述(done=true 或解析失败时为 null)
 */
public record AgentReflection(boolean done, String next) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 默认/解析失败: 视为"已足够", 直接整合答复(保守, 不平白多做步骤)。 */
    public static AgentReflection sufficient() {
        return new AgentReflection(true, null);
    }

    /**
     * 解析模型输出的自检 JSON({@code {"done":true}} 或 {@code {"done":false,"next":"…"}})。
     * 容错: 截首个 JSON 对象再解析; 解析不出、或 done 缺省、或要补步却没给 next, 一律当作 {@link #sufficient()}。纯函数。
     */
    public static AgentReflection parse(String raw) {
        String json = AgentPlanner.extractJson(raw);
        if (json == null) {
            return sufficient();
        }
        try {
            JsonNode node = JSON.readTree(json);
            boolean done = !node.has("done") || node.get("done").asBoolean(true);
            if (done) {
                return sufficient();
            }
            JsonNode next = node.get("next");
            String n = next != null && next.isTextual() ? next.asText().strip() : null;
            return (n == null || n.isBlank()) ? sufficient() : new AgentReflection(false, n);
        } catch (Exception e) {
            return sufficient();
        }
    }
}
