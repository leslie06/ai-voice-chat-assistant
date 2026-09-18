package com.vca.orchestrator.lead;

/**
 * 线索存储端口。<b>住在编排层而不是电话模块</b>: 与 {@code KnowledgeStore}/{@code MemoryStore} 一样,
 * 端口在编排层、实现在 {@code vca-store}、使用方在接入层 —— 否则 store 要反过来依赖电话模块。
 *与 {@code KnowledgeStore}/{@code MemoryStore} 同为旁路 SPI: 电话模块只声明"要存",
 * 具体落哪张表由 {@code vca-store} 实现; 未启用落库时是 {@link #NOOP}(工具照常可用, 只是存不下来)。
 */
public interface LeadStore {

    LeadStore NOOP = lead -> false;

    /**
     * 存一条线索。
     *
     * @return 是否真的存下来了 —— false 时技能不会让客户以为已经约上, 而是改口成"稍后有人回电"
     */
    boolean save(Lead lead);
}
