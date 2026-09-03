package com.vca.orchestrator.vad;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回声判别器的合成信号验证。
 *
 * <p><b>能测什么, 不能测什么, 先说清楚</b>: 这里验证的是<b>算法</b> —— 延迟估计、增益拟合、
 * 残差判决在各种配比下是否给出正确结论。真实声学环境(房间混响、扬声器非线性失真、手机自带 AEC
 * 的残留特性)无法在单测里复现, 那部分只能上真机验证。所以这些用例保证的是"数学没错",
 * 而不是"外放一定能打断"。
 *
 * <p>信号模型: 两个不相关声源的功率可加。麦克风某帧的功率 = 增益 × 下行对应帧的功率 + 人声功率,
 * 用独立噪声按目标幅度合成 —— 这是回声路径的标准建模, 也是判别器赖以成立的前提。
 */
class EchoGuardTest {

    private static final int STEP_MS = 10;
    private static final int SAMPLE_RATE = 16000;
    private static final int SAMPLES_PER_STEP = SAMPLE_RATE * STEP_MS / 1000;

    /**
     * 造一段"话"的幅度包络: 音节 + 音节间的停顿。
     *
     * <p>包络<b>必须有起伏</b> —— 判别器靠的正是"麦克风的音量起伏跟不跟着下行走",
     * 恒定音量没有可对齐的形状(真实语音也不是恒定音量, 恒定的那是噪声)。
     *
     * <p>用一个 {@link Random} <b>顺序</b>生成, 而不是每帧 {@code new Random(种子+帧号)}:
     * java.util.Random 对连续种子的首个输出高度相关, 那样造出来的"包络"几乎是条直线,
     * 测出来的相关性会是噪声 —— 不是算法不行, 是假信号不像话。
     */
    private static double[] envelope(int steps, long seed) {
        Random r = new Random(seed);
        double[] a = new double[steps];
        int t = 0;
        while (t < steps) {
            int len = 3 + r.nextInt(8);                       // 一个音节 30~100ms
            boolean pause = r.nextInt(4) == 0;                // 四分之一概率是音节间停顿
            double amp = pause ? 0.015 : 0.10 + 0.25 * r.nextDouble();
            for (int k = 0; k < len && t < steps; k++, t++) {
                a[t] = amp;
            }
        }
        return a;
    }

    /** 用独立噪声合成一帧目标幅度的 PCM。判别器只看每帧功率, 波形本身无所谓。 */
    private static byte[] pcm(double amp, Random rnd) {
        byte[] out = new byte[SAMPLES_PER_STEP * 2];
        for (int i = 0; i < SAMPLES_PER_STEP; i++) {
            short v = (short) (amp * 32767 * (rnd.nextDouble() * 2 - 1));
            out[i * 2] = (byte) (v & 0xFF);
            out[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return out;
    }

    private static short[] shorts(double amp, Random rnd) {
        short[] out = new short[SAMPLES_PER_STEP];
        for (int i = 0; i < SAMPLES_PER_STEP; i++) {
            out[i] = (short) (amp * 32767 * (rnd.nextDouble() * 2 - 1));
        }
        return out;
    }

    /**
     * 跑一段仿真。
     *
     * @param steps       仿真步数(每步 10ms)
     * @param playing     下行是否在放音
     * @param echoGain    回声<b>幅度</b>增益(0 = 戴耳机, 完全没有回声绕回来)
     * @param delaySteps  回声延迟(步)
     * @param userAmp     用户说话的幅度(0 = 用户没说话)
     * @return 仿真结束时的判别结果
     */
    private static boolean simulate(int steps, boolean playing, double echoGain,
                                    int delaySteps, double userAmp) {
        return simulate(steps, playing, echoGain, delaySteps, userAmp, 11, 29);
    }

    private static boolean simulate(int steps, boolean playing, double echoGain,
                                    int delaySteps, double userAmp, long botSeed, long humanSeed) {
        EchoGuard guard = new EchoGuard();
        double[] bot = envelope(steps, botSeed);        // 机器人说的话
        double[] human = envelope(steps, humanSeed);    // 用户说的话(与前者独立)
        Random rnd = new Random(botSeed * 31 + humanSeed);
        long now = 1_000_000;
        for (int t = 0; t < steps; t++, now += STEP_MS) {
            if (playing) {
                guard.onPlayback(pcm(bot[t], rnd), SAMPLE_RATE, now);
            }
            // 两个不相关声源功率相加 → 幅度按平方和开方
            double echo = (playing && t >= delaySteps) ? echoGain * bot[t - delaySteps] : 0;
            double user = userAmp > 0 ? userAmp / 0.3 * human[t] : 0;
            double mic = Math.sqrt(echo * echo + user * user);
            guard.onMic(shorts(mic, rnd), SAMPLE_RATE, now);
        }
        return guard.userSpeechLikely(now);
    }

    // ---- 回声不该被当成用户说话(否则机器人被自己掐断) ----

    @Test
    void pureEchoIsNotMistakenForUserSpeech() {
        assertThat(simulate(200, true, 0.5, 30, 0))
                .as("纯回声(增益 0.5, 延迟 300ms)")
                .isFalse();
    }

    @Test
    void echoIsRecognisedAcrossGainRange() {
        // 扬声器音量从很小到接近原声, 都该认出来是回声
        assertThat(simulate(200, true, 0.15, 30, 0)).as("弱回声 0.15").isFalse();
        assertThat(simulate(200, true, 0.9, 30, 0)).as("强回声 0.9").isFalse();
    }

    @Test
    void echoIsRecognisedAcrossDelayRange() {
        // 链路延迟随网络/前端缓冲变化很大, 100ms 到 1 秒都要覆盖
        assertThat(simulate(250, true, 0.5, 10, 0)).as("延迟 100ms").isFalse();
        assertThat(simulate(250, true, 0.5, 50, 0)).as("延迟 500ms").isFalse();
        assertThat(simulate(300, true, 0.5, 110, 0)).as("延迟 1100ms").isFalse();
    }

    // ---- 用户说话必须被放行(否则打不断, 等于退回半双工) ----

    @Test
    void userSpeakingWithNoPlaybackIsDetected() {
        assertThat(simulate(200, false, 0, 0, 0.25))
                .as("机器人没在说话, 用户开口")
                .isTrue();
    }

    @Test
    void userSpeakingOverEchoIsDetected() {
        // 双讲: 用户的声音叠在回声上。这是外放打断的实际场景。
        assertThat(simulate(200, true, 0.5, 30, 0.25))
                .as("双讲: 回声 0.5 + 用户 0.25")
                .isTrue();
    }

    @Test
    void quietUserOverLoudEchoIsStillDetected() {
        // 用户比回声轻不少时仍要能打断 —— 判别器往灵敏这边偏就是为了这个场景
        assertThat(simulate(200, true, 0.6, 30, 0.3))
                .as("回声偏大、用户偏小的双讲")
                .isTrue();
    }

    @Test
    void headphonesCaseIsNotSuppressed() {
        // 戴耳机: 机器人在放音但没有任何回声绕回麦克风。
        // 此时麦克风里的能量与下行毫不相关, 绝不能因为"正在放音"就判成回声。
        assertThat(simulate(200, true, 0, 0, 0.25))
                .as("戴耳机: 在放音但无回声, 用户开口")
                .isTrue();
    }

    @Test
    void delayBeyondSearchRangeFailsOpen() {
        // 延迟超出搜索上限(对不齐)时必须<b>放行</b>而不是当成回声压住 ——
        // 判错方向决定了退化行为: 放行最坏是偶尔被回声误打断(还有 bargeMs 兜着),
        // 压住则是用户彻底打不断, 后者严重得多。
        assertThat(simulate(400, true, 0.5, 200, 0))
                .as("延迟 2000ms, 超出 1200ms 搜索范围")
                .isTrue();
    }

    // ---- 边界 ----

    @Test
    void silenceIsNotUserSpeech() {
        assertThat(simulate(200, false, 0, 0, 0)).as("全程静音").isFalse();
    }

    @Test
    void decisionIsThrottledButNotStuck() {
        // 限流不能把判决卡死在初值上(用 Long.MIN_VALUE 当初值会溢出, 正是这里要防的回归)
        EchoGuard guard = new EchoGuard();
        double[] bot = envelope(200, 11);
        Random rnd = new Random(1);
        long now = 1_000_000;
        for (int t = 0; t < 200; t++, now += STEP_MS) {
            guard.onPlayback(pcm(bot[t], rnd), SAMPLE_RATE, now);
            double echo = t >= 30 ? 0.5 * bot[t - 30] : 0;
            guard.onMic(shorts(echo, rnd), SAMPLE_RATE, now);
        }
        assertThat(guard.userSpeechLikely(now)).as("首次判决必须真的算, 不能返回初值").isFalse();
    }

    /**
     * 多种子统计。单个种子过了可能只是运气 —— 这条按 40 组独立语料跑完整场景矩阵,
     * 看的是<b>准确率</b>而不是某一次的结果。
     *
     * <p>门槛设在 95% 而不是 100%: 判决每 100ms 出一次, 偶尔一次判错并不会造成用户可感知的问题 ——
     * 打断还要求 {@code bargeMs}(默认 400ms)的持续人声才确认, 单次判决的抖动会被它吸收掉。
     */
    @Test
    void staysAccurateAcrossManyIndependentUtterances() {
        int seeds = 40;
        int correct = 0;
        int total = 0;
        for (int i = 0; i < seeds; i++) {
            long b = 11 + i;
            long h = 29 + i;
            // 期望"这是回声"(不放行打断)
            correct += simulate(250, true, 0.5, 30, 0, b, h) ? 0 : 1;
            correct += simulate(250, true, 0.15, 30, 0, b, h) ? 0 : 1;
            correct += simulate(250, true, 0.9, 30, 0, b, h) ? 0 : 1;
            correct += simulate(320, true, 0.5, 110, 0, b, h) ? 0 : 1;
            correct += simulate(250, false, 0, 0, 0, b, h) ? 0 : 1;
            // 期望"有人在说话"(放行打断)
            correct += simulate(250, true, 0.0, 0, 0.25, b, h) ? 1 : 0;
            correct += simulate(250, true, 0.5, 30, 0.25, b, h) ? 1 : 0;
            correct += simulate(250, true, 0.6, 30, 0.3, b, h) ? 1 : 0;
            correct += simulate(250, false, 0, 0, 0.25, b, h) ? 1 : 0;
            correct += simulate(420, true, 0.5, 200, 0, b, h) ? 1 : 0;
            total += 10;
        }
        double accuracy = (double) correct / total;
        System.out.printf("回声判别准确率: %.1f%% (%d/%d)%n", accuracy * 100, correct, total);
        assertThat(accuracy).as("跨 " + seeds + " 组独立语料的判别准确率").isGreaterThan(0.95);
    }

    @Test
    void lockedDelayIsReportedAndCleared() {
        EchoGuard guard = new EchoGuard();
        double[] bot = envelope(250, 11);
        Random rnd = new Random(3);
        long now = 1_000_000;
        for (int t = 0; t < 250; t++, now += STEP_MS) {
            guard.onPlayback(pcm(bot[t], rnd), SAMPLE_RATE, now);
            double echo = t >= 40 ? 0.5 * bot[t - 40] : 0;
            guard.onMic(shorts(echo, rnd), SAMPLE_RATE, now);
        }
        guard.userSpeechLikely(now);
        // 估出的延迟应落在真值 400ms 附近(包络分辨率 10ms, 允许几帧误差)
        assertThat(guard.lockedLagMs()).isBetween(350, 450);

        guard.reset();
        assertThat(guard.lockedLagMs()).isEqualTo(-1);
    }
}
