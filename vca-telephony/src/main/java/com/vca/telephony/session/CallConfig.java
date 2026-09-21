package com.vca.telephony.session;

/**
 * 一路通话的参数。对应配置前缀 {@code vca.telephony.*}。
 *
 * @param pacingMs        下行节流粒度(ms)。电话上取 20(与 RTP 包长一致); 调大省 CPU 但打断粒度变粗
 * @param maxBufferedMs   下行缓冲上限(ms), 超出丢弃新音频
 * @param ttsSampleRate   TTS/S2S 产出的 PCM 采样率(Hz)。阿里云 CosyVoice 默认 24000, 会被降采样到线路速率
 * @param maxCallSeconds  单通最长时长(s), 到点主动挂机; <=0 不限。外呼必须设, 否则一通挂死的电话会一直烧钱
 * @param greetingBargeIn 开场白是否可被打断。外呼场景应为 true —— 客户常在开场白中途就说"不需要"
 * @param toneHangup      听到线路信号音(忙音/拨号音/拥塞音)就挂机。模拟线没有挂机信令, 客户挂断后线上只是
 *                        开始放忙音; 网关的忙音检测漏配/配错是常态, 这里是兜底, 见 {@link LineToneDetector}
 * @param answerGuardMs   接通后头这么久的上行音频不交给 VAD(ms); <=0 关闭。模拟线摘机瞬间线上有一个
 *                        响度、时长都够得上"开口"的冲击脉冲(实测接通后 0.2~0.9 秒, 峰值 0.16~0.22),
 *                        不挡掉它, 开场白会被当成"客户插话"清掉, 客户接通后只听到半个"您好"
 * @param noSpeechHangupMs 连续这么久"有人在说话"却一个字都没识别出来, 判定为线路噪声并挂机(ms); <=0 关闭。
 *                        兜住 450Hz 之外的信号音、传真音、串线噪声 —— 真人连说 20 秒不可能一个字都识别不出
 */
public record CallConfig(
        int pacingMs,
        int maxBufferedMs,
        int ttsSampleRate,
        int maxCallSeconds,
        boolean greetingBargeIn,
        boolean toneHangup,
        int noSpeechHangupMs,
        int answerGuardMs) {

    /** 电话默认: 20ms 节流、30s 缓冲上限、TTS 24k、单通 5 分钟、开场白可打断、忙音挂机开、20 秒无字挂机 */
    public static CallConfig defaults() {
        return new CallConfig(20, 30_000, 24_000, 300, true, true, 20_000, 1500);
    }
}
