package com.vca.store.knowledge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vca.store.embed.Embedder;
import com.vca.store.entity.KnowledgeChunk;
import com.vca.store.entity.KnowledgeDoc;
import com.vca.store.mapper.KnowledgeChunkMapper;
import com.vca.store.mapper.KnowledgeDocMapper;
import com.vca.store.util.TextChunker;
import com.vca.store.util.Vectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RAG 知识库的写侧与管理: 文档入库(切块 + 批量 embedding)、列表、删除。按 userId 隔离。
 * 检索(读侧)在 {@link MyBatisKnowledgeStore}。所有方法阻塞, 由 REST 层放 boundedElastic 调用。
 */
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final int MAX_TITLE = 255;

    private final KnowledgeDocMapper docs;
    private final KnowledgeChunkMapper chunks;
    /** 可空: 未配置 embedding 时入库不带向量, 检索召回为空(降级)。 */
    private final Embedder embedder;

    public KnowledgeService(KnowledgeDocMapper docs, KnowledgeChunkMapper chunks, Embedder embedder) {
        this.docs = docs;
        this.chunks = chunks;
        this.embedder = embedder;
    }

    /** 入库一篇文档: 建 doc + 切块 + 批量 embedding + 逐块 insert。返回切出的块数(0 表示无有效内容)。 */
    public int ingest(Long userId, String title, String rawText) {
        if (userId == null || rawText == null || rawText.isBlank()) {
            return 0;
        }
        List<String> pieces = TextChunker.chunk(rawText);
        if (pieces.isEmpty()) {
            return 0;
        }
        String t = (title == null || title.isBlank()) ? "未命名文档" : title.strip();
        if (t.length() > MAX_TITLE) {
            t = t.substring(0, MAX_TITLE);
        }
        LocalDateTime now = LocalDateTime.now();
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setUserId(userId);
        doc.setTitle(t);
        doc.setCreatedAt(now);
        docs.insert(doc);   // 回填自增 id

        List<float[]> vecs = embedder != null ? embedder.embedBatch(pieces) : List.of();
        for (int i = 0; i < pieces.size(); i++) {
            KnowledgeChunk ch = new KnowledgeChunk();
            ch.setUserId(userId);
            ch.setDocId(doc.getId());
            ch.setOrdinal(i);
            ch.setContent(pieces.get(i));
            if (i < vecs.size()) {
                ch.setEmbedding(Vectors.toBytes(vecs.get(i)));
            }
            ch.setCreatedAt(now);
            chunks.insert(ch);
        }
        log.info("知识库入库: user={}, doc={}, title={}, 切块={}", userId, doc.getId(), t, pieces.size());
        return pieces.size();
    }

    /** 列出某用户的文档(较新在前)。 */
    public List<KnowledgeDoc> list(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return docs.selectList(Wrappers.<KnowledgeDoc>query().eq("user_id", userId).orderByDesc("id"));
    }

    /** 删除某用户的一篇文档(连带其切块); 越权/不存在返回 false。 */
    public boolean delete(Long userId, Long docId) {
        if (userId == null || docId == null) {
            return false;
        }
        KnowledgeDoc doc = docs.selectById(docId);
        if (doc == null || !userId.equals(doc.getUserId())) {
            return false;
        }
        chunks.delete(Wrappers.<KnowledgeChunk>query().eq("user_id", userId).eq("doc_id", docId));
        docs.deleteById(docId);
        return true;
    }
}
