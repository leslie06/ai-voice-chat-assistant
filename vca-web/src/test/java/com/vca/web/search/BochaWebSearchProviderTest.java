package com.vca.web.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vca.orchestrator.search.WebSearchProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 博查响应解析: 取 data.webPages.value, summary 优先 snippet, 防御式降级。不打网络。 */
class BochaWebSearchProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesWebPages() {
        String json = """
                {"code":200,"data":{"webPages":{"value":[
                  {"name":"标题A","url":"https://a.com","snippet":"摘要A","summary":"更全A","datePublished":"2026-06-28"},
                  {"name":"标题B","url":"https://b.com","snippet":"摘要B"}
                ]}}}""";
        List<WebSearchProvider.Result> out = BochaWebSearchProvider.parse(mapper, json, 5);
        assertEquals(2, out.size());
        assertEquals("标题A", out.get(0).title());
        assertEquals("更全A", out.get(0).snippet());   // summary 优先
        assertEquals("2026-06-28", out.get(0).date());
        assertEquals("摘要B", out.get(1).snippet());     // 无 summary 用 snippet
    }

    @Test
    void respectsLimit() {
        String json = """
                {"data":{"webPages":{"value":[
                  {"name":"1","snippet":"a"},{"name":"2","snippet":"b"},{"name":"3","snippet":"c"}
                ]}}}""";
        assertEquals(2, BochaWebSearchProvider.parse(mapper, json, 2).size());
    }

    @Test
    void emptyOrBadJsonYieldsEmpty() {
        assertTrue(BochaWebSearchProvider.parse(mapper, null, 5).isEmpty());
        assertTrue(BochaWebSearchProvider.parse(mapper, "", 5).isEmpty());
        assertTrue(BochaWebSearchProvider.parse(mapper, "{\"data\":{}}", 5).isEmpty());
        assertTrue(BochaWebSearchProvider.parse(mapper, "not json", 5).isEmpty());
    }
}
