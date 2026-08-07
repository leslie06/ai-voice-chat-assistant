package com.vca.telephony.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下行节流缓冲。这里验的是"电话必须按实时节奏出声"这条硬约束的实现细节:
 * 帧长固定、尾帧补静音、打断即清空、空缓冲不出声。
 */
class PacingBufferTest {

    /** 8k/16bit/20ms = 320 字节, 且必须是偶数(16bit 采样不能劈成半个) */
    @Test
    void frameSizeMatchesTelephonyPacket() {
        assertThat(new PacingBuffer(8000, 20, 30_000).frameBytes()).isEqualTo(320);
        assertThat(new PacingBuffer(16000, 20, 30_000).frameBytes()).isEqualTo(640);
        // 奇数毫秒也要对齐到偶数字节
        assertThat(new PacingBuffer(8000, 21, 30_000).frameBytes() % 2).isZero();
    }

    @Test
    void emptyBufferYieldsNothing() {
        PacingBuffer buf = new PacingBuffer(8000, 20, 30_000);
        assertThat(buf.isEmpty()).isTrue();
        assertThat(buf.nextFrame()).isNull();   // 空缓冲绝不能往线路上写东西
    }

    /** 跨 chunk 边界切帧: TTS 吐的块大小与 20ms 帧长无关, 必须能任意拼接切分 */
    @Test
    void drainsAcrossChunkBoundaries() {
        PacingBuffer buf = new PacingBuffer(8000, 20, 30_000);
        buf.offer(filled(500, (byte) 1));
        buf.offer(filled(140, (byte) 2));   // 合计 640 = 恰好 2 帧

        byte[] f1 = buf.nextFrame();
        byte[] f2 = buf.nextFrame();
        assertThat(f1).hasSize(320);
        assertThat(f2).hasSize(320);
        // 第二帧横跨两个 chunk: 前 180 字节来自第一段, 后 140 字节来自第二段
        assertThat(f2[179]).isEqualTo((byte) 1);
        assertThat(f2[180]).isEqualTo((byte) 2);
        assertThat(buf.isEmpty()).isTrue();
        assertThat(buf.nextFrame()).isNull();
    }

    /** 尾帧不足一帧时补静音 —— 宁可补零也要保持时序, 否则对端听到的语速会忽快忽慢 */
    @Test
    void lastPartialFrameIsPaddedWithSilence() {
        PacingBuffer buf = new PacingBuffer(8000, 20, 30_000);
        buf.offer(filled(100, (byte) 7));

        byte[] frame = buf.nextFrame();
        assertThat(frame).hasSize(320);
        assertThat(frame[99]).isEqualTo((byte) 7);
        assertThat(frame[100]).isZero();
        assertThat(frame[319]).isZero();
        assertThat(buf.isEmpty()).isTrue();
    }

    /** 打断: 一次 clear 立刻停声, 不依赖对端配合 */
    @Test
    void clearDropsEverythingImmediately() {
        PacingBuffer buf = new PacingBuffer(8000, 20, 30_000);
        buf.offer(filled(16_000, (byte) 3));   // 1 秒音频
        assertThat(buf.bufferedMs()).isEqualTo(1000);

        buf.clear();

        assertThat(buf.isEmpty()).isTrue();
        assertThat(buf.bufferedMs()).isZero();
        assertThat(buf.nextFrame()).isNull();
    }

    /** 溢出丢新不丢旧: 丢旧会切掉已排队语音的开头, 比丢尾巴更糟 */
    @Test
    void overflowDropsIncomingNotQueued() {
        PacingBuffer buf = new PacingBuffer(8000, 20, 100);   // 上限 100ms = 1600 字节
        assertThat(buf.offer(filled(1600, (byte) 1))).isTrue();
        assertThat(buf.offer(filled(320, (byte) 2))).isFalse();

        assertThat(buf.bufferedMs()).isEqualTo(100);
        assertThat(buf.nextFrame()[0]).isEqualTo((byte) 1);   // 队首仍是先到的那段
    }

    private static byte[] filled(int n, byte v) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, v);
        return b;
    }
}
