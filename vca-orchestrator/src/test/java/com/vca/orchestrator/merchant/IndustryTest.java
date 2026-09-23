package com.vca.orchestrator.merchant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IndustryTest {

    @Test
    void unknownOrBlankCodeIsGeneric() {
        assertThat(Industry.of(null)).isEqualTo(Industry.GENERIC);
        assertThat(Industry.of("")).isEqualTo(Industry.GENERIC);
        assertThat(Industry.of("hair-salon")).isEqualTo(Industry.GENERIC);
        assertThat(Industry.of(" Dental ")).as("大小写与空白不计较").isEqualTo(Industry.DENTAL);
    }

    @Test
    void vocabularyPrefixesObeyVendorRulesAndAreDistinct() {
        for (Industry i : Industry.values()) {
            assertThat(i.vocabularyPrefix()).as("%s 的前缀只能小写字母数字且少于 10 个字符", i)
                    .matches("[a-z0-9]{1,9}");
        }
        assertThat(java.util.Arrays.stream(Industry.values()).map(Industry::vocabularyPrefix).distinct().count())
                .as("每个行业一张表, 前缀不能撞").isEqualTo(Industry.values().length);
    }

    @Test
    void baseHotWordsIncludeCommonServiceWordsAndHaveNoDuplicates() {
        for (Industry i : Industry.values()) {
            assertThat(i.baseHotWords()).contains("预约", "转人工", "回电");
            assertThat(i.baseHotWords()).doesNotHaveDuplicates();
            assertThat(i.baseHotWords().size()).isLessThan(HotWordSync.MAX_WORDS);
        }
        assertThat(Industry.DENTAL.baseHotWords()).contains("洗牙", "种植牙");
        assertThat(Industry.EDUCATION.baseHotWords()).contains("试听", "课时").doesNotContain("洗牙");
    }
}
