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
 * @param model             模型名。留空 = 用该厂商配置里的默认模型。
 *                          电话链路要用它指定 8k 窄带模型: 线路只有 8kHz, 拿宽带模型去听等于让它在
 *                          本该有高频摩擦音的地方瞎猜(洗牙被听成抵押、拿、压就是这么来的)。
 * @param vocabularyId      热词表 id(厂商侧注册好的)。留空 = 用该厂商配置里的默认值。
 *                          窄带电话线上这是提准的主要手段: 同一段 8kHz 音频, 不带热词"洗牙"被识别成
 *                          "抵押", 带上就对了。热词表与目标模型绑定, 换模型要重建。
 */
public record AsrConfig(
        VendorType vendor,
        String language,
        int sampleRate,
        List<String> hotWords,
        boolean enablePunctuation,
        String model,
        String vocabularyId
) {
    public AsrConfig {
        if (sampleRate <= 0) {
            sampleRate = 16000;
        }
        hotWords = hotWords == null ? List.of() : List.copyOf(hotWords);
        model = model == null ? "" : model.strip();
        vocabularyId = vocabularyId == null ? "" : vocabularyId.strip();
    }

    public static AsrConfig defaults(VendorType vendor) {
        return new AsrConfig(vendor, "zh-CN", 16000, List.of(), true, "", "");
    }
}
