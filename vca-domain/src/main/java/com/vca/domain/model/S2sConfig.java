package com.vca.domain.model;

import com.vca.domain.enums.AudioFormat;
import com.vca.domain.enums.VendorType;

/**
 * 端到端语音大模型(Speech-to-Speech)调用参数。
 * 这类厂商把 ASR+LLM+TTS 融合在一个流里, 延迟最低但可定制性低。
 *
 * @param vendor       选用厂商(如 VOLCANO 的实时语音大模型)
 * @param model        模型名
 * @param voice        回复音色
 * @param systemPrompt 人设/约束
 * @param outputFormat 下行音频格式
 */
public record S2sConfig(
        VendorType vendor,
        String model,
        String voice,
        String systemPrompt,
        AudioFormat outputFormat
) {
    public static S2sConfig defaults(VendorType vendor, String model, String voice) {
        return new S2sConfig(vendor, model, voice,
                "你是一个语音助手，用自然口语化的中文对话。", AudioFormat.PCM);
    }
}
