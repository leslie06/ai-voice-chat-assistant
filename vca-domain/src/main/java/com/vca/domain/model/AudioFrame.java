package com.vca.domain.model;

/**
 * 上行音频帧: 从浏览器采集、经 VAD 过滤后送往 ASR 的一小段音频。
 *
 * @param data         音频字节(默认 16kHz/16bit 单声道 PCM)
 * @param sequence     帧序号, 从 0 递增, 用于乱序检测
 * @param timestampMs  采集时刻(客户端单调时钟, 毫秒), 用于延迟埋点
 * @param endOfSpeech  是否为本轮说话的最后一帧(VAD 判停)。true 时 ASR 应尽快出 final
 */
public record AudioFrame(
        byte[] data,
        long sequence,
        long timestampMs,
        boolean endOfSpeech
) {
    /** 普通中间帧 */
    public static AudioFrame of(byte[] data, long sequence, long timestampMs) {
        return new AudioFrame(data, sequence, timestampMs, false);
    }

    /** 收尾帧: 通知 ASR 本轮结束 */
    public static AudioFrame endOfSpeech(long sequence, long timestampMs) {
        return new AudioFrame(new byte[0], sequence, timestampMs, true);
    }

    public int size() {
        return data == null ? 0 : data.length;
    }
}
