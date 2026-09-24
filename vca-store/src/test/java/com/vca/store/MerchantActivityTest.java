package com.vca.store;

import com.vca.orchestrator.merchant.MerchantProfile;
import com.vca.store.entity.PhoneCallSummary;
import com.vca.store.entity.PhoneLead;
import com.vca.store.mapper.PhoneCallSummaryMapper;
import com.vca.store.mapper.PhoneLeadMapper;
import com.vca.store.merchant.MerchantActivity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 商家后台读的通话/线索/录音: 只给自己店的, 接入号回收再分配后也不串; 通话 id 不能拿来穿越目录。 */
class MerchantActivityTest {

    private static final String MINE = "11111111-1111-1111-1111-111111111111";
    private static final String OLD_OWNER = "22222222-2222-2222-2222-222222222222";
    private static final String OTHER_SHOP = "33333333-3333-3333-3333-333333333333";

    @TempDir
    Path recordings;

    private PhoneCallSummaryMapper summaries;
    private PhoneLeadMapper leads;

    private MerchantActivity activity() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:activity" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE phone_call_summary (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY, call_id VARCHAR(64) NOT NULL, owner_id BIGINT NOT NULL,
                      peer_number VARCHAR(32), called_number VARCHAR(32), duration_sec INT NOT NULL, turns INT NOT NULL,
                      summary VARCHAR(1024), intent VARCHAR(8), follow_up VARCHAR(512), created_at TIMESTAMP NOT NULL)""");
            st.execute("""
                    CREATE TABLE phone_lead (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY, call_id VARCHAR(64) NOT NULL, owner_id BIGINT NOT NULL,
                      peer_number VARCHAR(32), called_number VARCHAR(32), name VARCHAR(64), phone VARCHAR(32),
                      intent VARCHAR(255), preferred_time VARCHAR(128), note VARCHAR(512), created_at TIMESTAMP NOT NULL)""");
        }
        var factory = MyBatisSupport.sqlSessionFactory(ds);
        summaries = MyBatisSupport.mapper(factory, PhoneCallSummaryMapper.class);
        leads = MyBatisSupport.mapper(factory, PhoneLeadMapper.class);
        return new MerchantActivity(summaries, leads, recordings);
    }

    private void call(String callId, long owner, String number, LocalDateTime at) {
        PhoneCallSummary s = new PhoneCallSummary();
        s.setCallId(callId);
        s.setOwnerId(owner);
        s.setCalledNumber(number);
        s.setPeerNumber("13800138000");
        s.setDurationSec(32);
        s.setTurns(4);
        s.setSummary("客户预约周六洗牙");
        s.setIntent("A");
        s.setCreatedAt(at);
        summaries.insert(s);
    }

    private static MerchantProfile shop(long owner, String number) {
        return new MerchantProfile(1L, owner, number, "美好口腔", true, "dental", "", "", "", "", "", "",
                "", "", "", "", "", "", "", "", null, null);
    }

    @Test
    void onlyThisShopsCallsAndLeadsAreVisible() throws Exception {
        MerchantActivity a = activity();
        LocalDateTime now = LocalDateTime.now();
        call(MINE, 11, "5000", now);
        call(OLD_OWNER, 99, "5000", now);          // 号码以前属于别的账号
        call(OTHER_SHOP, 12, "5001", now);
        call("44444444-4444-4444-4444-444444444444", 11, "5000", now.minusDays(40));   // 超出时间窗
        PhoneLead lead = new PhoneLead();
        lead.setCallId(MINE);
        lead.setOwnerId(11L);
        lead.setCalledNumber("5000");
        lead.setName("王女士");
        lead.setCreatedAt(now);
        leads.insert(lead);

        assertThat(a.calls(shop(11, "5000"), now.minusDays(30)))
                .extracting(PhoneCallSummary::getCallId).containsExactly(MINE);
        assertThat(a.calls(shop(11, "5000"), null)).as("不限时间就连 40 天前的也有").hasSize(2);
        assertThat(a.leads(shop(11, "5000"), now.minusDays(90)))
                .extracting(PhoneLead::getName).containsExactly("王女士");
        assertThat(a.leads(shop(12, "5001"), null)).isEmpty();
    }

    @Test
    void recordingIsServedOnlyForOwnCallsAndSafeIds() throws Exception {
        MerchantActivity a = activity();
        LocalDateTime now = LocalDateTime.now();
        call(MINE, 11, "5000", now);
        call(OLD_OWNER, 99, "5000", now);
        for (String id : new String[]{MINE, OLD_OWNER}) {
            Files.write(recordings.resolve(id + ".wav"), new byte[]{1, 2, 3});
        }
        Files.write(recordings.getParent().resolve("secret.wav"), new byte[]{9});

        assertThat(a.recording(shop(11, "5000"), MINE)).contains(recordings.resolve(MINE + ".wav"));
        assertThat(a.recording(shop(11, "5000"), OLD_OWNER)).as("号码的前任主人的录音").isEmpty();
        assertThat(a.recording(shop(11, "5000"), "../secret")).as("路径穿越").isEmpty();
        assertThat(a.hasRecording(MINE)).isTrue();

        Files.delete(recordings.resolve(MINE + ".wav"));   // 过了保留期被清理
        assertThat(a.recording(shop(11, "5000"), MINE)).isEmpty();
        assertThat(a.hasRecording(MINE)).isFalse();
    }

    @Test
    void noRecordingDirMeansNoPlayback() throws Exception {
        activity();
        call(MINE, 11, "5000", LocalDateTime.now());
        MerchantActivity noDir = new MerchantActivity(summaries, leads, null);
        assertThat(noDir.recording(shop(11, "5000"), MINE)).isEmpty();
        assertThat(noDir.hasRecording(MINE)).isFalse();
    }
}
