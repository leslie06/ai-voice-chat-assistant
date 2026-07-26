package com.vca.store;

import com.vca.store.mapper.UserMusicPlayMapper;
import com.vca.store.music.MusicPlayService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class MusicPlayServiceTest {

    @Test
    void sameUserAndSongAccumulatesPlayCount() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:music-play;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        try (Connection connection = ds.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE user_music_play (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      user_id BIGINT NOT NULL,
                      song_key CHAR(64) NOT NULL,
                      title VARCHAR(255) NOT NULL,
                      artist VARCHAR(255) NOT NULL,
                      duration_sec INT NOT NULL DEFAULT 0,
                      play_count BIGINT NOT NULL DEFAULT 1,
                      first_played_at TIMESTAMP NOT NULL,
                      last_played_at TIMESTAMP NOT NULL,
                      UNIQUE (user_id, song_key)
                    )
                    """);
        }
        SqlSessionFactory factory = MyBatisSupport.sqlSessionFactory(ds);
        MusicPlayService service = new MusicPlayService(
                MyBatisSupport.mapper(factory, UserMusicPlayMapper.class));

        assertThat(service.record(7, "晴天", "周杰伦", 269)).isTrue();
        assertThat(service.record(7, "晴天", "周杰伦", 269)).isTrue();
        assertThat(service.record(8, "晴天", "周杰伦", 269)).isTrue();

        try (Connection connection = ds.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT user_id, play_count FROM user_music_play ORDER BY user_id")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong("user_id")).isEqualTo(7);
            assertThat(rows.getLong("play_count")).isEqualTo(2);
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong("user_id")).isEqualTo(8);
            assertThat(rows.getLong("play_count")).isEqualTo(1);
            assertThat(rows.next()).isFalse();
        }
    }
}
