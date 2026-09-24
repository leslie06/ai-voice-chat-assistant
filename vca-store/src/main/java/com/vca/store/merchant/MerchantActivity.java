package com.vca.store.merchant;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vca.orchestrator.merchant.MerchantProfile;
import com.vca.store.entity.PhoneCallSummary;
import com.vca.store.entity.PhoneLead;
import com.vca.store.mapper.PhoneCallSummaryMapper;
import com.vca.store.mapper.PhoneLeadMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 一家门店的电话产出: 通话小结、线索、录音。商家后台页面读的就是这些。
 *
 * <p><b>按"接入号 + 所属账号"两个条件取</b>, 不只按接入号: 接入号会回收再分配(一家店停了, 号给了另一家),
 * 只看号码的话新店会看到旧店客户的电话和手机号。所属账号在落库时就写进了每一行。
 *
 * <p>录音是 FreeSWITCH 写在它的 recordings 目录里的双声道 wav(左 = 来电方, 右 = AI), 文件名就是通话 id。
 * 只有这家店名下有记录(小结或线索)的通话才给听, 通话 id 本身也要长得像 uuid —— 它会拼进文件路径。
 */
public final class MerchantActivity {

    /** FreeSWITCH 通道 uuid。拼路径前必须校验, 防止 ../ 之类的穿越 */
    private static final Pattern CALL_ID = Pattern.compile("^[0-9a-fA-F-]{36}$");
    private static final int MAX_ROWS = 200;

    private final PhoneCallSummaryMapper summaries;
    private final PhoneLeadMapper leads;
    /** 录音目录; null = 没配, 不提供回放 */
    private final Path recordingsDir;

    public MerchantActivity(PhoneCallSummaryMapper summaries, PhoneLeadMapper leads, Path recordingsDir) {
        this.summaries = summaries;
        this.leads = leads;
        this.recordingsDir = recordingsDir;
    }

    /** 最近的通话小结(10 秒以上的通话才有), 新的在前 */
    public List<PhoneCallSummary> calls(MerchantProfile shop, LocalDateTime since) {
        return summaries.selectList(Wrappers.<PhoneCallSummary>query()
                .eq("called_number", shop.number()).eq("owner_id", shop.ownerId())
                .ge(since != null, "created_at", since)
                .orderByDesc("id").last("limit " + MAX_ROWS));
    }

    /** 最近的线索(AI 在通话里记下的称呼、电话、意向、时间), 新的在前 */
    public List<PhoneLead> leads(MerchantProfile shop, LocalDateTime since) {
        return leads.selectList(Wrappers.<PhoneLead>query()
                .eq("called_number", shop.number()).eq("owner_id", shop.ownerId())
                .ge(since != null, "created_at", since)
                .orderByDesc("id").last("limit " + MAX_ROWS));
    }

    /**
     * 这家店某通电话的录音文件。
     *
     * @return 空 = 通话 id 不合法、不属于这家店、没配录音目录, 或文件已按保留期清理
     */
    public Optional<Path> recording(MerchantProfile shop, String callId) {
        if (recordingsDir == null || callId == null || !CALL_ID.matcher(callId).matches()) {
            return Optional.empty();
        }
        boolean ours = summaries.selectCount(Wrappers.<PhoneCallSummary>query().eq("call_id", callId)
                .eq("called_number", shop.number()).eq("owner_id", shop.ownerId())) > 0
                || leads.selectCount(Wrappers.<PhoneLead>query().eq("call_id", callId)
                .eq("called_number", shop.number()).eq("owner_id", shop.ownerId())) > 0;
        if (!ours) {
            return Optional.empty();
        }
        Path file = recordingsDir.resolve(callId + ".wav");
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    /** 所有门店最近的通话小结(运营后台), 新的在前 */
    public List<PhoneCallSummary> callsAll(LocalDateTime since) {
        return summaries.selectList(Wrappers.<PhoneCallSummary>query()
                .ge(since != null, "created_at", since).orderByDesc("id").last("limit " + MAX_ROWS));
    }

    /** 所有门店最近的线索(运营后台), 新的在前 */
    public List<PhoneLead> leadsAll(LocalDateTime since) {
        return leads.selectList(Wrappers.<PhoneLead>query()
                .ge(since != null, "created_at", since).orderByDesc("id").last("limit " + MAX_ROWS));
    }

    /**
     * 按天汇总: 有小结的通话数、意向分布、留资数。shop 为 null 时汇总所有门店(运营总览)。
     * 试点期一家店一天几十通, 取行在内存里汇总比写两套库都认的分组 SQL 简单。
     */
    public Stats stats(MerchantProfile shop, int days) {
        LocalDate today = LocalDate.now();
        LocalDateTime since = today.minusDays(days - 1L).atStartOfDay();
        var callQuery = Wrappers.<PhoneCallSummary>query().select("created_at", "intent", "called_number")
                .ge("created_at", since);
        var leadQuery = Wrappers.<PhoneLead>query().select("created_at", "called_number").ge("created_at", since);
        if (shop != null) {
            callQuery.eq("called_number", shop.number()).eq("owner_id", shop.ownerId());
            leadQuery.eq("called_number", shop.number()).eq("owner_id", shop.ownerId());
        }
        List<PhoneCallSummary> calls = summaries.selectList(callQuery);
        List<PhoneLead> leadRows = leads.selectList(leadQuery);
        Map<String, Integer> intents = new LinkedHashMap<>();
        for (String k : List.of("A", "B", "C", "D")) {
            intents.put(k, 0);
        }
        Map<LocalDate, int[]> byDay = new TreeMap<>();
        for (int i = 0; i < days; i++) {
            byDay.put(today.minusDays(i), new int[2]);
        }
        Map<String, Integer> byNumber = new LinkedHashMap<>();
        for (PhoneCallSummary c : calls) {
            intents.merge(c.getIntent() == null ? "C" : c.getIntent(), 1, Integer::sum);
            int[] d = byDay.get(c.getCreatedAt().toLocalDate());
            if (d != null) {
                d[0]++;
            }
            byNumber.merge(c.getCalledNumber() == null ? "" : c.getCalledNumber(), 1, Integer::sum);
        }
        for (PhoneLead l : leadRows) {
            int[] d = byDay.get(l.getCreatedAt().toLocalDate());
            if (d != null) {
                d[1]++;
            }
        }
        List<Day> daily = new ArrayList<>();
        byDay.forEach((date, v) -> daily.add(new Day(date.toString(), v[0], v[1])));
        return new Stats(calls.size(), intents, leadRows.size(), daily, byNumber);
    }

    /**
     * @param calls    有小结(10 秒以上)的通话数
     * @param intents  意向 A/B/C/D 各多少
     * @param leads    留资条数
     * @param daily    按天(旧的在前)
     * @param byNumber 按接入号的通话数(运营总览用)
     */
    public record Stats(int calls, Map<String, Integer> intents, int leads, List<Day> daily,
                        Map<String, Integer> byNumber) {
    }

    public record Day(String date, int calls, int leads) {
    }

    /** 录音还在不在(列表里决定显不显示"播放") */
    public boolean hasRecording(String callId) {
        return recordingsDir != null && callId != null && CALL_ID.matcher(callId).matches()
                && Files.isRegularFile(recordingsDir.resolve(callId + ".wav"));
    }
}
