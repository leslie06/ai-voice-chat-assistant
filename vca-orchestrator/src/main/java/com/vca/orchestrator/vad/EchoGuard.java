package com.vca.orchestrator.vad;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 回声感知的打断判别(双讲检测)。回答一个问题: <b>此刻麦克风里的声音, 是用户在说话, 还是机器人自己的声音绕回来了?</b>
 *
 * <h3>为什么需要它</h3>
 * 外放场景下扬声器的声音会被麦克风收回去。不加区分地判打断, 机器人会被自己的声音掐断, 陷入
 * "说一句→自打断→再说→再自打断"的死循环。现在的对策是 {@code halfDuplex=true} ——
 * 机器人说话期间<b>干脆不判打断</b>。代价是外放时语音打断彻底失效, 只剩手动按钮,
 * 而外放恰恰是语音助手最主要的使用场景(开车、做饭、躺着)。
 *
 * <h3>为什么不做完整的回声消除(AEC)</h3>
 * 真正的 AEC(自适应滤波把回声从信号里减掉)要在样本级对齐, 纯 Java 做不到能用的质量, 得引原生库。
 * 但这里<b>并不需要消除回声</b> —— 半双工模式下那段音频本来就不会送去识别。需要的只是<b>判别</b>:
 * 这股能量是不是回声。判别比消除简单一个数量级, 且纯 Java 就能做好。
 *
 * <h3>怎么做</h3>
 * 服务端手里同时握着两端信号: 下行 TTS 音频是它自己发出去的, 上行麦克风音频是它自己收的。
 * 于是:
 * <ol>
 *   <li>两路各自算 10ms 一帧的<b>能量包络</b>(不是原始波形)。用包络而不是波形是关键 ——
 *       波形要样本级对齐, 且会被扬声器/麦克风的传递函数、重采样、有损压缩打乱;
 *       而"音量的起伏形状"能穿过这些环节基本不变。</li>
 *   <li>在 0~{@value #MAX_LAG_MS}ms 的范围里搜索延迟 τ, 让下行包络与上行包络最相关。
 *       τ 一次性吸收掉全部链路延迟: 网络下行 + 前端抖动缓冲 + 播放调度 + 声学传播 + 网络上行。</li>
 *   <li>按最小二乘算出增益 g(扬声器音量 + 声学衰减), 得到<b>预测回声</b> {@code g × 下行[t-τ]}。</li>
 *   <li>看麦克风能量里<b>预测不了的那部分</b>(残差)占多大比例。纯回声时残差接近 0;
 *       用户开口时多出一个独立声源, 残差立刻抬起来 —— 这正是经典的双讲检测。</li>
 * </ol>
 *
 * <p>注意上行信号已经过浏览器自带的 AEC({@code getUserMedia({echoCancellation:true})}),
 * 这里判别的是<b>残余</b>回声。残余能量更小, 但形状仍跟随下行包络, 所以方法照样成立。
 *
 * <p><b>非线程安全</b>: 与 {@link HandsFreeVad} 一样, 由接入层在连接锁内串行调用。
 */
public final class EchoGuard {

    private static final Logger log = LoggerFactory.getLogger(EchoGuard.class);

    /** 包络帧长(ms)。10ms 足以刻画音节级的音量起伏, 又让互相关的开销小到可以忽略。 */
    static final int FRAME_MS = 10;
    /** 环形缓冲长度(ms): 要装得下"对齐窗 + 最大延迟"还有富余。 */
    private static final int RING_MS = 4000;
    private static final int RING = RING_MS / FRAME_MS;
    /**
     * <b>对齐窗</b>(ms): 估计回声延迟 τ 与增益 g 用的窗口。
     *
     * <p>必须长。延迟搜索要在上百个候选里取相关性最大的那个, 而窗口越短、零假设下的相关性抖动越大 ——
     * 40 帧时 Pearson 的标准差约 {@code 1/√39 ≈ 0.16}, 在 121 个候选里取最大值, 毫不相干的两段信号
     * 也经常能凑出 0.4 以上的"对齐"。那会把用户说话误判成回声, 让人根本打不断。
     * 150 帧把这个抖动压到 0.08 左右, 再配合 {@link #MIN_CORRELATION} 才有区分度。
     */
    static final int ALIGN_WINDOW_MS = 1500;
    private static final int ALIGN_WINDOW = ALIGN_WINDOW_MS / FRAME_MS;

    /**
     * <b>判决窗</b>(ms): 算残差占比用的窗口, 比对齐窗短得多。
     *
     * <p>两个窗口分开是有道理的: 回声的延迟和增益由链路结构与扬声器音量决定, 是<b>慢变量</b>,
     * 用长窗估才稳; 而"用户有没有开口"要求<b>快</b> —— 用 1.5 秒的窗去判, 用户刚说 300ms 时
     * 新增能量只占窗口的五分之一, 残差被稀释到根本触发不了阈值, 打断就慢了一整拍。
     */
    static final int DECISION_WINDOW_MS = 300;
    private static final int DECISION_WINDOW = DECISION_WINDOW_MS / FRAME_MS;
    /** 回声延迟的搜索上限(ms)。覆盖"网络 + 前端缓冲 + 播放"这条链路最坏的情况。 */
    static final int MAX_LAG_MS = 1200;
    private static final int MAX_LAG = MAX_LAG_MS / FRAME_MS;

    /**
     * 残差功率占比阈值: 麦克风功率里"下行解释不了"的部分超过这个比例, 就认为有独立声源(用户在说话)。
     *
     * <p>0.35 对应"人声功率约为残余回声的一半"就判有人说话 —— <b>偏灵敏</b>是有意的:
     * 误判成"用户在说话"只是让打断检测跑起来, 后面还有 {@code bargeThreshold} 和
     * {@code bargeMs} 两道关卡住; 而误判成"这是回声"会让用户<b>根本打不断</b>, 直接退回半双工的体验。
     * 两类错误的代价完全不对称, 所以往灵敏这边靠。
     */
    private static final double RESIDUAL_RATIO_THRESHOLD = 0.35;

    /** 低于此功率视作静音(≈RMS 0.003), 不参与判决 —— 拿噪声底算相关性只会得到随机结果。 */
    private static final double SILENCE_POWER = 0.003 * 0.003;

    /**
     * Pearson 相关系数的下限: 低于它说明麦克风的能量起伏与下行<b>无关</b>(戴耳机、或者根本没回声),
     * 此时不能声称"这是回声"。
     *
     * <p>用<b>去均值</b>的 Pearson 而不是余弦相似度: 功率包络全是非负数, 余弦相似度对两段毫不相干的
     * 信号也能给出 0.7 以上, 拿它当判据会把用户的说话误判成回声。去均值后无关信号的相关性才回落到 0 附近。
     *
     * <p>取 0.60 而不是更低: 真回声是下行包络<b>整段等比例缩放</b>后的副本, 相关性本该很高;
     * 而在 {@value #MAX_LAG_MS}ms 范围里搜出来的"最大相关"天然带着多重比较的乐观偏差
     * (见 {@link #ALIGN_WINDOW_MS})。门槛定高一点, 宁可偶尔漏判回声(退化成现在的行为),
     * 也不要把用户说话判成回声。
     */
    private static final double MIN_CORRELATION = 0.60;

    private final double[] refEnv = new double[RING];
    private final double[] micEnv = new double[RING];
    /** 每个槽写入时的绝对帧号; 与期望帧号不符即视为过期槽(读出 0), 省去清空整个环。 */
    private final long[] refTick = new long[RING];
    private final long[] micTick = new long[RING];

    /** 下一段下行音频预计开始播放的时刻(ms)。镜像前端"接着上一段往后排"的调度方式。 */
    private long playbackCursorMs;
    /** 最近一次估出的回声延迟(帧); 下次优先在它附近搜, 既省算力又让结果稳定。 */
    private int lockedLag = -1;
    /**
     * 上次判决的时刻与结果, 用于限流(没必要每帧都算一次互相关)。
     * 用单独的 {@code decided} 标志而不是把时刻初始化成 {@link Long#MIN_VALUE} ——
     * 那样 {@code now - last} 会溢出成负数, 判决被永远跳过、恒返回初值。
     */
    private long lastDecisionMs;
    private boolean decided;
    private boolean lastDecision = true;

    /** 判决限流间隔(ms): 判决窗 400ms, 每 100ms 重算一次已经足够跟上。 */
    private static final int DECISION_INTERVAL_MS = 100;

    /**
     * 记入一段<b>下行</b>音频(即将发给前端播放的 TTS/S2S 音频)。
     *
     * <p>时间轴按<b>播放时刻</b>而不是发送时刻铺开: TTS 常常成批吐出(几百毫秒音频在几十毫秒内发完),
     * 按发送时刻记会把包络压扁成一个尖峰, 与麦克风收到的真实起伏对不上。
     * 这里复刻前端的排播方式 —— 接着上一段的尾巴往后排, 落后于当前时刻就从当前时刻重新起排。
     *
     * @param pcm16le    小端 16bit 单声道 PCM
     * @param sampleRate 该段音频的采样率
     * @param nowMs      当前时刻(墙钟)
     */
    public void onPlayback(byte[] pcm16le, int sampleRate, long nowMs) {
        if (pcm16le == null || pcm16le.length < 2 || sampleRate <= 0) {
            return;
        }
        int samplesPerFrame = sampleRate * FRAME_MS / 1000;
        if (samplesPerFrame <= 0) {
            return;
        }
        long startMs = Math.max(nowMs, playbackCursorMs);
        int totalSamples = pcm16le.length / 2;
        int frames = totalSamples / samplesPerFrame;
        for (int f = 0; f < frames; f++) {
            double power = powerOfPcm(pcm16le, f * samplesPerFrame * 2, samplesPerFrame);
            write(refEnv, refTick, (startMs + (long) f * FRAME_MS) / FRAME_MS, power);
        }
        playbackCursorMs = startMs + (long) frames * FRAME_MS;
    }

    /**
     * 记入一段<b>上行</b>麦克风音频。调用方给的是已重采样到 VAD 目标采样率的帧。
     *
     * @param frame      16bit PCM 采样(已重采样)
     * @param sampleRate 采样率
     * @param nowMs      收到该帧的时刻(墙钟)
     */
    public void onMic(short[] frame, int sampleRate, long nowMs) {
        if (frame == null || frame.length == 0 || sampleRate <= 0) {
            return;
        }
        int samplesPerFrame = sampleRate * FRAME_MS / 1000;
        if (samplesPerFrame <= 0) {
            return;
        }
        int frames = frame.length / samplesPerFrame;
        for (int f = 0; f < frames; f++) {
            double power = powerOfShorts(frame, f * samplesPerFrame, samplesPerFrame);
            write(micEnv, micTick, (nowMs + (long) f * FRAME_MS) / FRAME_MS, power);
        }
    }

    /**
     * 此刻麦克风里是否<b>很可能有用户在说话</b>(而不只是机器人自己的回声)。
     *
     * @param nowMs 当前时刻(墙钟)
     * @return true = 存在下行解释不了的声源, 应当放行打断检测; false = 判定为纯回声, 不应触发打断
     */
    public boolean userSpeechLikely(long nowMs) {
        if (decided && nowMs - lastDecisionMs < DECISION_INTERVAL_MS) {
            return lastDecision;   // 限流: 沿用上次判决
        }
        decided = true;
        lastDecisionMs = nowMs;
        lastDecision = decide(nowMs);
        return lastDecision;
    }

    private boolean decide(long nowMs) {
        long endTick = nowMs / FRAME_MS;
        long decisionStart = endTick - DECISION_WINDOW;

        double micEnergy = 0;
        for (long t = decisionStart; t < endTick; t++) {
            micEnergy += read(micEnv, micTick, t);
        }
        if (micEnergy / DECISION_WINDOW < SILENCE_POWER) {
            return false;   // 麦克风基本是静音: 没什么可打断的
        }

        // 延迟与增益用长窗估(慢变量, 要稳)
        Alignment best = bestAlignment(endTick - ALIGN_WINDOW, endTick);
        if (best == null || best.correlation < MIN_CORRELATION) {
            // 对不齐 = 麦克风里的能量与下行毫无关系(戴耳机, 或下行本就没在放) → 是独立声源
            lockedLag = -1;
            return true;
        }
        lockedLag = best.lag;

        // 残差用短窗算(要快): 麦克风能量里超出"预测回声"的部分。
        // 纯回声时接近 0; 有人插话时多出一个独立声源, 功率相加, 残差立刻抬起来。
        double residual = 0;
        for (long t = decisionStart; t < endTick; t++) {
            double predicted = best.gain * read(refEnv, refTick, t - best.lag);
            residual += Math.max(0, read(micEnv, micTick, t) - predicted);
        }
        double ratio = residual / micEnergy;
        if (log.isTraceEnabled()) {
            log.trace("回声判别: 延迟={}ms, 增益={}, 相关={}, 残差占比={}",
                    best.lag * FRAME_MS, String.format("%.2f", best.gain),
                    String.format("%.2f", best.correlation), String.format("%.2f", ratio));
        }
        return ratio > RESIDUAL_RATIO_THRESHOLD;
    }

    /** 一次对齐的结果: 延迟(帧)、最小二乘增益、归一化相关系数。 */
    private record Alignment(int lag, double gain, double correlation) {
    }

    /**
     * 在延迟范围内找最佳对齐。已经锁定过延迟时只在其附近小范围搜 ——
     * 回声延迟由链路结构决定, 一次会话里基本不变, 每次全量搜既浪费也容易被噪声带偏。
     */
    private Alignment bestAlignment(long startTick, long endTick) {
        int from = 0;
        int to = MAX_LAG;
        if (lockedLag >= 0) {
            from = Math.max(0, lockedLag - 10);          // ±100ms
            to = Math.min(MAX_LAG, lockedLag + 10);
        }
        Alignment best = scan(startTick, endTick, from, to);
        // 锁定的窗口里没找到像样的对齐(比如用户换了个环境), 退回全量搜一次
        if (lockedLag >= 0 && (best == null || best.correlation < MIN_CORRELATION)) {
            best = scan(startTick, endTick, 0, MAX_LAG);
        }
        return best;
    }

    private Alignment scan(long startTick, long endTick, int from, int to) {
        Alignment best = null;
        int n = (int) (endTick - startTick);
        if (n <= 2) {
            return null;
        }
        for (int lag = from; lag <= to; lag++) {
            double sumMic = 0;
            double sumRef = 0;
            double dotProduct = 0;      // Σ 麦克风·下行, 用来算最小二乘增益(过原点)
            double refSquared = 0;
            double micSquared = 0;
            for (long t = startTick; t < endTick; t++) {
                double m = read(micEnv, micTick, t);
                double r = read(refEnv, refTick, t - lag);
                sumMic += m;
                sumRef += r;
                dotProduct += m * r;
                refSquared += r * r;
                micSquared += m * m;
            }
            if (refSquared <= 0 || micSquared <= 0) {
                continue;   // 该延迟下下行是静音: 谈不上"是回声"
            }
            // Pearson(去均值)判"形状像不像"; 增益用过原点的最小二乘(功率非负, 不该有截距)
            double covariance = dotProduct - sumMic * sumRef / n;
            double refVariance = refSquared - sumRef * sumRef / n;
            double micVariance = micSquared - sumMic * sumMic / n;
            if (refVariance <= 0 || micVariance <= 0) {
                continue;   // 有一路是恒定值(通常是静音): 相关性无从谈起
            }
            double correlation = covariance / Math.sqrt(refVariance * micVariance);
            if (best == null || correlation > best.correlation) {
                best = new Alignment(lag, Math.max(0, dotProduct / refSquared), correlation);
            }
        }
        return best;
    }

    /** 环形写入: 记下帧号, 读时据此判断是否过期。 */
    private static void write(double[] env, long[] ticks, long tick, double value) {
        int i = (int) Math.floorMod(tick, RING);
        env[i] = value;
        ticks[i] = tick;
    }

    /** 环形读取: 槽里存的不是这一帧(被覆盖或从未写过)就算 0(静音)。 */
    private static double read(double[] env, long[] ticks, long tick) {
        int i = (int) Math.floorMod(tick, RING);
        return ticks[i] == tick ? env[i] : 0.0;
    }

    /** 一帧的平均功率(均方)。用功率而非 RMS 的理由见类注释。 */
    private static double powerOfPcm(byte[] pcm16le, int byteOffset, int samples) {
        double sum = 0;
        for (int i = 0; i < samples; i++) {
            int off = byteOffset + i * 2;
            if (off + 1 >= pcm16le.length) {
                break;
            }
            short s = (short) ((pcm16le[off] & 0xFF) | (pcm16le[off + 1] << 8));
            double v = s / 32768.0;
            sum += v * v;
        }
        return sum / samples;
    }

    private static double powerOfShorts(short[] frame, int offset, int samples) {
        double sum = 0;
        for (int i = 0; i < samples && offset + i < frame.length; i++) {
            double v = frame[offset + i] / 32768.0;
            sum += v * v;
        }
        return sum / samples;
    }

    /** 最近一次估出的回声延迟(ms); -1 表示尚未锁定。供诊断日志/指标观察链路延迟。 */
    public int lockedLagMs() {
        return lockedLag < 0 ? -1 : lockedLag * FRAME_MS;
    }

    /** 会话或回合重置: 清掉延迟锁定, 下次重新全量搜索。 */
    public void reset() {
        lockedLag = -1;
        decided = false;
        lastDecisionMs = 0;
        lastDecision = true;
        playbackCursorMs = 0;
    }
}
