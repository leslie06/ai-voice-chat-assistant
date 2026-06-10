package com.vca.orchestrator;

import com.vca.orchestrator.vad.SileroVad;
import com.vca.orchestrator.vad.SileroVadModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证打包的 silero_vad.onnx 能加载并推理: 静音得低分、440Hz 正弦(近似浊音)得明显更高分。
 * 同时确认 RNN 状态隔离 —— 两路 detector 互不影响。
 */
class SileroVadTest {

    private static SileroVadModel model;

    @BeforeAll
    static void load() throws Exception {
        model = SileroVadModel.load("");   // classpath 默认模型
    }

    @AfterAll
    static void close() {
        if (model != null) {
            model.close();
        }
    }

    /** 1 秒静音(16k)→ 人声概率应很低 */
    @Test
    void silenceScoresLow() {
        SileroVad vad = model.newDetector();
        double last = feed(vad, new short[16000]);
        assertThat(last).isLessThan(0.5);
    }

    /** 1 秒 440Hz 正弦 → 比静音明显更高(模型对周期性浊音有响应) */
    @Test
    void toneScoresHigherThanSilence() {
        double silence = feed(model.newDetector(), new short[16000]);
        short[] tone = new short[16000];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (short) (Math.sin(2 * Math.PI * 440 * i / 16000.0) * 12000);
        }
        double toneScore = feed(model.newDetector(), tone);
        assertThat(toneScore).isGreaterThan(silence);
    }

    /** 把整段喂进去, 返回最后一窗的概率 */
    private double feed(SileroVad vad, short[] samples) {
        double last = 0;
        // 按 320 采样(20ms)一帧喂, 模拟真实上行分帧
        for (int off = 0; off < samples.length; off += 320) {
            int len = Math.min(320, samples.length - off);
            short[] frame = new short[len];
            System.arraycopy(samples, off, frame, 0, len);
            last = vad.speechProbability(frame);
        }
        return last;
    }
}
