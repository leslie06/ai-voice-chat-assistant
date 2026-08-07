package com.vca.telephony.media;

import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.TtsConfig;
import com.vca.domain.spi.TtsProvider;
import com.vca.orchestrator.vad.PcmAudio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 固定话术的预合成缓存。
 *
 * <p><b>为什么值得单独做一层</b>: 外呼接通后前 3 秒是挂机高发区 —— 客户"喂?"一声, 那头要是有
 * 几百毫秒空白就直接挂了。而开场白文本是固定的, 没有任何理由每通电话都现走一遍 LLM+TTS。
 * 提前合成好、转成线路采样率存着, 接通瞬间灌进节流缓冲即可出声, <b>首包延迟趋近于 0</b>,
 * 顺带省掉每通电话的开场白合成费用(按十万通量级不是小钱)。
 *
 * <p>同样适用于其他高频固定话术: "稍等一下"、"您说"、挂机语。
 *
 * <p>合成是<b>阻塞</b>的, 只应在启动时或首次用到时发生; 合成失败返回空数组, 通话照常进行
 * (只是没有开场白), 不让一次 TTS 抖动阻断整条外呼任务。
 */
public final class PromptCache {

    private static final Logger log = LoggerFactory.getLogger(PromptCache.class);

    private final TtsProvider tts;
    private final TtsConfig ttsConfig;
    /** 线路采样率, 合成结果会降采样到此 */
    private final int mediaRate;
    private final Duration timeout;
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    public PromptCache(TtsProvider tts, TtsConfig ttsConfig, int mediaRate, Duration timeout) {
        this.tts = tts;
        this.ttsConfig = ttsConfig;
        this.mediaRate = mediaRate;
        this.timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
    }

    /**
     * 取一段话术的 PCM(线路采样率)。首次调用会真的合成, 之后命中缓存。
     *
     * @return 永不为 null; 合成失败或文本为空时返回空数组
     */
    public byte[] get(String text) {
        if (text == null || text.isBlank()) {
            return new byte[0];
        }
        return cache.computeIfAbsent(text.strip(), this::synthesize);
    }

    /** 预热: 启动时把已知话术合成好, 别等第一通电话才现做。 */
    public void preload(String... texts) {
        for (String t : texts) {
            byte[] pcm = get(t);
            if (pcm.length > 0) {
                log.info("话术预合成完成({}ms): {}", pcm.length * 500 / mediaRate, brief(t));
            }
        }
    }

    private byte[] synthesize(String text) {
        try {
            List<AudioChunk> chunks = tts.synthesize(Flux.just(text), ttsConfig)
                    .collectList()
                    .block(timeout);
            if (chunks == null || chunks.isEmpty()) {
                log.warn("话术预合成无输出: {}", brief(text));
                return new byte[0];
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            for (AudioChunk c : chunks) {
                if (c.data() != null && c.data().length > 0) {
                    buf.write(c.data(), 0, c.data().length);
                }
            }
            return toMediaRate(buf.toByteArray());
        } catch (RuntimeException e) {
            // 合成失败不该阻断外呼: 没有开场白也能打, 只是首句要现合成
            log.warn("话术预合成失败, 本条留空: {} —— {}", brief(text), e.toString());
            return new byte[0];
        }
    }

    private byte[] toMediaRate(byte[] pcm) {
        if (pcm.length == 0 || ttsConfig.sampleRate() == mediaRate) {
            return pcm;
        }
        return PcmAudio.encodeLe(PcmAudio.resample(PcmAudio.decodeLe(pcm), ttsConfig.sampleRate(), mediaRate));
    }

    private static String brief(String text) {
        return text.length() <= 20 ? text : text.substring(0, 20) + "…";
    }
}
