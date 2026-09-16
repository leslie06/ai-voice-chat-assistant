package com.vca.domain.model;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WAV 头解析。这层是声音复刻的第一道闸: 拦不住格式不对的样本, 用户看到的就只有厂商那句
 * 语焉不详的 "detect audio failed"。
 */
class WavAudioTest {

    @Test
    void 解析标准pcm头() {
        byte[] wav = WavAudio.wrapPcm16Mono(new byte[16000 * 2 * 15], 16000);
        WavAudio info = WavAudio.parse(wav);
        assertNotNull(info);
        assertEquals(16000, info.sampleRate());
        assertEquals(1, info.channels());
        assertEquals(16, info.bitsPerSample());
        assertEquals(15.0, info.seconds(), 0.01);
    }

    /** 有些录音器会在 fmt 与 data 之间塞 LIST 块, 不能因此把整个文件判死。 */
    @Test
    void 跳过中间的额外块() {
        byte[] pcm = new byte[24000 * 2 * 12];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer head = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        head.put("RIFF".getBytes()).putInt(0).put("WAVE".getBytes());
        out.writeBytes(head.array());
        ByteBuffer fmt = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        fmt.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(24000).putInt(48000).putShort((short) 2).putShort((short) 16);
        out.writeBytes(fmt.array());
        ByteBuffer list = ByteBuffer.allocate(8 + 6).order(ByteOrder.LITTLE_ENDIAN);
        list.put("LIST".getBytes()).putInt(6).put(new byte[6]);
        out.writeBytes(list.array());
        ByteBuffer data = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        data.put("data".getBytes()).putInt(pcm.length);
        out.writeBytes(data.array());
        out.writeBytes(pcm);

        WavAudio info = WavAudio.parse(out.toByteArray());
        assertNotNull(info);
        assertEquals(24000, info.sampleRate());
        assertEquals(12.0, info.seconds(), 0.01);
    }

    @Test
    void 非wav一律判无效() {
        assertNull(WavAudio.parse(null));
        assertNull(WavAudio.parse(new byte[10]));
        assertNull(WavAudio.parse(new byte[100]));                 // 全零: 没有 RIFF 标识
        assertNull(WavAudio.parse("ID3 这是一个 mp3".getBytes()));
    }

    @Test
    void 包裹后的字节能被自己解析回来() {
        byte[] pcm = new byte[1000];
        byte[] wav = WavAudio.wrapPcm16Mono(pcm, 24000);
        assertEquals(44 + pcm.length, wav.length);
        assertTrue(WavAudio.parse(wav).seconds() > 0);
    }
}
