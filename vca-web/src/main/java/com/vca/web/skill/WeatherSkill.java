package com.vca.web.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vca.orchestrator.skill.Skill;
import com.vca.orchestrator.skill.SkillResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 查天气技能(数据型)。模型理解到用户问某地天气/气温/会不会下雨/要不要带伞时调用,
 * 参数是城市名(如"杭州""北京")。执行后把当前实况<b>回灌</b>给模型,
 * 由模型据此用自然口语组织回答(故比直接念数据更顺)——多一次 LLM 往返。
 *
 * <p>数据源用<a href="https://lbs.amap.com/api/webservice/guide/api/weatherinfo">高德开放平台 Web 服务</a>:
 * 国内、稳定、返回现成中文实况(天气/气温/风向/湿度)。高德天气接口的 {@code city} 只认 adcode(行政区编码),
 * 故先用地理编码把中文城市名转成 adcode 再查天气——两步链式请求, 任一步失败都回灌一句"查不到"让模型礼貌兜底。
 *
 * <p>仅当配置了 {@code vca.web.amap-key}(env {@code AMAP_API_KEY})时才注册本技能(见 WebAutoConfiguration)。
 */
public final class WeatherSkill implements Skill {

    public static final String NAME = "get_weather";

    private static final Logger log = LoggerFactory.getLogger(WeatherSkill.class);

    private final WebClient client;
    private final ObjectMapper mapper;
    private final String apiKey;

    public WeatherSkill(ObjectMapper mapper, String apiKey) {
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.client = WebClient.builder().baseUrl("https://restapi.amap.com").build();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "当用户询问某个城市的天气、气温、是否下雨/下雪、是否需要带伞、穿衣建议等时调用。"
                + "city 填用户问的城市名(如\"杭州\"\"北京\")；用户没说城市就向其追问，不要瞎猜。"
                + "问\"现在/今天\"的天气用 type=current；问\"明天/后天/这几天/未来几天/这周\"等用 type=forecast。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "city", Map.of(
                                "type", "string",
                                "description", "要查询天气的城市名，如\"杭州\""),
                        "type", Map.of(
                                "type", "string",
                                "enum", List.of("current", "forecast"),
                                "description", "current=当前实况(默认)；forecast=未来几天预报(今天起约4天)。"
                                        + "问\"明天/后天/这几天/未来/这周\"时用 forecast")),
                "required", List.of("city"));
    }

    @Override
    public Mono<SkillResult> execute(Map<String, Object> args) {
        Object c = args == null ? null : args.get("city");
        String city = c == null ? "" : c.toString().trim();
        if (city.isBlank()) {
            // 模型没给城市: 回灌让它追问, 而不是默认某地
            return Mono.just(SkillResult.feedback("缺少要查询的城市, 请让用户说出想查哪个城市的天气。"));
        }
        Object t = args == null ? null : args.get("type");
        boolean forecast = t != null && "forecast".equalsIgnoreCase(t.toString().trim());
        // 高德天气只认 adcode: 先地理编码拿到城市的 adcode, 再据此查实况/预报
        return adcodeOf(city)
                .flatMap(adcode -> forecast ? forecastOf(city, adcode) : weatherOf(city, adcode))
                .switchIfEmpty(Mono.just(SkillResult.feedback(
                        "没能查到\"" + city + "\"的天气, 请向用户致歉并建议换个城市名再试。")))
                .onErrorResume(e -> {
                    log.warn("天气查询失败: {} ({})", city, e.toString());
                    return Mono.just(SkillResult.feedback(
                            "没能查到\"" + city + "\"的天气(天气服务暂不可用), 请向用户致歉并建议稍后再试。"));
                });
    }

    /** 地理编码: 中文城市名 → adcode; 无结果返回空 Mono。 */
    private Mono<String> adcodeOf(String city) {
        return client.get()
                .uri(b -> b.path("/v3/geocode/geo")
                        .queryParam("key", apiKey)
                        .queryParam("address", city)
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(5))
                .flatMap(json -> {
                    JsonNode geo = read(json).path("geocodes").path(0);
                    String adcode = geo.path("adcode").asText("");
                    return adcode.isBlank() ? Mono.empty() : Mono.just(adcode);
                });
    }

    /** 查实况天气(base): adcode → 一句紧凑中文快照, 回灌给模型组织口语。 */
    private Mono<SkillResult> weatherOf(String city, String adcode) {
        return client.get()
                .uri(b -> b.path("/v3/weather/weatherInfo")
                        .queryParam("key", apiKey)
                        .queryParam("city", adcode)
                        .queryParam("extensions", "base")
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(5))
                .flatMap(json -> {
                    JsonNode live = read(json).path("lives").path(0);
                    if (live.path("weather").asText("").isBlank()) {
                        return Mono.empty();
                    }
                    return Mono.just(SkillResult.feedback(summarize(city, live)));
                });
    }

    /** 查未来几天预报(all): adcode → 逐日中文摘要(今天起约4天), 回灌给模型组织口语。 */
    private Mono<SkillResult> forecastOf(String city, String adcode) {
        return client.get()
                .uri(b -> b.path("/v3/weather/weatherInfo")
                        .queryParam("key", apiKey)
                        .queryParam("city", adcode)
                        .queryParam("extensions", "all")
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(5))
                .flatMap(json -> {
                    JsonNode fc = read(json).path("forecasts").path(0);
                    JsonNode casts = fc.path("casts");
                    if (!casts.isArray() || casts.isEmpty()) {
                        return Mono.empty();
                    }
                    return Mono.just(SkillResult.feedback(summarizeForecast(city, fc)));
                });
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            log.warn("高德响应解析失败: {}", e.toString());
            return mapper.createObjectNode();
        }
    }

    /** 把高德 live 实况抽成一句紧凑中文快照。 */
    private String summarize(String city, JsonNode live) {
        // city 用高德归一化后的名字(如"杭州"→"杭州市"), 没有再回退用户输入
        String name = live.path("city").asText(city);
        StringBuilder sb = new StringBuilder(name).append("当前天气：")
                .append(live.path("weather").asText("")).append("，")
                .append("气温").append(live.path("temperature").asText("?")).append("℃");
        String humidity = live.path("humidity").asText("");
        if (!humidity.isBlank()) {
            sb.append("，湿度").append(humidity).append("%");
        }
        String windDir = live.path("winddirection").asText("");
        String windPower = live.path("windpower").asText("");
        if (!windDir.isBlank()) {
            sb.append("，").append(windDir).append("风");
            if (!windPower.isBlank()) {
                sb.append(windPower).append("级");
            }
        }
        sb.append("。");
        return sb.toString();
    }

    /** 相对日标签: casts[0] 即今天, 故按下标给"今天/明天/后天", 更远的回退到"周X"。 */
    private static final String[] DAY_LABELS = {"今天", "明天", "后天"};
    /** 高德 cast.week: 1=周一 … 7=周日。 */
    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /** 把高德 casts 逐日抽成中文摘要(每天:标签+天气+温度区间), 回灌给模型。 */
    private String summarizeForecast(String city, JsonNode forecast) {
        String name = forecast.path("city").asText(city);
        JsonNode casts = forecast.path("casts");
        StringBuilder sb = new StringBuilder(name).append("未来几天天气：");
        for (int i = 0; i < casts.size(); i++) {
            JsonNode d = casts.get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append(dayLabel(i, d.path("week").asText("")))
                    .append(weather(d.path("dayweather").asText(""), d.path("nightweather").asText("")))
                    .append("，")
                    .append(d.path("nighttemp").asText("?")).append("到")
                    .append(d.path("daytemp").asText("?")).append("℃");
        }
        sb.append("。");
        return sb.toString();
    }

    /** 前三天用"今天/明天/后天", 再远的用星期(week 越界则空)。 */
    private String dayLabel(int index, String week) {
        if (index < DAY_LABELS.length) {
            return DAY_LABELS[index];
        }
        int w = parseInt(week);
        return (w >= 1 && w <= 7) ? WEEKDAYS[w - 1] : "之后";
    }

    /** 白天/夜间天气: 一样则只说一个, 不同则"白天X转夜间Y"。 */
    private String weather(String day, String night) {
        if (night.isBlank() || night.equals(day)) {
            return day;
        }
        if (day.isBlank()) {
            return night;
        }
        return day + "转" + night;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
