package com.vca.provider.tts.aliyun;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁住"音色 → 模型"的映射。这层错了不会编译报错, 只会在真机上以
 * {@code InvalidParameter / Engine return error code: 418} 失败, 所以用测试兜住。
 */
class AliyunTtsPropertiesTest {

    private final AliyunTtsProperties props = new AliyunTtsProperties();

    @Test
    void qwen音色走新模型() {
        assertThat(props.modelFor("longanhuan_v3.6")).isEqualTo("qwen-audio-3.0-tts-flash");
        assertThat(props.modelFor("longanfengyue")).isEqualTo("qwen-audio-3.0-tts-flash");
        assertThat(props.modelFor("loongjohn")).isEqualTo("qwen-audio-3.0-tts-flash");
        assertThat(props.modelFor("longchuanshu_v3.6")).isEqualTo("qwen-audio-3.0-tts-flash");
    }

    @Test
    void plus音色走plus模型() {
        assertThat(props.modelFor("longanlingxin")).isEqualTo("qwen-audio-3.0-tts-plus");
        assertThat(props.modelFor("longanlufeng")).isEqualTo("qwen-audio-3.0-tts-plus");
    }

    /** longanlingxi(flash) 与 longanlingxin(plus) 只差一个字母, 必须精确匹配而非前缀匹配。 */
    @Test
    void 相似音色不会互相串台() {
        assertThat(props.modelFor("longanlingxi")).isEqualTo("qwen-audio-3.0-tts-flash");
        assertThat(props.modelFor("longanlingxin")).isEqualTo("qwen-audio-3.0-tts-plus");
    }

    @Test
    void cosyvoice老音色仍回落老模型() {
        // _v3 后缀的普通话音色, 以及只有 CosyVoice 才有的方言/外语音色
        assertThat(props.modelFor("longanhuan_v3")).isEqualTo("cosyvoice-v3-flash");
        assertThat(props.modelFor("longjiaxin_v3")).isEqualTo("cosyvoice-v3-flash");
        assertThat(props.modelFor("loongyuuna_v3")).isEqualTo("cosyvoice-v3-flash");
        assertThat(props.modelFor("longanyang")).isEqualTo("cosyvoice-v3-flash");
    }

    @Test
    void 未知音色与空值回落默认模型() {
        // 声音复刻出来的 id 事先不可枚举, 必须退回配置的 model 而不是猜一个新模型
        assertThat(props.modelFor("cosyvoice-v3-prefix-xxxx")).isEqualTo("cosyvoice-v3-flash");
        assertThat(props.modelFor(null)).isEqualTo("cosyvoice-v3-flash");
        assertThat(props.modelFor("  ")).isEqualTo("cosyvoice-v3-flash");
    }

    @Test
    void 大小写与空格不影响判定() {
        assertThat(props.modelFor("  LongAnHuan_V3.6 ")).isEqualTo("qwen-audio-3.0-tts-flash");
    }

    /** 复刻音色的 id 以创建时的 target_model 打头(真机实测格式), 靠这个前缀路由回同一个模型。 */
    @Test
    void 复刻音色按前缀路由回绑定的模型() {
        assertThat(props.modelFor("qwen-audio-3.0-tts-flash-u1a-9528a83e439a428eb1b202e307f1eb24"))
                .isEqualTo("qwen-audio-3.0-tts-flash");
        assertThat(props.modelFor("qwen-audio-3.0-tts-plus-u1a-9528a83e439a428eb1b202e307f1eb24"))
                .isEqualTo("qwen-audio-3.0-tts-plus");
        assertThat(props.modelFor("cosyvoice-v3-flash-u1a-9528a83e439a428eb1b202e307f1eb24"))
                .isEqualTo("cosyvoice-v3-flash");
    }

    /** 模型名换成快照版时前缀更长, 必须先比长的, 否则 flash 的复刻音色会被判给它的短前缀。 */
    @Test
    void 快照版模型名也能前缀路由() {
        props.setQwenAudioFlashModel("qwen-audio-3.0-tts-flash-2026-07-20");
        assertThat(props.modelFor("qwen-audio-3.0-tts-flash-2026-07-20-u1a-9528a83e"))
                .isEqualTo("qwen-audio-3.0-tts-flash-2026-07-20");
    }

    @Test
    void 指令控制仅对qwen_audio开放() {
        assertThat(props.supportsInstruction("longanhuan_v3.6")).isTrue();
        assertThat(props.supportsInstruction("qwen-audio-3.0-tts-flash-u1a-9528a83e")).isTrue();
        assertThat(props.supportsInstruction("longanhuan_v3")).isFalse();
        assertThat(props.supportsInstruction("longjiaxin_v3")).isFalse();
    }

    @Test
    void 配置能覆盖各自的模型名() {
        props.setQwenAudioFlashModel("qwen-audio-3.0-tts-flash-2026-07-20");
        props.setModel("cosyvoice-v2");
        assertThat(props.modelFor("longanhuan_v3.6")).isEqualTo("qwen-audio-3.0-tts-flash-2026-07-20");
        assertThat(props.modelFor("longanhuan_v3")).isEqualTo("cosyvoice-v2");
    }
}
