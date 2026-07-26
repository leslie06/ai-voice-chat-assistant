package com.vca.store.mapper;

import com.vca.store.entity.UserMusicUpload;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 用户上传歌曲 Mapper。 */
public interface UserMusicUploadMapper {

    @Insert("""
            INSERT INTO user_music_upload
                (id, user_id, title, artist, original_filename, audio_object_key, lyrics_object_key,
                 audio_bytes, lyrics_bytes, rights_confirmed, created_at)
            VALUES
                (#{id}, #{userId}, #{title}, #{artist}, #{originalFilename}, #{audioObjectKey},
                 #{lyricsObjectKey}, #{audioBytes}, #{lyricsBytes}, #{rightsConfirmed}, #{createdAt})
            """)
    int insert(UserMusicUpload upload);

    @Select("""
            SELECT id, user_id, title, artist, original_filename, audio_object_key, lyrics_object_key,
                   audio_bytes, lyrics_bytes, rights_confirmed, status, text_labels, text_reason,
                   audio_task_id, audio_risk_level, moderation_labels, moderation_reason,
                   reviewed_at, created_at
            FROM user_music_upload
            WHERE user_id = #{userId}
            ORDER BY created_at DESC
            """)
    List<UserMusicUpload> listByUser(@Param("userId") long userId);

    @Select("""
            SELECT id, user_id, title, artist, original_filename, audio_object_key, lyrics_object_key,
                   audio_bytes, lyrics_bytes, rights_confirmed, status, text_labels, text_reason,
                   audio_task_id, audio_risk_level, moderation_labels, moderation_reason,
                   reviewed_at, created_at
            FROM user_music_upload
            WHERE status = 'approved'
            ORDER BY created_at DESC
            """)
    List<UserMusicUpload> listApproved();

    @Select("""
            SELECT id, user_id, title, artist, original_filename, audio_object_key, lyrics_object_key,
                   audio_bytes, lyrics_bytes, rights_confirmed, status, text_labels, text_reason,
                   audio_task_id, audio_risk_level, moderation_labels, moderation_reason,
                   reviewed_at, created_at
            FROM user_music_upload
            WHERE status IN ('pending', 'reviewing')
            ORDER BY created_at
            LIMIT #{limit}
            """)
    List<UserMusicUpload> listForReview(@Param("limit") int limit);

    @Select("""
            SELECT COUNT(1) FROM user_music_upload
            WHERE status = 'approved' AND lyrics_object_key = #{lyricsObjectKey}
            """)
    int countApprovedLyrics(@Param("lyricsObjectKey") String lyricsObjectKey);

    @Update("""
            UPDATE user_music_upload
            SET status = 'reviewing', text_labels = #{textLabels}, text_reason = #{textReason},
                audio_task_id = #{audioTaskId}
            WHERE id = #{id} AND status IN ('pending', 'reviewing')
            """)
    int markAudioSubmitted(@Param("id") String id,
                           @Param("textLabels") String textLabels,
                           @Param("textReason") String textReason,
                           @Param("audioTaskId") String audioTaskId);

    @Update("""
            UPDATE user_music_upload
            SET status = #{status}, audio_risk_level = #{audioRiskLevel},
                moderation_labels = #{moderationLabels}, moderation_reason = #{moderationReason},
                reviewed_at = #{reviewedAt}
            WHERE id = #{id}
            """)
    int completeReview(@Param("id") String id,
                       @Param("status") String status,
                       @Param("audioRiskLevel") String audioRiskLevel,
                       @Param("moderationLabels") String moderationLabels,
                       @Param("moderationReason") String moderationReason,
                       @Param("reviewedAt") java.time.LocalDateTime reviewedAt);
}
