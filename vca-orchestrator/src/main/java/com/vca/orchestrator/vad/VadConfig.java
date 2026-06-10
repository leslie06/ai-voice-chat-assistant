package com.vca.orchestrator.vad;

/**
 * 服务端语音活动检测(VAD)/断句参数。原先散落在浏览器前端的阈值, 现统一收口到后端。
 *
 * <p>{@code speechThreshold}/{@code bargeThreshold} 与所选检测器同尺度:
 * 能量法下是 RMS(语音约 0.015~0.1), Silero 下是人声概率(0~1, 通常 0.5/0.6)。
 * 切换检测器时记得一并调这两个阈值 —— {@link #silero(String)} 已给出 Silero 的合理默认。
 *
 * @param speechThreshold 人声判定阈值; 超过视为有人声(尺度随 {@code useSilero} 而定)
 * @param onsetMs         人声需持续这么久(ms)才判定为"开口", 开启本轮
 * @param silenceMs       句尾静音持续这么久(ms)即判定说完, 自动提交本轮
 * @param bargeThreshold  打断阈值, 通常略高于 {@code speechThreshold}; 抗回声自打断主要靠抬高它
 * @param bargeMs         机器人说话时人声持续这么久(ms)即判定插话→打断
 * @param prerollMs       预滚: 保留开口前这么久(ms)的音频随首帧补发, 不切第一个字
 * @param targetSampleRate VAD/ASR 统一采样率(Hz), 上行音频会重采样到此(Silero 要求 16000)
 * @param useSilero       是否启用 Silero(ONNX)语义级 VAD; false 用能量阈值法
 * @param sileroModelPath Silero 模型路径; 空则用打包进 classpath 的默认 {@code silero_vad.onnx}
 * @param bargeGraceMs    起播保护期: 机器人开口后这么久(ms)内不判打断, 避免被自己头几个字的回声掐断; 0=不保护
 * @param halfDuplex      半双工: true 时机器人说话期间<b>完全不收麦、不判打断</b>(只能用手动按钮打断),
 *                        外放无回声消除时靠它彻底断掉"自己声音→麦克风→自打断"的死循环; 戴耳机可关
 */
public record VadConfig(
        double speechThreshold,
        int onsetMs,
        int silenceMs,
        double bargeThreshold,
        int bargeMs,
        int prerollMs,
        int targetSampleRate,
        boolean useSilero,
        String sileroModelPath,
        int bargeGraceMs,
        boolean halfDuplex) {

    /** 与原前端常量一致的能量阈值法默认值(无起播保护、全双工, 保持库默认行为) */
    public static VadConfig defaults() {
        return new VadConfig(0.015, 150, 800, 0.020, 250, 400, 16000, false, "", 0, false);
    }

    /** Silero 默认: 阈值换成人声概率尺度(0.5/0.6), 时序沿用能量法, 采样率锁 16k。 */
    public static VadConfig silero(String modelPath) {
        return new VadConfig(0.5, 150, 800, 0.6, 250, 400, 16000, true, modelPath == null ? "" : modelPath, 0, false);
    }
}
