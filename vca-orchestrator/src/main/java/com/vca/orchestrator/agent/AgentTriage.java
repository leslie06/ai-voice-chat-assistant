package com.vca.orchestrator.agent;

/**
 * 判断一句用户请求是否"需要多步规划"。用于 Agent 路径的入口闸门(不依赖模型自觉): 只有命中的复杂任务
 * 才先规划再执行, 绝大多数闲聊/单步问答按原路零延迟走, 避免给简单回合平白加一道规划往返。
 *
 * <p>保守命中: 出现"分步/对比/规划/整理/先…再…/并/同时…"等明显的多意图、多步骤信号词才触发。
 * 纯函数, 可单测。和 {@link com.vca.orchestrator.search.WebSearchHeuristic} 同一套路。
 */
public final class AgentTriage {

    /** 多步/多意图信号词: 命中任一即认为该走 Agent 规划。 */
    private static final String[] SIGNALS = {
            "帮我规划", "规划一下", "做个计划", "制定计划", "安排一下", "行程",
            "分几步", "分步骤", "一步步", "步骤", "先后", "依次",
            "对比一下", "比较一下", "对比", "优缺点", "利弊", "哪个更",
            "整理成", "汇总", "总结一下并", "调研", "研究一下",
            "然后再", "接着再", "之后再", "并且帮我", "同时帮我", "再帮我",
            "查完", "查一下再", "搜一下再", "找出并", "分析并"
    };

    private AgentTriage() {
    }

    /**
     * @param request 本轮用户文本
     * @return 是否需要进入多步 Agent 规划路径
     */
    public static boolean isComplex(String request) {
        if (request == null || request.isBlank()) {
            return false;
        }
        String q = request.trim();
        for (String s : SIGNALS) {
            if (q.contains(s)) {
                return true;
            }
        }
        return false;
    }
}
