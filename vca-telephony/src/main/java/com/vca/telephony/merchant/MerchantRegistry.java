package com.vca.telephony.merchant;

import com.vca.orchestrator.merchant.MerchantProfile;
import com.vca.orchestrator.merchant.MerchantStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 按被叫号码找商家。
 *
 * <p><b>为什么是被叫号码</b>: 呼入时它就是"客户打给了哪一家"。FreeSWITCH 在 socket 握手时就把它给了我们
 * ({@code Caller-Destination-Number}), 不需要额外对账。
 *
 * <p><b>两个来源, 库里的优先</b>:
 * <ul>
 *   <li>数据库({@link MerchantStore}): 诊所在网页上自己维护的资料, 这是产品形态。按号码查、结果缓存
 *       {@code cacheTtl}; 资料一有改动, 存储层会通过变更通知让缓存整体作废 —— 改完下一通电话就生效, 不用重启。
 *       查不到的号码也缓存(负缓存), 否则扫号机器人拨的每个随机号都要打一次库。</li>
 *   <li>配置文件({@code vca.telephony.merchants}): 单店或联调时用, 启动定死。库里有同号的以库为准。</li>
 * </ul>
 * 两边都没命中就回退到<b>默认商家</b>(顶层那套配置), 所以什么都不配和以前一样。
 *
 * <p>库里的资料需要一步转换才能给通话用(把结构化资料渲染进人设、把留空的项回退成全局默认),
 * 转换逻辑归配置类, 这里只接一个 {@code converter}。新加载到一家商家时会回调 {@code onLoaded},
 * 电话接入层用它去预合成开场白 —— 不预合成, 这家店的第一通电话接通后要先等几秒合成。
 *
 * <p>查库失败不能让电话失败: 退回配置文件里的商家, 再退回默认, 并把错误记一行。
 */
public final class MerchantRegistry {

    private static final Logger log = LoggerFactory.getLogger(MerchantRegistry.class);

    /** 缓存里一项: 命中的商家, 或 null 表示"库里没有这个号"(负缓存) */
    private record Cached(Merchant merchant, long loadedAtNanos) {
    }

    private final Map<String, Merchant> staticByNumber = new LinkedHashMap<>();
    private final Merchant fallback;
    private final MerchantStore store;
    private final Function<MerchantProfile, Merchant> converter;
    private final long cacheTtlNanos;
    private final Consumer<Merchant> onLoaded;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    /** 只有配置文件那一套(单店/联调/单测) */
    public MerchantRegistry(Merchant fallback, List<Merchant> merchants) {
        this(fallback, merchants, MerchantStore.NOOP, p -> null, Duration.ofSeconds(30), m -> { });
    }

    public MerchantRegistry(Merchant fallback, List<Merchant> merchants, MerchantStore store,
                            Function<MerchantProfile, Merchant> converter, Duration cacheTtl,
                            Consumer<Merchant> onLoaded) {
        this.fallback = fallback;
        this.store = store == null ? MerchantStore.NOOP : store;
        this.converter = converter;
        this.cacheTtlNanos = cacheTtl == null ? Duration.ofSeconds(30).toNanos() : cacheTtl.toNanos();
        this.onLoaded = onLoaded == null ? m -> { } : onLoaded;
        if (merchants != null) {
            for (Merchant m : merchants) {
                if (m == null || m.number() == null || m.number().isBlank()) {
                    log.warn("商家配置缺少接入号码, 已忽略: {}", m == null ? "null" : m.label());
                    continue;
                }
                Merchant old = staticByNumber.put(m.number().trim(), m);
                if (old != null) {
                    log.warn("接入号码 {} 配了多家商家, 后一条生效: {}", m.number(), m.label());
                }
            }
        }
        // 资料一变就清整个缓存: 改的是哪一家不重要, 缓存重新填一遍的代价只是几次查库
        this.store.addChangeListener(this::invalidateAll);
    }

    /** 配置文件里登记的商家数(不含默认、不含库里的) */
    public int size() {
        return staticByNumber.size();
    }

    /** 配置文件里的商家 + 库里所有启用的商家(库里优先去重)。启动时预合成开场白用。 */
    public List<Merchant> all() {
        Map<String, Merchant> merged = new LinkedHashMap<>(staticByNumber);
        try {
            for (MerchantProfile p : store.listEnabled()) {
                Merchant m = converter.apply(p);
                if (m != null && m.number() != null && !m.number().isBlank()) {
                    merged.put(m.number(), m);
                }
            }
        } catch (RuntimeException e) {
            log.warn("读取商家列表失败, 只用配置文件里的: {}", e.toString());
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * @param calledNumber 客户拨打的号码; 线路没送号时为 null
     * @return 命中的商家; 没命中返回默认商家(永不为 null)
     */
    public Merchant resolve(String calledNumber) {
        if (calledNumber == null || calledNumber.isBlank()) {
            return fallback;
        }
        String number = calledNumber.trim();
        Merchant fromStore = lookupStore(number);
        if (fromStore != null) {
            return fromStore;
        }
        Merchant fromConfig = staticByNumber.get(number);
        if (fromConfig != null) {
            return fromConfig;
        }
        // 没匹配上不是错: 可能是还没登记的号码, 也可能这套部署本来就只有一家
        log.debug("被叫号码 {} 没有对应商家, 用默认配置", number);
        return fallback;
    }

    /** 作废全部缓存。资料变更时由存储层触发; 也可由管理接口手动调。 */
    public void invalidateAll() {
        cache.clear();
    }

    private Merchant lookupStore(String number) {
        if (store == MerchantStore.NOOP) {
            return null;
        }
        long now = System.nanoTime();
        Cached c = cache.get(number);
        if (c != null && now - c.loadedAtNanos() < cacheTtlNanos) {
            return c.merchant();
        }
        Merchant loaded;
        try {
            Optional<MerchantProfile> p = store.findByNumber(number);
            loaded = p.map(converter).orElse(null);
        } catch (RuntimeException e) {
            log.warn("按号码查商家失败(退回配置文件): number={}, {}", number, e.toString());
            return c != null ? c.merchant() : null;   // 有过期缓存就先用着, 总比没有强
        }
        cache.put(number, new Cached(loaded, now));
        if (loaded != null && (c == null || c.merchant() == null || !sameGreeting(c.merchant(), loaded))) {
            try {
                onLoaded.accept(loaded);
            } catch (RuntimeException e) {
                log.debug("商家加载回调失败(忽略): {}", e.toString());
            }
        }
        return loaded;
    }

    private static boolean sameGreeting(Merchant a, Merchant b) {
        return a.greeting() == null ? b.greeting() == null : a.greeting().equals(b.greeting());
    }
}
