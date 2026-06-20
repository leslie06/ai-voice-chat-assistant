package com.vca.orchestrator.skill;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 点歌技能(动作型)。模型理解到用户想听歌时调用, 参数是想听的歌名/歌手/描述
 * (如"晴天""周杰伦""适合下雨天的歌")。比旧的正则 {@code MusicIntent} 更能听懂模糊表达。
 *
 * <p>执行即终结回合: 下发一个 {@code music} 动作给客户端(由接入层去音源检索并播放),
 * 并念一句确认。不回灌模型, 省一次往返。明确的点歌仍可走编排层的正则快路径零延迟直达,
 * 只有正则没命中(模糊表达)才由模型决定调到这里。
 */
public final class PlayMusicSkill implements Skill {

    public static final String NAME = "play_music";
    /** 客户端动作类型, 与接入层 onMusicRequest 约定一致 */
    public static final String ACTION_TYPE = "music";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "当用户想听歌、点歌或要求播放音乐时调用。用户给具体歌名/歌手就用它；"
                + "如果用户只给了模糊描述(如\"适合下雨天的歌\"\"安静一点的\"\"伤感的歌\")，"
                + "你要先据此挑一首最贴切的具体歌曲，把 query 填成\"歌名\"或\"歌手 歌名\"，"
                + "不要把描述原样填进去(音源按关键词检索，描述搜不到)。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "要点播的具体歌名，或\"歌手 歌名\"；不要填模糊描述")),
                "required", List.of("query"));
    }

    @Override
    public Mono<SkillResult> execute(Map<String, Object> args) {
        Object q = args == null ? null : args.get("query");
        String query = q == null ? "" : q.toString().trim();
        if (query.isBlank()) {
            // 模型没给歌名: 回灌让它追问, 而不是瞎播
            return Mono.just(SkillResult.feedback("缺少要播放的歌曲, 请让用户说出想听的歌名或歌手。"));
        }
        return Mono.just(SkillResult.action(
                "好的，为您播放音乐" + query,
                ACTION_TYPE,
                Map.of("action", "play", "query", query)));
    }
}
