package com.vca.telephony.session;

import com.vca.orchestrator.vad.PcmAudio;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 线路信号音检测。参数取自线上那通卡死电话的录音实测:
 * 450Hz 纯音, 响 380ms / 停 320ms, 电平(RMS)约 0.27, 从客户挂断一直响到通话被人工掐掉(221 秒)。
 */
class LineToneDetectorTest {

    private static final int RATE = 8000;
    private static final int FRAME = RATE / 50;   // 20ms

    /** 逐帧喂入, 返回"第一次判定为信号音"时已经喂了多少毫秒; 始终没判定返回 -1 */
    private static int feed(LineToneDetector d, short[] signal) {
        for (int off = 0; off + FRAME <= signal.length; off += FRAME) {
            short[] f = new short[FRAME];
            System.arraycopy(signal, off, f, 0, FRAME);
            if (d.accept(PcmAudio.encodeLe(f))) {
                return (off + FRAME) * 1000 / RATE;
            }
        }
        return -1;
    }

    /** 通断节奏的单频信号音。onMs/offMs 都给 0 表示连续音 */
    private static short[] tone(double hz, double rms, int onMs, int offMs, int totalMs) {
        short[] s = new short[RATE * totalMs / 1000];
        double peak = rms * Math.sqrt(2) * 32767;
        int period = onMs + offMs;
        for (int i = 0; i < s.length; i++) {
            int ms = i * 1000 / RATE;
            boolean on = period == 0 || (ms % period) < onMs;
            s[i] = on ? (short) (peak * Math.sin(2 * Math.PI * hz * i / RATE)) : 0;
        }
        return s;
    }

    /** 过一遍 G.711 μ 律: 真实线路上信号音是被 8bit 压扩过的, 波形有量化台阶 */
    private static short[] throughMuLaw(short[] pcm) {
        short[] out = new short[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            double x = pcm[i] / 32768.0;
            double y = Math.signum(x) * Math.log1p(255 * Math.abs(x)) / Math.log1p(255);
            double q = Math.round(y * 127) / 127.0;                       // 8bit 量化
            double z = Math.signum(q) * (Math.pow(256, Math.abs(q)) - 1) / 255;
            out[i] = (short) Math.max(-32768, Math.min(32767, z * 32768));
        }
        return out;
    }

    /**
     * 类人声: 基频在 110~240Hz 间滑动, 叠共振峰与清音噪声, 按音节节奏(约 4Hz)起伏。
     * 要点是过零间隔忽长忽短 —— 这正是它与纯音的区别。
     */
    private static short[] speechLike(int totalMs, long seed) {
        Random rnd = new Random(seed);
        short[] s = new short[RATE * totalMs / 1000];
        double phase0 = 0, phase1 = 0, phase2 = 0;
        for (int i = 0; i < s.length; i++) {
            double t = i / (double) RATE;
            double f0 = 175 + 65 * Math.sin(2 * Math.PI * 0.7 * t);
            double f1 = 700 + 250 * Math.sin(2 * Math.PI * 1.3 * t);
            double f2 = 1800 + 400 * Math.sin(2 * Math.PI * 0.9 * t);
            phase0 += 2 * Math.PI * f0 / RATE;
            phase1 += 2 * Math.PI * f1 / RATE;
            phase2 += 2 * Math.PI * f2 / RATE;
            double syllable = 0.55 + 0.45 * Math.sin(2 * Math.PI * 4 * t);
            double v = 0.5 * Math.sin(phase0) + 0.3 * Math.sin(phase1) + 0.15 * Math.sin(phase2)
                    + 0.12 * (rnd.nextDouble() * 2 - 1);
            s[i] = (short) (v * syllable * 9000);
        }
        return s;
    }

    @Test
    void recognizesChineseBusyToneWithinAboutFourSeconds() {
        int at = feed(new LineToneDetector(RATE), tone(450, 0.27, 380, 320, 10_000));
        assertThat(at).as("实测参数的忙音必须认出来").isPositive();
        assertThat(at).as("窗口 4 秒, 认出来不该拖过 5 秒").isLessThanOrEqualTo(5000);
    }

    @Test
    void stillRecognizesBusyToneAfterG711Companding() {
        short[] line = throughMuLaw(tone(450, 0.27, 380, 320, 10_000));
        assertThat(feed(new LineToneDetector(RATE), line))
                .as("真实线路上的忙音是压扩过的, 量化台阶不该让它漏检").isPositive();
    }

    @Test
    void recognizesContinuousDialToneAndCongestionTone() {
        assertThat(feed(new LineToneDetector(RATE), tone(450, 0.2, 0, 0, 8000)))
                .as("有的插卡盒在对端挂断后放的是连续拨号音").isPositive();
        assertThat(feed(new LineToneDetector(RATE), tone(450, 0.2, 700, 700, 10_000)))
                .as("拥塞音 700/700").isPositive();
        assertThat(feed(new LineToneDetector(RATE), tone(425, 0.2, 500, 500, 10_000)))
                .as("425Hz 的欧式忙音也在频带内").isPositive();
    }

    @Test
    void quietBusyToneIsStillRecognized() {
        assertThat(feed(new LineToneDetector(RATE), tone(450, 0.05, 350, 350, 10_000)))
                .as("线路衰减大时忙音电平低得多").isPositive();
    }

    @Test
    void ringbackToneIsNotMistakenForHangup() {
        // 回铃音: 同样是 450Hz, 但响 1 秒停 4 秒 —— 那是"对方电话在响", 不是挂断
        assertThat(feed(new LineToneDetector(RATE), tone(450, 0.27, 1000, 4000, 30_000)))
                .as("回铃音不能触发挂机").isEqualTo(-1);
    }

    @Test
    void speechIsNeverMistakenForATone() {
        for (long seed = 1; seed <= 5; seed++) {
            assertThat(feed(new LineToneDetector(RATE), speechLike(20_000, seed)))
                    .as("类人声信号(seed=%d)不能被当成信号音", seed).isEqualTo(-1);
        }
    }

    @Test
    void silenceAndNoiseAreNotTones() {
        assertThat(feed(new LineToneDetector(RATE), new short[RATE * 10])).isEqualTo(-1);
        Random rnd = new Random(7);
        short[] noise = new short[RATE * 10];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (short) (rnd.nextInt(16000) - 8000);
        }
        assertThat(feed(new LineToneDetector(RATE), noise)).as("白噪声不是纯音").isEqualTo(-1);
    }

    @Test
    void shortBeepDoesNotTrigger() {
        // 一声 1 秒的提示音(按键回音、语音信箱的"嘀"), 前后是人声
        short[] before = speechLike(3000, 11);
        short[] beep = tone(450, 0.27, 0, 0, 1000);
        short[] after = speechLike(6000, 12);
        short[] all = new short[before.length + beep.length + after.length];
        System.arraycopy(before, 0, all, 0, before.length);
        System.arraycopy(beep, 0, all, before.length, beep.length);
        System.arraycopy(after, 0, all, before.length + beep.length, after.length);
        assertThat(feed(new LineToneDetector(RATE), all)).isEqualTo(-1);
    }

    @Test
    void tonesOutsideTheCallProgressBandAreIgnored() {
        assertThat(feed(new LineToneDetector(RATE), tone(1000, 0.27, 350, 350, 10_000)))
                .as("1kHz 测试音不是挂机信号").isEqualTo(-1);
        assertThat(feed(new LineToneDetector(RATE), tone(200, 0.27, 350, 350, 10_000)))
                .as("200Hz 低频哼声不是挂机信号").isEqualTo(-1);
    }
}
