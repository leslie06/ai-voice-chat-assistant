package com.vca.orchestrator.search;

/**
 * 判断一句用户问题是否"时效性强、该联网查"。用于自动注入式联网搜索(不依赖模型自觉调工具)。
 * 保守命中: 只在出现明显的时效/实时信号词时才触发, 避免对普通闲聊产生无谓的搜索调用与延迟。纯函数, 可单测。
 */
public final class WebSearchHeuristic {

    /** 强时效信号词: 命中任一即认为该联网。 */
    private static final String[] SIGNALS = {
            "最新", "最近", "近期", "今天", "今日", "现在", "目前", "当前", "实时", "这两天", "这几天",
            "新闻", "头条", "热搜", "股价", "股票", "大盘", "汇率", "金价", "油价", "币价", "行情",
            "比分", "赛果", "战绩", "排名", "发布了", "上市", "上线", "刚刚", "最新消息", "天气预报",
            "今年", "本周", "这周", "本月", "几月几号最新", "涨了", "跌了", "多少钱现在"
    };

    private WebSearchHeuristic() {
    }

    public static boolean isTimeSensitive(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String q = query.trim();
        for (String s : SIGNALS) {
            if (q.contains(s)) {
                return true;
            }
        }
        return false;
    }
}
