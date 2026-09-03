package com.vca.orchestrator;

import com.vca.orchestrator.vad.HandsFreeVad;
import com.vca.orchestrator.vad.PcmAudio;
import com.vca.orchestrator.vad.VadConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后端 VAD 状态机单测: 验证开口检测、句尾断句、插话打断的时序。
 * 直接喂 16kHz PCM(与目标采样率一致, 跳过重采样), 用 20ms 帧。
 */
class HandsFreeVadTest {

    private static final int RATE = 16000;
    private static final int FRAME = RATE / 50; // 20ms = 320 采样

    private final List<String> events = new ArrayList<>();
    private final HandsFreeVad vad = new HandsFreeVad(VadConfig.defaults(), new HandsFreeVad.Listener() {
        @Override public void onSpeechStart() { events.add("start"); }
        @Override public void onAudio(byte[] pcm16le) { events.add("audio"); }
        @Override public void onSpeechEnd() { events.add("end"); }
        @Override public void onBargeIn() { events.add("barge"); }
    });

    /** 一帧响亮人声(RMS ≈ 0.061, 超过语音/打断阈值) */
    private byte[] loud() {
        short[] s = new short[FRAME];
        java.util.Arrays.fill(s, (short) 2000);
        return PcmAudio.encodeLe(s);
    }

    /** 一帧静音 */
    private byte[] silent() {
        return PcmAudio.encodeLe(new short[FRAME]);
    }

    private void feed(byte[] frame, int times, boolean botSpeaking) {
        for (int i = 0; i < times; i++) {
            vad.accept(frame, botSpeaking);
        }
    }

    @Test
    void onsetThenSilenceCommitsTurn() {
        vad.start(RATE);
        // 默认 onset=150ms → 8 帧(20ms)足够触发开口
        feed(silent(), 5, false);                // 等你开口阶段的静音, 不应开启
        assertTrue(events.isEmpty(), "静音不应开启回合");

        feed(loud(), 8, false);                  // 持续人声 → 开口
        assertEquals("start", events.get(0));
        assertTrue(events.contains("audio"), "开口后应补发预滚音频");

        // 默认 silence=800ms → 40 帧静音触发提交
        feed(silent(), 40, false);
        assertTrue(events.contains("end"), "句尾静音应自动提交");
    }

    @Test
    void speechWhileBotSpeakingTriggersBargeIn() {
        vad.start(RATE);
        feed(loud(), 8, false);   // 开口
        feed(silent(), 40, false); // 提交 → 进入 WAIT
        events.clear();

        // 机器人未在说话时, 人声不应打断
        feed(loud(), 20, false);
        assertTrue(events.isEmpty(), "机器人没说话时不应打断");

        // 机器人说话时, 人声持续 ≥ bargeMs(250ms→13 帧) → 打断 + 立即开启新一轮
        feed(loud(), 13, true);
        assertEquals("barge", events.get(0));
        assertTrue(events.contains("start"), "打断后应立即开启新一轮");
    }

    /** 起播保护期内不打断: 机器人刚开口的 graceMs 内, 即便有持续人声(回声)也不掐断, 过了才允许。 */
    @Test
    void bargeGracePeriodSuppressesEarlyBargeIn() {
        // grace=600ms; 其余沿用默认(barge 阈值 0.020, bargeMs 250), 全双工以便验证打断
        VadConfig cfg = new VadConfig(0.015, 150, 800, 0.020, 250, 400, 16000, false, "", 600, false, false, false, 400, 1600);
        HandsFreeVad v = new HandsFreeVad(cfg, new HandsFreeVad.Listener() {
            @Override public void onSpeechStart() { events.add("start"); }
            @Override public void onAudio(byte[] pcm16le) { events.add("audio"); }
            @Override public void onSpeechEnd() { events.add("end"); }
            @Override public void onBargeIn() { events.add("barge"); }
        });
        v.start(RATE);
        for (int i = 0; i < 8; i++) v.accept(loud(), false);    // 开口
        for (int i = 0; i < 40; i++) v.accept(silent(), false); // 提交 → WAIT
        events.clear();

        // 机器人开口后保护期内(600ms→30 帧)持续人声: 不应打断
        feed20ms(v, loud(), 25, true);
        assertTrue(events.isEmpty(), "起播保护期内不应打断");

        // 过了保护期再持续人声 ≥ bargeMs → 才打断
        feed20ms(v, loud(), 20, true);
        assertTrue(events.contains("barge"), "保护期后应能正常打断");
    }

    private void feed20ms(HandsFreeVad v, byte[] frame, int times, boolean botSpeaking) {
        for (int i = 0; i < times; i++) v.accept(frame, botSpeaking);
    }

    /** 半双工: 机器人说话期间即使有持续人声(回声)也绝不打断, 从根上断掉自打断死循环。 */
    @Test
    void halfDuplexNeverBargesWhileBotSpeaking() {
        VadConfig cfg = new VadConfig(0.015, 150, 800, 0.020, 250, 400, 16000, false, "", 0, true, false, false, 400, 1600);
        HandsFreeVad v = new HandsFreeVad(cfg, new HandsFreeVad.Listener() {
            @Override public void onSpeechStart() { events.add("start"); }
            @Override public void onAudio(byte[] pcm16le) { events.add("audio"); }
            @Override public void onSpeechEnd() { events.add("end"); }
            @Override public void onBargeIn() { events.add("barge"); }
        });
        v.start(RATE);
        for (int i = 0; i < 8; i++) v.accept(loud(), false);    // 开口
        for (int i = 0; i < 40; i++) v.accept(silent(), false); // 提交 → WAIT
        events.clear();

        // 机器人说话时持续灌入响亮人声(模拟回声), 半双工下绝不打断
        feed20ms(v, loud(), 60, true);
        assertTrue(events.isEmpty(), "半双工下机器人说话期间不应有任何打断");
    }

    // ---- 回声感知打断(echoAware): 让外放场景也能语音打断 ----

    private static VadConfig echoAwareConfig() {
        // halfDuplex=true 但 echoAware=true: 半双工的"一刀切不判打断"应当被回声判别接管
        return new VadConfig(0.015, 150, 800, 0.020, 250, 400, 16000,
                false, "", 0, true, true, false, 400, 1600);
    }

    /** 虚拟时钟: 按帧推进, 让回声判别拿到真实的时间轴(见 HandsFreeVad 带 clock 的构造函数)。 */
    private static final class FakeClock implements java.util.function.LongSupplier {
        long nowMs = 1_000_000;

        @Override
        public long getAsLong() {
            return nowMs;
        }
    }

    private HandsFreeVad echoAwareVad(FakeClock clock) {
        return new HandsFreeVad(echoAwareConfig(), new HandsFreeVad.Listener() {
            @Override public void onSpeechStart() { events.add("start"); }
            @Override public void onAudio(byte[] pcm16le) { events.add("audio"); }
            @Override public void onSpeechEnd() { events.add("end"); }
            @Override public void onBargeIn() { events.add("barge"); }
        }, null, clock);
    }

    /** 造一段音节起伏的幅度包络(恒定音量没有可对齐的形状, 见 EchoGuard 说明)。 */
    private static double[] envelope(int frames, long seed) {
        java.util.Random r = new java.util.Random(seed);
        double[] a = new double[frames];
        int t = 0;
        while (t < frames) {
            int len = 2 + r.nextInt(4);
            double amp = r.nextInt(4) == 0 ? 0.015 : 0.10 + 0.25 * r.nextDouble();
            for (int k = 0; k < len && t < frames; k++, t++) {
                a[t] = amp;
            }
        }
        return a;
    }

    private static byte[] noise(double amp, java.util.Random rnd) {
        short[] s = new short[FRAME];
        for (int i = 0; i < FRAME; i++) {
            s[i] = (short) (amp * 32767 * (rnd.nextDouble() * 2 - 1));
        }
        return PcmAudio.encodeLe(s);
    }

    /** 把 v 推到 WAIT 状态(开口 → 句尾静音提交), 时钟同步推进。 */
    private void toWaitState(HandsFreeVad v, FakeClock clock) {
        for (int i = 0; i < 8; i++) {
            v.accept(loud(), false);
            clock.nowMs += 20;
        }
        for (int i = 0; i < 40; i++) {
            v.accept(silent(), false);
            clock.nowMs += 20;
        }
        events.clear();
    }

    /**
     * 回声不该打断: 机器人在说话, 麦克风收到的是它自己声音的延迟衰减副本。
     * 这正是外放时把半双工逼出来的那个场景 —— 现在应当由回声判别挡住, 而不是靠"干脆不判"。
     */
    @Test
    void echoAwareIgnoresItsOwnEcho() {
        FakeClock clock = new FakeClock();
        HandsFreeVad v = echoAwareVad(clock);
        v.start(RATE);
        toWaitState(v, clock);

        int frames = 150;
        double[] bot = envelope(frames, 11);
        java.util.Random rnd = new java.util.Random(5);
        int delay = 15;   // 15 帧 × 20ms = 300ms 回声延迟
        for (int t = 0; t < frames; t++) {
            v.onPlayback(noise(bot[t], rnd), RATE);
            double echo = t >= delay ? 0.5 * bot[t - delay] : 0;
            v.accept(noise(echo, rnd), true);
            clock.nowMs += 20;
        }
        assertTrue(events.isEmpty(), "自己的回声不应触发打断, 实际事件: " + events);
    }

    /**
     * 用户说话必须能打断 —— 这是开回声感知的<b>全部意义</b>。
     * 同样是半双工配置, 换成与下行无关的独立声源就该正常打断。
     */
    @Test
    void echoAwareStillAllowsRealBargeIn() {
        FakeClock clock = new FakeClock();
        HandsFreeVad v = echoAwareVad(clock);
        v.start(RATE);
        toWaitState(v, clock);

        int frames = 150;
        double[] bot = envelope(frames, 11);
        double[] human = envelope(frames, 29);
        java.util.Random rnd = new java.util.Random(5);
        int delay = 15;
        for (int t = 0; t < frames; t++) {
            v.onPlayback(noise(bot[t], rnd), RATE);
            // 双讲: 回声 + 用户, 两个不相关声源功率相加
            double echo = t >= delay ? 0.5 * bot[t - delay] : 0;
            double user = human[t];
            v.accept(noise(Math.sqrt(echo * echo + user * user), rnd), true);
            clock.nowMs += 20;
        }
        assertTrue(events.contains("barge"), "用户插话必须能打断, 实际事件: " + events);
    }
}
