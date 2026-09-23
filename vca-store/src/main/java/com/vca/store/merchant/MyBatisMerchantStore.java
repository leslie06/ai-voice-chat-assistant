package com.vca.store.merchant;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vca.orchestrator.merchant.MerchantProfile;
import com.vca.orchestrator.merchant.MerchantStore;
import com.vca.store.entity.PhoneMerchant;
import com.vca.store.mapper.PhoneMerchantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 商家资料的 MyBatis 实现。
 *
 * <p>写入后同步通知监听者(注册表作废缓存): 诊所在网页上改完资料, 下一通电话就该用新的。
 * 通知在写入线程上同步执行, 监听者只做清缓存这种毫秒级的事, 不阻塞。
 */
public class MyBatisMerchantStore implements MerchantStore {

    private static final Logger log = LoggerFactory.getLogger(MyBatisMerchantStore.class);

    private final PhoneMerchantMapper mapper;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public MyBatisMerchantStore(PhoneMerchantMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<MerchantProfile> findByNumber(String number) {
        if (number == null || number.isBlank()) {
            return Optional.empty();
        }
        PhoneMerchant row = mapper.selectOne(Wrappers.<PhoneMerchant>query()
                .eq("number", number.trim()).eq("enabled", true).last("limit 1"));
        return Optional.ofNullable(row).map(MyBatisMerchantStore::toProfile);
    }

    @Override
    public Optional<MerchantProfile> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(MyBatisMerchantStore::toProfile);
    }

    @Override
    public List<MerchantProfile> listByOwner(long ownerId) {
        return mapper.selectList(Wrappers.<PhoneMerchant>query().eq("owner_id", ownerId).orderByAsc("id"))
                .stream().map(MyBatisMerchantStore::toProfile).toList();
    }

    @Override
    public List<MerchantProfile> listEnabled() {
        return mapper.selectList(Wrappers.<PhoneMerchant>query().eq("enabled", true).orderByAsc("id"))
                .stream().map(MyBatisMerchantStore::toProfile).toList();
    }

    @Override
    public MerchantProfile save(MerchantProfile p) {
        if (p.number().isEmpty()) {
            throw new IllegalArgumentException("接入号不能为空");
        }
        // 接入号全局唯一: 两家店配同一个号, 来电只能落到一家, 另一家永远接不到还不知道为什么
        PhoneMerchant clash = mapper.selectOne(Wrappers.<PhoneMerchant>query()
                .eq("number", p.number()).last("limit 1"));
        if (clash != null && (p.id() == null || !clash.getId().equals(p.id()))) {
            throw new IllegalArgumentException("接入号 " + p.number() + " 已被「" + nz(clash.getName()) + "」使用");
        }
        LocalDateTime now = LocalDateTime.now();
        PhoneMerchant row = toRow(p);
        row.setUpdatedAt(now);
        if (p.id() == null) {
            row.setCreatedAt(now);
            mapper.insert(row);
        } else {
            PhoneMerchant existing = mapper.selectById(p.id());
            if (existing == null || !existing.getOwnerId().equals(p.ownerId())) {
                throw new IllegalArgumentException("商家不存在或不属于当前账号");
            }
            row.setCreatedAt(existing.getCreatedAt());
            mapper.updateById(row);
        }
        MerchantProfile saved = toProfile(mapper.selectById(row.getId()));
        log.info("商家资料已保存: id={}, 接入号={}, 名称={}", saved.id(), saved.number(), saved.label());
        notifyChanged();
        return saved;
    }

    @Override
    public boolean delete(long ownerId, long id) {
        int n = mapper.delete(Wrappers.<PhoneMerchant>query().eq("id", id).eq("owner_id", ownerId));
        if (n > 0) {
            log.info("商家资料已删除: id={}", id);
            notifyChanged();
        }
        return n > 0;
    }

    @Override
    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void notifyChanged() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.warn("商家变更通知失败(忽略): {}", e.toString());
            }
        }
    }

    static MerchantProfile toProfile(PhoneMerchant r) {
        return new MerchantProfile(r.getId(), r.getOwnerId() == null ? 0 : r.getOwnerId(), r.getNumber(),
                r.getName(), !Boolean.FALSE.equals(r.getEnabled()), r.getGreeting(), r.getSystemPrompt(),
                r.getTransferDialString(), r.getSummaryWebhook(), r.getTtsVoice(), r.getAddress(),
                r.getBusinessHours(), r.getPhone(), r.getTransport(), r.getServices(), r.getDoctors(),
                r.getBookingRules(), r.getNotes(), r.getCreatedAt(), r.getUpdatedAt());
    }

    private static PhoneMerchant toRow(MerchantProfile p) {
        PhoneMerchant r = new PhoneMerchant();
        r.setId(p.id());
        r.setOwnerId(p.ownerId());
        r.setNumber(p.number());
        r.setName(p.name());
        r.setEnabled(p.enabled());
        r.setGreeting(p.greeting());
        r.setSystemPrompt(p.systemPrompt());
        r.setTransferDialString(p.transferDialString());
        r.setSummaryWebhook(p.summaryWebhook());
        r.setTtsVoice(p.ttsVoice());
        r.setAddress(p.address());
        r.setBusinessHours(p.businessHours());
        r.setPhone(p.phone());
        r.setTransport(p.transport());
        r.setServices(p.services());
        r.setDoctors(p.doctors());
        r.setBookingRules(p.bookingRules());
        r.setNotes(p.notes());
        return r;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
