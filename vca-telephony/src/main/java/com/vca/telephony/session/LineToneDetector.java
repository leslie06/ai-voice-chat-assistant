package com.vca.telephony.session;

import com.vca.orchestrator.vad.PcmAudio;

/**
 * 线路信号音检测: 听出"对面已经不是人, 是交换机在放忙音/拨号音"。
 *
 * <h2>为什么需要</h2>
 * 模拟线(FXO 网关接的固话线、插卡盒)<b>没有挂机信令</b>: 客户挂断后, 线上只是开始放忙音。
 * 网关靠"听忙音"来判断该不该拆线, 而这一步要在网关上单独配、参数还得和当地制式对上,
 * 漏配或配错是常态。线上实测: 客户 22:48:30 挂断, 网关没察觉, 通话一直挂到 221 秒被人工掐掉 ——
 * 这期间线路被占着, 后面的来电一通都进不来; 而本进程的 VAD 把循环的忙音当成了
 * "有人一直在说话": 忙音的间隔 320ms 不到句尾静音要求的 800ms, 这一轮永远等不到"说完了"。
 *
 * <h2>怎么认</h2>
 * 信号音是<b>纯音</b>, 人声不是。逐帧看过零点: 纯音的过零间隔几乎恒定, 由此得到的频率落在
 * 一个很窄的带内; 人声的过零间隔忽长忽短(基频 + 共振峰 + 清音的宽带噪声)。
 * 最近 {@value #WINDOW_MS}ms 里响度够的帧若绝大多数都是同一频带内的纯音, 就判定为信号音。
 *
 * <p>国内信号音全是 450Hz(拨号音连续、忙音 350/350、拥塞音 700/700), 按录音实测:
 * 忙音 450Hz、响 380ms 停 320ms、电平 0.27。频带放宽到 {@value #MIN_HZ}~{@value #MAX_HZ}Hz
 * 顺带盖住 425Hz 的欧式制式。只要求"够多的纯音帧"而不死抠通断节奏, 所以连续的拨号音、
 * 节奏不同的拥塞音也认得出来; 回铃音(响 1 秒停 4 秒)在窗口里凑不够帧数, 不会误判。
 *
 * <p>纯函数式、无时钟依赖, 按帧喂入, 可确定性单测。非线程安全, 由调用方串行喂帧。
 */
final class LineToneDetector {

    static final int WINDOW_MS = 4000;
    static final int MIN_HZ = 380;
    static final int MAX_HZ = 520;

    /** 帧 RMS 低于此值按静音处理(不参与判定) */
    private static final double LOUD_RMS = 0.02;
    /** 窗口内至少要有这么久的响帧: 忙音占空比约一半, 4 秒里约 2 秒; 回铃音只有 1 秒, 刻意卡在它上面 */
    private static final int MIN_LOUD_MS = 1600;
    /** 响帧里纯音帧的占比下限。人声偶尔有一两帧凑巧像纯音, 但成片地像做不到 */
    private static final double MIN_TONAL_RATIO = 0.85;
    /** 过零检测的死区(满幅的比例): 停顿段的底噪在零点附近来回抖, 不设死区会数出一堆假过零 */
    private static final double DEAD_BAND = 0.01;
    /** 过零间隔的离散度上限: 纯音的间隔几乎恒定, 8k 采样下 450Hz 的半周期在 8 与 9 个采样之间跳 */
    private static final double MAX_INTERVAL_JITTER = 0.30;

    private final int sampleRate;
    /** 环形窗口: 0=静音, 1=响但不是纯音, 2=纯音; 每格同时记下这帧的时长 */
    private final byte[] kinds;
    private final int[] durationsMs;
    private int head;
    private int filled;
    private int windowMs;
    private int loudMs;
    private int tonalMs;

    LineToneDetector(int sampleRate) {
        this.sampleRate = sampleRate > 0 ? sampleRate : 8000;
        // 电话一帧 20ms, 给足余量; 帧更短时窗口靠 windowMs 计时收口, 不靠格数
        int slots = WINDOW_MS / 10 + 8;
        this.kinds = new byte[slots];
        this.durationsMs = new int[slots];
    }

    /**
     * 喂一帧上行音频。
     *
     * @return 此刻是否判定线上是信号音(忙音/拨号音/拥塞音)
     */
    boolean accept(byte[] pcm16le) {
        if (pcm16le == null || pcm16le.length < 4) {
            return false;
        }
        short[] s = PcmAudio.decodeLe(pcm16le);
        int ms = Math.max(1, s.length * 1000 / sampleRate);
        push(classify(s), ms);
        return windowMs >= WINDOW_MS - 40
                && loudMs >= MIN_LOUD_MS
                && tonalMs >= loudMs * MIN_TONAL_RATIO;
    }

    /** 清空窗口。通话复用检测器时用(目前每路通话一个实例, 留给单测) */
    void reset() {
        head = 0;
        filled = 0;
        windowMs = 0;
        loudMs = 0;
        tonalMs = 0;
    }

    private void push(byte kind, int ms) {
        // 窗口满了就从最老的一格开始淘汰, 直到腾出这一帧的时长
        while (filled > 0 && (windowMs + ms > WINDOW_MS || filled == kinds.length)) {
            int tail = (head - filled + kinds.length) % kinds.length;
            windowMs -= durationsMs[tail];
            if (kinds[tail] >= 1) {
                loudMs -= durationsMs[tail];
            }
            if (kinds[tail] == 2) {
                tonalMs -= durationsMs[tail];
            }
            filled--;
        }
        kinds[head] = kind;
        durationsMs[head] = ms;
        head = (head + 1) % kinds.length;
        filled++;
        windowMs += ms;
        if (kind >= 1) {
            loudMs += ms;
        }
        if (kind == 2) {
            tonalMs += ms;
        }
    }

    private byte classify(short[] s) {
        double sum = 0;
        for (short v : s) {
            sum += (double) v * v;
        }
        double rms = Math.sqrt(sum / s.length) / 32768.0;
        if (rms < LOUD_RMS) {
            return 0;
        }
        return isPureTone(s) ? (byte) 2 : (byte) 1;
    }

    /**
     * 这一帧是不是目标频带内的纯音。
     *
     * <p>用<b>过零间隔</b>而不是过零次数: 忙音一响一停, 边缘帧只有半帧有声, 按次数除以整帧时长
     * 会把频率估低一半; 按间隔算则只看有声的那半段, 边缘帧照样认得准。
     */
    private boolean isPureTone(short[] s) {
        int dead = (int) (32768 * DEAD_BAND);
        int lastSign = 0;
        int lastCross = -1;
        int count = 0;
        double sumIv = 0;
        double minIv = Double.MAX_VALUE;
        double maxIv = 0;
        for (int i = 0; i < s.length; i++) {
            int sign = s[i] > dead ? 1 : (s[i] < -dead ? -1 : 0);
            if (sign == 0) {
                continue;
            }
            if (lastSign != 0 && sign != lastSign) {
                if (lastCross >= 0) {
                    int iv = i - lastCross;
                    sumIv += iv;
                    minIv = Math.min(minIv, iv);
                    maxIv = Math.max(maxIv, iv);
                    count++;
                }
                lastCross = i;
            }
            lastSign = sign;
        }
        if (count < 4) {
            return false;   // 过零太少: 要么太短, 要么是低频隆隆声, 都不是信号音
        }
        double meanIv = sumIv / count;
        double hz = sampleRate / (2.0 * meanIv);
        if (hz < MIN_HZ || hz > MAX_HZ) {
            return false;
        }
        return (maxIv - minIv) <= meanIv * MAX_INTERVAL_JITTER + 1.0;
    }
}
