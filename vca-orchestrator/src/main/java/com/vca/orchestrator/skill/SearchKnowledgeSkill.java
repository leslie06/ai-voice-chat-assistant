package com.vca.orchestrator.skill;

import com.vca.orchestrator.knowledge.KnowledgeStore;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 知识库检索技能(数据型, RAG): 当用户的问题可能要依据其上传的文档/个人资料回答时, 模型调用本工具
 * 用一句检索词去知识库里召回相关片段, 召回结果回灌给模型据此作答。userId 由 {@code ConversationSession}
 * 在调用时注入 args(键 {@link #USER_ID_ARG}) —— 技能本身是单例 Bean, 不持有用户态。
 */
public class SearchKnowledgeSkill implements Skill {

    public static final String NAME = "search_knowledge";
    /** ConversationSession 注入当前用户 id 的 args 键(与 RememberSkill 同一约定)。 */
    public static final String USER_ID_ARG = RememberSkill.USER_ID_ARG;

    private final KnowledgeStore knowledge;

    public SearchKnowledgeSkill(KnowledgeStore knowledge) {
        this.knowledge = knowledge == null ? KnowledgeStore.NOOP : knowledge;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "检索用户上传到知识库的文档。只要用户问的是具体事实类问题 —— 产品、套餐、价格、功能、政策、"
                + "公司信息、订单/售后规则, 或他自己上传的资料 —— 就应<b>优先调用本工具</b>检索, 据召回的原文作答, "
                + "不要仅凭常识或猜测回答(知识库内容可能与你的预设不同, 以检索结果为准)。"
                + "只有纯闲聊、通用常识、与任何资料都无关时才不调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of(
                        "type", "string",
                        "description", "用于检索知识库的关键词或问题, 用用户问题里的核心词, 不要带寒暄。")),
                "required", List.of("query"));
    }

    @Override
    public Mono<SkillResult> execute(Map<String, Object> args) {
        String userId = str(args.get(USER_ID_ARG));
        String query = str(args.get("query"));
        if (userId == null || userId.isBlank() || query == null || query.isBlank()) {
            return Mono.just(SkillResult.feedback("资料库里没找到相关内容。"));
        }
        List<String> hits = knowledge.search(userId, query.strip());
        if (hits == null || hits.isEmpty()) {
            return Mono.just(SkillResult.feedback("资料库里没找到相关内容。"));
        }
        StringBuilder sb = new StringBuilder("依据知识库检索到以下资料, 据此回答用户:");
        for (String h : hits) {
            if (h != null && !h.isBlank()) {
                sb.append("\n- ").append(h.strip());
            }
        }
        return Mono.just(SkillResult.feedback(sb.toString()));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
