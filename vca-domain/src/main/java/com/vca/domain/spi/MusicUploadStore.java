package com.vca.domain.spi;

import java.time.Instant;
import java.util.List;

/** 登录用户上传歌曲的元数据存储。音频本体保存在对象存储。 */
public interface MusicUploadStore {

    void save(Upload upload);

    List<Upload> list(long userId);

    /** 所有审核通过、可以公开播放的上传歌曲。 */
    List<Upload> listApproved();

    /** 等待后台审核或等待异步音频结果的任务。 */
    List<Upload> listForReview(int limit);

    boolean isApprovedLyrics(String lyricsObjectKey);

    void markAudioSubmitted(String id, String textLabels, String textReason, String audioTaskId);

    void completeReview(String id, String status, String audioRiskLevel,
                        String moderationLabels, String moderationReason, Instant reviewedAt);

    record Upload(
            String id,
            long userId,
            String title,
            String artist,
            String originalFilename,
            String audioObjectKey,
            String lyricsObjectKey,
            long audioBytes,
            long lyricsBytes,
            boolean rightsConfirmed,
            String status,
            String textLabels,
            String textReason,
            String audioTaskId,
            String audioRiskLevel,
            String moderationLabels,
            String moderationReason,
            Instant reviewedAt,
            Instant createdAt
    ) {
    }
}
