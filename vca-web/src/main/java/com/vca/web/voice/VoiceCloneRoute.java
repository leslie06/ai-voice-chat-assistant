package com.vca.web.voice;

import com.vca.domain.enums.AudioFormat;
import com.vca.domain.enums.VendorType;
import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.TtsConfig;
import com.vca.domain.model.WavAudio;
import com.vca.domain.spi.TtsProvider;
import com.vca.domain.spi.VoiceCloneStore;
import com.vca.domain.spi.VoiceCloner;
import com.vca.orchestrator.auth.MemberTiers;
import com.vca.orchestrator.auth.TokenAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;

/**
 * 声音复刻 REST。鉴权与 {@code KnowledgeRoutes} 一致: {@code Authorization: Bearer <token>} → userId。
 *
 * <pre>
 *   POST   /api/voices              multipart(file,name,consent) → {voiceId,name,...}
 *   GET    /api/voices                          → {voices:[...], quota:{used,max,tier,vipMax}}
 *   POST   /api/voices/{id}/preview ?dialect=粤语                  → audio/wav 试听
 *   DELETE /api/voices/{id}                                        → {ok}
 * </pre>
 *
 * <p><b>为什么每个接口都要查库确认归属</b>: 厂商的音色表是账号级的, voice_id 又会明文出现在
 * 前端, 只要有人猜到别人的 id 就能直接拿来合成。归属只能由我们自己把关。
 */
public final class VoiceCloneRoute {

    private static final Logger log = LoggerFactory.getLogger(VoiceCloneRoute.class);

    /** 样本读入内存的上限。厂商限制 10MB, 这里放宽一点好给出更准确的报错。 */
    private static final int MAX_UPLOAD_BYTES = 12 * 1024 * 1024;
    /** 厂商要求 10~60 秒, 推荐 10~20 秒; 低于 10 秒相似度明显下降。 */
    private static final double MIN_SECONDS = 8.0;
    private static final double MAX_SECONDS = 60.0;
    private static final int MIN_SAMPLE_RATE = 16000;
    private static final String PREVIEW_TEXT = "你好呀，这是我的声音，以后就用它和你聊天啦。";

    private final VoiceCloner cloner;
    private final VoiceCloneStore store;
    private final TtsProvider tts;
    private final TokenAuthenticator authenticator;
    private final MemberTiers tiers;
    private final Quota free;
    private final Quota vip;

    /**
     * 一档会员的声音复刻配额。
     *
     * @param maxVoices    音色总数上限
     * @param createPerDay 每天最多创建几次(含失败后重试)
     */
    public record Quota(int maxVoices, int createPerDay) {
    }

    private VoiceCloneRoute(VoiceCloner cloner, VoiceCloneStore store, TtsProvider tts,
                            TokenAuthenticator authenticator, MemberTiers tiers, Quota free, Quota vip) {
        this.cloner = cloner;
        this.store = store;
        this.tts = tts;
        this.authenticator = authenticator;
        this.tiers = tiers;
        this.free = free;
        this.vip = vip;
    }

    /** {@code tiers} 可为 null(未启用账号会员): 那时所有人都按免费档。 */
    public static RouterFunction<ServerResponse> create(
            VoiceCloner cloner, VoiceCloneStore store, TtsProvider tts,
            TokenAuthenticator authenticator, MemberTiers tiers, Quota free, Quota vip) {
        VoiceCloneRoute r = new VoiceCloneRoute(cloner, store, tts, authenticator, tiers, free, vip);
        return RouterFunctions.route(POST("/api/voices"), r::create)
                .andRoute(GET("/api/voices"), r::list)
                .andRoute(POST("/api/voices/{id}/preview"), r::preview)
                .andRoute(DELETE("/api/voices/{id}"), r::delete);
    }

    // ---------------- 创建 ----------------

    private Mono<ServerResponse> create(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        return req.body(BodyExtractors.toMultipartData()).flatMap(parts -> {
            Map<String, Part> map = parts.toSingleValueMap();
            if (!(map.get("file") instanceof FilePart file)) {
                return json(400, Map.of("error", "缺少音频文件(form 字段名应为 file)"));
            }
            String name = trimmed(map.get("name"), "我的声音", 32);
            boolean consent = "true".equalsIgnoreCase(trimmed(map.get("consent"), "", 8));
            if (!consent) {
                return json(400, Map.of("error", "请先确认这是本人声音"));
            }
            return DataBufferUtils.join(file.content(), MAX_UPLOAD_BYTES)
                    .map(VoiceCloneRoute::toBytes)
                    .flatMap(wav -> blocking(() -> doCreate(uid, name, wav)).flatMap(this::respond))
                    .onErrorResume(e -> json(400, Map.of("error", "音频读取失败或超过 12MB")));
        }).switchIfEmpty(json(400, Map.of("error", "请求体缺失")));
    }

    /** 返回 {状态码, 响应体}, 让校验失败与成功走同一条链路。 */
    private Object[] doCreate(long uid, String name, byte[] wav) {
        String bad = validate(wav);
        if (bad != null) {
            return new Object[]{400, Map.of("error", bad)};
        }
        boolean vipUser = isVip(uid);
        Quota quota = vipUser ? vip : free;
        if (store.countByUser(uid) >= quota.maxVoices()) {
            // 免费档满了顺带告诉用户会员能到几个 —— 这是升级会员的主要入口
            return new Object[]{409, Map.of("error", vipUser || vip.maxVoices() <= quota.maxVoices()
                    ? "音色数量已达上限 " + quota.maxVoices() + " 个，请先删除不用的"
                    : "音色数量已达上限 " + quota.maxVoices() + " 个，升级会员可克隆 "
                            + vip.maxVoices() + " 个，或先删除不用的")};
        }
        if (store.countCreatedSince(uid, Instant.now().minus(Duration.ofDays(1))) >= quota.createPerDay()) {
            return new Object[]{429, Map.of("error", "今天创建得太频繁了，明天再试")};
        }
        WavAudio info = WavAudio.parse(wav);
        String voiceId;
        try {
            voiceId = cloner.create("u" + Long.toString(uid, 36), wav);
        } catch (Exception e) {
            log.warn("声音复刻失败: userId={}, err={}", uid, e.toString());
            return new Object[]{502, Map.of("error", "复刻失败，请换一段更清晰的录音再试")};
        }
        VoiceCloneStore.Clone clone = new VoiceCloneStore.Clone(
                voiceId, uid, name, cloner.targetModel(),
                (int) Math.round(info.seconds()), true, "active", Instant.now(), null);
        store.save(clone);
        return new Object[]{200, dto(clone)};
    }

    /** 样本校验。厂商那边失败只会回一句 "detect audio failed", 在这里说清楚问题出在哪。 */
    private static String validate(byte[] wav) {
        if (wav.length > 10 * 1024 * 1024) {
            return "音频超过 10MB";
        }
        WavAudio info = WavAudio.parse(wav);
        if (info == null) {
            return "只支持未压缩的 WAV(PCM)，请用页面内录音";
        }
        if (info.bitsPerSample() != 16) {
            return "需要 16 位采样，当前是 " + info.bitsPerSample() + " 位";
        }
        if (info.sampleRate() < MIN_SAMPLE_RATE) {
            return "采样率需不低于 16kHz，当前是 " + info.sampleRate() + "Hz";
        }
        double sec = info.seconds();
        if (sec < MIN_SECONDS) {
            return String.format("录音太短(%.1f 秒)，请录满 10 秒以上", sec);
        }
        if (sec > MAX_SECONDS) {
            return String.format("录音太长(%.1f 秒)，请控制在 60 秒内", sec);
        }
        return null;
    }

    // ---------------- 列表 / 试听 / 删除 ----------------

    private Mono<ServerResponse> list(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        return blocking(() -> {
            List<Map<String, Object>> voices = store.list(uid).stream().map(VoiceCloneRoute::dto).toList();
            boolean vipUser = isVip(uid);
            return Map.of("voices", voices,
                    "quota", Map.of("used", voices.size(),
                            "max", (vipUser ? vip : free).maxVoices(),
                            "tier", vipUser ? "vip" : "free",
                            "vipMax", vip.maxVoices()));
        }).flatMap(body -> json(200, body));
    }

    private Mono<ServerResponse> preview(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        String voiceId = req.pathVariable("id");
        String instruction = dialectInstruction(req.queryParam("dialect").orElse(null));
        // 注意别让 blocking() 里返回 null: Mono.fromCallable(() -> null) 得到的是<b>空 Mono</b>,
        // 后面的 flatMap 整个不执行, 处理器一个 ServerResponse 都不产出, 框架兜底回 200 ——
        // 归属校验就这么被绕过去了。用 Optional 保证链路上始终有元素。
        return blocking(() -> store.find(voiceId).filter(c -> c.userId() == uid))
                .flatMap(found -> {
                    if (found.isEmpty()) {
                        return json(404, Map.of("error", "音色不存在"));
                    }
                    TtsConfig cfg = new TtsConfig(VendorType.ALIYUN, voiceId,
                            AudioFormat.PCM, 24000, 1.0f, instruction);
                    return tts.synthesize(Flux.just(PREVIEW_TEXT), cfg)
                            .map(AudioChunk::data)
                            .reduce(new ByteArrayOutputStream(), (acc, b) -> {
                                acc.writeBytes(b);
                                return acc;
                            })
                            .flatMap(buf -> {
                                byte[] pcm = buf.toByteArray();
                                if (pcm.length == 0) {
                                    return json(502, Map.of("error", "试听合成失败"));
                                }
                                blocking(() -> {
                                    store.touchUsed(voiceId, Instant.now());
                                    return true;
                                }).subscribe();
                                return ServerResponse.ok()
                                        .contentType(MediaType.parseMediaType("audio/wav"))
                                        .bodyValue(WavAudio.wrapPcm16Mono(pcm, 24000));
                            })
                            .onErrorResume(e -> {
                                log.warn("试听失败: voiceId={}, err={}", voiceId, e.toString());
                                // 音色被厂商清理掉时合成必失败, 标记后前端提示重新复刻
                                return blocking(() -> {
                                    store.markInvalid(voiceId);
                                    return true;
                                }).then(json(502, Map.of("error", "试听失败，音色可能已失效")));
                            });
                });
    }

    private Mono<ServerResponse> delete(ServerRequest req) {
        Long uid = userId(req);
        if (uid == null) {
            return unauthorized();
        }
        String voiceId = req.pathVariable("id");
        return blocking(() -> {
            if (store.find(voiceId).filter(c -> c.userId() == uid).isEmpty()) {
                return false;
            }
            cloner.delete(voiceId);       // 云端删不掉也继续删本地, 否则本地记录会永远清不掉
            return store.delete(voiceId, uid);
        }).flatMap(ok -> ok ? json(200, Map.of("ok", true)) : json(404, Map.of("error", "音色不存在")));
    }

    // ---------------- 工具 ----------------

    /**
     * 方言 → 指令文本。白名单而非直接透传用户输入: instruction 会被原样送进合成模型,
     * 放开等于给了一个可以往模型里塞任意话术的口子。
     */
    public static String dialectInstruction(String dialect) {
        if (dialect == null || dialect.isBlank() || "普通话".equals(dialect)) {
            return null;
        }
        return switch (dialect) {
            case "粤语", "四川话", "东北话", "上海话", "河南话", "陕西话",
                 "山东话", "湖南话", "湖北话", "天津话", "重庆话", "云南话" ->
                    "请用" + dialect + "表达。";
            default -> null;
        };
    }

    /**
     * 是不是会员。查库(阻塞), 调用方都在 {@code blocking()} 里。
     * 未接会员实现时一律按免费档 —— 配额只会更紧, 不会漏发权益。
     */
    private boolean isVip(long userId) {
        return tiers != null && tiers.tierOf(userId) == MemberTiers.Tier.VIP;
    }

    private Long userId(ServerRequest req) {
        if (authenticator == null) {
            return null;
        }
        String header = req.headers().firstHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : header;
        String value = token == null ? null : authenticator.authenticate(token);
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Mono<ServerResponse> respond(Object[] result) {
        return json((int) result[0], result[1]);
    }

    private static Map<String, Object> dto(VoiceCloneStore.Clone c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("voiceId", c.voiceId());
        m.put("name", c.name());
        m.put("seconds", c.sampleSeconds());
        m.put("status", c.status());
        m.put("createdAt", c.createdAt() == null ? 0 : c.createdAt().toEpochMilli());
        return m;
    }

    private static String trimmed(Part part, String fallback, int max) {
        if (!(part instanceof FormFieldPart f)) {
            return fallback;
        }
        String v = f.value() == null ? "" : f.value().trim();
        if (v.isEmpty()) {
            return fallback;
        }
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static byte[] toBytes(DataBuffer buf) {
        byte[] bytes = new byte[buf.readableByteCount()];
        buf.read(bytes);
        DataBufferUtils.release(buf);
        return bytes;
    }

    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> c) {
        return Mono.fromCallable(c).subscribeOn(Schedulers.boundedElastic());
    }

    private static Mono<ServerResponse> json(int status, Object body) {
        return ServerResponse.status(status).bodyValue(body);
    }

    private static Mono<ServerResponse> unauthorized() {
        return json(401, Map.of("error", "未登录或登录已失效"));
    }
}
