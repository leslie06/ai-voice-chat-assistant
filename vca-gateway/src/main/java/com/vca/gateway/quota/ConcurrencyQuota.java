package com.vca.gateway.quota;

import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.exception.ProviderException;
import reactor.core.publisher.Flux;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 并发配额: 每个 (能力, 厂商) 一个信号量, 限制对该厂商的同时在途流式调用数。
 * 这是中等规模下保护厂商配额(避免吃 429)、并实现"满则故障转移"的关键。
 *
 * <p>本地信号量适用于单节点/多副本(每副本各自限流)。若要全局精确配额, 后续可换 Redis 计数。
 */
public class ConcurrencyQuota {

    private final ConcurrentHashMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    /**
     * 用配额"门"包裹一条流: 订阅时非阻塞抢占一个许可, 抢不到立即以配额异常失败(触发转移);
     * 流终止(完成/出错/取消)时归还许可(且只归还一次)。
     *
     * @param permits 该厂商的最大并发(首次见到该 key 时确定)
     */
    public <T> Flux<T> gate(Capability cap, VendorType vendor, int permits, Flux<T> source) {
        String key = key(cap, vendor);
        Semaphore sem = semaphores.computeIfAbsent(key, k -> new Semaphore(permits));
        return Flux.defer(() -> {
            if (!sem.tryAcquire()) {
                return Flux.error(ProviderException.quota(vendor, cap,
                        "厂商并发已满(" + key + "), 触发故障转移"));
            }
            AtomicBoolean released = new AtomicBoolean(false);
            return source.doFinally(sig -> {
                if (released.compareAndSet(false, true)) {
                    sem.release();
                }
            });
        });
    }

    /** 当前可用许可数(测试/监控用) */
    public int availablePermits(Capability cap, VendorType vendor) {
        Semaphore sem = semaphores.get(key(cap, vendor));
        return sem == null ? -1 : sem.availablePermits();
    }

    private static String key(Capability cap, VendorType vendor) {
        return cap.name() + ":" + vendor.name();
    }
}
