package com.vca.domain.model;

import com.vca.domain.enums.AudioFormat;
import com.vca.domain.enums.VendorType;

/**
 * TTS 调用参数(不含密钥, 见 {@link AsrConfig} 说明)。
 *
 * @param vendor     选用厂商
 * @param voice      音色 id(厂商相关, 如 CosyVoice 的 "longxiaochun")
 * @param format     输出音频格式
 * @param sampleRate 输出采样率(Hz)
 * @param speed      语速倍率(1.0 正常)
 */
public record TtsConfig(
        VendorType vendor,
        String voice,
        AudioFormat format,
        int sampleRate,
        float speed
) {
    public TtsConfig {
        if (sampleRate <= 0) {
            sampleRate = 24000;
        }
        if (speed <= 0) {
            speed = 1.0f;
        }
    }

    public static TtsConfig defaults(VendorType vendor, String voice) {
        return new TtsConfig(vendor, voice, AudioFormat.MP3, 24000, 1.0f);
    }
}
