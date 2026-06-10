package com.vca.orchestrator;

import com.vca.orchestrator.pipeline.SentenceSplitter;
import com.vca.orchestrator.pipeline.SentenceSplitterConfig;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class SentenceSplitterTest {

    private final SentenceSplitter splitter = new SentenceSplitter();

    /** 模拟 LLM 逐 token 吐字: 每个字符/片段一个 onNext */
    private Flux<String> tokensOf(String text) {
        return Flux.fromArray(text.split(""));
    }

    @Test
    void splitsAtHardTerminatorsAndFlushesRemainder() {
        // 两个完整句 + 末尾无标点的残留, 残留应在流结束时作为最后一句吐出
        StepVerifier.create(splitter.split(tokensOf("你好。今天天气不错！还有半句")))
                .expectNext("你好。")
                .expectNext("今天天气不错！")
                .expectNext("还有半句")
                .verifyComplete();
    }

    @Test
    void firstSentenceEmittedBeforeStreamCompletes() {
        // 关键: 第一句在 LLM 还没吐完时就应该出来(句子级流水线的核心)
        StepVerifier.create(splitter.split(tokensOf("第一句。第二句。")))
                .expectNext("第一句。")
                .expectNext("第二句。")
                .verifyComplete();
    }

    @Test
    void doesNotSplitOnAsciiDotInNumbers() {
        // ASCII '.' 不是硬终结符, 不应切断 "3.14"
        StepVerifier.create(splitter.split(tokensOf("圆周率是3.14哦。")))
                .expectNext("圆周率是3.14哦。")
                .verifyComplete();
    }

    @Test
    void softCutOnlyWhenClauseLongEnough() {
        // softCutMinChars=8: 长子句在逗号处提前切, 短子句不切
        SentenceSplitter s = new SentenceSplitter(
                new SentenceSplitterConfig("。！？", "，", 8, 40));
        StepVerifier.create(s.split(tokensOf("这是一个比较长的子句，后面还有内容。")))
                .expectNext("这是一个比较长的子句，")   // 长度达标, 在逗号处提前成句
                .expectNext("后面还有内容。")
                .verifyComplete();
    }

    @Test
    void forceCutWhenExceedingMaxChars() {
        // 无任何分隔符的超长串, 到 maxChars 强制切
        SentenceSplitter s = new SentenceSplitter(
                new SentenceSplitterConfig("。", "，", 8, 10));
        StepVerifier.create(s.split(tokensOf("一二三四五六七八九十甲乙丙丁")))
                .expectNext("一二三四五六七八九十")  // 10 字强制切
                .expectNext("甲乙丙丁")              // 残留
                .verifyComplete();
    }
}
