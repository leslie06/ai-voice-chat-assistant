package com.vca.domain.model;

import com.vca.domain.enums.VendorType;

import java.util.List;

/**
 * ASR 调用参数。注意: 不含 apiKey/密钥 —— 凭证由治理层(gateway)按厂商+多Key注入,
 * 编排层只描述"想要什么"。
 *
 * @param vendor            选用厂商
 * @param language          语言代码(如 "zh-CN")
 * @param sampleRate        采样率(Hz), 默认 16000
 * @param hotWords          业务热词, 直接提升专有名词识别准确率
 * @param enablePunctuation 是否开启智能标点
 */
public record AsrConfig(
        VendorType vendor,
        String language,
        int sampleRate,
        List<String> hotWords,
        boolean enablePunctuation
) {
    public AsrConfig {
        if (sampleRate <= 0) {
            sampleRate = 16000;
        }
        hotWords = hotWords == null ? List.of() : List.copyOf(hotWords);
    }

    public static AsrConfig defaults(VendorType vendor) {
        return new AsrConfig(vendor, "zh-CN", 16000, List.of(), true);
    }
}
