package com.vca.store.voice;

import com.vca.domain.spi.VoiceCloneStore;
import com.vca.store.entity.UserVoiceClone;
import com.vca.store.mapper.UserVoiceCloneMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/** MySQL 实现的声音复刻音色元数据存储。 */
public final class MyBatisVoiceCloneStore implements VoiceCloneStore {

    private final UserVoiceCloneMapper mapper;

    public MyBatisVoiceCloneStore(UserVoiceCloneMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(Clone clone) {
        UserVoiceClone e = new UserVoiceClone();
        e.setVoiceId(clone.voiceId());
        e.setUserId(clone.userId());
        e.setName(clone.name());
        e.setTargetModel(clone.targetModel());
        e.setSampleSeconds(clone.sampleSeconds());
        e.setRightsConfirmed(clone.rightsConfirmed());
        e.setStatus(clone.status() == null ? "active" : clone.status());
        e.setCreatedAt(local(clone.createdAt()));
        e.setLastUsedAt(local(clone.lastUsedAt()));
        mapper.insert(e);
    }

    @Override
    public List<Clone> list(long userId) {
        return mapper.listByUser(userId).stream().map(MyBatisVoiceCloneStore::toClone).toList();
    }

    @Override
    public Optional<Clone> find(String voiceId) {
        if (voiceId == null || voiceId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.find(voiceId)).map(MyBatisVoiceCloneStore::toClone);
    }

    @Override
    public int countByUser(long userId) {
        return mapper.countByUser(userId);
    }

    @Override
    public int countCreatedSince(long userId, Instant since) {
        return mapper.countCreatedSince(userId, local(since));
    }

    @Override
    public void touchUsed(String voiceId, Instant usedAt) {
        mapper.touchUsed(voiceId, local(usedAt));
    }

    @Override
    public void markInvalid(String voiceId) {
        mapper.markInvalid(voiceId);
    }

    @Override
    public boolean delete(String voiceId, long userId) {
        return mapper.delete(voiceId, userId) > 0;
    }

    private static LocalDateTime local(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private static Clone toClone(UserVoiceClone e) {
        return new Clone(
                e.getVoiceId(),
                e.getUserId() == null ? 0L : e.getUserId(),
                e.getName(),
                e.getTargetModel(),
                e.getSampleSeconds() == null ? 0 : e.getSampleSeconds(),
                Boolean.TRUE.equals(e.getRightsConfirmed()),
                e.getStatus(),
                instant(e.getCreatedAt()),
                instant(e.getLastUsedAt()));
    }
}
