package com.vca.orchestrator.vad;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重采样。电话链路把 8k 窄带上行升到 16k 喂 VAD/ASR, 是必经之路, 所以升采样质量直接影响误打断率。
 */
class PcmAudioResampleTest {

    /**
     * 8k→16k 用线性插值而非最近邻。最近邻会把每个源采样重复两遍, 产出阶梯状波形 ——
     * 电平(RMS)看着照常, 但引入大量高频谐波, Silero 这类看波形的 VAD 打分会失真。
     */
    @Test
    void upsamplingInterpolatesInsteadOfRepeating() {
        short[] in = {0, 1000, 2000, 3000};

        short[] out = PcmAudio.resample(in, 8000, 16000);

        assertThat(out).hasSize(8);
        // 偶数位落在源采样上, 奇数位取相邻两点的中值 —— 最近邻会得到 0,0,1000,1000,...
        assertThat(out[0]).isEqualTo((short) 0);
        assertThat(out[1]).isEqualTo((short) 500);
        assertThat(out[2]).isEqualTo((short) 1000);
        assertThat(out[3]).isEqualTo((short) 1500);
        assertThat(out[6]).isEqualTo((short) 3000);
        assertThat(out[7]).isEqualTo((short) 3000);   // 末尾无后继, 保持最后一个采样
    }

    /** 升采样不应改变信号能量(阶梯波形会抬高高频, RMS 也会偏) */
    @Test
    void upsamplingPreservesLevel() {
        short[] in = new short[160];
        for (int i = 0; i < in.length; i++) {
            in[i] = (short) (8000 * Math.sin(2 * Math.PI * 200 * i / 8000.0));
        }

        double before = PcmAudio.rms(in);
        double after = PcmAudio.rms(PcmAudio.resample(in, 8000, 16000));

        assertThat(after).isCloseTo(before, org.assertj.core.data.Offset.offset(0.01));
    }

    /** 降采样保持原有的区间均值(抗混叠)行为 */
    @Test
    void downsamplingAveragesInterval() {
        short[] in = {100, 300, 500, 700};

        short[] out = PcmAudio.resample(in, 16000, 8000);

        assertThat(out).containsExactly((short) 200, (short) 600);
    }

    @Test
    void sameRateIsPassThrough() {
        short[] in = {1, 2, 3};
        assertThat(PcmAudio.resample(in, 8000, 8000)).isSameAs(in);
    }
}
