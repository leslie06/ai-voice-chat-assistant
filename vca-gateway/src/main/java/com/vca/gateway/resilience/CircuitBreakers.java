package com.vca.gateway.resilience;

import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 (能力, 厂商) 维护一组熔断器。每个厂商独立熔断, 互不影响 —— 一家挂了不拖垮其他(舱壁)。
 */
public class CircuitBreakers {

    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final int failureThreshold;
    private final Duration openDuration;

    public CircuitBreakers(int failureThreshold, Duration openDuration) {
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    public boolean allow(Capability cap, VendorType vendor) {
        return breaker(cap, vendor).allowRequest();
    }

    public void recordSuccess(Capability cap, VendorType vendor) {
        breaker(cap, vendor).recordSuccess();
    }

    public void recordFailure(Capability cap, VendorType vendor) {
        breaker(cap, vendor).recordFailure();
    }

    public CircuitBreaker.State state(Capability cap, VendorType vendor) {
        return breaker(cap, vendor).state();
    }

    private CircuitBreaker breaker(Capability cap, VendorType vendor) {
        return breakers.computeIfAbsent(cap.name() + ":" + vendor.name(),
                k -> new CircuitBreaker(failureThreshold, openDuration));
    }
}
