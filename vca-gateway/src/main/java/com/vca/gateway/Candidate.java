package com.vca.gateway;

import com.vca.domain.enums.VendorType;

/**
 * 一个候选厂商及其调用参数。治理层按 Capability 维护一个有序候选列表:
 * 会话请求的厂商排首位, 其余作为故障转移备选。
 *
 * <p>model/voice 是厂商相关的, 故障转移时必须用候选自己的 model/voice, 不能沿用主厂商的。
 *
 * @param vendor         厂商
 * @param model          模型名(LLM/S2S 用; 可为 null 表示沿用会话配置)
 * @param voice          音色(TTS/S2S 用; 可为 null)
 * @param maxConcurrency 该厂商在本节点的最大并发(配额)
 */
public record Candidate(VendorType vendor, String model, String voice, int maxConcurrency) {
    public Candidate {
        if (maxConcurrency <= 0) maxConcurrency = 50;
    }

    public static Candidate of(VendorType vendor) {
        return new Candidate(vendor, null, null, 50);
    }
}
