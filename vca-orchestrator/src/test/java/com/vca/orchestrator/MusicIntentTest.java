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

    @Test
    void leavesFuzzyDescriptionsToLlmTool() {
        // 模糊描述不该被正则硬截歌名, 应放过去给 LLM 的 play_music 工具理解
        assertTrue(intent.parsePlay("给我播放一首适合下雨天听的歌").isEmpty());
        assertTrue(intent.parsePlay("放点伤感一点的").isEmpty());
        assertTrue(intent.parsePlay("来首适合开车听的歌").isEmpty());
        assertTrue(intent.parsePlay("随便放首歌").isEmpty());
        // 具体歌名/歌手仍走快路径(含"的"的歌名也不能误伤)
        assertEquals(Optional.of("周杰伦的晴天"), intent.parsePlay("放一首周杰伦的晴天"));
        assertEquals(Optional.of("七里香"), intent.parsePlay("播放七里香"));
    }

    @Test
    void recognizesSkipCommands() {
        for (String said : new String[]{
                "下一首", "下一曲", "下首", "切歌", "切一首", "换一首", "换首歌", "换个歌", "跳过这首",
                "下一首歌", "切歌吧", "请切歌", "帮我下一首", "我想听下一首", "下一首。", "跳过这首歌"}) {
            assertEquals(Optional.of(MusicIntent.CONTROL_NEXT), intent.parseControl(said),
                    "应识别为切歌: " + said);
        }
    }

    @Test
    void recognizesOtherControlCommands() {
        assertEquals(Optional.of(MusicIntent.CONTROL_PREVIOUS), intent.parseControl("上一首"));
        assertEquals(Optional.of(MusicIntent.CONTROL_PREVIOUS), intent.parseControl("上一曲"));
        assertEquals(Optional.of(MusicIntent.CONTROL_PREVIOUS), intent.parseControl("回上一首歌"));
        assertEquals(Optional.of(MusicIntent.CONTROL_PAUSE), intent.parseControl("暂停"));
        assertEquals(Optional.of(MusicIntent.CONTROL_PAUSE), intent.parseControl("暂停播放"));
        assertEquals(Optional.of(MusicIntent.CONTROL_PAUSE), intent.parseControl("先暂停一下"));
        assertEquals(Optional.of(MusicIntent.CONTROL_RESUME), intent.parseControl("继续"));
        assertEquals(Optional.of(MusicIntent.CONTROL_RESUME), intent.parseControl("继续播放"));
        assertEquals(Optional.of(MusicIntent.CONTROL_RESUME), intent.parseControl("接着放吧"));
        assertEquals(Optional.of(MusicIntent.CONTROL_STOP), intent.parseControl("停止播放"));
        assertEquals(Optional.of(MusicIntent.CONTROL_STOP), intent.parseControl("别放了"));
        assertEquals(Optional.of(MusicIntent.CONTROL_STOP), intent.parseControl("关掉音乐"));
        assertEquals(Optional.of(MusicIntent.CONTROL_STOP), intent.parseControl("不听了"));
    }

    @Test
    void doesNotTreatBareStopAsCommand() {
        // "停"太短, ASR 噪声里容易误命中, 且更像是让助手别说了 —— 故意不收
        assertTrue(intent.parseControl("停").isEmpty());
    }

    @Test
    void doesNotHijackNormalQuestions() {
        // 整句锚定的意义就在这里: 这些都含"下一首/切歌", 但都是正常对话, 不能被当成命令吞掉
        for (String said : new String[]{
                "下一首歌是什么", "下一首歌叫什么名字", "这首歌的下一首是哪首",
                "你能帮我切歌吗", "怎么切歌", "为什么不能下一首", "下一首歌我想听周杰伦",
                "上一首歌是谁唱的", "继续说", "继续讲这个故事", "暂停一下我去开门",
                "怎么暂停播放", "停止播放要按哪里"}) {
            assertTrue(intent.parseControl(said).isEmpty(), "不该被当成切歌命令: " + said);
        }
        assertTrue(intent.parseControl("").isEmpty());
        assertTrue(intent.parseControl(null).isEmpty());
    }

    @Test
    void skipCommandsAreNotMistakenForSongNames() {
        // "我想听下一首"两边都能沾边(parsePlay 会抽出歌名"下一首"), 故 respond() 里控歌必须排在点歌之前
        assertEquals(Optional.of(MusicIntent.CONTROL_NEXT), intent.parseControl("我想听下一首"));
    }
}
