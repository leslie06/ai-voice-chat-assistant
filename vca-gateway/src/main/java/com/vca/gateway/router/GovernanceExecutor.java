package com.vca.gateway.router;

import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.exception.ProviderException;
import com.vca.gateway.Candidate;
import com.vca.gateway.quota.ConcurrencyQuota;
import com.vca.gateway.resilience.CircuitBreakers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 治理执行器: 把"候选排序 + 熔断 + 并发配额 + 故障转移"统一收口成一个通用流程,
 * 各能力的受治理 provider 只需提供"如何用某候选发起调用"(invoker)。
 *
 * <p>转移策略:
 * <ul>
 *   <li>熔断打开的候选: 订阅前直接跳过, 试下一个;</li>
 *   <li>配额满: 失败转移到下一候选, 但<b>不</b>记为熔断失败(那是容量问题, 非健康问题);</li>
 *   <li>调用出错且<b>尚未吐出任何元素</b>: 记熔断失败并转移到下一候选;</li>
 *   <li>已经吐过元素再出错: 不再转移(避免重复输出), 直接抛错。</li>
 * </ul>
 *
 * <p>注意: 出错转移会重订阅 invoker 返回的流。对 LLM(输入是静态历史)安全; 对 ASR/TTS
 * (输入是实时热流)只有"订阅前跳过熔断候选"这一类转移是安全的, 实时输入的重放需上层另行保证。
 */
public class GovernanceExecutor {

    private static final Logger log = LoggerFactory.getLogger(GovernanceExecutor.class);

    private final VendorRouter router;
    private final ConcurrencyQuota quota;
    private final CircuitBreakers circuits;

    public GovernanceExecutor(VendorRouter router, ConcurrencyQuota quota, CircuitBreakers circuits) {
        this.router = router;
        this.quota = quota;
        this.circuits = circuits;
    }

    public <T> Flux<T> execute(Capability capability, VendorType requested,
                               Function<Candidate, Flux<T>> invoker) {
        List<Candidate> candidates = router.orderedCandidates(capability, requested);
        if (candidates.isEmpty()) {
            return Flux.error(ProviderException.fatal(requested, capability,
                    "无可用厂商: capability=" + capability + ", requested=" + requested, null));
        }
        return attempt(capability, candidates, 0, invoker);
    }

    private <T> Flux<T> attempt(Capability cap, List<Candidate> list, int idx,
                                Function<Candidate, Flux<T>> invoker) {
        if (idx >= list.size()) {
            VendorType last = list.get(list.size() - 1).vendor();
            return Flux.error(ProviderException.fatal(last, cap, "所有候选厂商均不可用", null));
        }
        Candidate c = list.get(idx);

        // 熔断打开: 订阅前直接跳过
        if (!circuits.allow(cap, c.vendor())) {
            log.warn("熔断打开, 跳过候选 {}:{}", cap, c.vendor());
            return attempt(cap, list, idx + 1, invoker);
        }

        AtomicBoolean emitted = new AtomicBoolean(false);
        Flux<T> guarded = quota.gate(cap, c.vendor(), c.maxConcurrency(),
                Flux.defer(() -> invoker.apply(c)));

        return guarded
                .doOnNext(x -> emitted.set(true))
                .doOnComplete(() -> circuits.recordSuccess(cap, c.vendor()))
                .onErrorResume(err -> {
                    boolean quotaFull = err instanceof ProviderException pe && pe.isQuotaExceeded();
                    if (!quotaFull) {
                        circuits.recordFailure(cap, c.vendor());
                    }
                    boolean canFailover = !emitted.get() && (idx + 1) < list.size();
                    if (canFailover) {
                        log.warn("候选 {}:{} 失败({}), 转移到下一候选",
                                cap, c.vendor(), quotaFull ? "配额满" : err.toString());
                        return attempt(cap, list, idx + 1, invoker);
                    }
                    return Flux.error(err);
                });
    }
}
