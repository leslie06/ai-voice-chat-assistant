package com.vca.web.music;

import com.vca.domain.model.MusicTrack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalMusicProviderTest {

    @TempDir
    Path dir;

    private LocalMusicProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        // 造一个小曲库(含子目录), 文件内容无所谓
        touch(dir.resolve("周杰伦-晴天.mp3"));
        touch(dir.resolve("周杰伦-七里香.mp3"));
        Path sub = Files.createDirectories(dir.resolve("网易云音乐"));
        touch(sub.resolve("光阴的故事-罗大佑.mp3"));
        touch(dir.resolve("readme.txt"));   // 非音频, 应被忽略
        provider = new LocalMusicProvider(dir.toString());
    }

    private void touch(Path p) throws IOException {
        Files.writeString(p, "x");
    }

    @Test
    void matchesByPartialName_andReturnsFullPlayableUrl() {
        StepVerifier.create(provider.search("晴天"))
                .assertNext(t -> {
                    assertEquals("晴天", t.title());      // "歌手 - 歌名" 拆分
                    assertEquals("周杰伦", t.artist());
                    assertTrue(t.full(), "本地曲库应是整首");
                    assertTrue(t.playUrl().startsWith("/music/files/"), t.playUrl());
                    assertTrue(t.playUrl().endsWith(".mp3"));
                })
                .verifyComplete();
    }

    @Test
    void matchesFileInSubdirectory() {
        StepVerifier.create(provider.search("光阴的故事"))
                .assertNext(t -> assertTrue(t.playUrl().contains("/music/files/"), t.playUrl()))
                .verifyComplete();
    }

    @Test
    void ignoresQueryWithoutSpecificEnoughMatch() {
        // 曲库里没有"不存在的歌"相关字, 应返回空
        StepVerifier.create(provider.search("完全不沾边XYZ")).verifyComplete();
    }

    @Test
    void emptyOnBlankOrMissingDir() {
        StepVerifier.create(provider.search("  ")).verifyComplete();
        StepVerifier.create(new LocalMusicProvider("/no/such/dir/xyz").search("晴天")).verifyComplete();
    }
}
