package com.vca.store.music;

import com.vca.domain.spi.MusicUploadStore;
import com.vca.store.entity.UserMusicUpload;
import com.vca.store.mapper.UserMusicUploadMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** MySQL 实现的用户上传歌曲元数据存储。 */
public final class MyBatisMusicUploadStore implements MusicUploadStore {

    private final UserMusicUploadMapper mapper;

    public MyBatisMusicUploadStore(UserMusicUploadMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(Upload upload) {
        UserMusicUpload entity = new UserMusicUpload();
        entity.setId(upload.id());
        entity.setUserId(upload.userId());
        entity.setTitle(upload.title());
        entity.setArtist(upload.artist());
        entity.setOriginalFilename(upload.originalFilename());
        entity.setAudioObjectKey(upload.audioObjectKey());
        entity.setLyricsObjectKey(upload.lyricsObjectKey());
        entity.setAudioBytes(upload.audioBytes());
        entity.setLyricsBytes(upload.lyricsBytes());
        entity.setRightsConfirmed(upload.rightsConfirmed());
        entity.setStatus(upload.status());
        entity.setCreatedAt(java.time.LocalDateTime.ofInstant(upload.createdAt(), ZoneId.systemDefault()));
        mapper.insert(entity);
    }

    @Override
    public List<Upload> list(long userId) {
        return map(mapper.listByUser(userId));
    }

    @Override
    public List<Upload> listApproved() {
        return map(mapper.listApproved());
    }

    @Override
    public List<Upload> listForReview(int limit) {
        return map(mapper.listForReview(Math.max(1, Math.min(limit, 100))));
    }

    @Override
    public boolean isApprovedLyrics(String lyricsObjectKey) {
        return lyricsObjectKey != null && mapper.countApprovedLyrics(lyricsObjectKey) > 0;
    }

    @Override
    public void markAudioSubmitted(String id, String textLabels, String textReason, String audioTaskId) {
        mapper.markAudioSubmitted(id, textLabels, textReason, audioTaskId);
    }

    @Override
    public void completeReview(String id, String status, String audioRiskLevel,
                               String moderationLabels, String moderationReason, Instant reviewedAt) {
        mapper.completeReview(id, status, audioRiskLevel, moderationLabels, moderationReason,
                reviewedAt == null ? null
                        : java.time.LocalDateTime.ofInstant(reviewedAt, ZoneId.systemDefault()));
    }

    private static List<Upload> map(List<UserMusicUpload> entities) {
        return entities.stream().map(entity -> new Upload(
                entity.getId(),
                entity.getUserId(),
                entity.getTitle(),
                entity.getArtist(),
                entity.getOriginalFilename(),
                entity.getAudioObjectKey(),
                entity.getLyricsObjectKey(),
                entity.getAudioBytes() == null ? 0 : entity.getAudioBytes(),
                entity.getLyricsBytes() == null ? 0 : entity.getLyricsBytes(),
                Boolean.TRUE.equals(entity.getRightsConfirmed()),
                entity.getStatus() == null ? "pending" : entity.getStatus(),
                entity.getTextLabels(),
                entity.getTextReason(),
                entity.getAudioTaskId(),
                entity.getAudioRiskLevel(),
                entity.getModerationLabels(),
                entity.getModerationReason(),
                entity.getReviewedAt() == null
                        ? null
                        : entity.getReviewedAt().atZone(ZoneId.systemDefault()).toInstant(),
                entity.getCreatedAt() == null
                        ? Instant.EPOCH
                        : entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant()))
                .toList();
    }
}
