package com.vca.store.embed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 给 {@link Embedder} 加一层"同一段文本只算一次"的短期缓存 + <b>在途去重</b>。
 *
 * <p>动机来自一个回合里的真实浪费: 长期记忆召回与知识库 RAG 检索用的是<b>同一句</b>用户输入, 却各自
 * 调一次 {@code embed(query)} —— 串行时白白多付一次网络往返的首字延迟, 并行后则是同时打两次同样的请求
 * (多花一份配额, 还更容易吃 429)。这里按文本做 key 收口: 第一个调用者真发请求, 同 key 的并发调用者
 * 挂在同一个 future 上等它的结果。
 *
 * <p>只缓存单条 {@link #embed}(短查询, 复用率高); {@link #embedBatch} 是文档入库切块, 每条文本各不相同,
 * 缓存只会白占内存, 故直接透传。
 *
 * <p>失败(委托返回 null 或抛异常)<b>不缓存</b>, 下次调用重新尝试 —— 否则一次网络抖动会把该问题钉死。
 * 容量超上限时整体清空: 这是个纯加速缓存, 命中率短暂归零没有正确性影响, 不值得为 LRU 引入额外结构。
 */
public class CachingEmbedder implements Embedder {

    private static final Logger log = LoggerFactory.getLogger(CachingEmbedder.class);

    /** 等待"别人正在算"的这次 embedding 的上限; 超时就当失败降级, 绝不把回合卡死。 */
    private static final long WAIT_TIMEOUT_SECONDS = 30;

    private final Embedder delegate;
    private final int maxEntries;
    private final Map<String, CompletableFuture<float[]>> cache = new ConcurrentHashMap<>();

    public CachingEmbedder(Embedder delegate, int maxEntries) {
        this.delegate = delegate;
        this.maxEntries = Math.max(16, maxEntries);
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return delegate.embed(text);
        }
        String key = text.strip();
        CompletableFuture<float[]> mine = null;
        CompletableFuture<float[]> f = cache.get(key);
        if (f == null) {
            mine = new CompletableFuture<>();
            CompletableFuture<float[]> raced = cache.putIfAbsent(key, mine);
            if (raced != null) {
                mine = null;      // 抢输了: 等赢家的结果, 不重复发请求
                f = raced;
            } else {
                f = mine;
            }
        }
        if (mine != null) {
            try {
                float[] v = delegate.embed(key);
                if (v == null) {
                    cache.remove(key, mine);   // 失败不留痕, 下次重试
                }
                mine.complete(v);
                evictIfFull();
                return v;
            } catch (RuntimeException e) {
                cache.remove(key, mine);
                mine.complete(null);           // 让等待者一起降级, 而不是把异常散播出去
                throw e;
            }
        }
        try {
            return f.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("等待同 key 的 embedding 失败(降级为无向量): {}", e.toString());
            return null;
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        return delegate.embedBatch(texts);
    }

    private void evictIfFull() {
        if (cache.size() > maxEntries) {
            cache.clear();
            log.debug("embedding 缓存超过 {} 条, 已清空", maxEntries);
        }
    }
}
