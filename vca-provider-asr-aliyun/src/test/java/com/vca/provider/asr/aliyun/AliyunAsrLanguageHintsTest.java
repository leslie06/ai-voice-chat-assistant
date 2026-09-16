package com.vca.provider.asr.aliyun;

import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语种提示。不下发时模型按默认 zh+en 自动判语种, 陕西话这类方言会明显变差;
 * 下发一个厂商不认的值又会把整条识别搞挂, 所以这层映射必须锁住。
 */
class AliyunAsrLanguageHintsTest {

    @Test
    void 中文各种写法都归到zh() {
        for (String s : List.of("zh", "zh-CN", "zh_CN", "ZH-cn", " zh ", "cmn", "zho")) {
            assertThat(AliyunAsrProvider.languageHints(s)).as(s).containsExactly("zh");
        }
    }

    /** 方言没有独立取值, 一律走 zh —— 这正是本次修复的场景。 */
    @Test
    void 方言归在zh下() {
        assertThat(AliyunAsrProvider.languageHints("zh-CN")).containsExactly("zh");
    }

    /** 粤语是唯一有独立取值的中文变体, 且常写成 zh-HK, 必须在落到 zh 之前拦下。 */
    @Test
    void 粤语单独走yue() {
        for (String s : List.of("yue", "zh-HK", "zh-yue", "zh-MO", "cantonese")) {
            assertThat(AliyunAsrProvider.languageHints(s)).as(s).containsExactly("yue");
        }
    }

    @Test
    void 其它语种按短码映射() {
        assertThat(AliyunAsrProvider.languageHints("en-US")).containsExactly("en");
        assertThat(AliyunAsrProvider.languageHints("ja")).containsExactly("ja");
        assertThat(AliyunAsrProvider.languageHints("ko")).containsExactly("ko");
        assertThat(AliyunAsrProvider.languageHints("de-DE")).containsExactly("de");
        assertThat(AliyunAsrProvider.languageHints("fr")).containsExactly("fr");
        assertThat(AliyunAsrProvider.languageHints("ru")).containsExactly("ru");
    }

    /** 不认识就返回空 = 不下发, 回到模型自动判别; 绝不能瞎猜一个值发出去。 */
    @Test
    void 不认识的写法不下发() {
        for (String s : List.of("", "  ", "klingon", "zz-ZZ", "陕西话")) {
            assertThat(AliyunAsrProvider.languageHints(s)).as(s).isEmpty();
        }
        assertThat(AliyunAsrProvider.languageHints(null)).isEmpty();
    }

    /** SDK 没有 languageHints 的 builder 方法, 只能走 parameter(); 确认它确实落进请求参数里。 */
    @Test
    void 参数确实进了请求体() {
        RecognitionParam param = RecognitionParam.builder()
                .model("paraformer-realtime-v2")
                .format("pcm")
                .sampleRate(16000)
                .apiKey("k")
                .parameter("language_hints", AliyunAsrProvider.languageHints("zh-CN"))
                .build();
        assertThat(param.getParameters()).containsEntry("language_hints", List.of("zh"));
    }
}
