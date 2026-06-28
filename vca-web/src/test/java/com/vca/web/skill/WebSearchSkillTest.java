package com.vca.web.skill;

import com.vca.orchestrator.search.WebSearchProvider;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
// SkillResult 通过 r.content()/r.terminal() 访问, 无需直接 import

/** web_search 工具: 声明、命中回灌结果、空结果与缺参的兜底。stub provider, 不打网络。 */
class WebSearchSkillTest {

    @Test
    void declaresTool() {
        WebSearchSkill skill = new WebSearchSkill((q, c) -> List.of(), 5);
        assertEquals(WebSearchSkill.NAME, skill.name());
        assertFalse(skill.description().isBlank());
        assertEquals(List.of("query"), skill.parameters().get("required"));
    }

    @Test
    void feedsBackResults() {
        WebSearchProvider stub = (q, c) -> List.of(
                new WebSearchProvider.Result("某新闻", "https://x.com", "事件摘要", "2026-06-28"));
        WebSearchSkill skill = new WebSearchSkill(stub, 5);
        StepVerifier.create(skill.execute(Map.of("query", "今天新闻")))
                .assertNext(r -> {
                    assertFalse(r.terminal());   // 数据型: 回灌模型, 不终结
                    assertTrue(r.content().contains("某新闻"));
                    assertTrue(r.content().contains("https://x.com"));
                })
                .verifyComplete();
    }

    @Test
    void emptyResultsAndMissingQuery() {
        WebSearchSkill skill = new WebSearchSkill((q, c) -> List.of(), 5);
        StepVerifier.create(skill.execute(Map.of("query", "今天新闻")))
                .assertNext(r -> assertTrue(r.content().contains("没搜到")))
                .verifyComplete();
        StepVerifier.create(skill.execute(Map.of()))
                .assertNext(r -> assertTrue(r.content().contains("没搜到")))
                .verifyComplete();
    }
}
