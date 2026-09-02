package com.vca.orchestrator;

import com.vca.domain.enums.AudioFormat;
import com.vca.domain.enums.VendorType;
import com.vca.domain.model.AsrConfig;
import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.Message;
import com.vca.domain.model.SessionContext;
import com.vca.domain.model.TtsConfig;
import com.vca.domain.spi.LlmProvider;
import com.vca.domain.spi.TtsProvider;
import com.vca.orchestrator.knowledge.KnowledgeStore;
import com.vca.orchestrator.memory.MemoryStore;
import com.vca.orchestrator.pipeline.SentenceSplitter;
import com.vca.orchestrator.search.WebSearchProvider;
import com.vca.orchestrator.session.ConversationSession;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 外部上下文装配(长期记忆 / 知识库 RAG / 联网检索)的行为约束。
 *
 * <p>这三路都是阻塞调用, 且此前是在订阅线程上<b>串行</b>跑的 —— 订阅线程在生产上是 WebSocket 的 netty
 * 事件循环或 ASR 回调线程, 于是既卡住同线程的其它会话, 又把三次网络往返原样叠进首字延迟。
 * 本测试把这两条约束钉住: <b>不在调用者线程上跑</b> + <b>三路并行</b>, 同时保证注入顺序不变。
 */
class ContextAssemblyTest {

    /** 每一路检索的模拟耗时; 串行=3×, 并行≈1×, 差距足够大到不会因机器抖动误判。 */
    private static final Duration FETCH = Duration.ofMillis(300);

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 记录"模型最终看到的消息列表"的假 LLM; 回复固定一句。 */
    private static LlmProvider capturingLlm(AtomicReference<List<Message>> seen) {
        return new LlmProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.DEEPSEEK;
            }

            @Override
            public Flux<String> chatStream(List<Message> history, LlmConfig cfg) {
                seen.set(List.copyOf(history));
                return Flux.just("好", "的", "。");
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
                return textSegments.map(seg -> new AudioChunk(new byte[0], AudioFormat.PCM, 0, seg, false));
            }
        };
    }

    private static ConversationSession session(LlmProvider llm) {
        SessionContext ctx = SessionContext.pipeline(
                "s-ctx", "u-1",
                AsrConfig.defaults(VendorType.ALIYUN),
                LlmConfig.defaults(VendorType.DEEPSEEK, "deepseek-chat"),
                TtsConfig.defaults(VendorType.ALIYUN, "longxiaochun"));
        return new ConversationSession(ctx, null, llm, silentTts(), null, new SentenceSplitter());
    }

    @Test
    void threeRetrievalsRunInParallelAndOffTheSubscribingThread() throws Exception {
        AtomicReference<List<Message>> seen = new AtomicReference<>();
        ConversationSession session = session(capturingLlm(seen));

        List<String> threads = new CopyOnWriteArrayList<>();
        session.setMemory(new MemoryStore() {
            @Override
            public List<String> recall(String userId, String query) {
                threads.add(Thread.currentThread().getName());
                sleep(FETCH);
                return List.of("用户叫小王");
            }

            @Override
            public void remember(String userId, String content) {
            }
        }, "1");
        session.setKnowledge((userId, query) -> {
            threads.add(Thread.currentThread().getName());
            sleep(FETCH);
            return List.of("公司年假 15 天");
        });
        session.setWebSearch((query, count) -> {
            threads.add(Thread.currentThread().getName());
            sleep(FETCH);
            return List.of(new WebSearchProvider.Result("今日头条", "https://x", "下雨", "2026-09-02"));
        }, true, 3);

        String caller = Thread.currentThread().getName();
        CountDownLatch done = new CountDownLatch(1);
        long start = System.nanoTime();
        // "今天" 命中时效信号词, 三路才会都启用
        session.handleTextTurn("今天的年假政策是什么").subscribe(c -> {
        }, e -> done.countDown(), done::countDown);
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // 三路都跑了, 且都不在调用者线程上 —— 事件循环不会被这三次阻塞 IO 占住
        assertThat(threads).hasSize(3);
        assertThat(threads).noneMatch(caller::equals);
        assertThat(threads).allMatch(t -> t.startsWith("boundedElastic"));

        // 并行: 总耗时接近一路(300ms), 而非三路串行(900ms)。留足余量, 只要没退回串行就算过
        assertThat(elapsed).isLessThan(FETCH.multipliedBy(2));

        // 注入顺序不变: 时间 → 记忆 → 知识库 → 联网, 之后才是历史快照(人设 system + 本轮 user)
        List<Message> messages = seen.get();
        assertThat(messages).isNotNull();
        List<String> systems = messages.stream()
                .filter(m -> m.role() == Message.Role.SYSTEM)
                .map(Message::content)
                .toList();
        assertThat(systems).hasSize(5);   // 时间 + 记忆 + 知识库 + 联网 + 人设(来自历史)
        assertThat(systems.get(0)).contains("【实时信息】");
        assertThat(systems.get(1)).contains("用户叫小王");
        assertThat(systems.get(2)).contains("公司年假 15 天");
        assertThat(systems.get(3)).contains("今日头条");
    }

    @Test
    void disabledRetrievalsCostNoThreadHop() throws Exception {
        AtomicReference<List<Message>> seen = new AtomicReference<>();
        ConversationSession session = session(capturingLlm(seen));   // 三路都没设置 = 全 NOOP

        CountDownLatch done = new CountDownLatch(1);
        long start = System.nanoTime();
        session.handleTextTurn("讲个笑话").subscribe(c -> {
        }, e -> done.countDown(), done::countDown);
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        // 纯闲聊不该为"三路都没开"付出任何等待
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
        // 只剩"时间"这一条注入(另一条 system 是历史里的人设), 三路都没往里加东西
        assertThat(seen.get()).isNotNull();
        List<String> systems = seen.get().stream()
                .filter(m -> m.role() == Message.Role.SYSTEM)
                .map(Message::content)
                .toList();
        assertThat(systems).hasSize(2);
        assertThat(systems.get(0)).contains("【实时信息】");
    }
}
