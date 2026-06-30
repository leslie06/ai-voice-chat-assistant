package com.vca.orchestrator.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.LlmEvent;
import com.vca.domain.model.Message;
import com.vca.domain.spi.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 多步任务规划器: 让模型把用户最近的请求拆成有序步骤(纯文本、不带工具)。无状态、进程级可共享。
 *
 * <p>P1 只负责"出计划"; 产出的 {@link AgentPlan} 由编排层作为上下文注入现有工具回合循环引导执行。
 * 规划本身多一次 LLM 往返, 故仅对 {@link AgentTriage} 判定为复杂的回合启用。失败/解析不出步骤时返回
 * {@link AgentPlan#empty()}, 编排层据此<b>静默退回</b>普通回合, 绝不因规划失败影响对话。
 */
public final class AgentPlanner {

    private static final Logger log = LoggerFactory.getLogger(AgentPlanner.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 最多保留的步骤数: 防模型拆得过细拖慢执行。 */
    private static final int MAX_STEPS = 6;

    private static final String PLAN_INSTRUCTION = """
            你是任务规划器。基于以上对话, 把用户<b>最近这条请求</b>拆解成 2 到 %d 个有序、可执行的步骤,
            每步一句话、聚焦一个子目标, 后一步可依赖前一步的结果。只考虑完成请求所需的步骤, 不要寒暄或解释。
            <b>只输出 JSON</b>, 形如 {"steps":["第一步…","第二步…"]}; 若该请求其实一步即可完成或只是闲聊,
            返回 {"steps":[]}。不要输出 JSON 以外的任何内容。""".formatted(MAX_STEPS);

    /**
     * 据当前上下文为最近一条用户请求生成计划。
     *
     * @param llm     复用会话当前的 LLM 厂商(规划与对话同模型)
     * @param context 工作上下文(已含时间/记忆/历史等注入 + 末尾的本轮用户消息)
     * @param cfg     模型参数
     * @return 计划; 解析不出步骤或调用失败时为 {@link AgentPlan#empty()}
     */
    public Mono<AgentPlan> plan(LlmProvider llm, List<Message> context, LlmConfig cfg) {
        List<Message> messages = new ArrayList<>(context);
        messages.add(Message.system(PLAN_INSTRUCTION));
        return llm.chat(messages, cfg, List.of())
                .filter(ev -> ev instanceof LlmEvent.TextDelta)
                .map(ev -> ((LlmEvent.TextDelta) ev).text())
                .collect(StringBuilder::new, StringBuilder::append)
                .map(sb -> parsePlan(sb.toString()))
                .onErrorResume(e -> {
                    log.warn("Agent 规划失败(退回普通回合): {}", e.toString());
                    return Mono.just(AgentPlan.empty());
                });
    }

    /**
     * 把模型输出解析成计划。容错: 从文本里截出第一个 JSON 对象/数组再解析, 兼容 {@code {"steps":[...]}}
     * 与裸数组 {@code [...]} 两种形态; 解析不出则视为空计划。纯函数, 可单测。
     */
    public static AgentPlan parsePlan(String raw) {
        if (raw == null || raw.isBlank()) {
            return AgentPlan.empty();
        }
        String json = extractJson(raw);
        if (json == null) {
            return AgentPlan.empty();
        }
        try {
            JsonNode node = JSON.readTree(json);
            JsonNode arr = node.isArray() ? node : node.get("steps");
            if (arr == null || !arr.isArray()) {
                return AgentPlan.empty();
            }
            List<String> steps = new ArrayList<>();
            for (JsonNode s : arr) {
                String text = s.isTextual() ? s.asText() : (s.has("description") ? s.get("description").asText() : null);
                if (text != null && !text.isBlank()) {
                    steps.add(text.strip());
                }
                if (steps.size() >= MAX_STEPS) {
                    break;
                }
            }
            return AgentPlan.of(steps);
        } catch (Exception e) {
            log.debug("Agent 计划 JSON 解析失败, 当作空计划: {}", json);
            return AgentPlan.empty();
        }
    }

    /** 从可能含多余文字/代码围栏的输出里截取第一个完整 JSON 对象或数组(按括号配平)。包内共享(反思解析复用)。 */
    static String extractJson(String raw) {
        int objStart = raw.indexOf('{');
        int arrStart = raw.indexOf('[');
        int start;
        char open;
        char close;
        if (arrStart >= 0 && (objStart < 0 || arrStart < objStart)) {
            start = arrStart;
            open = '[';
            close = ']';
        } else if (objStart >= 0) {
            start = objStart;
            open = '{';
            close = '}';
        } else {
            return null;
        }
        int depth = 0;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
