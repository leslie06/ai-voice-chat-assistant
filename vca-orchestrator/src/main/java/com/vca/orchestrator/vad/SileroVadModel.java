package com.vca.orchestrator.vad;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Silero VAD 的<b>共享</b>承载: 整个进程只加载一次 ONNX 模型, 持有线程安全的 {@link OrtSession}。
 * 每路会话通过 {@link #newDetector()} 拿到一个轻量的 {@link SileroVad}(只带各自的 RNN 隐状态/采样缓冲),
 * 共用同一个 session 做推理 —— 避免每条连接都重复加载 2MB 模型。
 *
 * <p>用法: 由接入层在启动时 {@link #load} 一次(失败则降级到 {@link EnergyVad}), 进程关闭时 {@link #close}。
 */
public final class SileroVadModel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SileroVadModel.class);

    /** 打包进 jar 的默认模型(snakers4/silero-vad v5) */
    private static final String CLASSPATH_MODEL = "silero_vad.onnx";

    private final OrtEnvironment env;
    private final OrtSession session;

    private SileroVadModel(OrtEnvironment env, OrtSession session) {
        this.env = env;
        this.session = session;
    }

    /**
     * 加载模型。{@code modelPath} 为空则从 classpath 取打包的默认模型。
     *
     * @param modelPath 外部 onnx 文件路径; 空/null 用 classpath 默认
     * @return 加载好的共享模型
     * @throws Exception 文件缺失或模型不合法时抛出, 由调用方决定是否降级
     */
    public static SileroVadModel load(String modelPath) throws Exception {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        byte[] model = readModel(modelPath);
        // 与官方示例一致: 单线程 inter/intra-op + CPU。模型极小, 多线程反而增开销/抖动,
        // 单线程在这种逐窗小推理上延迟更稳。
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setInterOpNumThreads(1);
        opts.setIntraOpNumThreads(1);
        opts.addCPU(true);
        OrtSession session = env.createSession(model, opts);
        log.info("Silero VAD 模型已加载: {} ({} bytes)",
                (modelPath == null || modelPath.isBlank()) ? "classpath:" + CLASSPATH_MODEL : modelPath, model.length);
        return new SileroVadModel(env, session);
    }

    private static byte[] readModel(String modelPath) throws Exception {
        if (modelPath != null && !modelPath.isBlank()) {
            return Files.readAllBytes(Path.of(modelPath));
        }
        try (InputStream in = SileroVadModel.class.getClassLoader().getResourceAsStream(CLASSPATH_MODEL)) {
            if (in == null) {
                throw new IllegalStateException("classpath 缺少 " + CLASSPATH_MODEL + ", 且未配置 sileroModelPath");
            }
            return in.readAllBytes();
        }
    }

    /** 新建一路会话用的检测器, 各自维护独立的 RNN 状态与采样缓冲。 */
    public SileroVad newDetector() {
        return new SileroVad(env, session);
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (Exception e) {
            log.warn("关闭 Silero session 出错: {}", e.toString());
        }
        // OrtEnvironment 为进程级单例, 由 onnxruntime 自行管理, 此处不关。
    }
}
