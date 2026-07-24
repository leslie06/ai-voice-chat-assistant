package com.vca.domain.model;

import java.util.List;

/**
 * 一次点歌返回的播放列表。
 *
 * @param tracks       可播放曲目，已按音源顺序排列
 * @param currentIndex 首次应播放的曲目下标
 */
public record MusicPlaylist(List<MusicTrack> tracks, int currentIndex) {

    public MusicPlaylist {
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
        currentIndex = tracks.isEmpty() ? -1 : Math.max(0, Math.min(currentIndex, tracks.size() - 1));
    }

    public static MusicPlaylist single(MusicTrack track) {
        return new MusicPlaylist(List.of(track), 0);
    }

    public MusicTrack current() {
        return currentIndex < 0 ? null : tracks.get(currentIndex);
    }
}
