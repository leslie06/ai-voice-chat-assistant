package com.vca.store.mapper;

import com.vca.store.entity.UserVoiceClone;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 用户声音复刻音色 Mapper。 */
public interface UserVoiceCloneMapper {

    String COLUMNS = """
            voice_id, user_id, name, target_model, sample_seconds,
            rights_confirmed, status, created_at, last_used_at
            """;

    @Insert("""
            INSERT INTO user_voice_clone
                (voice_id, user_id, name, target_model, sample_seconds,
                 rights_confirmed, status, created_at, last_used_at)
            VALUES
                (#{voiceId}, #{userId}, #{name}, #{targetModel}, #{sampleSeconds},
                 #{rightsConfirmed}, #{status}, #{createdAt}, #{lastUsedAt})
            """)
    int insert(UserVoiceClone clone);

    @Select("""
            SELECT voice_id, user_id, name, target_model, sample_seconds,
                   rights_confirmed, status, created_at, last_used_at
            FROM user_voice_clone
            WHERE user_id = #{userId}
            ORDER BY created_at DESC
            """)
    List<UserVoiceClone> listByUser(@Param("userId") long userId);

    @Select("""
            SELECT voice_id, user_id, name, target_model, sample_seconds,
                   rights_confirmed, status, created_at, last_used_at
            FROM user_voice_clone
            WHERE voice_id = #{voiceId}
            """)
    UserVoiceClone find(@Param("voiceId") String voiceId);

    @Select("SELECT COUNT(1) FROM user_voice_clone WHERE user_id = #{userId}")
    int countByUser(@Param("userId") long userId);

    @Select("""
            SELECT COUNT(1) FROM user_voice_clone
            WHERE user_id = #{userId} AND created_at >= #{since}
            """)
    int countCreatedSince(@Param("userId") long userId, @Param("since") LocalDateTime since);

    @Update("UPDATE user_voice_clone SET last_used_at = #{usedAt} WHERE voice_id = #{voiceId}")
    int touchUsed(@Param("voiceId") String voiceId, @Param("usedAt") LocalDateTime usedAt);

    @Update("UPDATE user_voice_clone SET status = 'invalid' WHERE voice_id = #{voiceId}")
    int markInvalid(@Param("voiceId") String voiceId);

    @Delete("DELETE FROM user_voice_clone WHERE voice_id = #{voiceId} AND user_id = #{userId}")
    int delete(@Param("voiceId") String voiceId, @Param("userId") long userId);
}
