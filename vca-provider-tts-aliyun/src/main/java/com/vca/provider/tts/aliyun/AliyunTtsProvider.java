package com.vca.provider.tts.aliyun;

import com.alibaba.dashscope.audio.tts.SpeechSynthesisResult;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.vca.domain.enums.AudioFormat;
import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.exception.ProviderException;
import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.TtsConfig;
import com.vca.domain.spi.TtsProvider;
import io.reactivex.Flowable;
import io.reactivex.processors.ReplayProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 阿里云 DashScope 流式语音合成(Qwen-Audio-3.0-TTS 与 CosyVoice)。
 *
 * <p>输入文本片段流(上游已分句)→ 流式吐 PCM 音频块, 体现句子级流水线。
 * 输出固定 PCM 24kHz 单声道 16bit。同样通过 Reactive Streams 在 Flux/Flowable 间桥接。
 *
 * <h2>首句提前建连(体感延迟的大头)</h2>
 * 每次合成都要新建一条 WebSocket, 实测握手约 <b>1.2 秒</b>, 而合成本身只要 0.6 秒。按"拿到句子再建连"
 * 的写法, 这 1.2 秒完整落在用户的等待里。
 *
 * <p>所以第一句改走 <b>流式输入(duplex)</b>: {@link #synthesize} 被订阅的那一刻(回合刚开始、大模型还没吐字)
 * 就把连接建好, 第一句到了直接发文本。实测"发文本 → 首帧音频"降到约 0.65 秒, 整整省下一次握手。
 * 后面的句子仍逐句用非流式输入 —— 它们是在前一句播放期间合成的, 握手时间被掩盖, 没必要改。
 *
 * <p><b>只有 Qwen-Audio-3.0 全系支持这个协议</b>({@link AliyunTtsProperties#supportsStreamingInput}),
 * {@code cosyvoice-v3-flash} 走它会报 "Missing required parameter 'payload.task_group'",
 * 因此那边自动退回逐句模式。预热失败、或回合迟迟不出文本(超过 {@link #WARM_MAX_IDLE}), 也一律退回逐句模式,
 * 保证"快不了"最多是慢一点, 不会变成合不出来。
 *
 * <p><b>两个模型族共用本 provider</b>: Qwen-Audio-3.0-TTS(2026-07 上线, CosyVoice-v3.5 的后继)
 * 走的是同一套 SpeechSynthesizer + WebSocket 协议, 只是模型名和音色表不同。具体每句用哪个模型
 * 由音色反推, 见 {@link AliyunTtsProperties#modelFor(String)}。
 */
public class AliyunTtsProvider implements TtsProvider {

    private static final Logger log = LoggerFactory.getLogger(AliyunTtsProvider.class);

    /**
     * 预热连接最多闲置多久。超过就放弃它、退回逐句模式 —— 服务端对空闲的 duplex 任务有自己的超时,
     * 用一条可能已经被对端关掉的连接去合成第一句, 比多花一次握手糟糕得多。
     */
    private static final Duration WARM_MAX_IDLE = Duration.ofSeconds(15);

    private final AliyunTtsProperties props;
    /**
     * 预热好、等着被认领的连接, 按"模型+音色+指令"分槽。每槽只留一条:
     * 一路会话同一时刻只可能有一个回合在合成, 多路并发时认领不到的那些各自现建, 不会互相等。
     */
    private final ConcurrentHashMap<String, WarmSession> prewarmed = new ConcurrentHashMap<>();

    public AliyunTtsProvider(AliyunTtsProperties props) {
        this.props = props;
    }

    /**
     * 回合一开始就建连(见 {@link TtsProvider#prewarm}): 此时 ASR 还没出文字, 握手与识别、生成完全重叠。
     * 槽里已有可用连接就不重复建。
     */
    @Override
    public void prewarm(TtsConfig cfg) {
        String key = warmKey(cfg);
        prewarmed.compute(key, (k, existing) -> {
            if (existing != null && existing.usable()) {
                return existing;
            }
            if (existing != null) {
                existing.abortIfUnused();
            }
            WarmSession fresh = WarmSession.openOrNull(props, cfg);
            if (fresh != null) {
                log.debug("TTS 提前建连, key={}", k);
                scheduleAbandon(k, fresh);
            }
            return fresh;
        });
    }

    /**
     * 预热的连接<b>一直没人来认领</b>时, 到点主动关掉。
     *
     * <p>回合在大模型出字之前就失败(比如识别被熔断跳过)或客户直接挂机, 这条连接就留在槽里没有出口:
     * 既不会被 {@code synthesize} 认领, 也等不到下一次同 key 的 prewarm 来顶替。结果是它一路挂到服务端的
     * 空闲超时(约 23s), 在日志里留下一条 {@code request timeout after 23 seconds} 的 task-failed ——
     * 时间上已经离开事发回合很远, 排查时极具误导性。
     */
    private void scheduleAbandon(String key, WarmSession session) {
        Schedulers.parallel().schedule(() -> {
            // 两参 remove: 只有槽里还是这一条(没被认领、没被顶替)才关
            if (prewarmed.remove(key, session)) {
                session.abortIfUnused();
                log.debug("TTS 预热连接闲置 {}s 无人认领, 已关闭, key={}", WARM_MAX_IDLE.toSeconds(), key);
            }
        }, WARM_MAX_IDLE.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static String warmKey(TtsConfig cfg) {
        return cfg.voice() + "|" + (cfg.instruction() == null ? "" : cfg.instruction());
    }

    @Override
    public VendorType vendor() {
        return VendorType.ALIYUN;
    }

    @Override
    public Flux<AudioChunk> synthesize(Flux<String> textSegments, TtsConfig cfg) {
        AtomicLong seq = new AtomicLong();
        Flux<String> segments = textSegments.filter(text -> text != null && !text.isBlank());
        return Flux.defer(() -> {
            // 先认领 prewarm 建好的连接(回合开始就建了, 通常已经可用);
            // 没有就地建一条 —— 订阅发生在大模型出字之前, 握手仍能和生成重叠一部分
            WarmSession warm = claimPrewarmed(cfg);
            if (warm == null) {
                warm = WarmSession.openOrNull(props, cfg);
            }
            WarmSession claimed = warm;
            AtomicBoolean firstTaken = new AtomicBoolean();
            // 逐句合成, concatMap 保序: 一句的音频全部吐完再合成下一句。
            return segments
                    .concatMap(text -> {
                        if (claimed != null && firstTaken.compareAndSet(false, true)) {
                            Flux<AudioChunk> warmed = claimed.speak(text, seq);
                            if (warmed != null) {
                                return warmed;
                            }
                        }
                        return synthesizeOne(text, cfg, seq);
                    })
                    // 回合一个字都没产出(空回复/被打断)时, 预热的连接不能就这么挂着
                    .doFinally(sig -> {
                        if (claimed != null) {
                            claimed.abortIfUnused();
                        }
                    });
        });
    }

    /** 取走本配置预热好的连接(取走即从槽里移除, 不会被两个回合同时用)。不可用则返回 null。 */
    private WarmSession claimPrewarmed(TtsConfig cfg) {
        WarmSession warm = prewarmed.remove(warmKey(cfg));
        if (warm == null) {
            return null;
        }
        if (!warm.usable()) {
            warm.abortIfUnused();
            return null;
        }
        return warm;
    }

    /** 一句一条连接的常规路径(非流式输入)。与官方 CosyVoice 文档一致, 兼容 v1/v2/v3。 */
    private Flux<AudioChunk> synthesizeOne(String text, TtsConfig cfg, AtomicLong seq) {
        return Flux.defer(() -> {
            String model = props.modelFor(cfg.voice());
            SpeechSynthesisParam param = paramFor(props, cfg, model);

            // callback 传 null: 用 Flowable 流式输出模式(非流式输入)
            SpeechSynthesizer synthesizer = new SpeechSynthesizer(param, null);

            Flowable<SpeechSynthesisResult> results;
            try {
                results = synthesizer.callAsFlowable(text);
            } catch (Exception e) {
                return Flux.<AudioChunk>error(ProviderException.fatal(
                        VendorType.ALIYUN, Capability.TTS, "DashScope TTS 启动失败: " + e.getMessage(), e));
            }

            return Flux.from(results)
                    .concatMap(r -> toChunk(r, seq))
                    .onErrorMap(AliyunTtsProvider::toProviderException)
                    .doOnSubscribe(s -> log.debug("阿里云 TTS 合成一句, model={}, voice={}, len={}",
                            model, cfg.voice(), text.length()));
        });
    }

    /**
     * 模型由音色反推: CosyVoice 与 Qwen-Audio-3.0 的音色表互不通用, 配错直接 418。
     * 指令控制(方言/情绪)只发给认识它的模型, 否则宁可不说方言也别让整句合成失败。
     */
    private static SpeechSynthesisParam paramFor(AliyunTtsProperties props, TtsConfig cfg, String model) {
        var builder = SpeechSynthesisParam.builder()
                .model(model)
                .voice(cfg.voice())
                .format(SpeechSynthesisAudioFormat.PCM_24000HZ_MONO_16BIT)
                .apiKey(props.getApiKey());
        String instruction = cfg.instruction();
        if (instruction != null && !instruction.isBlank() && props.supportsInstruction(cfg.voice())) {
            builder.instruction(instruction);
        }
        return builder.build();
    }

    private static Flux<AudioChunk> toChunk(SpeechSynthesisResult r, AtomicLong seq) {
        ByteBuffer buf = r.getAudioFrame();
        if (buf == null || !buf.hasRemaining()) {
            return Flux.empty();
        }
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        return Flux.just(new AudioChunk(bytes, AudioFormat.PCM, seq.getAndIncrement(), null, false));
    }

    private static Throwable toProviderException(Throwable e) {
        return e instanceof ProviderException ? e
                : ProviderException.retryable(VendorType.ALIYUN, Capability.TTS,
                "DashScope TTS 合成出错: " + e.getMessage(), e);
    }

    /**
     * 一条"已经建好、等着喂文本"的合成连接。只服务本回合的第一句。
     *
     * <p>音频用 {@code replay()} 接住并立刻 {@code connect()}: 连接必须在没有下游消费者的时候就建起来,
     * 而此时若不缓冲, 服务端先吐的帧就没人接。第一句的音频量是几百 KB 级, 缓冲代价可以忽略。
     */
    private static final class WarmSession {

        private final SpeechSynthesizer synthesizer;
        /**
         * <b>必须是会暂存的 Replay 流</b>: 建连是异步的, SDK 要等 WebSocket 握手完成才来订阅这条文本流。
         * 大模型比握手快的时候(实测常见), 用 PublishProcessor 会把这一句直接丢掉 —— 线上表现是
         * 服务端收到 run-task 后紧跟 finish-task, 回一个 task_failed, 这一轮直接没声音。
         */
        private final ReplayProcessor<String> textStream = ReplayProcessor.create();
        private final Flux<AudioChunk> audio;
        private final AtomicBoolean used = new AtomicBoolean();
        private final long openedAtNanos = System.nanoTime();
        private final String model;

        static WarmSession openOrNull(AliyunTtsProperties props, TtsConfig cfg) {
            String model = props.modelFor(cfg.voice());
            if (!props.supportsStreamingInput(cfg.voice())) {
                return null;   // CosyVoice: 不支持流式输入, 走逐句
            }
            try {
                return new WarmSession(props, cfg, model);
            } catch (Exception e) {
                // 预热只是优化, 失败了照常逐句合成
                log.debug("TTS 预热连接失败, 本回合退回逐句合成: {}", e.toString());
                return null;
            }
        }

        private WarmSession(AliyunTtsProperties props, TtsConfig cfg, String model) throws Exception {
            this.model = model;
            this.synthesizer = new SpeechSynthesizer(paramFor(props, cfg, model), null);
            AtomicLong warmSeq = new AtomicLong();
            Flowable<SpeechSynthesisResult> results = synthesizer.streamingCallAsFlowable(textStream);
            var hot = Flux.from(results)
                    .concatMap(r -> toChunk(r, warmSeq))
                    .onErrorMap(AliyunTtsProvider::toProviderException)
                    .replay();
            hot.connect();   // 立刻订阅 = 立刻握手建连
            this.audio = hot;
        }

        /** 还能不能用: 没被用过, 且没闲置太久(服务端对空闲的 duplex 任务有自己的超时) */
        boolean usable() {
            return !used.get()
                    && Duration.ofNanos(System.nanoTime() - openedAtNanos).compareTo(WARM_MAX_IDLE) <= 0;
        }

        /**
         * 把第一句喂进已建好的连接。
         *
         * @return null 表示这条连接不该再用(闲置过久), 调用方退回逐句合成
         */
        Flux<AudioChunk> speak(String text, AtomicLong seq) {
            if (Duration.ofNanos(System.nanoTime() - openedAtNanos).compareTo(WARM_MAX_IDLE) > 0) {
                log.debug("TTS 预热连接闲置超过 {}s, 放弃并退回逐句合成", WARM_MAX_IDLE.toSeconds());
                abortIfUnused();
                return null;
            }
            used.set(true);
            return Mono.<AudioChunk>fromRunnable(() -> {
                        log.debug("阿里云 TTS 合成首句(预热连接), model={}, len={}", model, text.length());
                        textStream.onNext(text);
                        textStream.onComplete();   // 本连接只合成这一句
                    })
                    .thenMany(audio)
                    // 序号由本回合统一分配: 预热连接内部另起了一套, 这里重新编号
                    .map(chunk -> new AudioChunk(chunk.data(), chunk.format(),
                            seq.getAndIncrement(), chunk.text(), chunk.last()))
                    // 播到一半被打断: 服务端还在吐这一句的音频, 明确取消, 不然要等它自己超时
                    .doOnCancel(this::cancelQuietly);
        }

        /** 回合没用上这条连接(空回复/被打断/闲置过久): 取消任务, 别把连接挂在那里。 */
        void abortIfUnused() {
            if (used.compareAndSet(false, true)) {
                cancelQuietly();
            }
        }

        /**
         * 收掉这条连接。
         *
         * <p><b>关键是第一步</b>: 结束上行文本流 = 让 SDK 发 finish-task, 这是唯一能让服务端立刻
         * 了结任务的动作。实测 {@code streamingCancel()} 和退订 Reactor 侧都做不到 —— 一条建好却
         * 还没喂过文本的连接, 两者都拦不住它挂到服务端的空闲超时(约 23s), 然后在日志里留下一条
         * task-failed; 那个时间点离事发回合已经很远, 排查时极具误导性。
         *
         * <p>{@code streamingCancel()} 仍要调: 播到一半被打断时, 任务是真跑起来了的, 得让服务端
         * 别再往下合成。
         */
        private void cancelQuietly() {
            try {
                textStream.onComplete();
            } catch (Exception e) {
                log.debug("结束 TTS 预热连接的文本流: {}", e.toString());
            }
            try {
                synthesizer.streamingCancel();
            } catch (Exception e) {
                // 任务已正常结束时取消会抛, 属正常情况
                log.debug("取消 TTS 预热连接: {}", e.toString());
            }
        }
    }
}
