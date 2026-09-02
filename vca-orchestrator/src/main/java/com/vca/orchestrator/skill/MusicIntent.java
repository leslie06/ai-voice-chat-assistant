package com.vca.orchestrator.skill;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 点歌意图识别(关键词路由, MVP)。
 *
 * <p>从"本轮用户说了什么"里抽取出想听的歌(歌名/歌手)。命中即短路普通 LLM 对话,
 * 改由 {@code ConversationSession} 通知前端去 QQ 音乐播放。
 *
 * <p>这是确定性的关键词方案: 零额外延迟、好维护, 但语句要大致符合触发词。
 * 想听懂"放首适合下雨天的歌"这类模糊表达, 可后续升级为 LLM function-calling。
 * 触发词与清洗规则都集中在这里, 方便按实际语料调优。
 */
public final class MusicIntent {

    /** 点歌触发词: 后面紧跟歌名/歌手。尽量选音乐场景专属的词以降低误触发。 */
    private static final Pattern PLAY = Pattern.compile(
            "(?:播放|放一首|放首|来一首|来首|点歌|点一首|我想听|我要听)\\s*(.+)");

    /** 去掉抽取结果开头的冗余量词/类目词 */
    private static final Pattern LEADING = Pattern.compile("^(?:的|首|一首|歌曲|歌|音乐)\\s*");
    /** 去掉结尾的语气词/类目词/标点 */
    private static final Pattern TRAILING = Pattern.compile("(?:这首歌|的歌|这首|歌曲|的音乐|音乐|吧|呗|啊|谢谢|[。！？!?,，.\\s])+$");

    /**
     * 描述性请求标记: 命中则<b>不</b>走正则快路径, 放过去交给 LLM 的 {@code play_music} 工具,
     * 由模型把"适合下雨天的歌""伤感一点的"这类模糊意图理解成一首具体的歌/歌手再点播。
     * 这些词几乎只出现在"描述一类歌"而非"报歌名"里(歌名含"的", 如"周杰伦的晴天", 不在此列)。
     */
    private static final Pattern DESCRIPTIVE = Pattern.compile(
            "适合|的时候|心情|那种|类似|风格|曲风|随便|来点|放点|一点|带感|燃一?点|安静|舒缓|抒情|"
                    + "伤感|悲伤|欢快|轻快|治愈|放松|轻松|浪漫|助眠|励志|怀旧|根据");

    /** 控歌命令。调用方(编排层)据当前播放状态决定哪些可执行, 见 ConversationSession#musicControlAllowed。 */
    public static final String CONTROL_NEXT = "next";
    public static final String CONTROL_PREVIOUS = "previous";
    public static final String CONTROL_PAUSE = "pause";
    public static final String CONTROL_RESUME = "resume";
    public static final String CONTROL_STOP = "stop";

    /** 全部控歌动作。接入层据此判断"这是个动作, 不是要搜的歌"。 */
    private static final java.util.Set<String> CONTROL_ACTIONS = java.util.Set.of(
            CONTROL_NEXT, CONTROL_PREVIOUS, CONTROL_PAUSE, CONTROL_RESUME, CONTROL_STOP);

    /**
     * 是否是控歌动作(而非点歌)。接入层必须先问这一句再决定要不要去音源搜歌 ——
     * 控歌没有歌名, 拿空串去搜必然搜不到, 用户看到的就是莫名其妙的"没找到《》"。
     */
    public static boolean isControl(String action) {
        return action != null && CONTROL_ACTIONS.contains(action);
    }

    /**
     * 控歌命令的整句模式。<b>刻意用整句锚定(^…$)而不是 find</b>: "下一首歌是什么""你能帮我切歌吗"
     * 都含命令词, 用 find 会把正常提问劫持成命令。前后的客套与语气词由 {@link #stripPolite} 先剥掉。
     *
     * <p>顺序即优先级(LinkedHashMap): 先匹配到的先返回。
     */
    private static final Map<String, Pattern> CONTROLS = new LinkedHashMap<>();

    static {
        CONTROLS.put(CONTROL_NEXT, Pattern.compile(
                "^(?:切歌|切下一首|切一首|下一首|下一曲|下首|换一首|换首|换个|跳过)(?:这首)?(?:歌曲|歌|音乐)?$"));
        CONTROLS.put(CONTROL_PREVIOUS, Pattern.compile(
                "^(?:上一首|上一曲|上首|前一首|返回上一首|回上一首)(?:歌曲|歌|音乐)?$"));
        CONTROLS.put(CONTROL_PAUSE, Pattern.compile(
                "^(?:暂停|先暂停|暂停播放|暂停音乐|停一下|先停)(?:歌曲|歌|音乐)?$"));
        // "继续"是日常高频词("继续说""继续讲"), 单说时只在<b>音乐已暂停</b>的状态下才当命令 —— 门闸在编排层
        CONTROLS.put(CONTROL_RESUME, Pattern.compile(
                "^(?:继续|接着放|接着听|继续放|继续听|继续播放|恢复播放|重新播放)(?:歌曲|歌|音乐)?$"));
        // 注意这里写的是"别放/不放/不听"而非"别放了": 语气词"了"已被 stripPolite 剥掉, 带"了"的写法永远匹配不上。
        // 不收"停"这种单字: 太短, ASR 噪声里容易误命中, 且更像是让助手别说了
        CONTROLS.put(CONTROL_STOP, Pattern.compile(
                "^(?:停止播放|停止音乐|停止播放音乐|别放|不放|不听|关掉音乐|关闭音乐|关掉歌)(?:歌曲|歌|音乐)?$"));
    }

    /** 命令前后的客套/语气词, 剥掉再做整句匹配。 */
    private static final Pattern POLITE_PREFIX = Pattern.compile("^(?:请|帮我|给我|麻烦|我想|我要)?(?:听)?\\s*");
    private static final Pattern POLITE_SUFFIX = Pattern.compile("(?:吧|呗|啊|了|谢谢|一下|[。！？!?,，.\\s])+$");

    /**
     * 解析控歌命令。<b>本方法只认句式, 不判状态</b> —— 某个命令此刻能不能执行(如"继续"只在暂停时有意义)
     * 由编排层据播放状态决定, 这样句式规则保持纯函数、可单测。
     *
     * @return 命中的动作(如 {@link #CONTROL_NEXT}), 非控歌命令返回 {@link Optional#empty()}
     */
    public Optional<String> parseControl(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String t = stripPolite(text.trim());
        for (Map.Entry<String, Pattern> e : CONTROLS.entrySet()) {
            if (e.getValue().matcher(t).matches()) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }

    private static String stripPolite(String text) {
        String t = POLITE_SUFFIX.matcher(text).replaceAll("");
        return POLITE_PREFIX.matcher(t).replaceFirst("").trim();
    }

    /**
     * 解析点歌意图。
     *
     * @return 想听的歌(已清洗的查询词), 非点歌或抽不出歌名时返回 {@link Optional#empty()}
     */
    public Optional<String> parsePlay(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher m = PLAY.matcher(text.trim());
        if (!m.find()) {
            return Optional.empty();
        }
        String q = m.group(1).trim();
        // 模糊描述(适合下雨天/伤感一点/某心情…)不在这里硬截歌名, 放过去给 LLM 工具理解成具体歌曲
        if (DESCRIPTIVE.matcher(q).find()) {
            return Optional.empty();
        }
        q = LEADING.matcher(q).replaceAll("");
        q = TRAILING.matcher(q).replaceAll("").trim();
        // 抽出来是空(如"我想听歌")或过长(更像一句正常对话, 非歌名)时不当作点歌
        if (q.isBlank() || q.length() > 30) {
            return Optional.empty();
        }
        return Optional.of(q);
    }
}
