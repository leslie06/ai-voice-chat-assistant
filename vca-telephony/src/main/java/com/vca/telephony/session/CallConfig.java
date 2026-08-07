package com.vca.telephony.session;

/**
 * 一路通话的参数。对应配置前缀 {@code vca.telephony.*}。
 *
 * @param pacingMs        下行节流粒度(ms)。电话上取 20(与 RTP 包长一致); 调大省 CPU 但打断粒度变粗
 * @param maxBufferedMs   下行缓冲上限(ms), 超出丢弃新音频
 * @param ttsSampleRate   TTS/S2S 产出的 PCM 采样率(Hz)。阿里云 CosyVoice 默认 24000, 会被降采样到线路速率
 * @param maxCallSeconds  单通最长时长(s), 到点主动挂机; <=0 不限。外呼必须设, 否则一通挂死的电话会一直烧钱
 * @param greetingBargeIn 开场白是否可被打断。外呼场景应为 true —— 客户常在开场白中途就说"不需要"
 */
public record CallConfig(
        int pacingMs,
        int maxBufferedMs,
        int ttsSampleRate,
        int maxCallSeconds,
        boolean greetingBargeIn) {

    /** 电话默认: 20ms 节流、30s 缓冲上限、TTS 24k、单通 5 分钟、开场白可打断 */
    public static CallConfig defaults() {
        return new CallConfig(20, 30_000, 24_000, 300, true);
    }
}
