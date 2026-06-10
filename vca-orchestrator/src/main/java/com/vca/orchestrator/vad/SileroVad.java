package com.vca.orchestrator.vad;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Silero VAD(ONNX, v5)的<b>单路</b>检测器: 维护本路会话的 RNN 隐状态与采样缓冲。
 * 由 {@link SileroVadModel#newDetector()} 创建, 与共享的 {@link OrtSession} 配合做逐帧打分。
 *
 * <p>模型契约(16kHz): 输入 {@code input[1,512] / state[2,1,128] / sr(int64 标量)},
 * 输出 {@code output[1,1]}(人声概率 0~1)与 {@code stateN[2,1,128]}(更新后的状态)。
 * 因此本类把任意长度的来帧拼进 512 采样(32ms)的窗口逐窗推理, 不足一窗的尾部留待下次。
 *
 * <p><b>非线程安全</b>: 每路会话独占一个实例(与 {@link HandsFreeVad} 同生命周期)。
 */
public final class SileroVad implements VoiceActivityDetector {

    private static final Logger log = LoggerFactory.getLogger(SileroVad.class);

    /** 16kHz 下 Silero v5 固定的窗口大小(采样数) */
    private static final int WINDOW = 512;
    private static final int SAMPLE_RATE = 16000;
    private static final int STATE_DIM = 128;

    private final OrtEnvironment env;
    private final OrtSession session;

    /** 攒够一窗(512)再推理; 不足的尾部留到下次。 */
    private final float[] window = new float[WINDOW];
    private int filled;
    /** RNN 隐状态 [2,1,128], 随每窗推理滚动更新。 */
    private float[][][] state = new float[2][1][STATE_DIM];
    /** 最近一窗的人声概率; 来帧不足一窗时沿用它, 保证调用方每帧都拿得到分值。 */
    private double lastProb;

    SileroVad(OrtEnvironment env, OrtSession session) {
        this.env = env;
        this.session = session;
    }

    @Override
    public double speechProbability(short[] frame16k) {
        if (frame16k == null) {
            return lastProb;
        }
        for (short sample : frame16k) {
            window[filled++] = sample / 32768f;
            if (filled == WINDOW) {
                lastProb = infer(window);
                filled = 0;
            }
        }
        return lastProb;
    }

    @Override
    public void reset() {
        filled = 0;
        lastProb = 0;
        state = new float[2][1][STATE_DIM];
    }

    /** 跑一窗推理, 更新 RNN 状态并返回人声概率; 出错时降级返回 0(当作静音), 不影响主流程。 */
    private double infer(float[] samples) {
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            OnnxTensor input = OnnxTensor.createTensor(env, new float[][]{samples});
            OnnxTensor st = OnnxTensor.createTensor(env, state);
            OnnxTensor sr = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{SAMPLE_RATE}), new long[0]);
            inputs.put("input", input);
            inputs.put("state", st);
            inputs.put("sr", sr);
            try (OrtSession.Result result = session.run(inputs)) {
                float prob = ((float[][]) result.get("output").orElseThrow().getValue())[0][0];
                state = (float[][][]) result.get("stateN").orElseThrow().getValue();
                return prob;
            }
        } catch (Exception e) {
            log.warn("Silero 推理出错, 本窗按静音处理: {}", e.toString());
            return 0;
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }
}
