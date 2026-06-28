package com.vca.orchestrator.knowledge;

import java.util.List;

/**
 * 知识库检索端口(RAG): 按 {@code userId} 隔离, 据当前问题语义召回该用户上传文档里最相关的片段。
 * 与 {@link com.vca.orchestrator.memory.MemoryStore} 同为旁路 SPI, 由 {@code vca-store} 实现(向量检索);
 * 未注入实现(账号系统/embedding 未启用)时为 {@link #NOOP}, 返回空 —— 模型据此知道"没有可用资料"。
 *
 * <p>仅负责<b>检索</b>; 文档的上传/切块/入库由 store 侧的 REST(/api/knowledge)处理, 编排层无需感知。
 */
public interface KnowledgeStore {

    KnowledgeStore NOOP = (userId, query) -> List.of();

    /** 据 {@code query} 召回该用户知识库里最相关的若干片段(原文); 无命中返回空列表。 */
    List<String> search(String userId, String query);
}
