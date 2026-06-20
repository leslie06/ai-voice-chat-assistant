package com.vca.orchestrator.skill;

import com.vca.domain.model.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 已注册技能的目录: 按名查找 + 生成下发给模型的 {@link ToolSpec} 列表。
 * 不可变, 进程级共享一份(技能本身无状态)。空注册表时 {@link #isEmpty()} 为真,
 * 编排层据此跳过 function-calling, 退回普通文本对话。
 */
public final class SkillRegistry {

    private final Map<String, Skill> byName;
    private final List<ToolSpec> toolSpecs;

    public SkillRegistry(List<Skill> skills) {
        Map<String, Skill> map = new LinkedHashMap<>();
        List<ToolSpec> specs = new ArrayList<>();
        if (skills != null) {
            for (Skill s : skills) {
                if (s == null || s.name() == null || s.name().isBlank()) {
                    continue;
                }
                if (map.putIfAbsent(s.name(), s) == null) {
                    specs.add(new ToolSpec(s.name(), s.description(), s.parameters()));
                }
            }
        }
        this.byName = Map.copyOf(map);
        this.toolSpecs = List.copyOf(specs);
    }

    public static SkillRegistry empty() {
        return new SkillRegistry(List.of());
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    /** 下发给模型的工具声明(顺序即注册顺序)。 */
    public List<ToolSpec> toolSpecs() {
        return toolSpecs;
    }

    public Optional<Skill> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }
}
