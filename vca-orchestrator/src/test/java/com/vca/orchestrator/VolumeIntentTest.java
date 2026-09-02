package com.vca.orchestrator;

import com.vca.orchestrator.skill.VolumeIntent;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 音量命令识别。与控歌同样是整句锚定 —— 关键词出现在提问里时不能被劫持。 */
class VolumeIntentTest {

    @Test
    void recognizesLouder() {
        for (String said : new String[]{
                "声音大点", "声音大一点", "大点声", "大声点", "大声一点", "音量大点", "调大音量",
                "声音太小", "太小声", "听不清", "请大声点", "能不能大声一点", "声音大点吧"}) {
            assertEquals(Optional.of(VolumeIntent.VOLUME_UP), VolumeIntent.parse(said), "应识别为调大: " + said);
        }
    }

    @Test
    void recognizesQuieter() {
        for (String said : new String[]{
                "声音小点", "声音小一点", "小点声", "小声点", "音量小点", "调小音量",
                "声音太大", "太大声", "太吵", "帮我小声一点"}) {
            assertEquals(Optional.of(VolumeIntent.VOLUME_DOWN), VolumeIntent.parse(said), "应识别为调小: " + said);
        }
    }

    @Test
    void doesNotHijackNormalSpeech() {
        // 这些都含关键词, 但都是正常对话 —— 整句锚定就是为了挡住它们
        for (String said : new String[]{
                "怎么调音量", "音量在哪里调", "声音大点能听清吗", "为什么声音太小",
                "他说话声音太大了我受不了", "这首歌音量小点会不会更好听", "把电视声音大点"}) {
            assertTrue(VolumeIntent.parse(said).isEmpty(), "不该被当成音量命令: " + said);
        }
        assertTrue(VolumeIntent.parse("").isEmpty());
        assertTrue(VolumeIntent.parse(null).isEmpty());
    }
}
