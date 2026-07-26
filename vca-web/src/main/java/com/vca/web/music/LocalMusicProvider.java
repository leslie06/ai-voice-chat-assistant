package com.vca.web.music;

import com.vca.domain.model.MusicTrack;
import com.vca.domain.spi.MusicProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 本地曲库音源: 在配置目录里按文件名匹配歌曲, <b>整首</b>播放。
 *
 * <p>检索到的曲目通过 {@code /music/files/**} 路由(见 {@code WebAutoConfiguration})流式回放,
 * 支持 Range 请求(可拖动进度)。匹配是文件名归一化后的包含/字符重叠, 命名越规范越准
 * (推荐 {@code 歌手 - 歌名.mp3})。合法、自用, 不需要任何会员。
 */
public class LocalMusicProvider implements MusicProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalMusicProvider.class);

    private static final Set<String> AUDIO_EXT = Set.of("mp3", "m4a", "flac", "wav", "aac", "ogg", "opus");
    private static final String LYRICS_PREFIX = "local:";
    private static final long MAX_LYRICS_BYTES = 512 * 1024;
    /** URL 前缀, 与文件服务路由一致 */
    static final String URL_PREFIX = "/music/files/";

    private final Path root;

    public LocalMusicProvider(String dir) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
    }

    @Override
    public Mono<MusicTrack> search(String query) {
        if (query == null || query.isBlank() || !Files.isDirectory(root)) {
            return Mono.empty();
        }
        // 扫描文件系统是阻塞操作, 丢到 boundedElastic, 别占用事件循环线程
        return Mono.fromCallable(() -> findBest(query))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(hit -> hit == null ? Mono.empty() : Mono.just(toTrack(hit)));
    }

    @Override
    public Mono<String> lyrics(String lyricsId) {
        if (lyricsId == null || !lyricsId.startsWith(LYRICS_PREFIX)) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> readLyrics(lyricsId.substring(LYRICS_PREFIX.length())))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(text -> text == null ? Mono.empty() : Mono.just(text))
                .onErrorResume(e -> {
                    log.warn("读取本地歌词失败: id={} ({})", lyricsId, e.toString());
                    return Mono.empty();
                });
    }

    /** 遍历曲库, 返回得分最高且达标的文件; 无则 null。遇不可读子目录跳过, 不中断。 */
    private Path findBest(String query) {
        String q = normalize(query);
        if (q.isEmpty()) {
            return null;
        }
        Best best = new Best();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isAudio(file)) {
                        int s = score(normalize(stem(file)), q);
                        if (s > best.score) {
                            best.score = s;
                            best.path = file;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;   // 跳过读不了的文件/目录
                }
            });
        } catch (IOException e) {
            log.warn("扫描曲库失败: {} ({})", root, e.toString());
            return null;
        }
        // 达标门槛: 命中包含关系(>=700), 或字符重叠率 >= 60%
        return best.score >= 60 ? best.path : null;
    }

    private MusicTrack toTrack(Path file) {
        String stem = stem(file);
        String title = stem;
        String artist = "";
        // 习惯命名 "歌手 - 歌名" 或 "歌手-歌名"; 按第一个分隔符拆, 左为歌手
        int sep = stem.indexOf(" - ");
        int sepLen = 3;
        if (sep < 0) {
            sep = stem.indexOf('-');
            sepLen = 1;
        }
        if (sep > 0 && sep < stem.length() - sepLen) {
            artist = stem.substring(0, sep).trim();
            title = stem.substring(sep + sepLen).trim();
        }
        return new MusicTrack(title, artist, toUrl(file), null, lyricsId(file), 0, true);
    }

    /** 查找音频旁边同名的 LRC，并生成只能由后端解析的本地歌词标识。 */
    private String lyricsId(Path audio) {
        Path lyrics = findSidecarLyrics(audio);
        if (lyrics == null) {
            return null;
        }
        String relative = root.relativize(lyrics.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return LYRICS_PREFIX + relative;
    }

    private Path findSidecarLyrics(Path audio) {
        Path parent = audio.getParent();
        if (parent == null) {
            return null;
        }
        String expectedStem = stem(audio);
        try (Stream<Path> files = Files.list(parent)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> isLyrics(path) && stem(path).equals(expectedStem))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** 只允许读取曲库根目录内、由服务端生成标识的 LRC，防止路径穿越。 */
    private String readLyrics(String relative) throws IOException {
        if (relative == null || relative.isBlank()) {
            return null;
        }
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || !isLyrics(file) || !Files.isRegularFile(file)) {
            return null;
        }
        try (java.io.InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes((int) MAX_LYRICS_BYTES + 1);
            if (bytes.length > MAX_LYRICS_BYTES) {
                throw new IllegalArgumentException("LRC 文件超过 512KB");
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        }
    }

    /** 把文件路径转成 /music/files/<相对路径>, 逐段做 path 编码(支持中文/空格) */
    private String toUrl(Path file) {
        Path rel = root.relativize(file);
        StringBuilder sb = new StringBuilder(URL_PREFIX);
        for (int i = 0; i < rel.getNameCount(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(UriUtils.encodePathSegment(rel.getName(i).toString(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** 匹配打分: 完全相等/包含给高分, 否则按 query 字符在文件名中的覆盖率(0-100) */
    private static int score(String stem, String q) {
        if (stem.isEmpty() || q.isEmpty()) {
            return 0;
        }
        if (stem.equals(q)) {
            return 1000;
        }
        if (stem.contains(q)) {
            return 800;
        }
        if (q.contains(stem) && stem.length() >= 2) {
            return 700;
        }
        int common = 0;
        for (int i = 0; i < q.length(); i++) {
            if (stem.indexOf(q.charAt(i)) >= 0) {
                common++;
            }
        }
        return common * 100 / q.length();
    }

    /** 归一化: 转小写、去掉"的"和所有非字母数字/汉字字符, 便于跨命名风格匹配 */
    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("的", "")
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]", "");
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static boolean isAudio(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && AUDIO_EXT.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static boolean isLyrics(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && "lrc".equalsIgnoreCase(name.substring(dot + 1));
    }

    /** 遍历过程中的当前最佳 */
    private static final class Best {
        int score;
        Path path;
    }
}
