package com.vca.orchestrator.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 识别错误率(CER/WER)。把一句参考文本与一句识别结果对齐, 数出替换/删除/插入三类错误。
 *
 * <p>为什么要有它: 换 ASR 模型、改采样率、动 VAD 预滚, 到底是变好还是变坏, 靠听是分不出来的 ——
 * 一两个字的差异在体感上等于没有, 但在长会话里会累积成"它老是听错"。有了这个数, 每次调参可以先看数再决定。
 *
 * <p><b>切分口径</b>(中英混说场景, 见 {@link #tokenize}): 中日韩表意文字<b>逐字</b>成 token,
 * 拉丁字母/数字<b>成串</b>成 token, 标点与空白丢弃。因此纯中文算出来的是 CER(字错率),
 * 纯英文是 WER(词错率), 混说则是两者的自然推广 —— 这正是中文语音评测的通行做法。
 *
 * <p><b>口径必须先固定再比较</b>: 归一化规则(丢标点、大小写、全角半角)一改, 数字就跟着变,
 * 跨版本的对比会失去意义。所以这里的规则写死在代码里而不做成配置, 要改就改代码并重跑整个基线。
 */
public final class ErrorRate {

    private ErrorRate() {
    }

    /**
     * 一次对齐的结果。
     *
     * @param substitutions 替换数(听成了别的字)
     * @param deletions     删除数(漏听)
     * @param insertions    插入数(多听出来的字)
     * @param refLength     参考文本的 token 数(错误率的分母)
     */
    public record Result(int substitutions, int deletions, int insertions, int refLength) {

        public int errors() {
            return substitutions + deletions + insertions;
        }

        /** 错误率 =(替换+删除+插入)/ 参考长度。参考为空时: 有插入记 1.0, 否则 0。 */
        public double rate() {
            if (refLength == 0) {
                return insertions == 0 ? 0.0 : 1.0;
            }
            return (double) errors() / refLength;
        }

        static Result zero() {
            return new Result(0, 0, 0, 0);
        }

        Result plus(Result o) {
            return new Result(substitutions + o.substitutions, deletions + o.deletions,
                    insertions + o.insertions, refLength + o.refLength);
        }
    }

    /**
     * 语料级错误率: <b>先把各句的错误数与参考长度分别求和, 再相除</b>。
     *
     * <p>不能对逐句错误率取平均 —— 那会让"两个字的短句错一个字"(50%)和"三十个字的长句错一个字"(3%)
     * 在总分里权重相同, 短句的噪声会主导整个指标。这是语音评测里最常见的一处算错。
     */
    public static Result aggregate(Collection<Result> results) {
        Result sum = Result.zero();
        for (Result r : results) {
            sum = sum.plus(r);
        }
        return sum;
    }

    /** 对齐一对(参考, 识别结果), 返回三类错误的计数。 */
    public static Result compare(String reference, String hypothesis) {
        List<String> ref = tokenize(reference);
        List<String> hyp = tokenize(hypothesis);
        return align(ref, hyp);
    }

    /**
     * 编辑距离对齐(Levenshtein), 回溯出替换/删除/插入各自的数量。
     * 三种操作等代价 —— 语音评测的标准口径, 不给替换加权。
     */
    private static Result align(List<String> ref, List<String> hyp) {
        int n = ref.size();
        int m = hyp.size();
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            d[i][0] = i;   // 全删
        }
        for (int j = 0; j <= m; j++) {
            d[0][j] = j;   // 全插
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int sub = d[i - 1][j - 1] + (ref.get(i - 1).equals(hyp.get(j - 1)) ? 0 : 1);
                int del = d[i - 1][j] + 1;
                int ins = d[i][j - 1] + 1;
                d[i][j] = Math.min(sub, Math.min(del, ins));
            }
        }
        // 回溯: 优先走"匹配/替换"这条对角线, 保证同代价路径下的分解稳定可复现
        int s = 0;
        int del = 0;
        int ins = 0;
        int i = n;
        int j = m;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0) {
                boolean same = ref.get(i - 1).equals(hyp.get(j - 1));
                if (d[i][j] == d[i - 1][j - 1] + (same ? 0 : 1)) {
                    if (!same) {
                        s++;
                    }
                    i--;
                    j--;
                    continue;
                }
            }
            if (i > 0 && d[i][j] == d[i - 1][j] + 1) {
                del++;
                i--;
                continue;
            }
            ins++;
            j--;
        }
        return new Result(s, del, ins, n);
    }

    /**
     * 切分成比较用的 token。
     * <ul>
     *   <li>中日韩表意文字: 逐字一个 token(中文没有词边界, 逐字是唯一无歧义的口径);</li>
     *   <li>拉丁字母与数字: 连续的一串算一个 token("2026" "wifi" 各算一个, 不拆成字符);</li>
     *   <li>标点、空白、其余符号: 丢弃(ASR 加不加标点属于后处理策略, 不该计入识别错误)。</li>
     * </ul>
     * 拉丁部分统一转小写。
     */
    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flush(run, out);
                out.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) {
                run.append(Character.toLowerCase(c));
            } else {
                flush(run, out);   // 标点/空白: 断开当前拉丁串, 自身丢弃
            }
        }
        flush(run, out);
        return out;
    }

    private static void flush(StringBuilder run, List<String> out) {
        if (run.length() > 0) {
            out.add(run.toString());
            run.setLength(0);
        }
    }

    /** 是否表意文字(含扩展区与兼容区; 假名/谚文按同样的逐字口径处理)。 */
    private static boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || b == Character.UnicodeBlock.HIRAGANA
                || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }
}
