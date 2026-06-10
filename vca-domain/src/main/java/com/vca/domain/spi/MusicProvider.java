package com.vca.domain.spi;

import com.vca.domain.model.MusicTrack;
import reactor.core.publisher.Mono;

/**
 * 音乐检索厂商接口: 按关键词找一首可播放的曲目。
 *
 * <p>与 ASR/LLM/TTS 不同, 音乐检索不进治理网关(无需熔断/配额), 由接入层直接调用。
 * 换音源(如从预览片段换成完整曲库)只需替换实现, 上层不变。
 *
 * <p>契约: 找不到合适曲目时返回<b>空</b> {@link Mono}(complete 而不 onNext), 不要抛异常。
 */
public interface MusicProvider {

    /**
     * @param query 关键词(歌名/歌手)
     * @return 最匹配的一首; 找不到则为空 Mono
     */
    Mono<MusicTrack> search(String query);
}
