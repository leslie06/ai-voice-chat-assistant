package com.vca.orchestrator;

import com.vca.orchestrator.skill.MusicIntent;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicIntentTest {

    private final MusicIntent intent = new MusicIntent();

    @Test
    void extractsSongFromCommonPhrasings() {
        assertEquals(Optional.of("晴天"), intent.parsePlay("我想听晴天"));
        assertEquals(Optional.of("周杰伦的晴天"), intent.parsePlay("放一首周杰伦的晴天"));
        assertEquals(Optional.of("七里香"), intent.parsePlay("播放七里香"));
        assertEquals(Optional.of("夜曲"), intent.parsePlay("点歌 夜曲 谢谢"));
    }

    @Test
    void ignoresNonMusicChat() {
        // 没有触发词
        assertTrue(intent.parsePlay("今天天气怎么样").isEmpty());
        // 触发词后没有具体歌名
        assertTrue(intent.parsePlay("我想听歌").isEmpty());
        // 空/null
        assertTrue(intent.parsePlay("").isEmpty());
        assertTrue(intent.parsePlay(null).isEmpty());
    }
}
