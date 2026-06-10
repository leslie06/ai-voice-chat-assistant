package com.vca.domain.enums;

/**
 * 厂商标识。新增厂商时在此登记一个枚举值，对应一个独立的 provider 实现模块。
 * 上层编排只认接口 + 该枚举，不感知具体 SDK。
 */
public enum VendorType {
    // ---- ASR / TTS 能力厂商 ----
    ALIYUN("aliyun", "阿里云"),
    XFYUN("xfyun", "讯飞"),
    TENCENT("tencent", "腾讯云"),
    VOLCANO("volcano", "火山引擎"),

    // ---- LLM 厂商 ----
    QWEN("qwen", "通义千问"),
    DEEPSEEK("deepseek", "DeepSeek"),
    DOUBAO("doubao", "豆包"),
    MOONSHOT("moonshot", "Kimi"),
    ZHIPU("zhipu", "智谱GLM");

    private final String code;
    private final String displayName;

    VendorType(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public static VendorType fromCode(String code) {
        for (VendorType v : values()) {
            if (v.code.equalsIgnoreCase(code)) {
                return v;
            }
        }
        throw new IllegalArgumentException("未知厂商 code: " + code);
    }
}
