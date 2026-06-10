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
     * 重采样到目标采样率。采用与原前端一致的最近邻抽取(nearest-sample), 简单且足够 VAD/桩用。
     */
    public static short[] resample(short[] in, int inRate, int outRate) {
        if (inRate <= 0 || inRate == outRate || in.length == 0) {
            return in;
        }
        double ratio = (double) inRate / outRate;
        int outLen = (int) Math.floor(in.length / ratio);
        short[] out = new short[Math.max(outLen, 0)];
        for (int i = 0; i < outLen; i++) {
            int srcIdx = (int) Math.floor(i * ratio);
            if (srcIdx >= in.length) {
                srcIdx = in.length - 1;
            }
            out[i] = in[srcIdx];
        }
        return out;
    }
}
