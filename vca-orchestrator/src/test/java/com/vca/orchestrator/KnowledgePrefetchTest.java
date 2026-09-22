package com.vca.orchestrator;

import com.vca.domain.enums.AudioFormat;
import com.vca.domain.enums.VendorType;
import com.vca.domain.model.AsrConfig;
import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.Message;
import com.vca.domain.model.SessionContext;
import com.vca.domain.model.TtsConfig;
import com.vca.domain.spi.AsrProvider;
import com.vca.domain.spi.LlmProvider;
import com.vca.domain.spi.TtsProvider;
import com.vca.orchestrator.knowledge.KnowledgeStore;
import com.vca.orchestrator.pipeline.SentenceSplitter;
import com.vca.orchestrator.session.ConversationSession;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库预取: 判停那一刻拿识别的<b>中间转写</b>提前查库, 与识别收尾并行。
 *
 * <p>电话里"停顿"的组成: 判停等静音 → 识别收尾(两三百毫秒) → 查知识库(一次向量接口, 100~200ms)
 * → 大模型 → 合成。查库原本必须等最终文本, 与识别收尾串着; 而中间转写与最终文本多半只差标点,
 * 判停时就能开始查。
 */
class KnowledgePrefetchTest {

    /** 记录每次检索的 query, 并模拟接口耗时 */
    private static final class RecordingStore implements KnowledgeStore {
        final List<String> queries = new CopyOnWriteArrayList<>();

        @Override
        public List<String> search(String userId, String query) {
            queries.add(query);
            try {
                Thread.sleep(80);   // 模拟向量接口往返
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return List.of("洗牙参考价 200 到 400 元");
        }
    }

    private static LlmProvider capturingLlm(AtomicReference<List<Message>> seen) {
        return new LlmProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.QWEN;
            }

            @Override
            public Flux<String> chatStream(List<Message> history, LlmConfig cfg) {
                seen.set(List.copyOf(history));
                return Flux.just("两百到四百元。");
            }
        };
    }

    private static TtsProvider silentTts() {
        return new TtsProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.ALIYUN;
            }

            @Override
            public Flux<AudioChunk> synthesize(Flux<String> textSegments, TtsConfig cfg) {
                return textSegments.map(t -> new AudioChunk(new byte[320], AudioFormat.PCM, 0, t, false));
            }
        };
    }

    private static ConversationSession session(KnowledgeStore store, AtomicReference<List<Message>> seen) {
        SessionContext ctx = SessionContext.pipeline("s-1", null,
                AsrConfig.defaults(VendorType.ALIYUN),
                LlmConfig.defaults(VendorType.QWEN, "qwen-flash"),
                TtsConfig.defaults(VendorType.ALIYUN, "v"));
        ConversationSession s = new ConversationSession(ctx, (AsrProvider) null, capturingLlm(seen), silentTts(),
                null, new SentenceSplitter());
        s.setKnowledge(store, "11");   // 电话: 知识库按商家账号归属
        return s;
    }

    private static boolean knowledgeInjected(List<Message> history) {
        return history != null && history.stream()
                .anyMatch(m -> m.content() != null && m.content().contains("洗牙参考价"));
    }

    @Test
    void prefetchedResultIsReusedWhenFinalTextOnlyDiffersInPunctuation() {
        RecordingStore store = new RecordingStore();
        AtomicReference<List<Message>> seen = new AtomicReference<>();
        ConversationSession s = session(store, seen);

        s.prefetchKnowledge("洗牙多少钱");                                 // 判停时的中间转写: 没标点
        s.handleTextTurn("洗牙多少钱？").blockLast(Duration.ofSeconds(5)); // 最终文本: 多了个问号

        assertThat(store.queries).as("只应查一次库, 回复时复用预取结果").hasSize(1);
        assertThat(knowledgeInjected(seen.get())).as("资料照常注入给模型").isTrue();
    }

    @Test
    void differentFinalTextDiscardsThePrefetchAndSearchesAgain() {
        RecordingStore store = new RecordingStore();
        AtomicReference<List<Message>> seen = new AtomicReference<>();
        ConversationSession s = session(store, seen);

        s.prefetchKnowledge("洗牙");                                        // 中间转写只到一半
        s.handleTextTurn("洗牙和种植牙都多少钱？").blockLast(Duration.ofSeconds(5));

        assertThat(store.queries).as("文本对不上就重新查, 不能拿半句的结果凑数").hasSize(2);
        assertThat(store.queries.get(1)).isEqualTo("洗牙和种植牙都多少钱？");
        assertThat(knowledgeInjected(seen.get())).isTrue();
    }

    @Test
    void prefetchIsSingleUse() {
        RecordingStore store = new RecordingStore();
        AtomicReference<List<Message>> seen = new AtomicReference<>();
        ConversationSession s = session(store, seen);

        s.prefetchKnowledge("洗牙多少钱");
        s.handleTextTurn("洗牙多少钱？").blockLast(Duration.ofSeconds(5));
        s.handleTextTurn("洗牙多少钱？").blockLast(Duration.ofSeconds(5));   // 同样的话再问一遍

        assertThat(store.queries).as("预取结果只能用一次, 第二轮要重新查").hasSize(2);
    }

    @Test
    void prefetchIsANoOpWithoutKnowledgeBase() {
        AtomicReference<List<Message>> seen = new AtomicReference<>();
        SessionContext ctx = SessionContext.pipeline("s-2", null,
                AsrConfig.defaults(VendorType.ALIYUN),
                LlmConfig.defaults(VendorType.QWEN, "qwen-flash"),
                TtsConfig.defaults(VendorType.ALIYUN, "v"));
        ConversationSession s = new ConversationSession(ctx, (AsrProvider) null, capturingLlm(seen), silentTts(),
                null, new SentenceSplitter());

        s.prefetchKnowledge("洗牙多少钱");   // 没接知识库: 不能抛, 也不能有副作用
        s.handleTextTurn("洗牙多少钱？").blockLast(Duration.ofSeconds(5));

        assertThat(knowledgeInjected(seen.get())).isFalse();
    }
}
