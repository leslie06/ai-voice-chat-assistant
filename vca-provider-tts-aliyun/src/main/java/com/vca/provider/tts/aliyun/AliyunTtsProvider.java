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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 阿里云 DashScope 流式语音合成(Qwen-Audio-3.0-TTS 与 CosyVoice)。
 *
 * <p>输入文本片段流(上游已分句)→ 流式吐 PCM 音频块, 体现句子级流水线。
 * 输出固定 PCM 24kHz 单声道 16bit。同样通过 Reactive Streams 在 Flux/Flowable 间桥接。
 *
 * <p>每个句子用一次"非流式输入"调用({@link SpeechSynthesizer#callAsFlowable}): 给一整句、
 * 流式吐出该句音频。这与官方 CosyVoice 文档一致, 兼容 v1/v2/v3(含 {@code cosyvoice-v3-flash});
 * 不用 {@code streamingCallAsFlowable} 的"流式输入(duplex)"协议——v3-flash 不支持它, 会报
 * "Missing required parameter 'payload.task_group'"。
 *
 * <p><b>两个模型族共用本 provider</b>: Qwen-Audio-3.0-TTS(2026-07 上线, CosyVoice-v3.5 的后继)
 * 走的是同一套 SpeechSynthesizer + WebSocket 协议, 只是模型名和音色表不同。具体每句用哪个模型
 * 由音色反推, 见 {@link AliyunTtsProperties#modelFor(String)}。
 */
public class AliyunTtsProvider implements TtsProvider {

    private static final Logger log = LoggerFactory.getLogger(AliyunTtsProvider.class);

    private final AliyunTtsProperties props;

    public AliyunTtsProvider(AliyunTtsProperties props) {
        this.props = props;
    }

    @Override
    public VendorType vendor() {
        return VendorType.ALIYUN;
    }

    @Override
    public Flux<AudioChunk> synthesize(Flux<String> textSegments, TtsConfig cfg) {
        AtomicLong seq = new AtomicLong();
        // 逐句合成, concatMap 保序: 一句的音频全部吐完再合成下一句。
        return textSegments
                .filter(text -> text != null && !text.isBlank())
                .concatMap(text -> synthesizeOne(text, cfg, seq));
    }

    private Flux<AudioChunk> synthesizeOne(String text, TtsConfig cfg, AtomicLong seq) {
        return Flux.defer(() -> {
            // 模型由音色反推: CosyVoice 与 Qwen-Audio-3.0 的音色表互不通用, 配错直接 418。
            String model = props.modelFor(cfg.voice());
            SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                    .model(model)
                    .voice(cfg.voice())
                    .format(SpeechSynthesisAudioFormat.PCM_24000HZ_MONO_16BIT)
                    .apiKey(props.getApiKey())
                    .build();

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
                    .concatMap(r -> {
                        ByteBuffer buf = r.getAudioFrame();
                        if (buf == null || !buf.hasRemaining()) {
                            return Flux.<AudioChunk>empty();
                        }
                        byte[] bytes = new byte[buf.remaining()];
                        buf.get(bytes);
                        return Flux.just(new AudioChunk(
                                bytes, AudioFormat.PCM, seq.getAndIncrement(), null, false));
                    })
                    .onErrorMap(e -> e instanceof ProviderException ? e
                            : ProviderException.retryable(VendorType.ALIYUN, Capability.TTS,
                            "DashScope TTS 合成出错: " + e.getMessage(), e))
                    .doOnSubscribe(s -> log.debug("阿里云 TTS 合成一句, model={}, voice={}, len={}",
                            model, cfg.voice(), text.length()));
        });
    }
}
