package com.vca.orchestrator.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 错误率计算(CER/WER)。度量本身错了, 后面所有基于它的结论都是错的, 所以逐条钉死。 */
class ErrorRateTest {

    @Test
    void identicalTextHasZeroErrors() {
        ErrorRate.Result r = ErrorRate.compare("今天天气怎么样", "今天天气怎么样");
        assertThat(r.errors()).isZero();
        assertThat(r.rate()).isZero();
        assertThat(r.refLength()).isEqualTo(7);
    }

    @Test
    void countsSubstitutionDeletionAndInsertionSeparately() {
        // 参考 上海天气  / 识别 上海田七  → 两处替换
        assertThat(ErrorRate.compare("上海天气", "上海田七").substitutions()).isEqualTo(2);
        // 漏字 → 删除
        assertThat(ErrorRate.compare("上海天气", "上海天").deletions()).isEqualTo(1);
        // 多字 → 插入
        assertThat(ErrorRate.compare("上海天气", "上海的天气").insertions()).isEqualTo(1);
    }

    @Test
    void punctuationIsIgnoredButCharactersAreNot() {
        // ASR 加不加标点是后处理策略, 不该算成识别错误
        assertThat(ErrorRate.compare("今天天气怎么样", "今天天气怎么样？").errors()).isZero();
        assertThat(ErrorRate.compare("好的，没问题。", "好的没问题").errors()).isZero();
    }

    @Test
    void latinRunsAreOneTokenAndCaseInsensitive() {
        // "wifi" 算一个词而不是四个字符: 否则英文的错误率会被放大四倍
        assertThat(ErrorRate.tokenize("打开 wifi")).containsExactly("打", "开", "wifi");
        assertThat(ErrorRate.compare("打开 WiFi", "打开 wifi").errors()).isZero();
        // 一个英文词听错 = 一次替换(而不是按字母数计)
        assertThat(ErrorRate.compare("play some jazz", "play some jam").substitutions()).isEqualTo(1);
    }

    @Test
    void mixedChineseAndEnglishTokenizesBothWays() {
        assertThat(ErrorRate.tokenize("我想听 taylor swift 的歌"))
                .containsExactly("我", "想", "听", "taylor", "swift", "的", "歌");
    }

    @Test
    void digitsStayTogether() {
        assertThat(ErrorRate.tokenize("2026 年")).containsExactly("2026", "年");
    }

    @Test
    void corpusRateSumsErrorsAndLengthsInsteadOfAveragingRates() {
        // 关键性质: 短句的高错误率不能主导整体。
        // 句 A: 参考 2 字错 1 → 逐句 50%; 句 B: 参考 20 字错 1 → 逐句 5%。
        // 逐句平均会得到 27.5%, 而正确的语料级口径是 2/22 ≈ 9.1%。
        ErrorRate.Result a = ErrorRate.compare("你好", "你号");
        ErrorRate.Result b = ErrorRate.compare(
                "帮我查一下明天从北京飞上海的航班", "帮我查一下明天从北京飞上海的航斑");
        assertThat(a.rate()).isEqualTo(0.5);

        ErrorRate.Result corpus = ErrorRate.aggregate(List.of(a, b));
        double naiveAverage = (a.rate() + b.rate()) / 2;
        assertThat(corpus.rate()).isLessThan(naiveAverage);
        assertThat(corpus.rate())
                .isCloseTo((double) corpus.errors() / corpus.refLength(), within(1e-9));
    }

    @Test
    void emptyReferenceIsHandledWithoutDividingByZero() {
        assertThat(ErrorRate.compare("", "").rate()).isZero();
        assertThat(ErrorRate.compare("", "多出来的").rate()).isEqualTo(1.0);
        assertThat(ErrorRate.compare(null, null).rate()).isZero();
    }

    @Test
    void completelyWrongTranscriptDoesNotExceedReasonableBounds() {
        // 全错但长度相同 → 100%
        ErrorRate.Result r = ErrorRate.compare("一二三四", "五六七八");
        assertThat(r.rate()).isEqualTo(1.0);
        assertThat(r.substitutions()).isEqualTo(4);
    }
}
