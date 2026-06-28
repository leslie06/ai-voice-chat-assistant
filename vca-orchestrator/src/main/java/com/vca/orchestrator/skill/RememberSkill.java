package com.vca.orchestrator.skill;

import com.vca.orchestrator.memory.MemoryStore;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 长期记忆技能(数据型): 当用户透露了值得长期记住的个人信息/偏好时, 模型调用本工具把它存进长期记忆,
 * 之后每次对话都会作为上下文回灌, 让助手记得。userId 由 {@code ConversationSession} 在调用时注入 args
 * (键 {@link #USER_ID_ARG}) —— 技能本身是单例 Bean, 不持有用户态。
 */
public class RememberSkill implements Skill {

    public static final String NAME = "remember";
    /** ConversationSession 注入当前用户 id 的 args 键(下划线前缀避免与模型参数冲突)。 */
    public static final String USER_ID_ARG = "__user_id";

    private final MemoryStore memory;

    public RememberSkill(MemoryStore memory) {
        this.memory = memory == null ? MemoryStore.NOOP : memory;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "当用户透露了值得长期记住的个人信息或偏好(如姓名、称呼、喜好、习惯、所在城市、重要事实)时调用, "
                + "把它存入长期记忆; 之后的对话即使是新会话也会记得。不要记流水账或一次性的临时信息。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of("content", Map.of(
                        "type", "string",
                        "description", "要长期记住的一句话, 用第三人称简洁陈述, 例如 '用户喜欢周杰伦的歌' 或 '用户在学英语'")),
                "required", List.of("content"));
    }

    @Override
    public Mono<SkillResult> execute(Map<String, Object> args) {
        String userId = str(args.get(USER_ID_ARG));
        String content = str(args.get("content"));
        if (userId == null || userId.isBlank() || content == null || content.isBlank()) {
            return Mono.just(SkillResult.feedback("没记住(缺少内容或未登录)。"));
        }
        memory.remember(userId, content.strip());
        return Mono.just(SkillResult.feedback("好的，我记住了。"));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
