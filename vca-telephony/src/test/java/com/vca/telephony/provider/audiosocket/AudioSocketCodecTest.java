package com.vca.telephony.provider.audiosocket;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AudioSocket 帧格式。协议是确定的, 所以这层完全可以先于 Asterisk 环境验完。 */
class AudioSocketCodecTest {

    @Test
    void headerIsTypeThenBigEndianLength() {
        byte[] frame = AudioSocketCodec.encode(AudioSocketCodec.TYPE_AUDIO, new byte[320]);

        assertThat(frame).hasSize(3 + 320);
        assertThat(frame[0]).isEqualTo((byte) 0x10);
        assertThat(frame[1]).isEqualTo((byte) 0x01);   // 320 = 0x0140, 大端
        assertThat(frame[2]).isEqualTo((byte) 0x40);
    }

    @Test
    void terminateFrameHasEmptyPayload() {
        byte[] frame = AudioSocketCodec.terminateFrame();

        assertThat(frame).containsExactly(0x00, 0x00, 0x00);
    }

    @Test
    void readsFramesBackToBack() throws Exception {
        byte[] pcm = {1, 2, 3, 4};
        byte[] stream = concat(
                AudioSocketCodec.encode(AudioSocketCodec.TYPE_UUID, "abc".getBytes(StandardCharsets.US_ASCII)),
                AudioSocketCodec.audioFrame(pcm),
                AudioSocketCodec.terminateFrame());
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(stream));

        AudioSocketCodec.Frame f1 = AudioSocketCodec.read(in);
        AudioSocketCodec.Frame f2 = AudioSocketCodec.read(in);
        AudioSocketCodec.Frame f3 = AudioSocketCodec.read(in);

        assertThat(f1.type()).isEqualTo(AudioSocketCodec.TYPE_UUID);
        assertThat(f2.isAudio()).isTrue();
        assertThat(f2.payload()).containsExactly(pcm);
        assertThat(f3.type()).isEqualTo(AudioSocketCodec.TYPE_TERMINATE);
        assertThat(AudioSocketCodec.read(in)).isNull();   // 干净 EOF
    }

    /** 帧读到一半断了必须能和正常 EOF 区分开: 前者是异常断连, 后者是对端正常收线 */
    @Test
    void truncatedFrameThrowsInsteadOfReturningNull() {
        byte[] half = {0x10, 0x01, 0x40, 1, 2, 3};   // 声称 320 字节, 实际只有 3
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(half));

        assertThatThrownBy(() -> AudioSocketCodec.read(in)).isInstanceOf(EOFException.class);
    }

    @Test
    void rejectsOversizedPayload() {
        assertThatThrownBy(() -> AudioSocketCodec.encode(AudioSocketCodec.TYPE_AUDIO, new byte[70_000]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** UUID 帧两种编码都要认: 16 字节裸 UUID 和 ASCII 字符串 */
    @Test
    void parsesBothUuidEncodings() {
        byte[] raw = new byte[16];
        for (int i = 0; i < 16; i++) {
            raw[i] = (byte) i;
        }

        assertThat(AudioSocketCodec.parseUuid(raw))
                .isEqualTo("00010203-0405-0607-0809-0a0b0c0d0e0f");
        assertThat(AudioSocketCodec.parseUuid("d7c9f0e2-1111".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("d7c9f0e2-1111");
        assertThat(AudioSocketCodec.parseUuid(new byte[0])).isEmpty();
    }

    @Test
    void swapsSampleByteOrder() {
        assertThat(AudioSocketCodec.swap16(new byte[]{0x01, 0x02, 0x03, 0x04}))
                .containsExactly(0x02, 0x01, 0x04, 0x03);
        // 奇数长度不该丢字节(不该发生, 但不能因此丢整帧)
        assertThat(AudioSocketCodec.swap16(new byte[]{0x01, 0x02, 0x09}))
                .containsExactly(0x02, 0x01, 0x09);
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
