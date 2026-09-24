package com.vca.store.gateway;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vca.orchestrator.merchant.GatewayAccount;
import com.vca.orchestrator.merchant.GatewayStore;
import com.vca.store.entity.PhoneGateway;
import com.vca.store.mapper.PhoneGatewayMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 语音网关分机落库。 */
public class MyBatisGatewayStore implements GatewayStore {

    private final PhoneGatewayMapper mapper;

    public MyBatisGatewayStore(PhoneGatewayMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<GatewayAccount> listAll() {
        return mapper.selectList(Wrappers.<PhoneGateway>query().orderByAsc("id")).stream()
                .map(MyBatisGatewayStore::toAccount).toList();
    }

    @Override
    public Optional<GatewayAccount> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(MyBatisGatewayStore::toAccount);
    }

    @Override
    public Optional<GatewayAccount> findByMerchant(long merchantId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<PhoneGateway>query().eq("merchant_id", merchantId)))
                .map(MyBatisGatewayStore::toAccount);
    }

    @Override
    public GatewayAccount save(GatewayAccount a) {
        PhoneGateway row = new PhoneGateway();
        row.setId(a.id());
        row.setMerchantId(a.merchantId());
        row.setAccessNumber(a.accessNumber());
        row.setLabel(a.label() == null ? "" : a.label());
        row.setLineUser(a.lineUser());
        row.setLinePassword(a.linePassword());
        row.setPhoneUser(a.phoneUser());
        row.setPhonePassword(a.phonePassword());
        row.setCreatedAt(a.createdAt() == null ? LocalDateTime.now() : a.createdAt());
        if (a.id() == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        return toAccount(mapper.selectById(row.getId()));
    }

    @Override
    public boolean delete(long id) {
        return mapper.deleteById(id) > 0;
    }

    private static GatewayAccount toAccount(PhoneGateway r) {
        return new GatewayAccount(r.getId(), r.getMerchantId(), r.getAccessNumber(), r.getLabel(),
                r.getLineUser(), r.getLinePassword(), r.getPhoneUser(), r.getPhonePassword(), r.getCreatedAt());
    }
}
