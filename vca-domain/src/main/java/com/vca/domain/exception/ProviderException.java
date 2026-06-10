package com.vca.domain.exception;

import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;

/**
 * 厂商调用异常的统一类型。治理层据此决定重试/熔断/故障转移:
 * <ul>
 *   <li>{@code retryable=true} 且非配额类 → 可同厂商重试</li>
 *   <li>{@code quotaExceeded=true} → 换 Key 或换厂商, 不要重试打爆配额</li>
 *   <li>否则 → 熔断并故障转移到备用厂商</li>
 * </ul>
 */
public class ProviderException extends RuntimeException {

    private final VendorType vendor;
    private final Capability capability;
    private final boolean retryable;
    private final boolean quotaExceeded;

    public ProviderException(VendorType vendor, Capability capability,
                             boolean retryable, boolean quotaExceeded,
                             String message, Throwable cause) {
        super(message, cause);
        this.vendor = vendor;
        this.capability = capability;
        this.retryable = retryable;
        this.quotaExceeded = quotaExceeded;
    }

    /** 配额/限流类(HTTP 429 等): 换 Key 或换厂商 */
    public static ProviderException quota(VendorType vendor, Capability cap, String message) {
        return new ProviderException(vendor, cap, false, true, message, null);
    }

    /** 临时性故障(超时/5xx): 可重试 */
    public static ProviderException retryable(VendorType vendor, Capability cap, String message, Throwable cause) {
        return new ProviderException(vendor, cap, true, false, message, cause);
    }

    /** 不可恢复故障(鉴权失败/参数错误): 直接故障转移 */
    public static ProviderException fatal(VendorType vendor, Capability cap, String message, Throwable cause) {
        return new ProviderException(vendor, cap, false, false, message, cause);
    }

    public VendorType vendor() {
        return vendor;
    }

    public Capability capability() {
        return capability;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public boolean isQuotaExceeded() {
        return quotaExceeded;
    }
}
