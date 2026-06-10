package com.vca.domain.model;

import com.vca.domain.enums.AudioFormat;

/**
 * 下行音频块: TTS/S2S 流式产出的一段音频, 边收边回传前端边播。
 *
 * @param data     合成音频字节
 * @param format   编码格式
 * @param sequence 块序号
 * @param text     该音频块对应的文本(用于前端字幕同步, 可为 null)
 * @param last     是否为本句/本轮的最后一块
 * @param textRole {@code text} 归属: 机器人回复({@code ASSISTANT}) 还是用户说的话({@code USER})。
 *                 S2S 端到端模式下, 厂商会同时回吐"用户语音转写"和"机器人回复转写", 用它区分字幕落在哪一侧。
 */
public record AudioChunk(
        byte[] data,
        AudioFormat format,
        long sequence,
        String text,
        boolean last,
        TextRole textRole
) {
    /** 文本归属侧 */
    public enum TextRole { ASSISTANT, USER }

    /** 兼容旧签名: 不指定归属时默认机器人侧(TTS/S2S 回复) */
    public AudioChunk(byte[] data, AudioFormat format, long sequence, String text, boolean last) {
        this(data, format, sequence, text, last, TextRole.ASSISTANT);
    }

    public static AudioChunk of(byte[] data, AudioFormat format, long sequence) {
        return new AudioChunk(data, format, sequence, null, false);
    }

    /** 标记流结束的空块 */
    public static AudioChunk end(AudioFormat format, long sequence) {
        return new AudioChunk(new byte[0], format, sequence, null, true);
    }

    /** 用户语音的转写(S2S 下用于在页面显示"你说了什么"); 无音频, 只带文本 */
    public static AudioChunk userTranscript(String text, long sequence) {
        return new AudioChunk(new byte[0], AudioFormat.PCM, sequence, text, false, TextRole.USER);
    }

    public int size() {
        return data == null ? 0 : data.length;
    }
}
