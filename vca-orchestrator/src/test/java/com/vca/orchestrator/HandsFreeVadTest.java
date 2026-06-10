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
        VadConfig cfg = new VadConfig(0.015, 150, 800, 0.020, 250, 400, 16000, false, "", 600, false);
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
        VadConfig cfg = new VadConfig(0.015, 150, 800, 0.020, 250, 400, 16000, false, "", 0, true);
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
}
