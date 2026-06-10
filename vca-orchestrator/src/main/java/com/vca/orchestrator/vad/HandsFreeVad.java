package com.vca.orchestrator.vad;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 免提模式的服务端 VAD 状态机。把原先写在浏览器里的"开口检测 / 句尾断句 / 插话打断 / 预滚"
 * 逻辑整体搬到后端: 前端只需持续上传原始 PCM, 由本类决定何时开启一轮、何时提交、何时打断。
 *
 * <p>状态流转(对应原前端 await/speak/wait):
 * <pre>
 *   OFF   关闭(非免提)
 *   AWAIT 聆听·等你开口     —— 持续算电平, 不喂 ASR; 检测到持续人声→开口
 *   SPEAK 录音中            —— 上传音频给 ASR; 句尾静音达阈值→提交
 *   WAIT  等待机器人回复     —— 监听打断; 机器人说话时检测到持续人声→打断并立即开启新一轮
 * </pre>
 *
 * <p>两个关键点沿用原设计:
 * <ol>
 *   <li>只有真检测到开口才开启本轮 ASR, 避免一直把静音推给云端被判 IdleTimeout;</li>
 *   <li>始终维护预滚缓冲, 开口/打断瞬间先补发之前 ~{@code prerollMs} 的音频, 不切第一个字。</li>
 * </ol>
 *
 * <p>本类<b>非线程安全</b>: 调用方需串行调用(WebSocket 接入层在连接锁内调用)。
 */
public class HandsFreeVad {

    /** VAD 决策回调。由接入层把这些语义事件接到"开启回合/喂帧/提交/打断"。 */
    public interface Listener {
        /** 检测到开口(或打断后开启新一轮): 应开启本轮 ASR */
        void onSpeechStart();

        /** 一帧已确认属于本轮说话的音频(16k 单声道 PCM, 含补发的预滚): 应喂给 ASR */
        void onAudio(byte[] pcm16le);

        /** 句尾静音达阈值: 应提交本轮 */
        void onSpeechEnd();

        /** 机器人说话期间检测到持续人声: 应打断当前回合 */
        void onBargeIn();
    }

    private enum State { OFF, AWAIT, SPEAK, WAIT }

    private final VadConfig cfg;
    private final Listener listener;
    /** 逐帧人声打分器: 能量法或 Silero, 状态机逻辑对其无感。 */
    private final VoiceActivityDetector detector;

    private State state = State.OFF;
    private int inputSampleRate = 48000;

    private double speechMs;
    private double silenceMs;
    private double bargeMs;
    /** 机器人本次连续说话已持续的时长(ms), 用于起播保护期判定 */
    private double botSpeakingMs;

    private final Deque<short[]> preroll = new ArrayDeque<>();
    private double prerollMs;

    /** 默认用能量阈值法(原行为, 零依赖)。 */
    public HandsFreeVad(VadConfig cfg, Listener listener) {
        this(cfg, listener, new EnergyVad());
    }

    public HandsFreeVad(VadConfig cfg, Listener listener, VoiceActivityDetector detector) {
        this.cfg = cfg;
        this.listener = listener;
        this.detector = detector == null ? new EnergyVad() : detector;
    }

    /** 开启免提: 进入"等你开口"。inputSampleRate 为前端上行 PCM 的采样率。 */
    public void start(int inputSampleRate) {
        this.inputSampleRate = inputSampleRate > 0 ? inputSampleRate : 48000;
        state = State.AWAIT;
        resetCounters();
        clearPreroll();
        detector.reset();
    }

    /** 关闭免提 */
    public void stop() {
        state = State.OFF;
        resetCounters();
        clearPreroll();
        detector.reset();
    }

    public boolean isActive() {
        return state != State.OFF;
    }

    /**
     * 喂入一帧前端上行的原始 PCM(小端 16bit, 采样率 = {@link #start} 传入的值)。
     *
     * @param pcm16leNative 原始 PCM 字节
     * @param botSpeaking   后端当前是否正在向用户播放音频(用于打断判定, 等价于原前端的 playing>0)
     */
    public void accept(byte[] pcm16leNative, boolean botSpeaking) {
        if (state == State.OFF) {
            return;
        }
        short[] frame = PcmAudio.resample(PcmAudio.decodeLe(pcm16leNative), inputSampleRate, cfg.targetSampleRate());
        if (frame.length == 0) {
            return;
        }
        double level = detector.speechProbability(frame);
        double frameMs = frame.length * 1000.0 / cfg.targetSampleRate();
        pushPreroll(frame, frameMs);

        switch (state) {
            // 半双工: 机器人说话期间不判打断(回声进不来), 彻底断掉自打断死循环; 手动按钮仍可打断
            case WAIT -> {
                if (!cfg.halfDuplex()) {
                    bargeDetect(level, frameMs, botSpeaking);
                }
            }
            case AWAIT -> {
                if (level > cfg.speechThreshold()) {
                    speechMs += frameMs;
                    if (speechMs >= cfg.onsetMs()) {
                        begin();
                    }
                } else {
                    speechMs = Math.max(0, speechMs - frameMs);
                }
            }
            case SPEAK -> {
                listener.onAudio(PcmAudio.encodeLe(frame));
                if (level > cfg.speechThreshold()) {
                    silenceMs = 0;
                } else {
                    silenceMs += frameMs;
                    if (silenceMs >= cfg.silenceMs()) {
                        end();
                    }
                }
            }
            default -> {
            }
        }
    }

    /**
     * 机器人这一轮结束/被打断/出错后调用: 回到"等你开口"。
     * 若正处于 SPEAK(例如刚打断后立刻开启的新一轮)或已关闭, 则不打扰。
     */
    public void resumeListening() {
        if (state == State.OFF || state == State.SPEAK) {
            return;
        }
        state = State.AWAIT;
        resetCounters();
        clearPreroll();
        detector.reset();
    }

    // ---- 内部 ----

    /** 开口 → 开启本轮: 先回调开启, 再把预滚(含开口瞬间)补发出去 */
    private void begin() {
        state = State.SPEAK;
        resetCounters();
        listener.onSpeechStart();
        flushPreroll();
    }

    /** 句尾静音 → 提交本轮, 转入等待机器人回复 */
    private void end() {
        state = State.WAIT;
        resetCounters();
        listener.onSpeechEnd();
    }

    /** 打断判定: 机器人在说话且人声持续够久 → 打断, 并把这次插话当作新一轮开始(含预滚, 不丢开头) */
    private void bargeDetect(double level, double frameMs, boolean botSpeaking) {
        if (!botSpeaking) {
            bargeMs = 0;
            botSpeakingMs = 0;
            return;
        }
        botSpeakingMs += frameMs;
        // 起播保护期: 机器人刚开口的头 bargeGraceMs 内不判打断, 避免被自己头几个字的回声掐断
        if (botSpeakingMs < cfg.bargeGraceMs()) {
            bargeMs = 0;
            return;
        }
        if (level > cfg.bargeThreshold()) {
            bargeMs += frameMs;
        } else {
            bargeMs = Math.max(0, bargeMs - frameMs);
        }
        if (bargeMs >= cfg.bargeMs()) {
            listener.onBargeIn();
            begin();
        }
    }

    private void pushPreroll(short[] frame, double frameMs) {
        preroll.addLast(frame);
        prerollMs += frameMs;
        while (prerollMs > cfg.prerollMs() && preroll.size() > 1) {
            short[] dropped = preroll.removeFirst();
            prerollMs -= dropped.length * 1000.0 / cfg.targetSampleRate();
        }
    }

    private void flushPreroll() {
        for (short[] frame : preroll) {
            listener.onAudio(PcmAudio.encodeLe(frame));
        }
        clearPreroll();
    }

    private void clearPreroll() {
        preroll.clear();
        prerollMs = 0;
    }

    private void resetCounters() {
        speechMs = 0;
        silenceMs = 0;
        bargeMs = 0;
        botSpeakingMs = 0;
    }
}
