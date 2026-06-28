package com.vca.orchestrator.vad;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 语义端点判定: 完整度分类 + 自适应静音阈值。 */
class EndpointPolicyTest {

    private static EndpointPolicy.Completeness c(String s) {
        return EndpointPolicy.classify(s);
    }

    @Test
    void incompleteWhenEndingWithConnectorOrFiller() {
        assertThat(c("我想问一下然后")).isEqualTo(EndpointPolicy.Completeness.INCOMPLETE);
        assertThat(c("这道题的解法就是")).isEqualTo(EndpointPolicy.Completeness.INCOMPLETE);
        assertThat(c("嗯那个")).isEqualTo(EndpointPolicy.Completeness.INCOMPLETE);
        assertThat(c("我要把")).isEqualTo(EndpointPolicy.Completeness.INCOMPLETE);   // 介词收尾
        assertThat(c("如果")).isEqualTo(EndpointPolicy.Completeness.INCOMPLETE);
        assertThat(c("I want to")).isEqualTo(EndpointPolicy.Completeness.INCOMPLETE);
    }

    @Test
    void completeWhenEndingWithTerminalPunctuation() {
        assertThat(c("今天天气怎么样？")).isEqualTo(EndpointPolicy.Completeness.COMPLETE);
        assertThat(c("帮我订一张机票。")).isEqualTo(EndpointPolicy.Completeness.COMPLETE);
        assertThat(c("Done!")).isEqualTo(EndpointPolicy.Completeness.COMPLETE);
    }

    @Test
    void neutralForOrdinaryRunningText() {
        assertThat(c("帮我查一下北京的天气")).isEqualTo(EndpointPolicy.Completeness.NEUTRAL);
        assertThat(c("播放周杰伦的歌")).isEqualTo(EndpointPolicy.Completeness.NEUTRAL);
        assertThat(c("")).isEqualTo(EndpointPolicy.Completeness.NEUTRAL);
        assertThat(c(null)).isEqualTo(EndpointPolicy.Completeness.NEUTRAL);
    }

    @Test
    void veryShortIsTreatedAsIncomplete() {
        assertThat(c("我")).isEqualTo(EndpointPolicy.Completeness.INCOMPLETE);
    }

    @Test
    void asciiTailNeedsWordBoundary() {
        // "to" 是连接词收尾→未完, 但含 to 的整词(auto, 以 o 结尾)不应被判为未完
        assertThat(c("auto")).isNotEqualTo(EndpointPolicy.Completeness.INCOMPLETE);
    }

    @Test
    void requiredSilenceAdapts() {
        int base = 1000, min = 400, max = 1600;
        int incomplete = EndpointPolicy.requiredSilenceMs("我想说然后", base, min, max);
        assertThat(incomplete).isGreaterThan(base).isLessThanOrEqualTo(max);
        int complete = EndpointPolicy.requiredSilenceMs("好的。", base, min, max);
        assertThat(complete).isLessThan(base).isGreaterThanOrEqualTo(min);
        assertThat(EndpointPolicy.requiredSilenceMs("查天气", base, min, max)).isEqualTo(base);
    }
}
