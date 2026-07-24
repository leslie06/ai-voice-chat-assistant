package com.vca.web.music;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OssMusicProviderTest {

    @Test
    void parsesSupportedObjectAndSplitsArtistTitle() {
        OssMusicProvider.Song song = OssMusicProvider.songOf("music/华语/周杰伦 - 晴天.mp3", 1024);

        assertEquals("周杰伦", song.artist());
        assertEquals("晴天", song.title());
        assertEquals("music/华语/周杰伦 - 晴天.mp3", song.key());
    }

    @Test
    void ignoresFoldersEmptyObjectsAndUnsupportedFiles() {
        assertNull(OssMusicProvider.songOf("music/", 1));
        assertNull(OssMusicProvider.songOf("music/空文件.mp3", 0));
        assertNull(OssMusicProvider.songOf("music/readme.txt", 10));
    }

    @Test
    void selectsBestSongByTitle() {
        List<OssMusicProvider.Song> songs = List.of(
                OssMusicProvider.songOf("music/周杰伦 - 晴天.mp3", 10),
                OssMusicProvider.songOf("music/周杰伦 - 七里香.mp3", 10));

        OssMusicProvider.Song hit = OssMusicProvider.bestSong(songs, "晴天");

        assertEquals("晴天", hit.title());
    }
}
