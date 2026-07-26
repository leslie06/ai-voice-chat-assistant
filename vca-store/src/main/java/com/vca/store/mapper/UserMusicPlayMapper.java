package com.vca.store.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** 用户听歌统计 Mapper。相同用户、相同歌曲使用 MySQL upsert 原子累计，避免并发丢次数。 */
public interface UserMusicPlayMapper {

    @Insert("""
            INSERT INTO user_music_play
                (user_id, song_key, title, artist, duration_sec, play_count, first_played_at, last_played_at)
            VALUES
                (#{userId}, #{songKey}, #{title}, #{artist}, #{durationSec}, 1, #{playedAt}, #{playedAt})
            ON DUPLICATE KEY UPDATE
                title = VALUES(title),
                artist = VALUES(artist),
                duration_sec = VALUES(duration_sec),
                play_count = play_count + 1,
                last_played_at = VALUES(last_played_at)
            """)
    int record(@Param("userId") long userId,
               @Param("songKey") String songKey,
               @Param("title") String title,
               @Param("artist") String artist,
               @Param("durationSec") int durationSec,
               @Param("playedAt") LocalDateTime playedAt);
}
