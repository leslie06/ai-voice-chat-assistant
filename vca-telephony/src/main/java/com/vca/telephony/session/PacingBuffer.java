package com.vca.telephony.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 下行音频的实时节流缓冲。<b>电话接入与浏览器接入最本质的结构差异就在这里。</b>
 *
 * <p>浏览器路径是"后端尽快发、前端自己缓冲慢慢播"; 电话不行 —— 媒体必须按实时节奏喂给对端
 * (8k 单声道 16bit 下每 20ms 恰好 320 字节), 一次性灌进去会被媒体服务器丢弃或造成语音撕裂。
 * 所以 TTS 产出的音频先进本缓冲, 由一个固定周期的节流器逐帧取走。
 *
 * <p><b>顺带解决了两个原本很别扭的问题:</b>
 * <ol>
 *   <li><b>"机器人还在不在说话"不用估算了。</b> 浏览器版要维护 {@code playbackEndsAtMs} 去推算前端播放
 *       进度(TTS 下发远快于播放, 不能用"后端发完了"当判据); 电话版 {@link #isEmpty()} 就是精确答案。</li>
 *   <li><b>打断变成一次 {@link #clear()}。</b> 缓冲清空即刻停声, 不依赖对端配合。</li>
 * </ol>
 *
 * <p>本类是纯数据结构, <b>不持有线程</b> —— 定时驱动放在 {@code CallSession}, 便于单测直接手动步进。
 * 所有方法线程安全(网络线程 offer / 节流线程 nextFrame / 打断线程 clear 会并发)。
 */
public final class PacingBuffer {

    private static final Logger log = LoggerFactory.getLogger(PacingBuffer.class);

    /** 每帧字节数 = sampleRate × 2(16bit) × pacingMs / 1000 */
    private final int frameBytes;
    /** 缓冲上限, 防止超长回复把内存吃爆 */
    private final int capacityBytes;
    private final int bytesPerSecond;

    private final Deque<byte[]> chunks = new ArrayDeque<>();
    /** 队首 chunk 已被消费掉的字节数 */
    private int headOffset;
    private int bufferedBytes;
    private boolean overflowLogged;

    /**
     * @param sampleRate    媒体采样率(Hz)
     * @param pacingMs      节流粒度(ms), 电话上通常 20
     * @param maxBufferedMs 缓冲上限(ms); 超出后新音频被丢弃(丢新不丢旧, 否则会切掉已排队语音的开头)
     */
    public PacingBuffer(int sampleRate, int pacingMs, int maxBufferedMs) {
        if (sampleRate <= 0 || pacingMs <= 0) {
            throw new IllegalArgumentException("sampleRate/pacingMs 必须为正");
        }
        this.bytesPerSecond = sampleRate * 2;
        // 对齐到偶数字节: 16bit 采样不能被劈成半个
        this.frameBytes = Math.max(2, (bytesPerSecond * pacingMs / 1000) & ~1);
        this.capacityBytes = Math.max(frameBytes, bytesPerSecond * Math.max(maxBufferedMs, 0) / 1000);
    }

    /** 每帧字节数 */
    public int frameBytes() {
        return frameBytes;
    }

    /**
     * 排入一段下行 PCM(必须已经是目标采样率)。
     *
     * @return false 表示缓冲已满、本段被丢弃
     */
    public synchronized boolean offer(byte[] pcm) {
        if (pcm == null || pcm.length == 0) {
            return true;
        }
        if (bufferedBytes + pcm.length > capacityBytes) {
            if (!overflowLogged) {
                log.warn("下行缓冲溢出({}ms), 丢弃后续音频 —— 回复过长或节流器停摆", bufferedMs());
                overflowLogged = true;   // 一轮只告警一次, 免刷屏
            }
            return false;
        }
        chunks.addLast(pcm);
        bufferedBytes += pcm.length;
        return true;
    }

    /**
     * 取下一帧。不足一帧时用静音补齐 —— 宁可补零也要保持时序, 否则对端会听到语速忽快忽慢。
     *
     * @return 恰好 {@link #frameBytes()} 字节; 缓冲为空时返回 null(此时不应向对端写任何东西)
     */
    public synchronized byte[] nextFrame() {
        if (bufferedBytes == 0) {
            return null;
        }
        byte[] frame = new byte[frameBytes];
        int written = 0;
        while (written < frameBytes && !chunks.isEmpty()) {
            byte[] head = chunks.peekFirst();
            int available = head.length - headOffset;
            int take = Math.min(available, frameBytes - written);
            System.arraycopy(head, headOffset, frame, written, take);
            written += take;
            headOffset += take;
            bufferedBytes -= take;
            if (headOffset >= head.length) {
                chunks.pollFirst();
                headOffset = 0;
            }
        }
        // 尾帧不足部分保持 0(静音), frame 已按需长度分配
        return frame;
    }

    /** 打断: 立刻丢掉所有未播音频。 */
    public synchronized void clear() {
        chunks.clear();
        headOffset = 0;
        bufferedBytes = 0;
        overflowLogged = false;
    }

    /** 缓冲是否已空 —— 即"机器人是否已经说完"。 */
    public synchronized boolean isEmpty() {
        return bufferedBytes == 0;
    }

    /** 当前积压时长(ms), 用于诊断与背压观察。 */
    public synchronized int bufferedMs() {
        return (int) (bufferedBytes * 1000L / bytesPerSecond);
    }
}
