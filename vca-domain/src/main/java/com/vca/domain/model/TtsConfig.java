package com.vca.domain.model;

import com.vca.domain.enums.AudioFormat;
import com.vca.domain.enums.VendorType;

/**
 * TTS 调用参数(不含密钥, 见 {@link AsrConfig} 说明)。
 *
 * @param vendor      选用厂商
 * @param voice       音色 id(厂商相关, 如 CosyVoice 的 "longxiaochun")
 * @param format      输出音频格式
 * @param sampleRate  输出采样率(Hz)
 * @param speed       语速倍率(1.0 正常)
 * @param instruction 指令控制文本(可空)。自然语言描述该怎么念, 方言就靠它 ——
 *                    如"请用四川话表达。"。仅部分模型支持(Qwen-Audio-3.0 全系、CosyVoice v3/v3.5),
 *                    不支持的厂商必须丢弃本字段而不是原样转发。
 */
public record TtsConfig(
        VendorType vendor,
        String voice,
        AudioFormat format,
        int sampleRate,
        float speed,
        String instruction
) {
    public TtsConfig {
        if (sampleRate <= 0) {
            sampleRate = 24000;
        }
        if (speed <= 0) {
            speed = 1.0f;
        }
        if (instruction != null && instruction.isBlank()) {
            instruction = null;
        }
    }

    /** 不带指令的旧签名: 绝大多数调用点(电话开场白、故障转移改写等)都不需要指令。 */
    public TtsConfig(VendorType vendor, String voice, AudioFormat format, int sampleRate, float speed) {
        this(vendor, voice, format, sampleRate, speed, null);
    }

    public static TtsConfig defaults(VendorType vendor, String voice) {
        return new TtsConfig(vendor, voice, AudioFormat.MP3, 24000, 1.0f, null);
    }

    /** 换音色/指令, 其余参数保持不变。 */
    public TtsConfig withVoice(VendorType vendor, String voice, String instruction) {
        return new TtsConfig(vendor, voice, format, sampleRate, speed, instruction);
    }

    /** 去掉指令(跨厂商故障转移时用: 指令是厂商相关的, 转发给不认识它的厂商只会报错)。 */
    public TtsConfig withoutInstruction() {
        return instruction == null ? this
                : new TtsConfig(vendor, voice, format, sampleRate, speed, null);
    }
}
