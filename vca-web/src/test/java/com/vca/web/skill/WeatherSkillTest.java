package com.vca.web.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vca.orchestrator.skill.SkillResult;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WeatherSkill 的离线分支(声明 + 缺参), 不打网络。高德实况解析靠真实接口, 不在单测覆盖。 */
class WeatherSkillTest {

    private final WeatherSkill skill = new WeatherSkill(new ObjectMapper(), "test-key");

    @Test
    void declaresGetWeatherToolWithRequiredCity() {
        assertEquals(WeatherSkill.NAME, skill.name());
        assertFalse(skill.description().isBlank());
        Map<String, Object> params = skill.parameters();
        assertEquals(List.of("city"), params.get("required"));
        Object props = params.get("properties");
        assertTrue(props instanceof Map<?, ?> m && m.containsKey("city"), "properties 应含 city");
    }

    @Test
    void missingCityFeedsBackInsteadOfGuessing() {
        StepVerifier.create(skill.execute(Map.of()))
                .assertNext(r -> {
                    assertFalse(r.terminal(), "数据型应回灌而非终结");
                    assertTrue(r.content().contains("城市"), r.content());
                })
                .verifyComplete();
    }

    @Test
    void blankCityAlsoFeedsBack() {
        StepVerifier.create(skill.execute(Map.of("city", "   ")))
                .assertNext(r -> assertEquals(SkillResult.feedback(r.content()), r))
                .verifyComplete();
    }
}
