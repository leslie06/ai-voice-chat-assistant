package com.vca.provider.llm.qwen;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 极简多 Key 轮询。单厂商账号的并发上限有限, 多 Key 可横向扩配额。
 * 这里只做无状态 round-robin; gateway 会接管为"按当前并发择优 + 配额计数"。
 */
final class ApiKeyPool {

    private final List<String> keys;
    private final AtomicInteger cursor = new AtomicInteger();

    ApiKeyPool(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("Qwen 未配置 api key (vca.providers.llm.qwen.keys)");
        }
        this.keys = List.copyOf(keys);
    }

    /** 取下一个 Key, 跨线程安全的环形轮询 */
    String next() {
        int idx = Math.floorMod(cursor.getAndIncrement(), keys.size());
        return keys.get(idx);
    }

    int size() {
        return keys.size();
    }
}
