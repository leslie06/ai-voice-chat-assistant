package com.vca.web.music;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.vca.domain.model.MusicPlaylist;
import com.vca.domain.model.MusicTrack;
import com.vca.domain.spi.MusicProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
    private volatile Catalog catalog = new Catalog(List.of(), 0);

    public OssMusicProvider(OSS client, String bucket, String prefix,
                            Duration urlLifetime, Duration catalogCache) {
        this.client = client;
        this.bucket = bucket;
        String p = prefix == null ? "" : prefix.strip();
        this.prefix = p.isEmpty() ? "" : (p.endsWith("/") ? p : p + "/");
        this.urlLifetime = urlLifetime;
        this.cacheMillis = Math.max(0, catalogCache.toMillis());
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
        if (hit == null) {
            return null;
        }
        Date expiresAt = new Date(System.currentTimeMillis() + urlLifetime.toMillis());
        URL url = client.generatePresignedUrl(bucket, hit.key(), expiresAt, HttpMethod.GET);
        return new MusicTrack(hit.title(), hit.artist(), url.toString(), null, 0, true);
    }

    private MusicPlaylist findPlaylist(String query) {
        List<Song> songs = songs();
        Song hit = bestSong(songs, query);
        if (hit == null) {
            return null;
        }
        List<MusicTrack> tracks = signedTracks(songs);
        int currentIndex = songs.indexOf(hit);
        return new MusicPlaylist(tracks, currentIndex);
    }

    private List<MusicTrack> signedCatalog() {
        return signedTracks(songs());
    }

    private List<MusicTrack> signedTracks(List<Song> songs) {
        Date expiresAt = new Date(System.currentTimeMillis() + urlLifetime.toMillis());
        List<MusicTrack> tracks = new ArrayList<>(songs.size());
        for (Song song : songs) {
            URL url = client.generatePresignedUrl(bucket, song.key(), expiresAt, HttpMethod.GET);
            tracks.add(new MusicTrack(song.title(), song.artist(), url.toString(), null, 0, true));
        }
        return List.copyOf(tracks);
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
        String marker = null;
        do {
            ListObjectsRequest request = new ListObjectsRequest(bucket)
                    .withPrefix(prefix)
                    .withMarker(marker)
                    .withMaxKeys(1000);
            ObjectListing page = client.listObjects(request);
            for (OSSObjectSummary object : page.getObjectSummaries()) {
                Song song = songOf(object.getKey(), object.getSize());
                if (song != null) {
                    songs.add(song);
                }
            }
            marker = page.isTruncated() ? page.getNextMarker() : null;
        } while (marker != null && !marker.isBlank());
        songs.sort(Comparator.comparing(Song::key));
        return List.copyOf(songs);
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
        return new Song(key, stem, title, artist);
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

    record Song(String key, String fileStem, String title, String artist) {
    }

    private record Catalog(List<Song> songs, long expiresAt) {
    }
}
