package com.vca.orchestrator.vad;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Silero VAD(ONNX, v5)的<b>单路</b>检测器: 维护本路会话的 RNN 隐状态与采样缓冲。
 * 由 {@link SileroVadModel#newDetector()} 创建, 与共享的 {@link OrtSession} 配合做逐帧打分。
 *
 * <p>模型契约(16kHz, silero v5): 输入 {@code input[1, 64+512] / state[2,1,128] / sr(int64)},
 * 输出 {@code output[1,1]}(人声概率 0~1)与 {@code stateN[2,1,128]}(更新后的状态)。
 * <b>关键</b>: 每窗输入 = 上一窗尾部 64 采样(context) + 本窗 512 新采样 = 576, 少了 context 前缀
 * 模型会对任何输入恒输出≈0(等于失效)。因此本类把来帧拼进 512 采样(32ms)的新窗, 推理时前置
 * 64 采样上文凑成 576, 推理后用本窗尾部 64 采样更新上文; 不足一窗的尾部留待下次。
 *
 * <p><b>非线程安全</b>: 每路会话独占一个实例(与 {@link HandsFreeVad} 同生命周期)。
 */
public final class SileroVad implements VoiceActivityDetector {

    private static final Logger log = LoggerFactory.getLogger(SileroVad.class);

    /** 16kHz 下 Silero v5 每窗新采样数 */
    private static final int WINDOW = 512;
    /** 16kHz 下需前置的上文采样数(silero v5 内部上下文窗) */
    private static final int CONTEXT = 64;
    private static final int SAMPLE_RATE = 16000;
    private static final int STATE_DIM = 128;

    private final OrtEnvironment env;
    private final OrtSession session;

    /** 攒够一窗(512)再推理; 不足的尾部留到下次。 */
    private final float[] window = new float[WINDOW];
    private int filled;
    /** 上一窗尾部的 64 采样, 作为本窗推理的上文前缀; 初始全 0。 */
    private float[] context = new float[CONTEXT];
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
        context = new float[CONTEXT];
        state = new float[2][1][STATE_DIM];
    }

    /** 跑一窗推理, 更新 RNN 状态与上文并返回人声概率; 出错时降级返回 0(当作静音), 不影响主流程。 */
    private double infer(float[] newWindow) {
        // 输入 = 上文(64) + 本窗新采样(512) = 576; 缺这 64 前缀模型会恒输出≈0
        float[] input = new float[CONTEXT + WINDOW];
        System.arraycopy(context, 0, input, 0, CONTEXT);
        System.arraycopy(newWindow, 0, input, CONTEXT, WINDOW);

        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            OnnxTensor in = OnnxTensor.createTensor(env, new float[][]{input});
            OnnxTensor st = OnnxTensor.createTensor(env, state);
            // sr 作 1 维 int64 张量 [1], 与官方示例一致
            OnnxTensor sr = OnnxTensor.createTensor(env, new long[]{SAMPLE_RATE});
            inputs.put("input", in);
            inputs.put("state", st);
            inputs.put("sr", sr);
            try (OrtSession.Result result = session.run(inputs)) {
                float prob = ((float[][]) result.get("output").orElseThrow().getValue())[0][0];
                state = (float[][][]) result.get("stateN").orElseThrow().getValue();
                // 更新上文: 取本窗尾部 64 采样, 供下一窗作前缀
                System.arraycopy(newWindow, WINDOW - CONTEXT, context, 0, CONTEXT);
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
