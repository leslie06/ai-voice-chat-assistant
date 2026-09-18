package com.vca.orchestrator.call;

/**
 * 通话小结存储端口。与 {@code LeadStore}/{@code KnowledgeStore} 同构: 端口在编排层、实现在 {@code vca-store}、
 * 使用方在接入层。未启用落库时是 {@link #NOOP} —— 摘要照样生成、照样推送, 只是没有历史可翻。
 */
public interface CallSummaryStore {

    CallSummaryStore NOOP = summary -> false;

    /** @return 是否落库成功; 失败只记日志, 不影响推送 */
    boolean save(CallSummary summary);
}
