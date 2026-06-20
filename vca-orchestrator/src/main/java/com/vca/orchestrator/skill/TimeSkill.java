package com.vca.orchestrator.skill;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Map;

/**
 * 当前日期时间技能(数据型)。用户问"现在几点""今天几号""星期几"时模型调用,
 * 结果<b>回灌</b>给模型, 由它用自然口语作答(因此能处理"今天离周末还有几天"这类需推理的追问)。
 *
 * <p>纯本地、零外部依赖, 用来演示 function-calling 的完整往返(工具结果 → 模型组织回答)。
 * 时钟可注入便于测试。
 */
public final class TimeSkill implements Skill {

    public static final String NAME = "get_current_time";

    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private final Clock clock;

    public TimeSkill() {
        this(Clock.systemDefaultZone());
    }

    public TimeSkill(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "获取当前的日期和时间。当用户询问现在几点、今天几号、今天星期几等与当前时间相关的问题时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public Mono<SkillResult> execute(Map<String, Object> args) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        String weekday = WEEKDAYS[now.getDayOfWeek().getValue() - 1];
        String text = String.format(Locale.CHINA,
                "现在是 %d年%d月%d日 %s %02d:%02d (时区 %s)",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(), weekday,
                now.getHour(), now.getMinute(),
                now.getZone().getDisplayName(TextStyle.SHORT, Locale.CHINA));
        return Mono.just(SkillResult.feedback(text));
    }
}
