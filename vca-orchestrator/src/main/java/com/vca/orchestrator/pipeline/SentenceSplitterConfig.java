package com.vca.orchestrator.pipeline;

/**
 * 分句策略参数。
 *
 * @param hardTerminators 句末硬切分字符: 一旦出现立即成句(送 TTS)
 * @param softDelimiters  软切分字符(逗号/顿号): 仅当当前句已较长时才在此切, 以尽早开播
 * @param softCutMinChars 软切分的最小句长: 短于此不在软分隔处切, 避免碎句
 * @param maxChars        强制切分长度: 超过此长度即便没有任何分隔符也切一刀, 防止长句憋住 TTS
 */
public record SentenceSplitterConfig(
        String hardTerminators,
        String softDelimiters,
        int softCutMinChars,
        int maxChars
) {
    public SentenceSplitterConfig {
        if (softCutMinChars <= 0) softCutMinChars = 8;
        if (maxChars <= 0) maxChars = 40;
    }

    /**
     * 中文语音默认值。注意未把 ASCII '.' 列为硬切分符 —— 避免把 "3.14"、"v2.0" 误切。
     */
    public static SentenceSplitterConfig chineseDefault() {
        return new SentenceSplitterConfig("。！？!?；;\n", "，,、", 8, 40);
    }

    boolean isHard(char c) {
        return hardTerminators.indexOf(c) >= 0;
    }

    boolean isSoft(char c) {
        return softDelimiters.indexOf(c) >= 0;
    }
}
