package com.vca.store;

import com.vca.domain.spi.MusicUploadStore;
import com.vca.store.mapper.UserMusicUploadMapper;
import com.vca.store.music.MyBatisMusicUploadStore;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MusicUploadStoreTest {

    @Test
    void savesAndListsOnlyCurrentUsersUploads() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:music-upload;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE user_music_upload (
                      id VARCHAR(36) PRIMARY KEY,
                      user_id BIGINT NOT NULL,
                      title VARCHAR(255) NOT NULL,
                      artist VARCHAR(255) NOT NULL,
                      original_filename VARCHAR(255) NOT NULL,
                      audio_object_key VARCHAR(512) NOT NULL,
                      lyrics_object_key VARCHAR(512),
                      audio_bytes BIGINT NOT NULL,
                      lyrics_bytes BIGINT NOT NULL,
                      rights_confirmed BOOLEAN NOT NULL,
                      status VARCHAR(16) NOT NULL DEFAULT 'pending',
                      text_labels VARCHAR(512),
                      text_reason VARCHAR(1024),
                      audio_task_id VARCHAR(128),
                      audio_risk_level VARCHAR(16),
                      moderation_labels VARCHAR(1024),
                      moderation_reason VARCHAR(2048),
                      reviewed_at TIMESTAMP,
                      created_at TIMESTAMP NOT NULL
                    )
                    """);
        }
        SqlSessionFactory factory = MyBatisSupport.sqlSessionFactory(dataSource);
        MusicUploadStore store = new MyBatisMusicUploadStore(
                MyBatisSupport.mapper(factory, UserMusicUploadMapper.class));
        store.save(upload("one", 7));
        store.save(upload("two", 8));

        assertThat(store.list(7)).hasSize(1);
        assertThat(store.list(7).get(0).id()).isEqualTo("one");
        assertThat(store.list(7).get(0).lyricsObjectKey()).endsWith(".lrc");
        assertThat(store.listApproved()).isEmpty();
        assertThat(store.listForReview(10)).hasSize(2);
        store.markAudioSubmitted("one", "", "", "task-one");
        store.completeReview("one", "approved", "none", "", "", Instant.now());
        assertThat(store.listApproved()).extracting(MusicUploadStore.Upload::id)
                .containsExactly("one");
        assertThat(store.isApprovedLyrics("music/users/7/one/晴天.lrc")).isTrue();
    }

    private static MusicUploadStore.Upload upload(String id, long userId) {
        return new MusicUploadStore.Upload(
                id, userId, "晴天", "周杰伦", "晴天.mp3",
                "music/users/" + userId + "/" + id + "/晴天.mp3",
                "music/users/" + userId + "/" + id + "/晴天.lrc",
                1234, 123, true, "pending", null, null, null, null,
                null, null, null, Instant.now());
    }
}
