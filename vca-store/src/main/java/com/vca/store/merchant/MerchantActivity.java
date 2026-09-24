package com.vca.store.merchant;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vca.orchestrator.merchant.MerchantProfile;
import com.vca.store.entity.PhoneCallSummary;
import com.vca.store.entity.PhoneLead;
import com.vca.store.mapper.PhoneCallSummaryMapper;
import com.vca.store.mapper.PhoneLeadMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
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

    /** 录音还在不在(列表里决定显不显示"播放") */
    public boolean hasRecording(String callId) {
        return recordingsDir != null && callId != null && CALL_ID.matcher(callId).matches()
                && Files.isRegularFile(recordingsDir.resolve(callId + ".wav"));
    }
}
