package com.vca.store.call;

import com.vca.orchestrator.call.CallSummary;
import com.vca.orchestrator.call.CallSummaryStore;
import com.vca.store.entity.PhoneCallSummary;
import com.vca.store.mapper.PhoneCallSummaryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

/** 通话小结落库。失败只记日志 —— 摘要主要是推给商家看的, 存不下来不该让推送也跟着失败。 */
public class MyBatisCallSummaryStore implements CallSummaryStore {

    private static final Logger log = LoggerFactory.getLogger(MyBatisCallSummaryStore.class);

    private final PhoneCallSummaryMapper mapper;

    public MyBatisCallSummaryStore(PhoneCallSummaryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean save(CallSummary s) {
        if (s == null) {
            return false;
        }
        Long owner = parse(s.ownerId());
        if (owner == null) {
            log.debug("通话小结没有商家归属, 不落库: callId={}", s.callId());
            return false;
        }
        try {
            PhoneCallSummary row = new PhoneCallSummary();
            row.setCallId(s.callId());
            row.setOwnerId(owner);
            row.setPeerNumber(s.peerNumber());
            row.setCalledNumber(s.calledNumber());
            row.setDurationSec(s.durationSec());
            row.setTurns(s.turns());
            row.setSummary(s.summary());
            row.setIntent(s.intent());
            row.setFollowUp(s.followUp());
            row.setCreatedAt(s.createdAt() == null ? LocalDateTime.now() : s.createdAt());
            return mapper.insert(row) > 0;
        } catch (Exception e) {
            log.warn("通话小结落库失败: callId={}, {}", s.callId(), e.toString());
            return false;
        }
    }

    private static Long parse(String ownerId) {
        try {
            return ownerId == null || ownerId.isBlank() ? null : Long.parseLong(ownerId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
