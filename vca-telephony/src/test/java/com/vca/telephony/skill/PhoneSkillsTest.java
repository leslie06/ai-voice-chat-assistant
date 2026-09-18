package com.vca.telephony.skill;

import com.vca.orchestrator.lead.Lead;
import com.vca.orchestrator.lead.LeadStore;
import com.vca.orchestrator.skill.SkillResult;
import com.vca.telephony.session.CallConversationFactory.CallContext;
import com.vca.telephony.spi.CallEvent;
import com.vca.telephony.spi.CallLeg;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** 电话专用工具: 留资、转人工、主动挂机。 */
class PhoneSkillsTest {

    private static final class StubLeg implements CallLeg {
        final List<String> transfers = new CopyOnWriteArrayList<>();
        volatile boolean transferable = true;
        volatile String hangupReason;

        @Override
        public String callId() {
            return "call-9";
        }

        @Override
        public String peerNumber() {
            return "13800138000";
        }

        @Override
        public String calledNumber() {
            return "01088886666";
        }

        @Override
        public boolean transfer(String dialString) {
            transfers.add(dialString);
            return transferable;
        }

        @Override
        public Flux<byte[]> inboundAudio() {
            return Flux.never();
        }

        @Override
        public void writeAudio(byte[] pcm) {
        }

        @Override
        public Flux<CallEvent> events() {
            return Flux.never();
        }

        @Override
        public void hangup(String reason) {
            hangupReason = reason;
        }
    }

    private static CallContext context(StubLeg leg, Runnable endCall) {
        return new CallContext(leg.callId(), leg.peerNumber(), leg.calledNumber(), leg, endCall);
    }

    /** 通话事实(通话 id/来电号码/商家)由系统填, 模型只填它从客户那听来的 */
    @Test
    void saveLeadFillsCallFactsItself() {
        StubLeg leg = new StubLeg();
        List<Lead> saved = new CopyOnWriteArrayList<>();
        LeadStore store = lead -> {
            saved.add(lead);
            return true;
        };
        Map<String, Object> args = new HashMap<>();
        args.put("name", "王先生");
        args.put("intent", "种植牙面诊");
        args.put("preferred_time", "这周六上午");
        args.put("phone", "");          // 模型给了空串, 应当当成"没留"

        SkillResult r = new SaveLeadSkill(store, context(leg, () -> { }), "11").execute(args).block();

        assertThat(saved).hasSize(1);
        Lead lead = saved.get(0);
        assertThat(lead.callId()).isEqualTo("call-9");
        assertThat(lead.ownerId()).isEqualTo("11");
        assertThat(lead.peerNumber()).isEqualTo("13800138000");
        assertThat(lead.calledNumber()).isEqualTo("01088886666");
        assertThat(lead.name()).isEqualTo("王先生");
        assertThat(lead.intent()).isEqualTo("种植牙面诊");
        assertThat(lead.phone()).isNull();
        // 数据型: 回灌给模型, 由它用自己的话确认
        assertThat(r.terminal()).isFalse();
        assertThat(r.content()).contains("已登记");
    }

    /** 存不下来不能让客户以为已经约上 */
    @Test
    void saveLeadTellsTheTruthWhenStoreFails() {
        StubLeg leg = new StubLeg();
        LeadStore failing = lead -> {
            throw new IllegalStateException("db down");
        };

        SkillResult r = new SaveLeadSkill(failing, context(leg, () -> { }), "11")
                .execute(Map.of("intent", "洗牙")).block();

        assertThat(r.content()).contains("登记失败");
        assertThat(r.content()).contains("回电");
    }

    @Test
    void transferBridgesTheCall() {
        StubLeg leg = new StubLeg();

        SkillResult r = new TransferToHumanSkill(context(leg, () -> { }), "user/1000")
                .execute(Map.of("reason", "客户要投诉")).block();

        assertThat(leg.transfers).containsExactly("user/1000");
        assertThat(r.terminal()).isTrue();
        assertThat(r.content()).contains("转接");
    }

    /** 桥接失败(线路问题)时不能假装转成功 */
    @Test
    void transferFailureIsNotHiddenFromTheCustomer() {
        StubLeg leg = new StubLeg();
        leg.transferable = false;

        SkillResult r = new TransferToHumanSkill(context(leg, () -> { }), "user/1000")
                .execute(Map.of()).block();

        assertThat(r.content()).contains("没成功");
        assertThat(r.content()).contains("回电");
    }

    /** 没配坐席号码时连桥接都不该尝试 */
    @Test
    void transferWithoutAnAgentNumberOffersACallback() {
        StubLeg leg = new StubLeg();

        SkillResult r = new TransferToHumanSkill(context(leg, () -> { }), "").execute(Map.of()).block();

        assertThat(leg.transfers).isEmpty();
        assertThat(r.content()).contains("回电");
    }

    /** 挂机必须等告别语播完 —— 工具只置位, 不直接挂 */
    @Test
    void endCallDefersTheHangupUntilPlaybackDrains() {
        StubLeg leg = new StubLeg();
        AtomicBoolean requested = new AtomicBoolean();

        SkillResult r = new EndCallSkill(leg.callId(), () -> requested.set(true)).execute(Map.of()).block();

        assertThat(requested).isTrue();      // 只是置位
        assertThat(leg.hangupReason).isNull();   // 没有当场挂断
        assertThat(r.terminal()).isTrue();
        assertThat(r.content()).contains("再见");
    }
}
