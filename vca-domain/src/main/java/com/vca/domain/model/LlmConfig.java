package com.vca.domain.model;

import com.vca.domain.enums.VendorType;

/**
 * LLM 调用参数(不含密钥, 见 {@link AsrConfig} 说明)。
 *
 * @param vendor       选用厂商
 * @param model        模型名(如 "deepseek-chat" / "qwen-plus")
 * @param systemPrompt 系统提示词(人设/约束); 语音场景建议要求"口语化、简短"
 * @param temperature  采样温度
 * @param maxTokens    单轮最大输出 token; 语音场景建议适中以降低延迟
 */
public record LlmConfig(
        VendorType vendor,
        String model,
        String systemPrompt,
        double temperature,
        int maxTokens
) {
    public LlmConfig {
        if (maxTokens <= 0) {
            maxTokens = 1024;
        }
    }

    public static LlmConfig defaults(VendorType vendor, String model) {
        return new LlmConfig(
                vendor, model,
                "你是一个语音助手，请用口语化、简短的中文回答，避免长段落和列表。"
                        + "只回答用户当前这句话，不要复述、重复或续写之前已经说过的内容。",
                0.7, 1024);
    }
}
