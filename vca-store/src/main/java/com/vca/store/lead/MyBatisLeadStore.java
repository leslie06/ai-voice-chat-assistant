package com.vca.store.lead;

import com.vca.store.entity.PhoneLead;
import com.vca.store.mapper.PhoneLeadMapper;
import com.vca.orchestrator.lead.Lead;
import com.vca.orchestrator.lead.LeadStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

/**
 * 电话线索落库。
 *
 * <p><b>存不下来不能让通话失败</b>: 客户已经在电话里说完了, 这时抛异常只会让 AI 语无伦次。
 * 所以失败只返回 false + 记一行 warn, 由技能改口成"稍后有人回电"。
 */
public class MyBatisLeadStore implements LeadStore {

    private static final Logger log = LoggerFactory.getLogger(MyBatisLeadStore.class);

    private final PhoneLeadMapper mapper;

    public MyBatisLeadStore(PhoneLeadMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean save(Lead lead) {
        if (lead == null) {
            return false;
        }
        Long owner = parseOwner(lead.ownerId());
        if (owner == null) {
            log.warn("线索没有商家归属(vca.telephony.knowledge-owner 未配?), 不落库: callId={}", lead.callId());
            return false;
        }
        try {
            PhoneLead row = new PhoneLead();
            row.setCallId(lead.callId());
            row.setOwnerId(owner);
            row.setPeerNumber(lead.peerNumber());
            row.setCalledNumber(lead.calledNumber());
            row.setName(lead.name());
            row.setPhone(lead.phone());
            row.setIntent(lead.intent());
            row.setPreferredTime(lead.preferredTime());
            row.setNote(lead.note());
            row.setCreatedAt(lead.createdAt() == null ? LocalDateTime.now() : lead.createdAt());
            return mapper.insert(row) > 0;
        } catch (Exception e) {
            log.warn("线索落库失败(不影响通话): callId={}, {}", lead.callId(), e.toString());
            return false;
        }
    }

    private static Long parseOwner(String ownerId) {
        try {
            return ownerId == null || ownerId.isBlank() ? null : Long.parseLong(ownerId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
