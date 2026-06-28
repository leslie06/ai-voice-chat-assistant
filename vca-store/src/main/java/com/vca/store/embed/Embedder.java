package com.vca.store.embed;

import java.util.List;

/**
 * 文本向量化端口(store 内部用, 不进编排层)。长期记忆与 RAG 知识库共用同一实现。
 * 实现为<b>阻塞</b>调用(底层一次 HTTP 往返), 只应在 boundedElastic / 后台 worker 线程上调用;
 * 任何失败返回 null(单条)或与输入等长、对应位可为 null 的列表(批量), 调用方据此降级。
 */
public interface Embedder {

    /** 向量化一段文本; 失败返回 null。 */
    float[] embed(String text);

    /** 批量向量化(顺序与输入一致); 整体失败返回与输入等长的全 null 列表, 调用方逐条判空。 */
    List<float[]> embedBatch(List<String> texts);
}
