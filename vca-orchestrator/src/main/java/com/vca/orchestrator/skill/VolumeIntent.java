package com.vca.orchestrator.skill;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 音量调节意图("声音大点""小声点")。与 {@link MusicIntent} 分开是因为它<b>不是音乐专属</b>:
 * 没在放歌时说"声音大点"指的是助手说话的音量, 放歌时指的是整体音量, 因此也没有"必须有当前曲目"这道门闸。
 *
 * <p>同样用<b>整句锚定</b>: "怎么调音量""声音大点能听清吗"都含关键词, 用 find 会把正常提问劫持成命令。
 * 纯函数, 可单测。
 */
public final class VolumeIntent {

    public static final String VOLUME_UP = "up";
    public static final String VOLUME_DOWN = "down";

    private static final Map<String, Pattern> COMMANDS = new LinkedHashMap<>();

    static {
        COMMANDS.put(VOLUME_UP, Pattern.compile(
                "^(?:声音大点|声音大一点|大点声|大声点|大声一点|大点儿声|音量大点|音量大一点|"
                        + "调大音量|音量调大|把音量调大|声音太小|太小声|听不清)$"));
        COMMANDS.put(VOLUME_DOWN, Pattern.compile(
                "^(?:声音小点|声音小一点|小点声|小声点|小声一点|小点儿声|音量小点|音量小一点|"
                        + "调小音量|音量调小|把音量调小|声音太大|太大声|太吵)$"));
    }

    /** 命令前后的客套/语气词, 剥掉再做整句匹配(与 MusicIntent 同一套规则)。 */
    private static final Pattern POLITE_PREFIX = Pattern.compile("^(?:请|帮我|给我|麻烦|你|能不能|可以)?\\s*");
    private static final Pattern POLITE_SUFFIX = Pattern.compile("(?:吧|呗|啊|了|谢谢|一下|嘛|吗|[。！？!?,，.\\s])+$");

    private VolumeIntent() {
    }

    /**
     * 解析音量命令。
     *
     * @return {@link #VOLUME_UP} / {@link #VOLUME_DOWN}, 非音量命令返回 {@link Optional#empty()}
     */
    public static Optional<String> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String t = POLITE_SUFFIX.matcher(text.trim()).replaceAll("");
        t = POLITE_PREFIX.matcher(t).replaceFirst("").trim();
        for (Map.Entry<String, Pattern> e : COMMANDS.entrySet()) {
            if (e.getValue().matcher(t).matches()) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }
}
