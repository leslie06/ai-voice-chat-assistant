package com.vca.orchestrator.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 时效性启发式: 强时效问题命中(该联网), 普通闲聊/常识不命中(不触发搜索)。 */
class WebSearchHeuristicTest {

    @Test
    void hitsTimeSensitive() {
        assertTrue(WebSearchHeuristic.isTimeSensitive("今天有什么大新闻"));
        assertTrue(WebSearchHeuristic.isTimeSensitive("英伟达最新股价多少"));
        assertTrue(WebSearchHeuristic.isTimeSensitive("苹果最近发布了什么"));
        assertTrue(WebSearchHeuristic.isTimeSensitive("现在的美元汇率"));
        assertTrue(WebSearchHeuristic.isTimeSensitive("昨晚的比分"));
    }

    @Test
    void missesNonTimeSensitive() {
        assertFalse(WebSearchHeuristic.isTimeSensitive("你好呀"));
        assertFalse(WebSearchHeuristic.isTimeSensitive("帮我写一首诗"));
        assertFalse(WebSearchHeuristic.isTimeSensitive("水的沸点是多少度"));
        assertFalse(WebSearchHeuristic.isTimeSensitive(""));
        assertFalse(WebSearchHeuristic.isTimeSensitive(null));
    }
}
