package com.vca.domain.spi;

import com.vca.domain.model.MusicPlaylist;
import com.vca.domain.model.MusicTrack;
import reactor.core.publisher.Mono;

import java.util.List;

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

    /**
     * 返回点歌命中的歌曲及同音源播放列表。只支持单曲的音源使用默认实现即可。
     */
    default Mono<MusicPlaylist> playlist(String query) {
        return search(query).map(MusicPlaylist::single);
    }

    /**
     * 浏览完整曲库，供 KTV 点歌面板展示。仅支持搜索的音源可保持默认空列表。
     */
    default Mono<List<MusicTrack>> catalog() {
        return Mono.just(List.of());
    }
}
