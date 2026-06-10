package com.vca.orchestrator.vad;

/**
 * 能量(RMS)阈值法 VAD: 原前端的做法, 现作为 {@link VoiceActivityDetector} 的默认实现与降级方案。
 * 零依赖、零延迟, 但抗噪差 —— 安静环境够用, 嘈杂环境建议切 {@link SileroVad}。
 */
public final class EnergyVad implements VoiceActivityDetector {

    @Override
    public double speechProbability(short[] frame16k) {
        return PcmAudio.rms(frame16k);
    }
}
