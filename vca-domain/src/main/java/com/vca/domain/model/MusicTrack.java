package com.vca.domain.model;

/**
 * 一首可播放的曲目: 音源检索后返回给前端直接播放。
 *
 * @param title       歌名
 * @param artist      歌手
 * @param playUrl     可直接播放的音频地址(浏览器 {@code <audio>} 能放, 如 m4a/mp3)
 * @param coverUrl    封面图地址(可为 null)
 * @param durationSec 时长(秒, 未知为 0)
 * @param full        是否整首(本地曲库为 true; 在线试听片段为 false)
 */
public record MusicTrack(
        String title,
        String artist,
        String playUrl,
        String coverUrl,
        int durationSec,
        boolean full
) {
}
