package com.vca.orchestrator.vad;

/**
 * 逐帧"有人声"打分器。把 {@link HandsFreeVad} 状态机里"这一帧算不算人声"的判断抽出来,
 * 让能量阈值法与 Silero(ML)等不同实现可互换, 而状态机/预滚/打断逻辑保持不变。
 *
 * <p>实现可带内部状态(如 Silero 的 RNN 隐状态、采样窗口缓冲), 因此<b>每路会话一个实例</b>,
 * 且与 {@link HandsFreeVad} 一样<b>非线程安全</b>(由接入层在连接锁内串行调用)。
 */
public interface VoiceActivityDetector {

    /**
     * 给一帧 16k 单声道 PCM 打分。
     *
     * @param frame16k 已重采样到 16k 的 16bit 采样
     * @return 该帧"有人声"的程度, 归一化到 0~1(越大越像人声); 与 {@link VadConfig} 里的阈值同尺度比较
     */
    double speechProbability(short[] frame16k);

    /** 一轮聆听开始/结束时复位内部状态(缓冲、RNN 隐状态等)。能量法无状态, 默认空实现。 */
    default void reset() {
    }
}
