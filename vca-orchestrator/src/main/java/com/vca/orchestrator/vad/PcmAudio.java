package com.vca.orchestrator.vad;

/**
 * 16bit 单声道 PCM 的纯函数音频工具: 编解码、电平(RMS)、重采样。
 * 这些原本写在浏览器里(DataView/Float32/降采样), 现搬到后端, 便于单测与复用。
 */
public final class PcmAudio {

    private PcmAudio() {
    }

    /** 小端字节 → 16bit 采样 */
    public static short[] decodeLe(byte[] bytes) {
        if (bytes == null || bytes.length < 2) {
            return new short[0];
        }
        int n = bytes.length / 2;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            int lo = bytes[2 * i] & 0xff;
            int hi = bytes[2 * i + 1];
            out[i] = (short) ((hi << 8) | lo);
        }
        return out;
    }

    /** 16bit 采样 → 小端字节 */
    public static byte[] encodeLe(short[] samples) {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            short s = samples[i];
            out[2 * i] = (byte) (s & 0xff);
            out[2 * i + 1] = (byte) ((s >> 8) & 0xff);
        }
        return out;
    }

    /** 归一化 RMS 电平(0~1) */
    public static double rms(short[] samples) {
        if (samples.length == 0) {
            return 0;
        }
        double sum = 0;
        for (short s : samples) {
            double x = s / 32768.0;
            sum += x * x;
        }
        return Math.sqrt(sum / samples.length);
    }

    /**
     * 重采样到目标采样率。
     *
     * <p><b>降采样</b>(如 48k→16k)对每个输出点覆盖的源采样区间取<b>均值</b>: 这步均值兼当一个粗糙的
     * 抗混叠低通。早先用最近邻抽取(每 N 个取 1 个)会把 &gt;奈奎斯特(8kHz)的高频<b>混叠</b>进语音带,
     * 能量(RMS)看着照常、但波形结构被毁 —— Silero 这类依赖波形的 VAD 会把正常说话误判成静音。
     *
     * <p><b>升采样</b>仍用最近邻(VAD/ASR 够用)。
     */
    public static short[] resample(short[] in, int inRate, int outRate) {
        if (inRate <= 0 || inRate == outRate || in.length == 0) {
            return in;
        }
        double ratio = (double) inRate / outRate;
        int outLen = (int) Math.floor(in.length / ratio);
        short[] out = new short[Math.max(outLen, 0)];
        if (ratio > 1) {
            // 降采样: 区间均值(抗混叠)
            for (int i = 0; i < outLen; i++) {
                int start = (int) Math.floor(i * ratio);
                int end = (int) Math.floor((i + 1) * ratio);
                if (end <= start) {
                    end = start + 1;
                }
                if (end > in.length) {
                    end = in.length;
                }
                long sum = 0;
                for (int j = start; j < end; j++) {
                    sum += in[j];
                }
                out[i] = (short) (sum / (end - start));
            }
        } else {
            // 升采样: 最近邻
            for (int i = 0; i < outLen; i++) {
                int srcIdx = (int) Math.floor(i * ratio);
                if (srcIdx >= in.length) {
                    srcIdx = in.length - 1;
                }
                out[i] = in[srcIdx];
            }
        }
        return out;
    }
}
