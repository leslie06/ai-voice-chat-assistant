package com.vca.store;

import com.vca.store.embed.CachingEmbedder;
import com.vca.store.embed.Embedder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CachingEmbedder} 的去重语义。要防住的浪费很具体: 一个回合里长期记忆召回与知识库 RAG
 * 检索用的是同一句用户输入, 两路并行发起后会同时打两次一模一样的 embedding 请求。
 */
class CachingEmbedderTest {

    /** 记录被真正调用了几次的假 embedder; 可选在返回前阻塞, 用来制造"在途"窗口。 */
    private static class CountingEmbedder implements Embedder {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch release;

        CountingEmbedder(CountDownLatch release) {
            this.release = release;
        }

        @Override
        public float[] embed(String text) {
            calls.incrementAndGet();
            if (release != null) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return "boom".equals(text) ? null : new float[]{1, 2, 3};
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            calls.incrementAndGet();
            return texts.stream().map(t -> new float[]{9}).toList();
        }
    }

    @Test
    void sameTextIsEmbeddedOnce() {
        CountingEmbedder delegate = new CountingEmbedder(null);
        Embedder embedder = new CachingEmbedder(delegate, 64);

        assertThat(embedder.embed("今天天气")).containsExactly(1, 2, 3);
        assertThat(embedder.embed("今天天气")).containsExactly(1, 2, 3);
        assertThat(embedder.embed("  今天天气  ")).containsExactly(1, 2, 3);   // 首尾空白不算另一句

        assertThat(delegate.calls).hasValue(1);
    }

    @Test
    void concurrentCallersShareOneInFlightRequest() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountingEmbedder delegate = new CountingEmbedder(release);
        Embedder embedder = new CachingEmbedder(delegate, 64);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch started = new CountDownLatch(2);
            var a = pool.submit(() -> {
                started.countDown();
                return embedder.embed("同一句问题");
            });
            var b = pool.submit(() -> {
                started.countDown();
                return embedder.embed("同一句问题");
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);        // 让两个调用都进到 embed 里, 制造真实的"在途"重叠
            release.countDown();

            assertThat(a.get(5, TimeUnit.SECONDS)).containsExactly(1, 2, 3);
            assertThat(b.get(5, TimeUnit.SECONDS)).containsExactly(1, 2, 3);
            // 关键: 并发的同一句只发一次请求, 后到者挂在同一个 future 上等结果
            assertThat(delegate.calls).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failureIsNotCachedSoNextCallRetries() {
        CountingEmbedder delegate = new CountingEmbedder(null);
        Embedder embedder = new CachingEmbedder(delegate, 64);

        assertThat(embedder.embed("boom")).isNull();
        assertThat(embedder.embed("boom")).isNull();
        // 一次网络抖动不该把这个问题永久钉死成"没有向量"
        assertThat(delegate.calls).hasValue(2);
    }

    @Test
    void batchIsPassedThroughWithoutCaching() {
        CountingEmbedder delegate = new CountingEmbedder(null);
        Embedder embedder = new CachingEmbedder(delegate, 64);

        embedder.embedBatch(List.of("切块一", "切块二"));
        embedder.embedBatch(List.of("切块一", "切块二"));
        // 入库切块每条文本各不相同, 缓存只会白占内存, 故直接透传
        assertThat(delegate.calls).hasValue(2);
    }

    @Test
    void cacheIsClearedWhenOverCapacity() {
        CountingEmbedder delegate = new CountingEmbedder(null);
        Embedder embedder = new CachingEmbedder(delegate, 16);   // 下限即 16

        for (int i = 0; i < 20; i++) {
            embedder.embed("q" + i);
        }
        embedder.embed("q0");   // 已被清掉, 重新算
        assertThat(delegate.calls.get()).isEqualTo(21);
    }
}
