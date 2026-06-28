package com.vca.store.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vca.orchestrator.memory.MemoryStore;
import com.vca.store.entity.UserMemory;
import com.vca.store.mapper.UserMemoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;

/** 用 MyBatis-Plus 存取用户长期记忆({@code user_memory} 表)的 {@link MemoryStore} 实现。按 userId 隔离。 */
public class MyBatisMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MyBatisMemoryStore.class);
    /** 回灌上下文时最多取多少条(较新优先), 防止 prompt 膨胀。 */
    private static final int MAX_RECALL = 40;
    private static final int MAX_LEN = 512;

    private final UserMemoryMapper memories;

    public MyBatisMemoryStore(UserMemoryMapper memories) {
        this.memories = memories;
    }

    @Override
    public List<String> recall(String userId) {
        Long uid = parse(userId);
        if (uid == null) {
            return List.of();
        }
        try {
            return memories.selectList(Wrappers.<UserMemory>query()
                            .eq("user_id", uid).orderByDesc("id").last("limit " + MAX_RECALL))
                    .stream().map(UserMemory::getContent)
                    .filter(s -> s != null && !s.isBlank()).toList();
        } catch (Exception e) {
            log.debug("recall 失败(忽略): {}", e.toString());
            return List.of();
        }
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
