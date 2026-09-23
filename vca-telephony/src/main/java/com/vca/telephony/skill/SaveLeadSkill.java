package com.vca.telephony.skill;

import com.vca.orchestrator.skill.Skill;
import com.vca.orchestrator.skill.SkillResult;
import com.vca.orchestrator.lead.Lead;
import com.vca.orchestrator.lead.LeadStore;
import com.vca.orchestrator.merchant.Industry;
import com.vca.telephony.session.CallConversationFactory.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 留资/预约登记(数据型)。客户表达了想预约、想让人回电、或留下了联系方式时, 模型调用本工具把信息记下来。
 *
 * <p><b>按通话建实例</b>(不是进程级单例): 通话 id、商家、来电号码都是这一路通话的事实, 不该让模型去传 ——
 * 模型只负责把客户说的姓名/意向/时间填进来, 填错的空间越小越好。
 *
 * <p>回灌而不是终结回合: 存完要让模型用自己的话确认("好的王先生, 周六上午给您留位"), 比写死一句自然。
 */
public final class SaveLeadSkill implements Skill {

    public static final String NAME = "save_lead";

    private static final Logger log = LoggerFactory.getLogger(SaveLeadSkill.class);

    private final LeadStore store;
    private final CallContext call;
    private final String ownerId;
    private final Industry industry;

    public SaveLeadSkill(LeadStore store, CallContext call, String ownerId) {
        this(store, call, ownerId, null);
    }

    /** @param industry 商家行业, 决定"意向"字段怎么向模型解释(诊所是想做的项目, 培训机构是想学的课程); null 按其他商家 */
    public SaveLeadSkill(LeadStore store, CallContext call, String ownerId, Industry industry) {
        this.store = store == null ? LeadStore.NOOP : store;
        this.call = call;
        this.ownerId = ownerId;
        this.industry = industry == null ? Industry.GENERIC : industry;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "登记客户的预约或留资信息。只要客户表达了想预约、想到店、想让人回电, 或主动留下了姓名/电话/"
                + "诉求/方便的时间, 就调用本工具把已知信息记下来 —— 不必等信息齐全, 缺的可以之后补。"
                + "不要为了调用本工具而盘问客户, 只记他说过的。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of("type", "string", "description", "客户称呼, 如“王先生”; 没说就留空"));
        props.put("phone", Map.of("type", "string",
                "description", "客户留的回电号码; 没说就留空(系统已记录来电号码)"));
        props.put("intent", Map.of("type", "string", "description", industry.leadIntentHint()));
        props.put("preferred_time", Map.of("type", "string",
                "description", "期望到店/回电时间, 用客户的原话即可, 如“这周六上午”"));
        props.put("note", Map.of("type", "string", "description", "其它需要转告商家的信息; 没有就留空"));
        return Map.of("type", "object", "properties", props, "required", java.util.List.of());
    }

    @Override
    public Mono<SkillResult> execute(Map<String, Object> args) {
        return Mono.fromSupplier(() -> {
            Lead lead = new Lead(call.callId(), ownerId, call.peerNumber(), call.calledNumber(),
                    text(args, "name"), text(args, "phone"), text(args, "intent"),
                    text(args, "preferred_time"), text(args, "note"), LocalDateTime.now());
            boolean saved;
            try {
                saved = store.save(lead);
            } catch (RuntimeException e) {
                log.warn("[{}] 线索保存失败: {}", call.callId(), e.toString());
                saved = false;
            }
            log.info("[{}] 留资: 意向={}, 称呼={}, 回电={}, 时间={}, 已保存={}",
                    call.callId(), lead.intent(), lead.name(), lead.phone(), lead.preferredTime(), saved);
            return SkillResult.feedback(saved
                    ? "已登记。请用一句话向客户确认已记下, 并告诉他稍后会有人联系确认。"
                    : "登记失败(系统问题)。请告诉客户稍后会有人回电, 不要说系统出错。");
        });
    }

    private static String text(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        String s = v == null ? null : String.valueOf(v).trim();
        return s == null || s.isEmpty() || "null".equals(s) ? null : s;
    }
}
