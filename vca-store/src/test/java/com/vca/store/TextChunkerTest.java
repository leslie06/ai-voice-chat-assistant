package com.vca.store;

import com.vca.store.util.TextChunker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 文本切块: 大小约束、句界优先、空白归一。 */
class TextChunkerTest {

    @Test
    void emptyOrBlankYieldsNothing() {
        assertThat(TextChunker.chunk(null)).isEmpty();
        assertThat(TextChunker.chunk("   \n\t  ")).isEmpty();
    }

    @Test
    void shortTextIsSingleChunk() {
        List<String> out = TextChunker.chunk("一句话。");
        assertThat(out).containsExactly("一句话。");
    }

    @Test
    void longTextSplitsIntoBoundedChunks() {
        String sentence = "这是一个用于测试切块的句子。";   // 含句号边界
        String text = sentence.repeat(100);                  // 远超单块
        List<String> out = TextChunker.chunk(text, 50, 10);
        assertThat(out.size()).isGreaterThan(1);
        // 每块不超过 size(允许在句界提前断), 且非空
        assertThat(out).allSatisfy(c -> {
            assertThat(c).isNotBlank();
            assertThat(c.length()).isLessThanOrEqualTo(50);
        });
        // 切块合起来应覆盖全部去空白内容(可有重叠)
        String joined = String.join("", out).replace(" ", "");
        assertThat(joined.length()).isGreaterThanOrEqualTo(text.replace(" ", "").length());
    }

    @Test
    void collapsesWhitespace() {
        List<String> out = TextChunker.chunk("行一\n\n  行二   行三");
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isEqualTo("行一 行二 行三");
    }
}
