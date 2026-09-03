package com.vca.provider.asr.aliyun;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.timestamp.Sentence;
import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.exception.ProviderException;
import com.vca.domain.model.AsrConfig;
import com.vca.domain.model.AsrEvent;
import com.vca.domain.model.AudioFrame;
import com.vca.domain.spi.AsrProvider;
import io.reactivex.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.nio.ByteBuffer;

/**
 * 阿里云 DashScope 实时语音识别(Paraformer)。
 *
 * <p>DashScope SDK 基于 RxJava {@link Flowable}; 与 Reactor {@link Flux} 通过 Reactive Streams
 * {@code Publisher} 双向桥接 —— 上行 PCM 帧 Flux→Flowable, 识别结果 Flowable→Flux。
 *
 * <p>契约适配: 把每个中间结果作为 partial(实时字幕)发出, 并在上行音频流结束(用户说完)后,
 * 把所有"句末"片段拼接成<b>一个 final</b> 发出 —— 编排层取这个 final 触发 LLM, 避免多句被截断。
 */
public class AliyunAsrProvider implements AsrProvider {

    private static final Logger log = LoggerFactory.getLogger(AliyunAsrProvider.class);

    private final AliyunAsrProperties props;

    public AliyunAsrProvider(AliyunAsrProperties props) {
        this.props = props;
    }

    /** 热词不支持的告警只打一次, 免得每轮识别刷屏。 */
    private final java.util.concurrent.atomic.AtomicBoolean hotWordsWarned =
            new java.util.concurrent.atomic.AtomicBoolean();

    @Override
    public VendorType vendor() {
        return VendorType.ALIYUN;
    }

    /**
     * 配了 {@code AsrConfig.hotWords} 却没配 vocabulary-id: 这些热词<b>不会生效</b>。
     * 以前是静默忽略 —— 配了热词却发现专有名词还是识别不准, 根本无从查起。
     */
    private void warnHotWordsUnsupported(int count) {
        if (hotWordsWarned.compareAndSet(false, true)) {
            log.warn("配置了 {} 个热词, 但阿里云 DashScope 不支持随请求内联热词, 这些词不会生效。"
                    + "请先用 DashScope 的 vocabulary 接口注册热词表, 再设 "
                    + "vca.providers.asr.aliyun.vocabulary-id", count);
        }
    }

    @Override
    public Flux<AsrEvent> transcribe(Flux<AudioFrame> audio, AsrConfig cfg) {
        return Flux.defer(() -> {
            // punctuation_prediction_enabled 必须显式下发: 语义端点判定(EndpointPolicy)靠中间转写里的
            // 句末标点判"已说完", 标点没了就只剩无标点规则可用, 判停会整体变慢。
            RecognitionParam.RecognitionParamBuilder<?, ?> builder = RecognitionParam.builder()
                    .model(props.getModel())
                    .format("pcm")
                    .sampleRate(cfg.sampleRate())
                    .apiKey(props.getApiKey())
                    .parameter("punctuation_prediction_enabled", cfg.enablePunctuation());
            if (!props.getVocabularyId().isBlank()) {
                builder.vocabularyId(props.getVocabularyId());
            } else if (!cfg.hotWords().isEmpty()) {
                warnHotWordsUnsupported(cfg.hotWords().size());
            }
            RecognitionParam param = builder.build();

            // 上行: Reactor Flux<AudioFrame> → RxJava Flowable<ByteBuffer>(滤掉 endOfSpeech 空帧)
            Flowable<ByteBuffer> audioFlow = Flowable.fromPublisher(
                    audio.filter(f -> f.size() > 0)
                            .map(f -> ByteBuffer.wrap(f.data())));

            Flowable<com.alibaba.dashscope.audio.asr.recognition.RecognitionResult> results;
            try {
                results = new Recognition().streamCall(param, audioFlow);
            } catch (Exception e) {
                return Flux.<AsrEvent>error(ProviderException.fatal(
                        VendorType.ALIYUN, Capability.ASR, "DashScope ASR 启动失败: " + e.getMessage(), e));
            }

            StringBuilder fullText = new StringBuilder();
            return Flux.from(results)
                    .concatMap(r -> {
                        Sentence s = r.getSentence();
                        if (s == null || s.getText() == null || s.getText().isBlank()) {
                            return Flux.<AsrEvent>empty();
                        }
                        if (r.isSentenceEnd()) {
                            fullText.append(s.getText());
                        }
                        // 中间/句末都作为 partial 推给前端做实时字幕
                        return Flux.just(AsrEvent.partial(s.getText(),
                                s.getBeginTime() == null ? 0 : s.getBeginTime()));
                    })
                    // 上行结束后, 拼接出完整一句作为 final
                    .concatWith(Flux.defer(() -> {
                        String text = fullText.toString().trim();
                        return text.isEmpty() ? Flux.empty()
                                : Flux.just(AsrEvent.finalResult(text, 0, 1.0));
                    }))
                    .onErrorMap(e -> e instanceof ProviderException ? e
                            : ProviderException.retryable(VendorType.ALIYUN, Capability.ASR,
                            "DashScope ASR 识别出错: " + e.getMessage(), e))
                    .doOnSubscribe(s -> log.debug("阿里云 ASR 开始, model={}, sr={}", props.getModel(), cfg.sampleRate()));
        });
    }
}
