package com.vca.store.music;

import com.vca.store.mapper.UserMusicPlayMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;

/** 记录用户播放的歌曲，并按“用户 + 歌名 + 歌手”累计播放次数。 */
public final class MusicPlayService {

    private final UserMusicPlayMapper plays;

    public MusicPlayService(UserMusicPlayMapper plays) {
        this.plays = plays;
    }

    public boolean record(long userId, String title, String artist, int durationSec) {
        String safeTitle = clean(title, 255);
        if (userId <= 0 || safeTitle == null) {
            return false;
        }
        String safeArtist = clean(artist, 255);
        if (safeArtist == null) {
            safeArtist = "未知歌手";
        }
        int safeDuration = Math.max(0, Math.min(durationSec, 24 * 60 * 60));
        String songKey = sha256((safeTitle + "\0" + safeArtist).toLowerCase(Locale.ROOT));
        plays.record(userId, songKey, safeTitle, safeArtist, safeDuration, LocalDateTime.now());
        return true;
    }

    private static String clean(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.strip();
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法生成歌曲标识", e);
        }
    }
}
