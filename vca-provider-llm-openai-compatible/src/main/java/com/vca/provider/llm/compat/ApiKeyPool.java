package com.vca.provider.llm.compat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 极简多 Key 轮询。单厂商账号的并发上限有限, 多 Key 可横向扩配额。
 */
final class ApiKeyPool {

    private final String providerName;
    private final List<String> keys;
    private final AtomicInteger cursor = new AtomicInteger();

    ApiKeyPool(String providerName, List<String> keys) {
        this.providerName = providerName;
        if (keys == null || keys.isEmpty() || keys.stream().allMatch(k -> k == null || k.isBlank())) {
            throw new IllegalArgumentException(providerName + " 未配置 api key");
        }
        this.keys = keys.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(String::trim)
                .toList();
    }

    String next() {
        int idx = Math.floorMod(cursor.getAndIncrement(), keys.size());
        return keys.get(idx);
    }

    int size() {
        return keys.size();
    }

    @Override
    public String toString() {
        return providerName + "ApiKeyPool(size=" + size() + ")";
    }
}
