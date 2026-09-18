package com.vca.telephony.merchant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按被叫号码找商家。
 *
 * <p><b>为什么是被叫号码</b>: 呼入时它就是"客户打给了哪一家"。FreeSWITCH 在 socket 握手时就把它给了我们
 * ({@code Caller-Destination-Number}), 不需要额外对账 —— 这也是当初从 AudioSocket 换过来的收益之一。
 *
 * <p>没配任何商家、或号码没匹配上时一律回退到<b>默认商家</b>(顶层那套配置), 所以单店部署完全不受影响:
 * 不配 {@code merchants} 就和以前一样。
 *
 * <p>现在是配置驱动、启动时定死。等商家多到需要自助开通, 换成查库 + 缓存即可 —— 调用方只认这个接口。
 */
public final class MerchantRegistry {

    private static final Logger log = LoggerFactory.getLogger(MerchantRegistry.class);

    private final Map<String, Merchant> byNumber = new LinkedHashMap<>();
    private final Merchant fallback;

    public MerchantRegistry(Merchant fallback, List<Merchant> merchants) {
        this.fallback = fallback;
        if (merchants != null) {
            for (Merchant m : merchants) {
                if (m == null || m.number() == null || m.number().isBlank()) {
                    log.warn("商家配置缺少接入号码, 已忽略: {}", m == null ? "null" : m.label());
                    continue;
                }
                Merchant old = byNumber.put(m.number().trim(), m);
                if (old != null) {
                    log.warn("接入号码 {} 配了多家商家, 后一条生效: {}", m.number(), m.label());
                }
            }
        }
    }

    /** 已配置的商家数(不含默认) */
    public int size() {
        return byNumber.size();
    }

    public List<Merchant> all() {
        return List.copyOf(byNumber.values());
    }

    /**
     * @param calledNumber 客户拨打的号码; 线路没送号时为 null
     * @return 命中的商家; 没命中返回默认商家(永不为 null)
     */
    public Merchant resolve(String calledNumber) {
        if (calledNumber == null || calledNumber.isBlank() || byNumber.isEmpty()) {
            return fallback;
        }
        Merchant m = byNumber.get(calledNumber.trim());
        if (m != null) {
            return m;
        }
        // 没匹配上不是错: 可能是还没登记的号码, 也可能这套部署本来就只有一家
        log.debug("被叫号码 {} 没有对应商家, 用默认配置", calledNumber);
        return fallback;
    }
}
