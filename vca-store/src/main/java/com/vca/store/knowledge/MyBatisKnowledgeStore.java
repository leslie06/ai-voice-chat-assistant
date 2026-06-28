package com.vca.store.knowledge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vca.orchestrator.knowledge.KnowledgeStore;
import com.vca.store.embed.Embedder;
import com.vca.store.entity.KnowledgeChunk;
import com.vca.store.mapper.KnowledgeChunkMapper;
import com.vca.store.util.Vectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * RAG 检索(读侧)的 {@link KnowledgeStore} 实现: 对 query 做 embedding, 在该用户的全部切块上算余弦取 top-K。
 * embedder 不可用时返回空(模型据此知道无资料可依)。检索是暴力余弦, 单用户几千块内毫秒级; 过万再换向量库。
 */
public class MyBatisKnowledgeStore implements KnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(MyBatisKnowledgeStore.class);
    private static final int TOP_K = 5;
    private static final double MIN_SCORE = 0.30;
    /** 单用户最多扫描的切块数(再在内存里算余弦)。 */
    private static final int SCAN_LIMIT = 5000;

    private final KnowledgeChunkMapper chunks;
    private final Embedder embedder;

    public MyBatisKnowledgeStore(KnowledgeChunkMapper chunks, Embedder embedder) {
        this.chunks = chunks;
        this.embedder = embedder;
    }

    @Override
    public List<String> search(String userId, String query) {
        Long uid = parse(userId);
        if (uid == null || embedder == null || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            float[] q = embedder.embed(query);
            if (q == null) {
                return List.of();
            }
            List<KnowledgeChunk> rows = chunks.selectList(Wrappers.<KnowledgeChunk>query()
                    .eq("user_id", uid).orderByDesc("id").last("limit " + SCAN_LIMIT));
            return Vectors.topK(q, rows, c -> Vectors.fromBytes(c.getEmbedding()), TOP_K, MIN_SCORE)
                    .stream().map(s -> s.item().getContent())
                    .filter(s -> s != null && !s.isBlank()).toList();
        } catch (Exception e) {
            log.debug("知识库检索失败(忽略): {}", e.toString());
            return List.of();
        }
    }

    private static Long parse(String userId) {
        try {
            return userId == null ? null : Long.parseLong(userId);
        } catch (Exception e) {
            return null;
        }
    }
}
