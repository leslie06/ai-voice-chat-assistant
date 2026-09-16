package com.vca.domain.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 极简 WAV 读写。只处理未压缩 PCM(fmt 标签 1), 够用来校验复刻样本、以及把裸 PCM 包成 WAV。
 *
 * <p>不引 javax.sound 是因为它在无音频设备的服务器上行为不稳, 而这里要的只是读头部几个字段。
 */
public record WavAudio(int sampleRate, int channels, int bitsPerSample, int dataBytes) {

    /** WAV 头固定 44 字节(标准 PCM 无附加块时)。 */
    private static final int HEADER = 44;

    public double seconds() {
        int bytesPerFrame = Math.max(1, channels * bitsPerSample / 8);
        return (double) dataBytes / bytesPerFrame / Math.max(1, sampleRate);
    }

    /**
     * 解析 WAV 头。不是合法 PCM WAV 时返回 null —— 调用方据此回 400, 而不是让厂商去报
     * "detect audio failed" 那种难懂的错。
     */
    public static WavAudio parse(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) { // "RIFF" / "WAVE"
            return null;
        }
        // 逐块扫到 fmt 与 data: 有些录音器会在中间塞 LIST/fact 块
        int pos = 12;
        int rate = 0;
        int channels = 0;
        int bits = 0;
        int dataBytes = -1;
        while (pos + 8 <= bytes.length) {
            int id = b.getInt(pos);
            int size = b.getInt(pos + 4);
            if (size < 0) {
                return null;
            }
            int body = pos + 8;
            if (id == 0x20746d66) { // "fmt "
                if (body + 16 > bytes.length || b.getShort(body) != 1) {
                    return null;
                }
                channels = b.getShort(body + 2);
                rate = b.getInt(body + 4);
                bits = b.getShort(body + 14);
            } else if (id == 0x61746164) { // "data"
                dataBytes = Math.min(size, bytes.length - body);
                break;
            }
            pos = body + size + (size % 2); // 块按偶数字节对齐
        }
        if (rate <= 0 || channels <= 0 || bits <= 0 || dataBytes < 0) {
            return null;
        }
        return new WavAudio(rate, channels, bits, dataBytes);
    }

    /** 把裸 PCM(16bit 单声道)包成 WAV, 供试听下载。 */
    public static byte[] wrapPcm16Mono(byte[] pcm, int sampleRate) {
        ByteBuffer h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        h.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + pcm.length)
                .put(new byte[]{'W', 'A', 'V', 'E'})
                .put(new byte[]{'f', 'm', 't', ' '}).putInt(16)
                .putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate * 2)
                .putShort((short) 2).putShort((short) 16)
                .put(new byte[]{'d', 'a', 't', 'a'}).putInt(pcm.length);
        byte[] out = new byte[HEADER + pcm.length];
        System.arraycopy(h.array(), 0, out, 0, HEADER);
        System.arraycopy(pcm, 0, out, HEADER, pcm.length);
        return out;
    }
}
