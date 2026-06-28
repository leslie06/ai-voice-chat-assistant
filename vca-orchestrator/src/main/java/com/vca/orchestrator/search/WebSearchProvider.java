package com.vca.orchestrator.search;

import java.util.List;

/**
 * 联网搜索端口: 据查询词返回实时网页结果, 让助手能回答最新/时效性信息(新闻、行情、近期发布等)。
 * 与 {@link com.vca.orchestrator.knowledge.KnowledgeStore} 同为旁路 SPI, 由接入层实现(博查 Bocha);
 * 未注入实现(未配 key)时为 {@link #NOOP}, 返回空 —— 联网能力静默关闭, 不影响对话。
 */
public interface WebSearchProvider {

    WebSearchProvider NOOP = (query, count) -> List.of();

    /** 一条网页搜索结果。字段可能为空(实现按数据源尽力填充)。 */
    record Result(String title, String url, String snippet, String date) {
    }

    /** 搜索 {@code query}, 最多返回 {@code count} 条; 无结果/失败返回空列表(不抛)。 */
    List<Result> search(String query, int count);
}
