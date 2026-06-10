package com.vca.domain.enums;

/**
 * 音频编码格式。上行(采集)通常用 PCM/OPUS，下行(合成)可用 PCM/MP3/OPUS。
 */
public enum AudioFormat {
    /** 16bit 小端 PCM 裸流, ASR 标准输入 */
    PCM,
    /** Opus 编码, 适合弱网上行 */
    OPUS,
    /** MP3, 体积小, 适合下行播放 */
    MP3
}
