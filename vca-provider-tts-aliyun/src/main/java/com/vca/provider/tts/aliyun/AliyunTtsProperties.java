package com.vca.provider.tts.aliyun;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;
import java.util.Set;

/**
 * 阿里云 DashScope 流式合成配置(CosyVoice 与 Qwen-Audio-3.0-TTS 共用)。
 *
 * <pre>
 * vca:
 *   providers:
 *     tts:
 *       aliyun:
 *         enabled: true
 *         api-key: ${DASHSCOPE_API_KEY}
 *         qwen-audio-flash-model: qwen-audio-3.0-tts-flash   # 主线(默认音色走这条)
 *         model: cosyvoice-v3-flash                          # 老音色/兜底
 * </pre>
 *
 * <p><b>为什么有三个模型名</b>: 这两个模型族走的是同一套 SpeechSynthesizer + WebSocket 协议,
 * 因此共用一个 provider; 但<b>音色表互不通用</b>, 把 CosyVoice 的音色喂给 Qwen-Audio 模型(反之亦然)
 * 会直接以 {@code InvalidParameter / Engine return error code: 418} 失败。而音色是前端逐句选的、
 * 模型却是进程级配置, 所以只能<b>由音色反推模型</b> —— 见 {@link #modelFor(String)}。
 *
 * <p>输出固定为 PCM 24kHz 单声道 16bit(前端按此采样率播放)。
 */
@ConfigurationProperties(prefix = "vca.providers.tts.aliyun")
public class AliyunTtsProperties {

    /**
     * Qwen-Audio-3.0-TTS-Flash 的系统音色(2026-07-20 上线时的 12 个)。
     * 注意 {@code longanlingxi}(flash) 与 {@code longanlingxin}(plus) 只差一个字母, 必须精确匹配。
     */
    private static final Set<String> QWEN_AUDIO_FLASH_VOICES = Set.of(
            "longanfengyue", "longanyuanfei", "longanlingxi", "longanxiaoxin", "longanhuan_v3.6",
            "longjielidou_v3.6", "longpaopao_v3.6", "longhuohuo_v3.6", "longchuanshu_v3.6",
            "loongmary", "loongeva_v3.6", "loongjohn");

    /** Qwen-Audio-3.0-TTS-Plus 的系统音色(旗舰音质, 比 flash 慢, 只有两个)。 */
    private static final Set<String> QWEN_AUDIO_PLUS_VOICES = Set.of(
            "longanlingxin", "longanlufeng");

    private boolean enabled = false;
    private String apiKey = "";

    /** CosyVoice 模型: 承接 {@code _v3}/无后缀等老音色, 也是未知音色(如声音复刻)的兜底。 */
    private String model = "cosyvoice-v3-flash";

    /** 主线模型: 首包约 300ms, 16 语种 + 20 方言, 支持细粒度情绪标签。 */
    private String qwenAudioFlashModel = "qwen-audio-3.0-tts-flash";

    /** 旗舰音质模型, 仅 {@link #QWEN_AUDIO_PLUS_VOICES} 两个音色用得上。 */
    private String qwenAudioPlusModel = "qwen-audio-3.0-tts-plus";

    /**
     * 按音色反推该用哪个模型。
     *
     * <p>两类音色:
     * <ol>
     *   <li><b>系统音色</b>: 查上面两张表;</li>
     *   <li><b>声音复刻音色</b>: 厂商返回的 id 就以创建时的 target_model 打头, 实测形如
     *       {@code qwen-audio-3.0-tts-flash-u1a-9528a83e439a428eb1b202e307f1eb24}, 所以直接
     *       拿配置里的模型名做前缀匹配即可 —— 换成快照版模型名(…-2026-07-20)也一样成立。</li>
     * </ol>
     *
     * <p>都不匹配时退回 {@link #model}, 保持与升级前一致的行为。
     */
    public String modelFor(String voice) {
        if (voice == null || voice.isBlank()) {
            return model;
        }
        String v = voice.trim().toLowerCase(Locale.ROOT);
        if (QWEN_AUDIO_FLASH_VOICES.contains(v)) {
            return qwenAudioFlashModel;
        }
        if (QWEN_AUDIO_PLUS_VOICES.contains(v)) {
            return qwenAudioPlusModel;
        }
        // 复刻音色: 先比更长的前缀, 避免 a-b 与 a-b-c 这类互为前缀的模型名判错
        for (String m : longestFirst(qwenAudioPlusModel, qwenAudioFlashModel, model)) {
            if (m != null && !m.isBlank() && v.startsWith(m.toLowerCase(Locale.ROOT) + "-")) {
                return m;
            }
        }
        return model;
    }

    /** 该音色是否支持指令控制(方言)。Qwen-Audio-3.0 全系支持, CosyVoice 这边只对复刻音色开放。 */
    public boolean supportsInstruction(String voice) {
        String m = modelFor(voice);
        return m != null && m.startsWith("qwen-audio-");
    }

    private static String[] longestFirst(String... models) {
        String[] copy = models.clone();
        java.util.Arrays.sort(copy, (a, b) -> {
            int la = a == null ? 0 : a.length();
            int lb = b == null ? 0 : b.length();
            return Integer.compare(lb, la);
        });
        return copy;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getQwenAudioFlashModel() {
        return qwenAudioFlashModel;
    }

    public void setQwenAudioFlashModel(String qwenAudioFlashModel) {
        this.qwenAudioFlashModel = qwenAudioFlashModel;
    }

    public String getQwenAudioPlusModel() {
        return qwenAudioPlusModel;
    }

    public void setQwenAudioPlusModel(String qwenAudioPlusModel) {
        this.qwenAudioPlusModel = qwenAudioPlusModel;
    }
}
