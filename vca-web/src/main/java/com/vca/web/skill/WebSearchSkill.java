package com.vca.web.skill;

import com.vca.orchestrator.search.WebSearchProvider;
import com.vca.orchestrator.skill.Skill;
import com.vca.orchestrator.skill.SkillResult;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * 联网搜索技能(数据型)。当用户需要最新/实时/超出模型知识范围的信息(新闻、行情、赛事、近期发布、
 * 今天的情况等)时, 模型调用本工具检索, 把结果<b>回灌</b>给模型据此作答(可注明来源)——多一次 LLM 往返。
 *
 * <p>底层走 {@link WebSearchProvider}(博查 Bocha)。与编排层的"自动注入"互补: 命中时效启发式时编排已自动注入,
 * 模型一般不再调本工具; 启发式没覆盖到、但模型自己判断需要联网时, 仍可经本工具补上。
 *
 * <p>仅当配置了搜索 key、{@link WebSearchProvider} 在场时才注册(见 WebAutoConfiguration)。
 */
public final class WebSearchSkill implements Skill {

    public static final String NAME = "web_search";

    private final WebSearchProvider provider;
    private final int count;

    public WebSearchSkill(WebSearchProvider provider, int count) {
        this.provider = provider == null ? WebSearchProvider.NOOP : provider;
        this.count = count > 0 ? count : 5;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "当用户的问题需要最新、实时或超出你知识范围的信息时调用 —— 例如新闻热点、股价行情汇率、"
                + "体育赛果、近期发布/上市、今天/最近发生的事、某事物的当前状态等。query 用简洁的检索关键词。"
                + "纯常识、历史知识、闲聊不要调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of(
                        "type", "string",
                        "description", "联网检索的关键词或问题, 用核心词, 不要带寒暄。")),
                "required", List.of("query"));
    }

    @Override
    public Mono<SkillResult> execute(Map<String, Object> args) {
        String query = str(args.get("query"));
        if (query == null || query.isBlank()) {
            return Mono.just(SkillResult.feedback("没搜到相关信息。"));
        }
        // 阻塞的 HTTP 调用放到弹性线程, 不占编排/事件循环线程
        return Mono.fromCallable(() -> provider.search(query.strip(), count))
                .subscribeOn(Schedulers.boundedElastic())
                .map(hits -> {
                    if (hits == null || hits.isEmpty()) {
                        return SkillResult.feedback("联网没搜到相关信息。");
                    }
                    StringBuilder sb = new StringBuilder("联网搜索结果(请据此回答, 可注明来源):");
                    for (WebSearchProvider.Result r : hits) {
                        if (r == null) {
                            continue;
                        }
                        sb.append("\n- ");
                        if (r.title() != null && !r.title().isBlank()) {
                            sb.append(r.title().strip()).append(": ");
                        }
                        if (r.snippet() != null) {
                            sb.append(r.snippet().strip());
                        }
                        if (r.date() != null && !r.date().isBlank()) {
                            sb.append(" (").append(r.date().strip()).append(")");
                        }
                        if (r.url() != null && !r.url().isBlank()) {
                            sb.append(" 来源: ").append(r.url().strip());
                        }
                    }
                    return SkillResult.feedback(sb.toString());
                })
                .onErrorReturn(SkillResult.feedback("联网搜索出错了。"));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
