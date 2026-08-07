package com.vca.telephony.provider.audiosocket;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Asterisk AudioSocket 的帧编解码。纯函数, 不碰 IO 之外的任何状态, 便于单测灌字节流。
 *
 * <p>帧格式极简 —— 这正是选它的原因:
 * <pre>
 *   +--------+------------------+------------------+
 *   | type   | length (2B, 大端) | payload (length) |
 *   | 1 byte |                  |                  |
 *   +--------+------------------+------------------+
 * </pre>
 *
 * <p>音频负载是 <b>8kHz / 16bit / 单声道 SLIN</b> —— 和本项目上下行的 PCM 契约逐字对上, 无需转码。
 *
 * <p><b>字节序注意</b>: SLIN 在不同 Asterisk 构建上落到线路的字节序可能不同, 而本项目全链路按
 * 小端解析({@link com.vca.orchestrator.vad.PcmAudio#decodeLe})。首次联调时若听到的是刺耳噪声而非人声,
 * 十有八九就是这里 —— 把 {@link AudioSocketConfig#swapPayloadBytes()} 打开即可, 不用改代码。
 */
public final class AudioSocketCodec {

    /** 挂机: 载荷为空 */
    public static final int TYPE_TERMINATE = 0x00;
    /** 通话 UUID: Asterisk 建连后的第一帧, 是与 originate 侧对账的唯一键 */
    public static final int TYPE_UUID = 0x01;
    /** 音频帧 */
    public static final int TYPE_AUDIO = 0x10;
    /** 错误: 载荷 1 字节错误码 */
    public static final int TYPE_ERROR = 0xff;

    public static final int HEADER_BYTES = 3;
    public static final int MAX_PAYLOAD = 0xffff;

    private AudioSocketCodec() {
    }

    /** 一帧 */
    public record Frame(int type, byte[] payload) {
        public boolean isAudio() {
            return type == TYPE_AUDIO;
        }
    }

    /** 组帧 */
    public static byte[] encode(int type, byte[] payload) {
        byte[] body = payload == null ? new byte[0] : payload;
        if (body.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("AudioSocket 载荷超长: " + body.length);
        }
        byte[] out = new byte[HEADER_BYTES + body.length];
        out[0] = (byte) type;
        out[1] = (byte) ((body.length >> 8) & 0xff);   // 长度是大端(网络序)
        out[2] = (byte) (body.length & 0xff);
        System.arraycopy(body, 0, out, HEADER_BYTES, body.length);
        return out;
    }

    public static byte[] audioFrame(byte[] pcm) {
        return encode(TYPE_AUDIO, pcm);
    }

    public static byte[] terminateFrame() {
        return encode(TYPE_TERMINATE, new byte[0]);
    }

    /**
     * 从流里读一帧(阻塞)。
     *
     * @return null 表示对端正常关闭(EOF)
     * @throws EOFException 帧读到一半断了 —— 这是异常断连, 与正常 EOF 要分开处理
     */
    public static Frame read(DataInputStream in) throws IOException {
        int type = in.read();
        if (type < 0) {
            return null;   // 干净的 EOF
        }
        int hi = in.read();
        int lo = in.read();
        if (hi < 0 || lo < 0) {
            throw new EOFException("AudioSocket 帧头不完整");
        }
        int length = (hi << 8) | lo;
        byte[] payload = new byte[length];
        in.readFully(payload);   // 不足即抛 EOFException
        return new Frame(type, payload);
    }

    /**
     * 解析 UUID 帧。不同 Asterisk 版本可能发 16 字节裸 UUID, 也可能发 36 字符 ASCII, 两种都认。
     */
    public static String parseUuid(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return "";
        }
        if (payload.length == 16) {
            StringBuilder sb = new StringBuilder(36);
            for (int i = 0; i < 16; i++) {
                if (i == 4 || i == 6 || i == 8 || i == 10) {
                    sb.append('-');
                }
                sb.append(String.format("%02x", payload[i]));
            }
            return sb.toString();
        }
        return new String(payload, StandardCharsets.US_ASCII).trim();
    }

    /**
     * 就地翻转 16bit 采样的字节序。返回新数组, 不改入参。
     * 载荷长度为奇数时末尾那个孤字节原样保留(不该发生, 但不能因此丢帧)。
     */
    public static byte[] swap16(byte[] pcm) {
        byte[] out = new byte[pcm.length];
        int pairs = pcm.length / 2;
        for (int i = 0; i < pairs; i++) {
            out[2 * i] = pcm[2 * i + 1];
            out[2 * i + 1] = pcm[2 * i];
        }
        if ((pcm.length & 1) == 1) {
            out[pcm.length - 1] = pcm[pcm.length - 1];
        }
        return out;
    }
}
