package com.vca.domain.enums;

/**
 * 厂商能力类型。一个厂商可提供多种能力(如阿里同时有 ASR/TTS)，
 * 在治理层按 (Capability, VendorType) 维度做配额与路由。
 */
public enum Capability {
    /** 流式语音识别 (Speech-to-Text) */
    ASR,
    /** 大模型对话 (流式 token) */
    LLM,
    /** 流式语音合成 (Text-to-Speech) */
    TTS,
    /** 端到端语音大模型 (Speech-to-Speech, 融合 ASR+LLM+TTS) */
    S2S
}
