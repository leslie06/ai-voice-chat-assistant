package com.vca.telephony.session;

import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.AudioFrame;
import com.vca.orchestrator.session.ConversationSession;
import com.vca.orchestrator.vad.HandsFreeVad;
import com.vca.orchestrator.vad.PcmAudio;
import com.vca.orchestrator.vad.VadConfig;
import com.vca.orchestrator.vad.VoiceActivityDetector;
import com.vca.telephony.spi.CallEvent;
import com.vca.telephony.spi.CallLeg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一路电话通话的编排。<b>它与 {@code VoiceWebSocketHandler.Connection} 是平级的两个接入层</b>:
 * 同样负责"VAD 接线 + 回合管理 + epoch 门闸", 只是 IO 两端从 WebSocket 换成了 {@link CallLeg}。
 * 中间的 {@link ConversationSession}(ASR→LLM→TTS、Skill、RAG、记忆、落库)完全不感知自己在打电话。
 *
 * <h2>与浏览器版的三处实质差异</h2>
 * <ol>
 *   <li><b>下行必须节流</b>: 经 {@link PacingBuffer} 每 {@code pacingMs} 吐一帧, 不能尽快发。</li>
 *   <li><b>"机器人还在说话"不再靠估算</b>: 浏览器版要用 {@code playbackEndsAtMs} 推算前端播放进度,
 *       这里 {@code !pacing.isEmpty()} 就是精确答案。打断也退化成一次 {@link PacingBuffer#clear()}。</li>
 *   <li><b>回合结束后何时回到聆听是精确的</b>: 浏览器版要起一个"等前端播完"的定时器(定早了打断窗口
 *       提前关闭, 就是"说话打不断"的老根因); 这里等缓冲排空即可, 不存在估算误差。</li>
 * </ol>
 *
 * <p>本类<b>不自己持有定时线程</b>: {@link #tick()} 是公开的, {@link #start()} 只是把它挂到
 * {@code Flux.interval} 上。单测可以直接手动步进, 不需要真等 20ms。
 *
 * <p>线程模型: 上行音频(网络线程)、节流(定时线程)、回合收尾(reactor 线程)会并发触碰 VAD 与回合状态,
 * 故触碰共享状态的方法一律 {@code synchronized}(与 {@code Connection} 同构)。
 * 向 {@link CallLeg#writeAudio} 的写出放在锁外, 避免网络 IO 卡住整路会话。
 */
public final class CallSession {

    private static final Logger log = LoggerFactory.getLogger(CallSession.class);

    private final CallLeg leg;
    private final ConversationSession conversation;
    private final HandsFreeVad vad;
    private final PacingBuffer pacing;
    private final CallConfig cfg;
    private final int mediaRate;

    /** 预合成开场白(已是线路采样率的 PCM); 为空则接通后直接进入聆听 */
    private final byte[] greeting;

    private final AtomicLong seq = new AtomicLong();
    private final long startedAtMs = System.currentTimeMillis();

    private Disposable inboundSub;
    private Disposable eventSub;
    private Disposable ticker;

    private volatile boolean answered;
    private volatile boolean closed;

    /** 回合代号: 每开启一轮 +1, 打断时也 +1。只有"当前代号"的下行音频会进缓冲。 */
    private volatile long epoch;
    /** >=0 表示该代号的回合已产完, 等缓冲排空后回到聆听; -1 表示无待处理。 */
    private volatile long resumeEpoch = -1;

    private Sinks.Many<AudioFrame> turnSink;
    private Disposable turnSubscription;

    /**
     * @param vadConfig VAD 阈值。<b>务必用电话专用的一组</b>: 浏览器那套是按 48k 麦克风调的,
     *                  窄带 + 线路底噪下的电平分布完全不同
     * @param detector  逐帧人声打分器(Silero 或能量法), 每路通话一个实例(Silero 的 RNN 状态不可共享)
     * @param greeting  预合成开场白, 已是线路采样率的 PCM; null 则接通后直接聆听
     */
    public CallSession(CallLeg leg, ConversationSession conversation, VadConfig vadConfig,
                       VoiceActivityDetector detector, CallConfig cfg, byte[] greeting) {
        this.leg = leg;
        this.conversation = conversation;
        this.cfg = cfg == null ? CallConfig.defaults() : cfg;
        this.mediaRate = leg.sampleRate();
        this.pacing = new PacingBuffer(mediaRate, this.cfg.pacingMs(), this.cfg.maxBufferedMs());
        this.greeting = greeting;
        this.vad = new HandsFreeVad(vadConfig, vadListener(), detector);
    }

    /** VAD 决策回调 —— 接线方式与浏览器版逐条对应。 */
    private HandsFreeVad.Listener vadListener() {
        return new HandsFreeVad.Listener() {
            @Override
            public void onSpeechStart() {
                ensureTurnStarted();
            }

            @Override
            public void onAudio(byte[] pcm16le) {
                emitFrame(pcm16le);
            }

            @Override
            public void onSpeechEnd() {
                // 同浏览器链路: 体感延迟从客户闭嘴那一刻起算, 判停等待的静音要减掉
                conversation.markUserSpeechEnd(
                        System.currentTimeMillis() - vad.lastEndpointSilenceMs(),
                        vad.lastEndpointSilenceMs(), vad.lastEndpointReason());
                commitTurn();
            }

            @Override
            public void onBargeIn() {
                log.info("[{}] 打断: 客户插话", leg.callId());
                bargeIn();
            }
        };
    }

    /** 订阅媒体与信令, 并起节流器。生产入口。 */
    public void start() {
        attach();
        ticker = Flux.interval(Duration.ofMillis(cfg.pacingMs()), Schedulers.parallel())
                .subscribe(t -> tick());
    }

    /**
     * 只订阅媒体与信令, <b>不起节流器</b> —— 由调用方自己驱动 {@link #tick()}。
     * 单测用它做确定性步进, 不必真等 20ms 一拍。
     */
    public void attach() {
        eventSub = leg.events().subscribe(this::onEvent,
                err -> {
                    log.warn("[{}] 信令流异常: {}", leg.callId(), err.toString());
                    close("signaling-error");
                });
        inboundSub = leg.inboundAudio().subscribe(this::onInboundAudio,
                err -> {
                    log.warn("[{}] 上行媒体异常: {}", leg.callId(), err.toString());
                    close("media-error");
                });
    }

    // ---- 信令 ----

    private void onEvent(CallEvent event) {
        switch (event.type()) {
            case ANSWERED -> onAnswered();
            case HANGUP -> close(event.detail() == null ? "peer-hangup" : event.detail());
            case DTMF -> log.info("[{}] DTMF: {}", leg.callId(), event.detail());
            // 早期媒体(彩铃/运营商提示音)不进对话: 人还没接, 跑 ASR/LLM/TTS 是纯烧钱
            case EARLY_MEDIA, RINGING -> log.debug("[{}] 信令: {}", leg.callId(), event.type());
        }
    }

    /**
     * 真接通。开场白是<b>预合成</b>的, 直接灌进缓冲即可出声 —— 外呼接通后前 3 秒是挂机高发区,
     * 走一遍 LLM+TTS 的首包延迟在这里是致命的, 而开场白文本本来就是固定的。
     */
    private synchronized void onAnswered() {
        if (answered || closed) {
            return;
        }
        answered = true;
        if (greeting != null && greeting.length > 0) {
            pacing.offer(greeting);
        }
        vad.start(mediaRate);
        log.info("[{}] 接通, 开场白 {}ms", leg.callId(), pacing.bufferedMs());
    }

    // ---- 上行 ----

    /** 上行音频。未接通前一律丢弃(见 {@link CallEvent.Type#EARLY_MEDIA})。 */
    private synchronized void onInboundAudio(byte[] pcm) {
        if (!answered || closed) {
            return;
        }
        vad.accept(pcm, botPlaying());
    }

    // ---- 回合(与 Connection 同构) ----

    private synchronized void ensureTurnStarted() {
        if (turnSubscription != null) {
            return;
        }
        // 客户开口就掐掉还在播的音频。正常回合走到这里时缓冲本就是空的; 唯一有内容的情况是
        // 客户在开场白播放中途就插话 —— 那时 VAD 还在 AWAIT, 不会触发 onBargeIn, 得在这里兜住。
        if (cfg.greetingBargeIn() && !pacing.isEmpty()) {
            pacing.clear();
        }
        resumeEpoch = -1;
        seq.set(0);
        final long myEpoch = ++epoch;
        turnSink = Sinks.many().unicast().onBackpressureBuffer();
        turnSubscription = conversation.handleUserTurn(turnSink.asFlux())
                .subscribe(chunk -> onDownlink(chunk, myEpoch),
                        err -> onTurnFinished(myEpoch, err),
                        () -> onTurnFinished(myEpoch, null));
    }

    private synchronized void emitFrame(byte[] pcm16le) {
        if (turnSink != null) {
            turnSink.tryEmitNext(AudioFrame.of(pcm16le, seq.getAndIncrement(), System.currentTimeMillis()));
        }
    }

    /** 客户说完: 补一帧 endOfSpeech 并结束上行流, 触发 ASR 出 final。 */
    private synchronized void commitTurn() {
        if (turnSink == null) {
            return;
        }
        turnSink.tryEmitNext(AudioFrame.endOfSpeech(seq.getAndIncrement(), System.currentTimeMillis()));
        turnSink.tryEmitComplete();
    }

    /**
     * 下行音频块 → 降采样到线路速率 → 进节流缓冲。
     * {@code chunkEpoch} 不等于当前代号说明这轮已被打断, 残留块一律丢弃(即便上游 TTS 取消有延迟)。
     */
    private synchronized void onDownlink(AudioChunk chunk, long chunkEpoch) {
        if (chunkEpoch != epoch || closed) {
            return;
        }
        byte[] data = chunk.data();
        if (data == null || data.length == 0) {
            return;   // 收尾空块
        }
        pacing.offer(toMediaRate(data));
    }

    private synchronized void bargeIn() {
        // 先翻代号, 再取消上游 —— 顺序不能反: conversation.bargeIn() 会同步触发旧轮收尾,
        // 代号没先翻的话 onTurnFinished 的守卫挡不住, 会把刚开始的新一轮误判成"已结束"。
        epoch++;
        resumeEpoch = -1;
        pacing.clear();          // 立刻停声, 不依赖对端配合
        conversation.bargeIn();
        if (turnSubscription != null) {
            turnSubscription.dispose();
        }
        resetTurn();
    }

    /** 回合产完/出错。运行在 reactor 线程, 故加锁。 */
    private synchronized void onTurnFinished(long turnEpoch, Throwable err) {
        if (turnEpoch != epoch) {
            return;   // 旧轮的收尾信号(已被打断/换轮), 忽略
        }
        if (err != null) {
            log.warn("[{}] 回合出错: {}", leg.callId(), err.toString());
        }
        resetTurn();
        // 不在这里 resumeListening: 缓冲里通常还压着好几秒没播的音频, 那段时间 VAD 必须留在 WAIT
        // 才能被客户插话打断。改为等 tick() 发现缓冲排空后再回到聆听。
        if (vad.isActive()) {
            resumeEpoch = epoch;
        }
    }

    private synchronized void resetTurn() {
        turnSink = null;
        turnSubscription = null;
    }

    // ---- 下行节流 ----

    /**
     * 节流一拍: 取一帧发给对端。缓冲空且本轮已产完时, 顺带把 VAD 放回"等你开口"。
     * 公开是为了单测能手动步进, 不必真等定时器。
     */
    public void tick() {
        byte[] frame;
        synchronized (this) {
            if (closed) {
                return;
            }
            if (cfg.maxCallSeconds() > 0
                    && System.currentTimeMillis() - startedAtMs > cfg.maxCallSeconds() * 1000L) {
                log.info("[{}] 达单通时长上限, 挂机", leg.callId());
                close("max-duration");
                return;
            }
            frame = pacing.nextFrame();
            if (frame == null && resumeEpoch >= 0 && resumeEpoch == epoch) {
                resumeEpoch = -1;
                vad.resumeListening();   // 已播完, 现在回到聆听才不会关掉打断窗口
            }
        }
        if (frame != null) {
            leg.writeAudio(frame);   // 网络 IO 放锁外
        }
    }

    /** 机器人此刻是否还在出声 —— 缓冲里还有没有货就是精确答案, 不用估算。 */
    private boolean botPlaying() {
        return !pacing.isEmpty();
    }

    // ---- 收尾 ----

    public synchronized void close(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        log.info("[{}] 通话结束: {} (时长 {}s)", leg.callId(), reason,
                (System.currentTimeMillis() - startedAtMs) / 1000);
        dispose(ticker);
        dispose(inboundSub);
        dispose(eventSub);
        dispose(turnSubscription);
        pacing.clear();
        vad.stop();
        conversation.close();
        leg.hangup(reason);
    }

    private static void dispose(Disposable d) {
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
    }

    private byte[] toMediaRate(byte[] pcm) {
        if (cfg.ttsSampleRate() == mediaRate) {
            return pcm;
        }
        return PcmAudio.encodeLe(PcmAudio.resample(PcmAudio.decodeLe(pcm), cfg.ttsSampleRate(), mediaRate));
    }

    // ---- 诊断 ----

    public boolean isAnswered() {
        return answered;
    }

    public boolean isClosed() {
        return closed;
    }

    public int pendingPlaybackMs() {
        return pacing.bufferedMs();
    }
}
