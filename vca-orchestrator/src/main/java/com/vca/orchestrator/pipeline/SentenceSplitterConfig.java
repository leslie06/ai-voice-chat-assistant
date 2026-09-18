package com.vca.orchestrator.pipeline;

/**
 * 分句策略参数。
 *
 * <p><b>首句单独一套阈值</b>: 一轮回复的体感延迟完全由第一句决定 —— 第一句切出来才能开始合成、开始出声,
 * 后面的句子都在播上一句的时候就合成好了。所以首句用更小的阈值尽早切出去(代价是第一句可能短一点),
 * 之后的句子按正常阈值切, 保证语气连贯。
 *
 * @param hardTerminators      句末硬切分字符: 一旦出现立即成句(送 TTS)
 * @param softDelimiters       软切分字符(逗号/顿号): 仅当当前句已较长时才在此切, 以尽早开播
 * @param softCutMinChars      软切分的最小句长: 短于此不在软分隔处切, 避免碎句
 * @param maxChars             强制切分长度: 超过此长度即便没有任何分隔符也切一刀, 防止长句憋住 TTS
 * @param firstSoftCutMinChars 本轮<b>第一句</b>的软切分最小句长
 * @param firstMaxChars        本轮<b>第一句</b>的强制切分长度
 */
public record SentenceSplitterConfig(
        String hardTerminators,
        String softDelimiters,
        int softCutMinChars,
        int maxChars,
        int firstSoftCutMinChars,
        int firstMaxChars
) {
    public SentenceSplitterConfig {
        if (softCutMinChars <= 0) softCutMinChars = 8;
        if (maxChars <= 0) maxChars = 40;
        if (firstSoftCutMinChars <= 0) firstSoftCutMinChars = softCutMinChars;
        if (firstMaxChars <= 0) firstMaxChars = maxChars;
    }

    /** 兼容旧签名: 首句与后续句同阈值。 */
    public SentenceSplitterConfig(String hardTerminators, String softDelimiters, int softCutMinChars, int maxChars) {
        this(hardTerminators, softDelimiters, softCutMinChars, maxChars, softCutMinChars, maxChars);
    }

    /**
     * 中文语音默认值。注意未把 ASCII '.' 列为硬切分符 —— 避免把 "3.14"、"v2.0" 误切。
     *
     * <p>首句 4 字即可在逗号处切、16 字无标点也强制切: 实测能把"大模型第一个字"到"听见第一声"
     * 这段从约 1.8 秒压到 1 秒以内, 而中文回复的开头(如"好的，"/"今天是星期四，")本来就是可独立成句的短语。
     */
    public static SentenceSplitterConfig chineseDefault() {
        return new SentenceSplitterConfig("。！？!?；;\n", "，,、", 8, 40, 4, 16);
    }

    boolean isHard(char c) {
        return hardTerminators.indexOf(c) >= 0;
    }

    boolean isSoft(char c) {
        return softDelimiters.indexOf(c) >= 0;
    }

    /** 第几句(0 起)该用的软切分最小句长 */
    int softCutMinChars(int emitted) {
        return emitted == 0 ? firstSoftCutMinChars : softCutMinChars;
    }

    /** 第几句(0 起)该用的强制切分长度 */
    int maxChars(int emitted) {
        return emitted == 0 ? firstMaxChars : maxChars;
    }
}
