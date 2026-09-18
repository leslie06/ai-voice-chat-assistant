package com.vca.telephony.skill;

import com.vca.orchestrator.skill.Skill;
import com.vca.orchestrator.skill.SkillResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 主动挂机(动作型)。客户说"没别的了""再见""就这样"时调用, 说完告别语就挂断。
 *
 * <p>为什么需要它: 电话没有"关掉页面"这个动作。客户说完再见往往就把手机拿开了, 不主动挂机的话,
 * 这通电话会一直占着线路、一直烧识别的钱, 直到单通时长上限(默认 5 分钟)。
 *
 * <p><b>不能立刻挂</b>: 告别语还在缓冲里, 直接挂客户只会听到半句。所以只置位, 由 {@code CallSession}
 * 在缓冲排空后执行 —— 这也是它不直接调 {@code CallLeg.hangup} 的原因。
 */
public final class EndCallSkill implements Skill {

    public static final String NAME = "end_call";

    private static final Logger log = LoggerFactory.getLogger(EndCallSkill.class);

    private final String callId;
    private final Runnable hangupAfterPlayback;

    public EndCallSkill(String callId, Runnable hangupAfterPlayback) {
        this.callId = callId;
        this.hangupAfterPlayback = hangupAfterPlayback;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "结束通话。客户明确表示没有其它问题了、说再见/就这样/不用了, 或事情已经办完且客户没有新问题时调用。"
                + "调用后你说的那句告别语会先播完再挂断。客户还在问问题时不要调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    @Override
    public Mono<SkillResult> execute(Map<String, Object> args) {
        return Mono.fromRunnable(() -> {
            log.info("[{}] AI 判断通话可以结束, 告别语播完后挂机", callId);
            hangupAfterPlayback.run();
        }).thenReturn(SkillResult.reply("好的，感谢您的来电，再见。"));
    }
}
