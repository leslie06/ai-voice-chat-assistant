package com.vca.orchestrator.pipeline;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 实时分句器: 把 LLM 的逐 token 文本流, 边到边切成"可以立即拿去合成"的句子流。
 *
 * <p>这是句子级流水线的关键 —— LLM 还在生成后半句时, 前半句已经被切出来送进 TTS,
 * 因此"首句响应延迟 ≈ 首句生成时间", 而不是整段生成时间。
 *
 * <p>切分规则:
 * <ol>
 *   <li>遇到硬终结符(。！？等)立即成句;</li>
 *   <li>当前句已达 {@code softCutMinChars} 且遇到软分隔符(，、)时成句, 以更早开播;</li>
 *   <li>当前句超过 {@code maxChars} 仍无分隔符则强制切一刀;</li>
 *   <li>流结束时, 把残留缓冲作为最后一句吐出。</li>
 * </ol>
 *
 * <p>线程安全: 每次订阅通过 {@link Flux#defer} 持有独立缓冲, 且 LLM token 流是有序串行的,
 * 因此无需额外同步。
 */
public class SentenceSplitter {

    private final SentenceSplitterConfig config;

    public SentenceSplitter(SentenceSplitterConfig config) {
        this.config = config;
    }

    public SentenceSplitter() {
        this(SentenceSplitterConfig.chineseDefault());
    }

    /**
     * @param tokens LLM 的文本增量流
     * @return 完整句子流(每个元素是一句, 含末尾标点)
     */
    public Flux<String> split(Flux<String> tokens) {
        return Flux.defer(() -> {
            StringBuilder buffer = new StringBuilder();
            return tokens
                    .concatMapIterable(token -> {
                        buffer.append(token);
                        return drain(buffer);
                    })
                    .concatWith(Flux.defer(() -> flushRemainder(buffer)));
        });
    }

    /** 从缓冲区抽出所有已成型的句子, 残留部分留在 buffer 里 */
    private List<String> drain(StringBuilder buffer) {
        List<String> sentences = new ArrayList<>();
        int cut = 0; // 已成句的边界(下一句的起点)
        for (int i = 0; i < buffer.length(); i++) {
            char c = buffer.charAt(i);
            int segLen = i - cut + 1;
            boolean hard = config.isHard(c);
            boolean soft = config.isSoft(c) && segLen >= config.softCutMinChars();
            boolean tooLong = segLen >= config.maxChars();

            if (hard || soft || tooLong) {
                String seg = buffer.substring(cut, i + 1).trim();
                if (hasSpeakableChar(seg)) {
                    sentences.add(seg);
                    cut = i + 1;
                } else if (hard) {
                    // 纯标点(如孤立的 "。"): 丢弃但仍推进边界, 避免卡住
                    cut = i + 1;
                }
            }
        }
        if (cut > 0) {
            buffer.delete(0, cut);
        }
        return sentences;
    }

    /** 流结束: 把残留缓冲作为最后一句 */
    private Flux<String> flushRemainder(StringBuilder buffer) {
        String rest = buffer.toString().trim();
        buffer.setLength(0);
        return hasSpeakableChar(rest) ? Flux.just(rest) : Flux.empty();
    }

    /** 是否包含至少一个非空白、非分隔符的"可读"字符 */
    private boolean hasSpeakableChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && !config.isHard(c) && !config.isSoft(c)) {
                return true;
            }
        }
        return false;
    }
}
