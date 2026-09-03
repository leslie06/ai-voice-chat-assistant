package com.vca.orchestrator.vad;

/**
 * 语义端点判定(自适应断句)。固定静音时长断句的毛病: 太短会把"说一半停顿找词"的人切断, 太长则说完还要干等。
 * 本策略据 <b>ASR 中间转写</b>的"句子完整度"动态调整句尾静音阈值:
 * <ul>
 *   <li>末尾是<b>连词/口头停顿/结构助词</b>(然后…/那个…/我想…/把…) → 多半没说完, <b>拉长</b>静音阈值, 多等一会;</li>
 *   <li>末尾有<b>句末标点</b>(。！？) → 已说完, <b>缩短</b>阈值, 更快响应;</li>
 *   <li>其余(信息不足) → 用基线阈值。</li>
 * </ul>
 * 纯启发式、零额外延迟/成本(不调模型); 只在能拿到 ASR 文本的<b>三段式</b>路径生效。中文为主, 兼顾常见英文连接词。
 */
public final class EndpointPolicy {

    /** 末尾命中即判"未说完"(拉长等待): 连词 / 口头停顿 / 介词或结构助词收尾(明显半句)。 */
    private static final String[] INCOMPLETE_TAILS = {
            // 连词 / 连接性短语
            "然后", "那么", "所以", "但是", "可是", "因为", "而且", "如果", "就是", "就是说", "的话",
            "不过", "还有", "以及", "并且", "或者", "虽然", "即使", "不仅", "比如", "例如", "首先", "其次", "另外",
            // 口头停顿 / 迟疑
            "那个", "这个", "嗯", "呃", "唉", "额", "唔", "就",
            // 介词 / 结构助词收尾 —— 后面显然还要跟成分
            "的", "地", "得", "把", "被", "跟", "和", "与", "对", "向", "给", "让", "在", "从", "到", "为", "由", "用",
            // 常见英文连接词收尾
            "and", "or", "but", "so", "because", "if", "then", "that", "which", "to", "the", "a", "an", "of", "with"
    };

    /**
     * <b>不带标点</b>时判"已说完"的句末信号。加这一组的原因是实测出来的:
     * 真实 ASR 的中间转写基本不带标点, 而原先判 COMPLETE 只认句末标点 —— 于是线上永远判不出
     * COMPLETE, 语义端点只会<b>加</b>延迟不会减(评测报告里"不带标点"那一片提速命中率是 0%)。
     *
     * <p>选词标准是<b>语法功能</b>而非常用度, 只收在句末几乎不可能后接成分的:
     * <ul>
     *   <li>{@code 吗} —— 是非疑问句的句末语气词, 出现即整句问完;</li>
     *   <li>{@code 了} —— 句末的完成/变化语气词("几点了""我知道了""快递到哪了");</li>
     *   <li>疑问谓词收尾({@code 怎么样/怎么办/为什么/多久/多远/多少/哪里/哪儿}) —— 问句的落点;</li>
     *   <li>寒暄收尾({@code 再见/谢谢/拜拜}) —— 独立成句。</li>
     * </ul>
     *
     * <p>刻意<b>不收</b> {@code 吧} 和 {@code 呢}: 它们同样能做停顿词("我觉得吧""怎么说呢"),
     * 收进来会把没说完的人当场切断 —— 那是所有判错里代价最高的一种, 不值得为几十毫秒去赌。
     */
    private static final String[] COMPLETE_TAILS = {
            "吗", "了", "怎么样", "怎么办", "为什么", "多久", "多远", "多少", "哪里", "哪儿",
            "再见", "谢谢", "拜拜"
    };

    /**
     * 句首连词: 命中且整句还很短时判"没说完"。
     *
     * <p>补的是原实现的一个结构性盲区 —— 它只看句<b>尾</b>, 而"如果明天…""虽然这样…""所以我打算…"
     * 这类半句, 提示词全在句<b>首</b>, 尾部什么特征都没有, 于是全被判成 NEUTRAL 按基线等。
     * 这些词引出的是从句或并列的第一支, 主句还没来。
     */
    private static final String[] LEADING_CONNECTIVES = {
            "如果", "虽然", "因为", "即使", "尽管", "不但", "不仅", "首先", "其次", "另外",
            "而且", "所以", "或者", "比如", "既然", "要是", "假如", "除了", "一方面"
    };

    /**
     * 句首连词规则的长度上限(字数)。超过它就不再据句首判"没说完" ——
     * 说得够长时, 从句多半已经说完、主句也跟上了("虽然下雨了但是我还是想出去走走"),
     * 再按半句多等就是白等。取 10 是让它覆盖"连词 + 几个字"的真半句, 不误伤成型的长句。
     */
    private static final int LEADING_CONNECTIVE_MAX_CHARS = 10;

    private EndpointPolicy() {
    }

    /**
     * 据中间转写算本轮句尾应等待的静音时长(ms)。
     *
     * @param interim 当前 ASR 中间转写(可空)
     * @param baseMs  基线静音阈值
     * @param minMs   最短(说完时的下限, 防止过于激进)
     * @param maxMs   最长(没说完时的上限, 防止无限等)
     */
    public static int requiredSilenceMs(String interim, int baseMs, int minMs, int maxMs) {
        Completeness c = classify(interim);
        return switch (c) {
            case COMPLETE -> clamp((int) (baseMs * 0.45), minMs, baseMs);     // 已说完: 缩短, 不超过基线
            case INCOMPLETE -> clamp((int) (baseMs * 1.6), baseMs, maxMs);    // 没说完: 拉长, 不低于基线
            case NEUTRAL -> baseMs;
        };
    }

    enum Completeness { COMPLETE, INCOMPLETE, NEUTRAL }

    /** 句子完整度分类。包级可见以便单测。 */
    static Completeness classify(String interim) {
        if (interim == null) {
            return Completeness.NEUTRAL;
        }
        String t = interim.strip();
        if (t.isEmpty()) {
            return Completeness.NEUTRAL;
        }
        char last = t.charAt(t.length() - 1);
        if (isTerminalPunctuation(last)) {
            return Completeness.COMPLETE;
        }
        // 极短(剥掉标点后 < 2 字)且无句末标点: 多半还没说清, 当未完待续多等等
        String core = stripTrailingPunctuation(t);
        if (core.isEmpty()) {
            return Completeness.NEUTRAL;
        }
        if (core.codePointCount(0, core.length()) < 2) {
            return Completeness.INCOMPLETE;
        }
        String lower = core.toLowerCase();
        // 先判"没说完": 切错的代价远高于多等一会, 冲突时让保守的一方赢。
        for (String tail : INCOMPLETE_TAILS) {
            if (endsWithWord(lower, core, tail)) {
                return Completeness.INCOMPLETE;
            }
        }
        if (startsWithConnectiveAndShort(core)) {
            return Completeness.INCOMPLETE;
        }
        // 再判"已说完": 无标点时靠句末语气词/疑问谓词, 覆盖真实中间转写没有标点的常态。
        for (String tail : COMPLETE_TAILS) {
            if (endsWithWord(lower, core, tail)) {
                return Completeness.COMPLETE;
            }
        }
        return Completeness.NEUTRAL;
    }

    /** 句首是连词且整句还短: 从句刚起头, 主句没来。 */
    private static boolean startsWithConnectiveAndShort(String core) {
        if (core.codePointCount(0, core.length()) > LEADING_CONNECTIVE_MAX_CHARS) {
            return false;
        }
        for (String c : LEADING_CONNECTIVES) {
            if (core.startsWith(c)) {
                return true;
            }
        }
        return false;
    }

    /** 中文直接后缀匹配; 英文需是独立单词收尾(前面是词边界), 避免 "to" 误命中 "Tokyo/auto"。 */
    private static boolean endsWithWord(String lower, String core, String tail) {
        boolean ascii = tail.chars().allMatch(ch -> ch < 128);
        if (!ascii) {
            return core.endsWith(tail);
        }
        if (!lower.endsWith(tail)) {
            return false;
        }
        int idx = lower.length() - tail.length();
        if (idx == 0) {
            return true;
        }
        char before = lower.charAt(idx - 1);
        return !Character.isLetterOrDigit(before);   // 词边界
    }

    private static boolean isTerminalPunctuation(char c) {
        return c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?' || c == '；' || c == ';';
    }

    private static String stripTrailingPunctuation(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (isTerminalPunctuation(c) || c == '，' || c == ',' || c == '、' || c == '…' || c == ' ') {
                end--;
            } else {
                break;
            }
        }
        return s.substring(0, end);
    }

    private static int clamp(int v, int lo, int hi) {
        if (lo > hi) {
            lo = hi;
        }
        return Math.max(lo, Math.min(hi, v));
    }
}
