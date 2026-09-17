package com.vca.web.voice;

import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.TtsConfig;
import com.vca.domain.model.WavAudio;
import com.vca.domain.enums.AudioFormat;
import com.vca.domain.enums.VendorType;
import com.vca.domain.spi.TtsProvider;
import com.vca.domain.spi.VoiceCloneStore;
import com.vca.domain.spi.VoiceCloner;
import com.vca.orchestrator.auth.MemberTiers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直接对着 RouterFunction 打请求, 验的是那些"线上才会踩、单测覆盖不到"的分支:
 * 未登录、没勾同意、样本不合规、配额满、以及<b>拿别人的音色</b>。
 */
class VoiceCloneRouteWebTest {

    private static final String TOKEN = "Bearer t-user-7";

    private StubStore store;
    private StubCloner cloner;
    private WebTestClient client;
    /** 当前登录用户的等级, 各用例按需改。 */
    private MemberTiers.Tier tier;

    @BeforeEach
    void setUp() {
        store = new StubStore();
        cloner = new StubCloner();
        TtsProvider tts = new TtsProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.ALIYUN;
            }

            @Override
            public Flux<AudioChunk> synthesize(Flux<String> text, TtsConfig cfg) {
                ttsInstruction = cfg.instruction();
                return text.map(t -> new AudioChunk(new byte[480], AudioFormat.PCM, 0, null, false));
            }
        };
        tier = MemberTiers.Tier.FREE;
        // 令牌 → userId: "t-user-7" 解出 7, 其余一律未登录
        client = WebTestClient.bindToRouterFunction(VoiceCloneRoute.create(
                cloner, store, tts,
                token -> "t-user-7".equals(token) ? "7" : null,
                userId -> tier,
                new VoiceCloneRoute.Quota(2, 5),
                new VoiceCloneRoute.Quota(4, 5))).build();
    }

    private static byte[] sample(double seconds, int rate) {
        return WavAudio.wrapPcm16Mono(new byte[(int) (seconds * rate) * 2], rate);
    }

    private String list() {
        return client.get().uri("/api/voices").header("Authorization", TOKEN).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec post(byte[] wav, String consent) {
        MultipartBodyBuilder b = new MultipartBodyBuilder();
        b.part("file", new org.springframework.core.io.ByteArrayResource(wav) {
            @Override
            public String getFilename() {
                return "sample.wav";
            }
        });
        b.part("name", "我的声音");
        if (consent != null) {
            b.part("consent", consent);
        }
        return client.post().uri("/api/voices").header("Authorization", TOKEN)
                .body(BodyInserters.fromMultipartData(b.build())).exchange();
    }

    @Test
    void 未登录一律401() {
        client.get().uri("/api/voices").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/voices").header("Authorization", "Bearer 别人的")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void 没勾本人声音不给复刻() {
        post(sample(15, 16000), null).expectStatus().isBadRequest();
        post(sample(15, 16000), "false").expectStatus().isBadRequest();
        assertEquals(0, cloner.calls.get(), "校验没过就不该把样本发给厂商");
    }

    @Test
    void 样本不合规时本地就拦掉() {
        post(sample(3, 16000), "true").expectStatus().isBadRequest();      // 太短
        post(sample(90, 16000), "true").expectStatus().isBadRequest();     // 太长
        post(sample(15, 8000), "true").expectStatus().isBadRequest();      // 采样率低
        post("这不是 wav".getBytes(), "true").expectStatus().isBadRequest();
        assertEquals(0, cloner.calls.get(), "不合规的样本不该白白花一次厂商调用");
    }

    @Test
    void 复刻成功并按配额拦住第三个() {
        post(sample(15, 16000), "true").expectStatus().isOk();
        post(sample(15, 16000), "true").expectStatus().isOk();
        post(sample(15, 16000), "true").expectStatus().isEqualTo(409);
        assertEquals(2, store.rows.size());
        assertEquals(2, cloner.calls.get());
    }

    /** 会员的权益就是这一档配额: 同一个用户, 等级不同上限不同。 */
    @Test
    void 会员按更高的一档配额算() {
        tier = MemberTiers.Tier.VIP;
        for (int i = 0; i < 4; i++) {
            post(sample(15, 16000), "true").expectStatus().isOk();
        }
        post(sample(15, 16000), "true").expectStatus().isEqualTo(409);
        assertEquals(4, store.rows.size());
        String body = list();
        assertTrue(body.contains("\"max\":4"), body);
        assertTrue(body.contains("\"tier\":\"vip\""), body);
    }

    /** 免费档满了要指向会员 —— 这是升级入口, 不能只说"请先删除"。 */
    @Test
    void 免费档满了提示可升级会员() {
        post(sample(15, 16000), "true").expectStatus().isOk();
        post(sample(15, 16000), "true").expectStatus().isOk();
        String error = post(sample(15, 16000), "true").expectStatus().isEqualTo(409)
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(error != null && error.contains("升级会员可克隆 4 个"), error);
        String body = list();
        assertTrue(body.contains("\"max\":2"), body);
        assertTrue(body.contains("\"tier\":\"free\""), body);
        assertTrue(body.contains("\"vipMax\":4"), body);
    }

    /** 会员到期由 MemberTiers 实现负责降级, 路由这边只认当下这一次查询的结果。 */
    @Test
    void 会员到期后回到免费档上限() {
        tier = MemberTiers.Tier.VIP;
        post(sample(15, 16000), "true").expectStatus().isOk();
        post(sample(15, 16000), "true").expectStatus().isOk();
        tier = MemberTiers.Tier.FREE;
        post(sample(15, 16000), "true").expectStatus().isEqualTo(409);
        assertEquals(2, store.rows.size(), "已经复刻好的音色不因到期被删, 只是不能再建新的");
    }

    /** 音色 id 在前端是明文, 猜到别人的也不能用 —— 试听和删除都要按 userId 过滤。 */
    @Test
    void 拿不到别人的音色() {
        store.rows.add(new VoiceCloneStore.Clone("qwen-audio-3.0-tts-flash-u9-abc", 999L,
                "别人的声音", "qwen-audio-3.0-tts-flash", 15, true, "active", Instant.now(), null));
        client.post().uri("/api/voices/qwen-audio-3.0-tts-flash-u9-abc/preview")
                .header("Authorization", TOKEN).exchange().expectStatus().isNotFound();
        client.delete().uri("/api/voices/qwen-audio-3.0-tts-flash-u9-abc")
                .header("Authorization", TOKEN).exchange().expectStatus().isNotFound();
        assertTrue(cloner.deleted.isEmpty(), "不该替别人删云端音色");
        assertEquals(1, store.rows.size());
    }

    @Test
    void 试听本人音色返回wav() {
        post(sample(15, 16000), "true").expectStatus().isOk();
        String id = store.rows.get(0).voiceId();
        byte[] body = client.post().uri("/api/voices/" + id + "/preview?dialect=粤语")
                .header("Authorization", TOKEN).exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertTrue(body != null && body.length > 44);
        assertEquals("请用粤语表达。", ttsInstruction, "方言要透到合成参数里");
    }

    /** 上一条用例要看合成时拿到的 instruction, 用字段截获。 */
    private static String ttsInstruction;

    // ---------------- 桩 ----------------

    private static final class StubCloner implements VoiceCloner {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> deleted = new ArrayList<>();

        @Override
        public String targetModel() {
            return "qwen-audio-3.0-tts-flash";
        }

        @Override
        public String create(String prefix, byte[] wav) {
            return "qwen-audio-3.0-tts-flash-" + prefix + "-" + calls.incrementAndGet();
        }

        @Override
        public boolean delete(String voiceId) {
            deleted.add(voiceId);
            return true;
        }
    }

    private static final class StubStore implements VoiceCloneStore {
        final List<Clone> rows = new ArrayList<>();

        @Override
        public void save(Clone clone) {
            rows.add(clone);
        }

        @Override
        public List<Clone> list(long userId) {
            return rows.stream().filter(c -> c.userId() == userId).toList();
        }

        @Override
        public Optional<Clone> find(String voiceId) {
            return rows.stream().filter(c -> c.voiceId().equals(voiceId)).findFirst();
        }

        @Override
        public int countByUser(long userId) {
            return list(userId).size();
        }

        @Override
        public int countCreatedSince(long userId, Instant since) {
            return 0;
        }

        @Override
        public void touchUsed(String voiceId, Instant usedAt) {
        }

        @Override
        public void markInvalid(String voiceId) {
        }

        @Override
        public boolean delete(String voiceId, long userId) {
            return rows.removeIf(c -> c.voiceId().equals(voiceId) && c.userId() == userId);
        }
    }
}
