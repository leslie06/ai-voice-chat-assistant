package com.vca.orchestrator.merchant;

import com.vca.domain.spi.VocabularyClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 按行业自动维护识别热词表: 每个行业一张, 词 = 行业基础词 + 该行业下所有启用门店的店名和项目名。
 *
 * <p><b>为什么按行业而不是按门店</b>: 厂商对每个账号能建的表数有上限, 按门店建撑不了几家;
 * 而热词只是加权, 同行业的店共用一张表互不妨碍 —— 客户打给 A 店时把 B 店的名字也拉高一点先验,
 * 没有任何坏处。表数于是只与行业数挂钩, 门店随便加。
 *
 * <p><b>怎么做到重启不重建</b>: 建表时给每个行业一个固定前缀({@link Industry#vocabularyPrefix()}),
 * 厂商把前缀编进表 id; 启动时按前缀列一下就找回自己的表, 查出词表比对, 一样就直接用, 不一样才改。
 * 不需要在库里另存"行业 → 表 id"的映射。
 *
 * <p><b>什么时候跑</b>: 启动时一次; 之后门店资料一有增删改, 延迟 {@code debounce} 合并一次(连续改十个字段
 * 只跑一遍)。每次都是全量比对, 幂等。厂商接口失败只记 warn, 上一张表继续用 —— 热词表不通不能让电话不通。
 *
 * <p>识别时用哪张表由调用方决定: 门店自己配了表就用门店的, 否则 {@link #vocabularyIdFor(Industry)}, 再否则全局默认。
 */
public final class HotWordSync implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HotWordSync.class);

    /** 厂商单表上限 500 词, 留余量 */
    static final int MAX_WORDS = 400;

    private final MerchantStore store;
    private final VocabularyClient client;
    private final String targetModel;
    private final ScheduledExecutorService scheduler;
    private final Duration debounce;
    private final Map<Industry, String> ids = new ConcurrentHashMap<>();
    private final AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();
    private final Object syncLock = new Object();

    /**
     * @param targetModel 电话识别用的模型; 热词表必须绑定它, 换模型会自动重建
     * @param scheduler   跑同步的线程(同步是几次 HTTP 往返, 不能占用请求线程); 归本类所有, {@link #close()} 时关掉
     * @param debounce    资料变更后多久合并执行一次
     */
    public HotWordSync(MerchantStore store, VocabularyClient client, String targetModel,
                       ScheduledExecutorService scheduler, Duration debounce) {
        this.store = store;
        this.client = client;
        this.targetModel = targetModel;
        this.scheduler = scheduler;
        this.debounce = debounce == null ? Duration.ofSeconds(3) : debounce;
    }

    /** 挂上资料变更监听并安排第一次同步 */
    public void start() {
        store.addChangeListener(this::requestSync);
        requestSync();
    }

    /** 安排一次同步; 短时间内多次调用合并成一次 */
    public void requestSync() {
        ScheduledFuture<?> next = scheduler.schedule(this::runQuietly, debounce.toMillis(), TimeUnit.MILLISECONDS);
        ScheduledFuture<?> prev = pending.getAndSet(next);
        if (prev != null) {
            prev.cancel(false);
        }
    }

    /** 这个行业当前的热词表 id; 还没建成(或从没同步过)时为空 */
    public Optional<String> vocabularyIdFor(Industry industry) {
        return industry == null ? Optional.empty() : Optional.ofNullable(ids.get(industry));
    }

    /**
     * 同步一遍: 有启用门店的每个行业都保证有一张与当前词集一致的表。同步执行, 供启动流程与单测直接调。
     *
     * @return 这一轮结束后各行业的表 id(只含这一轮处理过的行业)
     */
    public Map<Industry, String> syncNow() {
        synchronized (syncLock) {
            Map<Industry, List<String>> wanted = wantedWords();
            Map<Industry, String> result = new EnumMap<>(Industry.class);
            for (Map.Entry<Industry, List<String>> e : wanted.entrySet()) {
                Industry industry = e.getKey();
                try {
                    String id = ensure(industry, e.getValue());
                    ids.put(industry, id);
                    result.put(industry, id);
                } catch (RuntimeException ex) {
                    // 上一张表(如果有)继续用: 热词表不通不能让电话不通
                    log.warn("热词表同步失败(行业 {}, 继续用旧表 {}): {}", industry.label(), ids.get(industry), ex.toString());
                }
            }
            return result;
        }
    }

    /** 各行业期望的词集: 基础词在前, 再按门店顺序追加店名与项目名, 去重、封顶 */
    Map<Industry, List<String>> wantedWords() {
        Map<Industry, LinkedHashSet<String>> byIndustry = new EnumMap<>(Industry.class);
        for (MerchantProfile p : store.listEnabled()) {
            Industry ind = p.industryPreset();
            LinkedHashSet<String> words = byIndustry.computeIfAbsent(ind, i -> new LinkedHashSet<>(i.baseHotWords()));
            words.addAll(p.hotWords());
        }
        Map<Industry, List<String>> out = new EnumMap<>(Industry.class);
        for (Map.Entry<Industry, LinkedHashSet<String>> e : byIndustry.entrySet()) {
            List<String> list = new ArrayList<>(e.getValue());
            out.put(e.getKey(), list.size() > MAX_WORDS ? list.subList(0, MAX_WORDS) : list);
        }
        return out;
    }

    private String ensure(Industry industry, List<String> words) {
        String id = ids.get(industry);
        if (id == null) {
            List<String> existing = client.list(industry.vocabularyPrefix());
            id = existing.isEmpty() ? null : existing.get(0);
        }
        if (id != null) {
            VocabularyClient.Snapshot current = client.query(id);
            if (!targetModel.equals(current.targetModel())) {
                // 绑错模型的表对识别没用, 换一张; 旧表删掉, 免得占账号的表数配额
                log.info("热词表 {} 绑定的是 {}, 现在识别用 {}, 重建", id, current.targetModel(), targetModel);
                client.delete(id);
                id = null;
            } else if (sameWords(current.words(), words)) {
                log.debug("热词表 {}({}) 已是最新, {} 词", id, industry.label(), words.size());
                return id;
            } else {
                client.update(id, words);
                log.info("热词表已更新: 行业={}, id={}, 词数={}", industry.label(), id, words.size());
                return id;
            }
        }
        id = client.create(industry.vocabularyPrefix(), targetModel, words);
        log.info("热词表已建: 行业={}, id={}, model={}, 词数={}", industry.label(), id, targetModel, words.size());
        return id;
    }

    private static boolean sameWords(List<String> a, List<String> b) {
        Set<String> sa = new HashSet<>(a);
        Set<String> sb = new HashSet<>(b);
        return sa.equals(sb);
    }

    private void runQuietly() {
        try {
            syncNow();
        } catch (RuntimeException e) {
            log.warn("热词表同步异常: {}", e.toString());
        }
    }

    /** 取消还没跑的同步并关掉调度线程(调度器归本类所有) */
    @Override
    public void close() {
        ScheduledFuture<?> p = pending.getAndSet(null);
        if (p != null) {
            p.cancel(false);
        }
        scheduler.shutdownNow();
    }
}
