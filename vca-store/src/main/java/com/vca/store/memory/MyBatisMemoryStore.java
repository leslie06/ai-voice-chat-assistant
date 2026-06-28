package com.vca.store.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vca.orchestrator.memory.MemoryStore;
import com.vca.store.embed.Embedder;
import com.vca.store.entity.UserMemory;
import com.vca.store.mapper.UserMemoryMapper;
import com.vca.store.util.Vectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用 MyBatis-Plus 存取用户长期记忆({@code user_memory} 表)的 {@link MemoryStore} 实现。按 userId 隔离。
 *
 * <p><b>向量召回</b>: 写入时对内容做 embedding 存进 BLOB; {@code recall(userId, query)} 在 query 非空且
 * 有可用 embedding 时, 对 query 做 embedding、在该用户记忆里算余弦取最相关的若干条; 否则退回"最近 N 条"。
 * embedder 不可用(未配 key)时退化为纯文本: 写入不带向量、召回走最近 N —— 与升级前行为一致。
 */
public class MyBatisMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MyBatisMemoryStore.class);
    /** 退回"最近 N 条"或语义召回的候选扫描上限。 */
    private static final int MAX_RECALL = 40;
    /** 语义召回最终注入的条数与最低相似度门槛。 */
    private static final int SEMANTIC_TOP_K = 8;
    private static final double SEMANTIC_MIN_SCORE = 0.25;
    /** 语义召回时最多扫描该用户多少条候选(再在内存里算余弦)。 */
    private static final int SCAN_LIMIT = 500;
    private static final int MAX_LEN = 512;

    private final UserMemoryMapper memories;
    /** 可空: 未配置 embedding 时退化为关键词级(无向量)。 */
    private final Embedder embedder;

    public MyBatisMemoryStore(UserMemoryMapper memories, Embedder embedder) {
        this.memories = memories;
        this.embedder = embedder;
    }

    @Override
    public List<String> recall(String userId, String query) {
        Long uid = parse(userId);
        if (uid == null) {
            return List.of();
        }
        try {
            float[] q = (embedder != null && query != null && !query.isBlank()) ? embedder.embed(query) : null;
            if (q == null) {
                return recentTexts(uid);   // 无 query / 无 embedding → 最近 N 条
            }
            List<UserMemory> rows = memories.selectList(Wrappers.<UserMemory>query()
                    .eq("user_id", uid).orderByDesc("id").last("limit " + SCAN_LIMIT));
            List<Vectors.Scored<UserMemory>> top = Vectors.topK(q, rows,
                    m -> Vectors.fromBytes(m.getEmbedding()), SEMANTIC_TOP_K, SEMANTIC_MIN_SCORE);
            if (top.isEmpty()) {
                return recentTexts(uid);   // 都没向量或都不相关 → 兜底最近 N
            }
            return top.stream().map(s -> s.item().getContent())
                    .filter(s -> s != null && !s.isBlank()).toList();
        } catch (Exception e) {
            log.debug("recall 失败(忽略): {}", e.toString());
            return List.of();
        }
    }

    private List<String> recentTexts(Long uid) {
        return memories.selectList(Wrappers.<UserMemory>query()
                        .eq("user_id", uid).orderByDesc("id").last("limit " + MAX_RECALL))
                .stream().map(UserMemory::getContent)
                .filter(s -> s != null && !s.isBlank()).toList();
    }

    @Override
    public void remember(String userId, String content) {
        Long uid = parse(userId);
        if (uid == null || content == null || content.isBlank()) {
            return;
        }
        String c = content.strip();
        if (c.length() > MAX_LEN) {
            c = c.substring(0, MAX_LEN);
        }
        try {
            // 去重: 同一用户已有完全相同的记忆就不再存
            Long exists = memories.selectCount(Wrappers.<UserMemory>query().eq("user_id", uid).eq("content", c));
            if (exists != null && exists > 0) {
                return;
            }
            UserMemory m = new UserMemory();
            m.setUserId(uid);
            m.setContent(c);
            if (embedder != null) {
                m.setEmbedding(Vectors.toBytes(embedder.embed(c)));   // 失败→null, 仍存内容
            }
            m.setCreatedAt(LocalDateTime.now());
            memories.insert(m);
            log.info("记住长期记忆: user={}, content={}", uid, c);
        } catch (Exception e) {
            log.warn("remember 失败(忽略): {}", e.toString());
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
