package com.vca.web.music;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.vca.domain.model.MusicPlaylist;
import com.vca.domain.model.MusicTrack;
import com.vca.domain.spi.MusicProvider;
import com.vca.domain.spi.MusicUploadStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 阿里云 OSS 私有曲库。对象保持私有，命中歌曲后只给浏览器返回短期 GET 签名 URL。
 */
public final class OssMusicProvider implements MusicProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OssMusicProvider.class);
    private static final Set<String> AUDIO_EXT = Set.of("mp3", "m4a", "flac", "wav", "aac", "ogg", "opus");

    private final OSS client;
    private final String bucket;
    private final String prefix;
    private final Duration urlLifetime;
    private final long cacheMillis;
    private final MusicUploadStore uploads;
    private volatile Catalog catalog = new Catalog(List.of(), 0);

    public OssMusicProvider(OSS client, String bucket, String prefix,
                            Duration urlLifetime, Duration catalogCache,
                            MusicUploadStore uploads) {
        this.client = client;
        this.bucket = bucket;
        String p = prefix == null ? "" : prefix.strip();
        this.prefix = p.isEmpty() ? "" : (p.endsWith("/") ? p : p + "/");
        this.urlLifetime = urlLifetime;
        this.cacheMillis = Math.max(0, catalogCache.toMillis());
        this.uploads = uploads;
    }

    @Override
    public Mono<MusicTrack> search(String query) {
        if (query == null || query.isBlank()) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> find(query))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(track -> track == null ? Mono.empty() : Mono.just(track))
                .onErrorResume(e -> {
                    log.warn("OSS 曲库检索失败: query={}, bucket={}, prefix={} ({})",
                            query, bucket, prefix, e.toString());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<MusicPlaylist> playlist(String query) {
        if (query == null || query.isBlank()) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> findPlaylist(query))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> result == null ? Mono.empty() : Mono.just(result))
                .onErrorResume(e -> {
                    log.warn("OSS 播放列表生成失败: query={}, bucket={}, prefix={} ({})",
                            query, bucket, prefix, e.toString());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<List<MusicTrack>> catalog() {
        return Mono.fromCallable(this::signedCatalog)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("OSS 曲库列表生成失败: bucket={}, prefix={} ({})",
                            bucket, prefix, e.toString());
                    return Mono.just(List.of());
                });
    }

    private MusicTrack find(String query) {
        Song hit = bestSong(songs(), query);
        if (hit != null) {
            Date expiresAt = new Date(System.currentTimeMillis() + urlLifetime.toMillis());
            URL url = client.generatePresignedUrl(bucket, hit.key(), expiresAt, HttpMethod.GET);
            return new MusicTrack(hit.title(), hit.artist(), url.toString(), null, hit.lyricsKey(), 0, true);
        }
        MusicUploadStore.Upload uploaded = bestUpload(approvedUploads(), query);
        return uploaded == null ? null : signedUpload(uploaded);
    }

    private MusicPlaylist findPlaylist(String query) {
        List<Song> songs = songs();
        Song hit = bestSong(songs, query);
        if (hit != null) {
            List<MusicTrack> tracks = signedCatalog();
            int currentIndex = songs.indexOf(hit);
            return new MusicPlaylist(tracks, currentIndex);
        }
        List<MusicUploadStore.Upload> uploaded = approvedUploads();
        MusicUploadStore.Upload uploadHit = bestUpload(uploaded, query);
        if (uploadHit == null) {
            return null;
        }
        List<MusicTrack> tracks = signedCatalog();
        int currentIndex = songs.size() + uploaded.indexOf(uploadHit);
        return new MusicPlaylist(tracks, currentIndex);
    }

    private List<MusicTrack> signedCatalog() {
        List<MusicTrack> tracks = new ArrayList<>(signedTracks(songs()));
        tracks.addAll(signedUserUploads(approvedUploads()));
        return List.copyOf(tracks);
    }

    private List<MusicTrack> signedTracks(List<Song> songs) {
        Date expiresAt = new Date(System.currentTimeMillis() + urlLifetime.toMillis());
        List<MusicTrack> tracks = new ArrayList<>(songs.size());
        for (Song song : songs) {
            URL url = client.generatePresignedUrl(bucket, song.key(), expiresAt, HttpMethod.GET);
            tracks.add(new MusicTrack(
                    song.title(), song.artist(), url.toString(), null, song.lyricsKey(), 0, true));
        }
        return List.copyOf(tracks);
    }

    StoredUpload uploadForUser(long userId, String uploadId, String title, String artist,
                               String originalFilename, byte[] audio, byte[] lyrics) {
        String stem = safeObjectName((artist == null || artist.isBlank() ? "" : artist + " - ") + title);
        String folder = prefix + "users/" + userId + "/" + uploadId + "/";
        String audioKey = folder + stem + ".mp3";
        String lyricsKey = lyrics == null ? null : folder + stem + ".lrc";
        try {
            put(audioKey, audio, "audio/mpeg");
            if (lyrics != null) {
                put(lyricsKey, lyrics, "text/plain; charset=utf-8");
            }
            return new StoredUpload(
                    uploadId, title, artist, originalFilename, audioKey, lyricsKey,
                    audio.length, lyrics == null ? 0 : lyrics.length);
        } catch (RuntimeException error) {
            tryDelete(audioKey);
            tryDelete(lyricsKey);
            throw error;
        }
    }

    void rollbackUpload(StoredUpload upload) {
        if (upload != null) {
            tryDelete(upload.audioObjectKey());
            tryDelete(upload.lyricsObjectKey());
        }
    }

    List<MusicTrack> signedUserUploads(List<MusicUploadStore.Upload> uploads) {
        return uploads.stream().map(this::signedUpload).toList();
    }

    private MusicTrack signedUpload(MusicUploadStore.Upload upload) {
        Date expiresAt = new Date(System.currentTimeMillis() + urlLifetime.toMillis());
        URL url = client.generatePresignedUrl(
                bucket, upload.audioObjectKey(), expiresAt, HttpMethod.GET);
        return new MusicTrack(
                upload.title(), upload.artist(), url.toString(), null,
                upload.lyricsObjectKey(), 0, true);
    }

    private List<MusicUploadStore.Upload> approvedUploads() {
        return uploads == null ? List.of() : uploads.listApproved();
    }

    private static MusicUploadStore.Upload bestUpload(
            List<MusicUploadStore.Upload> uploads, String query) {
        String q = normalize(query);
        MusicUploadStore.Upload best = null;
        int bestScore = 0;
        for (MusicUploadStore.Upload upload : uploads) {
            int value = score(normalize(upload.artist() + " " + upload.title()), q);
            if (value > bestScore) {
                best = upload;
                bestScore = value;
            }
        }
        return bestScore >= 60 ? best : null;
    }

    private void put(String key, byte[] bytes, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        metadata.setContentType(contentType);
        client.putObject(new PutObjectRequest(
                bucket, key, new ByteArrayInputStream(bytes), metadata));
    }

    private void tryDelete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            client.deleteObject(bucket, key);
        } catch (Exception error) {
            log.warn("回滚 OSS 用户歌曲失败: key={} ({})", key, error.toString());
        }
    }

    private static String safeObjectName(String value) {
        String safe = value == null ? "" : value.strip()
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ");
        if (safe.isBlank()) {
            safe = "未命名歌曲";
        }
        return safe.length() <= 160 ? safe : safe.substring(0, 160);
    }

    @Override
    public Mono<String> lyrics(String lyricsId) {
        if (lyricsId == null || lyricsId.isBlank()) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> readLyrics(lyricsId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(value -> value == null ? Mono.empty() : Mono.just(value))
                .onErrorResume(e -> {
                    log.warn("OSS 歌词读取失败: id={} ({})", lyricsId, e.toString());
                    return Mono.empty();
                });
    }

    private String readLyrics(String lyricsId) throws Exception {
        boolean known = songs().stream().anyMatch(song -> lyricsId.equals(song.lyricsKey()))
                || (uploads != null && uploads.isApprovedLyrics(lyricsId));
        if (!known) {
            return null;
        }
        return readLyricsObject(lyricsId);
    }

    String readUserLyrics(String lyricsId) throws Exception {
        return readLyricsObject(lyricsId);
    }

    private String readLyricsObject(String lyricsId) throws Exception {
        try (com.aliyun.oss.model.OSSObject object = client.getObject(bucket, lyricsId);
             java.io.InputStream input = object.getObjectContent()) {
            byte[] bytes = input.readNBytes(512 * 1024 + 1);
            if (bytes.length > 512 * 1024) {
                throw new IllegalArgumentException("LRC 文件超过 512KB");
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        }
    }

    private List<Song> songs() {
        Catalog current = catalog;
        long now = System.currentTimeMillis();
        if (current.expiresAt() > now) {
            return current.songs();
        }
        synchronized (this) {
            current = catalog;
            if (current.expiresAt() > now) {
                return current.songs();
            }
            List<Song> loaded = loadCatalog();
            catalog = new Catalog(loaded, now + cacheMillis);
            log.info("OSS 曲库已刷新: oss://{}/{}, 共{}首", bucket, prefix, loaded.size());
            return loaded;
        }
    }

    private List<Song> loadCatalog() {
        List<Song> songs = new ArrayList<>();
        Set<String> lyricsKeys = new HashSet<>();
        String marker = null;
        do {
            ListObjectsRequest request = new ListObjectsRequest(bucket)
                    .withPrefix(prefix)
                    .withMarker(marker)
                    .withMaxKeys(1000);
            ObjectListing page = client.listObjects(request);
            for (OSSObjectSummary object : page.getObjectSummaries()) {
                // 用户上传曲库按账号隔离，不能进入所有人共享的公共曲库。
                if (object.getKey().startsWith(prefix + "users/")) {
                    continue;
                }
                if (isLyrics(object.getKey(), object.getSize())) {
                    lyricsKeys.add(object.getKey());
                    continue;
                }
                Song song = songOf(object.getKey(), object.getSize());
                if (song != null) {
                    songs.add(song);
                }
            }
            marker = page.isTruncated() ? page.getNextMarker() : null;
        } while (marker != null && !marker.isBlank());
        // Stream.toList() 返回不可修改列表；复制为可变列表后再按 OSS key 排序。
        songs = new ArrayList<>(attachLyrics(songs, lyricsKeys));
        songs.sort(Comparator.comparing(Song::key));
        return List.copyOf(songs);
    }

    static List<Song> attachLyrics(List<Song> songs, Set<String> lyricsKeys) {
        Map<String, String> byStem = new HashMap<>();
        for (String key : lyricsKeys) {
            byStem.put(objectStem(key), key);
        }
        return songs.stream()
                .map(song -> new Song(song.key(), song.fileStem(), song.title(), song.artist(),
                        byStem.get(objectStem(song.key()))))
                .toList();
    }

    private static boolean isLyrics(String key, long size) {
        if (key == null || key.endsWith("/") || size <= 0) {
            return false;
        }
        int dot = key.lastIndexOf('.');
        return dot > key.lastIndexOf('/') && "lrc".equalsIgnoreCase(key.substring(dot + 1));
    }

    private static String objectStem(String key) {
        int dot = key == null ? -1 : key.lastIndexOf('.');
        return dot > key.lastIndexOf('/') ? key.substring(0, dot) : key;
    }

    static Song bestSong(List<Song> songs, String query) {
        String q = normalize(query);
        Song best = null;
        int bestScore = 0;
        for (Song song : songs) {
            int score = score(normalize(song.fileStem()), q);
            if (score > bestScore) {
                bestScore = score;
                best = song;
            }
        }
        return bestScore >= 60 ? best : null;
    }

    static Song songOf(String key, long size) {
        if (key == null || key.endsWith("/") || size <= 0) {
            return null;
        }
        String file = key.substring(key.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        if (dot <= 0 || !AUDIO_EXT.contains(file.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            return null;
        }
        String stem = file.substring(0, dot);
        String title = stem;
        String artist = "";
        int separator = stem.indexOf(" - ");
        int separatorLength = 3;
        if (separator < 0) {
            separator = stem.indexOf('-');
            separatorLength = 1;
        }
        if (separator > 0 && separator < stem.length() - separatorLength) {
            artist = stem.substring(0, separator).strip();
            title = stem.substring(separator + separatorLength).strip();
        }
        return new Song(key, stem, title, artist, null);
    }

    private static int score(String stem, String query) {
        if (stem.isEmpty() || query.isEmpty()) {
            return 0;
        }
        if (stem.equals(query)) {
            return 1000;
        }
        if (stem.contains(query)) {
            return 800;
        }
        if (query.contains(stem) && stem.length() >= 2) {
            return 700;
        }
        int common = 0;
        for (int i = 0; i < query.length(); i++) {
            if (stem.indexOf(query.charAt(i)) >= 0) {
                common++;
            }
        }
        return common * 100 / query.length();
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace("的", "")
                .replaceAll("[^\\p{IsHan}a-z0-9]", "");
    }

    @Override
    public void close() {
        client.shutdown();
    }

    record Song(String key, String fileStem, String title, String artist, String lyricsKey) {
    }

    record StoredUpload(
            String id,
            String title,
            String artist,
            String originalFilename,
            String audioObjectKey,
            String lyricsObjectKey,
            long audioBytes,
            long lyricsBytes
    ) {
    }

    private record Catalog(List<Song> songs, long expiresAt) {
    }
}
