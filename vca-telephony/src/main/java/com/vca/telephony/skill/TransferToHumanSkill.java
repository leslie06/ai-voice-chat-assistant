package com.vca.telephony.skill;

import com.vca.orchestrator.skill.Skill;
import com.vca.orchestrator.skill.SkillResult;
import com.vca.telephony.session.CallConversationFactory.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 转人工(动作型)。客户明确要求找真人、或问到 AI 答不了也不该瞎答的事(投诉、退费、病情判断)时调用。
 *
 * <p>终结回合: 先念一句"稍等, 给您转接", 再让媒体服务器把通话桥接到坐席。桥接之后本进程不再收发音频,
 * 剩下的对话归坐席, 但挂机事件仍会回来, 会话照常收尾落库。
 *
 * <p>没配坐席号码、或接入层不支持转接时, <b>不假装转成功</b> —— 告诉客户转不过去、留下回电方式,
 * 比让他对着静音等更好。
 */
public final class TransferToHumanSkill implements Skill {

    public static final String NAME = "transfer_to_human";

    private static final Logger log = LoggerFactory.getLogger(TransferToHumanSkill.class);

    private final CallContext call;
    private final String dialString;

    /**
     * @param dialString 媒体服务器的拨号串(如 {@code sofia/gateway/trunk/13800138000}); 空 = 没配坐席
     */
    public TransferToHumanSkill(CallContext call, String dialString) {
        this.call = call;
        this.dialString = dialString;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "把电话转接给人工客服。客户明确说要找真人/转人工, 或问到你答不了、也不该替商家拍板的事"
                + "(投诉、退费、具体病情判断、需要改已确认的预约)时调用。能自己答的不要转。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of("reason", Map.of("type", "string",
                        "description", "转接原因, 一句话, 给商家看的")),
                "required", List.of());
    }

    @Override
    public Mono<SkillResult> execute(Map<String, Object> args) {
        return Mono.fromSupplier(() -> {
            String reason = args == null ? null : String.valueOf(args.getOrDefault("reason", ""));
            if (dialString == null || dialString.isBlank() || !call.session().supportsTransfer()) {
                log.info("[{}] 请求转人工但{}, 原因={}", call.callId(),
                        dialString == null || dialString.isBlank() ? "未配坐席号码" : "接入层不支持转接", reason);
                return SkillResult.reply("不好意思，现在没法直接转接，我把您的号码记下来，稍后让同事回电给您可以吗？");
            }
            log.info("[{}] 转人工, 原因={}", call.callId(), reason);
            // 只登记, 由通话会话在确认语播完之后再桥接 —— 桥接之后本进程的声音就送不出去了。
            // 坐席没接的话通话会回到 AI 这边, 由会话补一句"留个称呼, 让同事回电"。
            call.transfer().accept(dialString);
            return SkillResult.reply("好的，我帮您转接人工，请稍等。");
        });
    }
}
