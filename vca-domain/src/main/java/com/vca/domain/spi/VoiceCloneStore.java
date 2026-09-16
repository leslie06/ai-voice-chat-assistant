package com.vca.domain.spi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 用户声音复刻音色的元数据存储。
 *
 * <p>音色本体在厂商侧(阿里云百炼), 这里只存"谁的、叫什么、绑哪个模型、上次什么时候用过"。
 * 之所以要落库而不是每次去厂商 list:
 * <ul>
 *   <li><b>归属</b>: 厂商的音色表是账号级的, 不区分用户。不落库就无法阻止 A 猜到 B 的
 *       voice_id 后拿来用 —— voice_id 是会在前端明文出现的;</li>
 *   <li><b>配额</b>: 厂商账号上限 1000 个, 要按用户分配额;</li>
 *   <li><b>过期</b>: 厂商会自动删除"过去 1 年未用过"的音色, 靠 {@code lastUsedAt} 才能提前提醒。</li>
 * </ul>
 */
public interface VoiceCloneStore {

    void save(Clone clone);

    /** 该用户的音色, 新的在前。 */
    List<Clone> list(long userId);

    /** 按 voice id 查, 同时用于归属校验。 */
    Optional<Clone> find(String voiceId);

    int countByUser(long userId);

    /** 该用户在 {@code since} 之后创建了几个(按天限流用)。 */
    int countCreatedSince(long userId, Instant since);

    /** 记一次使用, 用于推算厂商侧的自动清理时间。 */
    void touchUsed(String voiceId, Instant usedAt);

    /** 合成时被厂商判为无效(音色已被清理/删除)时标记, 前端据此提示重新复刻。 */
    void markInvalid(String voiceId);

    /** 删除, 只允许删自己的; 返回是否真的删掉了。 */
    boolean delete(String voiceId, long userId);

    /**
     * @param voiceId       厂商返回的音色 id, 形如 {@code qwen-audio-3.0-tts-flash-u1a-9528a83e...}
     * @param userId        归属用户
     * @param name          用户起的显示名
     * @param targetModel   创建时绑定的合成模型, 合成时必须一模一样, 不能跨模型复用
     * @param sampleSeconds 样本时长(秒), 便于排查相似度问题
     * @param status        active / invalid
     */
    record Clone(
            String voiceId,
            long userId,
            String name,
            String targetModel,
            int sampleSeconds,
            boolean rightsConfirmed,
            String status,
            Instant createdAt,
            Instant lastUsedAt
    ) {
        public boolean active() {
            return !"invalid".equals(status);
        }
    }
}
