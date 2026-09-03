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

    @Test
    void defaultTuningProducesTheThreeAdvertisedTiers() {
        // 与 application.yml 的默认值一致(base=900, min=400, max=1600)。
        // 这三个数就是注释里承诺给用户的效果, 改动任一默认值都应该在这里先被打醒。
        int base = 900, min = 400, max = 1600;
        assertThat(EndpointPolicy.requiredSilenceMs("今天天气怎么样？", base, min, max)).isEqualTo(405);
        // 无标点也要走快档: 真实 ASR 的中间转写基本不带标点, 只认标点等于线上永远提不了速。
        // (这里原本断言的是 base —— 那正是当时的局限, 评测报告把它量化出来后才补上无标点规则。)
        assertThat(EndpointPolicy.requiredSilenceMs("今天天气怎么样", base, min, max)).isEqualTo(405);
        assertThat(EndpointPolicy.requiredSilenceMs("明天限号吗", base, min, max)).isEqualTo(405);
        // 既无完整信号也无未完信号 → 基线档
        assertThat(EndpointPolicy.requiredSilenceMs("查一下航班", base, min, max)).isEqualTo(base);
        assertThat(EndpointPolicy.requiredSilenceMs("我想问一下然后", base, min, max)).isEqualTo(1440);
        // 句首连词 + 短句: 从句起了头, 主句还没来 → 慢档
        assertThat(EndpointPolicy.requiredSilenceMs("如果明天", base, min, max)).isEqualTo(1440);
        // 但说得够长就不该再按半句等
        assertThat(EndpointPolicy.requiredSilenceMs("虽然下雨了但是我还是想出去走走", base, min, max))
                .isEqualTo(base);
    }

    @Test
    void ambiguousParticlesAreNotTreatedAsComplete() {
        // 吧/呢 既能收尾也能做停顿词("我觉得吧""怎么说呢")。把它们当"说完了"会当场切断用户,
        // 是代价最高的一类判错 —— 宁可退回基线也不赌。
        int base = 900, min = 400, max = 1600;
        assertThat(EndpointPolicy.classify("我觉得吧")).isNotEqualTo(EndpointPolicy.Completeness.COMPLETE);
        assertThat(EndpointPolicy.classify("怎么说呢")).isNotEqualTo(EndpointPolicy.Completeness.COMPLETE);
        assertThat(EndpointPolicy.requiredSilenceMs("我觉得吧", base, min, max)).isGreaterThanOrEqualTo(base);
    }

    @Test
    void everyTierStaysFasterOrSaferThanTheOldFixedThreshold() {
        // 老配置是固定 1200ms。下调基线到 900 之所以安全, 靠的是"没说完"这一档反而等得更久 ——
        // 当初把基线抬到 1200 就是为了这个场景, 现在由专门的检测兜住, 基线不必再为它整体加高。
        int base = 900, min = 400, max = 1600, oldFixed = 1200;
        assertThat(EndpointPolicy.requiredSilenceMs("说完了。", base, min, max)).isLessThan(oldFixed);
        assertThat(EndpointPolicy.requiredSilenceMs("普通一句话", base, min, max)).isLessThan(oldFixed);
        assertThat(EndpointPolicy.requiredSilenceMs("这个嗯那个", base, min, max)).isGreaterThan(oldFixed);
    }
}
