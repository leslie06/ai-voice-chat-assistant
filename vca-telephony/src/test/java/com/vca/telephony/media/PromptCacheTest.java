package com.vca.telephony.media;

import com.vca.domain.enums.AudioFormat;
import com.vca.domain.enums.VendorType;
import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.TtsConfig;
import com.vca.domain.spi.TtsProvider;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 开场白预合成。外呼接通后前 3 秒是挂机高发区, 这层的意义就是把首包延迟压到 0。 */
class PromptCacheTest {

    private static final int TTS_RATE = 24_000;
    private static final int MEDIA_RATE = 8_000;

    private static TtsProvider tts(AtomicInteger calls, int bytesPerCall) {
        return new TtsProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.ALIYUN;
            }

            @Override
            public Flux<AudioChunk> synthesize(Flux<String> textSegments, TtsConfig cfg) {
                return textSegments.doOnNext(t -> calls.incrementAndGet())
                        .map(t -> new AudioChunk(new byte[bytesPerCall], AudioFormat.PCM, 0, t, false));
            }
        };
    }

    private static PromptCache cache(TtsProvider provider) {
        TtsConfig cfg = new TtsConfig(VendorType.ALIYUN, "longxiaochun", AudioFormat.PCM, TTS_RATE, 1.0f);
        return new PromptCache(provider, cfg, MEDIA_RATE, Duration.ofSeconds(5));
    }

    /** 合成结果要降到线路采样率, 否则灌进节流缓冲会变速 */
    @Test
    void synthesizedPromptIsResampledToMediaRate() {
        // 1 秒 24k PCM = 48000 字节 → 1 秒 8k = 16000 字节
        PromptCache cache = cache(tts(new AtomicInteger(), TTS_RATE * 2));

        assertThat(cache.get("您好")).hasSize(MEDIA_RATE * 2);
    }

    /** 只合成一次 —— 每通电话都现合成开场白既慢又费钱, 这是这层存在的全部理由 */
    @Test
    void synthesizesOnceThenServesFromCache() {
        AtomicInteger calls = new AtomicInteger();
        PromptCache cache = cache(tts(calls, 480));

        byte[] first = cache.get("您好，这边是贷款咨询");
        byte[] second = cache.get("您好，这边是贷款咨询");

        assertThat(calls.get()).isEqualTo(1);
        assertThat(second).isSameAs(first);
    }

    @Test
    void blankTextNeverHitsTts() {
        AtomicInteger calls = new AtomicInteger();
        PromptCache cache = cache(tts(calls, 480));

        assertThat(cache.get(null)).isEmpty();
        assertThat(cache.get("   ")).isEmpty();
        assertThat(calls.get()).isZero();
    }

    /**
     * 失败不进缓存。启动时 TTS 抖一下(配额/超时/音色配错)就永久没有开场白, 是最难查的那种问题 ——
     * 服务看着一切正常, 只是每通电话都少了开场白, 而它正是首包延迟优化的全部意义。
     */
    @Test
    void failureIsNotCachedSoNextCallRetries() {
        AtomicInteger calls = new AtomicInteger();
        TtsProvider flaky = new TtsProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.ALIYUN;
            }

            @Override
            public Flux<AudioChunk> synthesize(Flux<String> textSegments, TtsConfig cfg) {
                // 第一次失败(模拟启动瞬间的抖动), 之后正常
                if (calls.incrementAndGet() == 1) {
                    return Flux.error(new IllegalStateException("配额抖动"));
                }
                return textSegments.map(t -> new AudioChunk(
                        new byte[TTS_RATE * 2], AudioFormat.PCM, 0, t, false));
            }
        };
        PromptCache cache = cache(flaky);

        assertThat(cache.get("您好")).isEmpty();                      // 启动预热失败
        assertThat(cache.get("您好")).hasSize(MEDIA_RATE * 2);        // 下一通电话自动重试, 拿到了
        assertThat(calls.get()).isEqualTo(2);
    }

    /** TTS 抖动不该阻断外呼: 没有开场白也能打, 只是首句要现合成 */
    @Test
    void ttsFailureDegradesToEmptyInsteadOfThrowing() {
        PromptCache cache = cache(new TtsProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.ALIYUN;
            }

            @Override
            public Flux<AudioChunk> synthesize(Flux<String> textSegments, TtsConfig cfg) {
                return Flux.error(new IllegalStateException("配额用尽"));
            }
        });

        assertThat(cache.get("您好")).isEmpty();
    }
}
