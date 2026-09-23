package com.vca.orchestrator.merchant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 结构化资料 → 给模型看的文本。只输出填了的项; 末尾必须带"没有的别编"这条硬约束。 */
class MerchantProfileTest {

    private static MerchantProfile clinic() {
        return new MerchantProfile(1L, 11, "5000", "美好口腔", true, "", "", "", "", "",
                "东城区东直门南大街 12 号 2 层", "每天 9:00-20:00，除夕初一休息", "010-8888-6666",
                "地铁 2 号线东直门 C 口步行 5 分钟", "洗牙 200-400 元\n种植牙 6800 元起", "张伟 种植 15 年",
                "留称呼和手机号，前台 1 小时内回电确认", "", null, null);
    }

    @Test
    void rendersOnlyFilledFieldsAsPlainLines() {
        String text = clinic().renderProfile();
        assertThat(text).contains("机构名称: 美好口腔")
                .contains("营业时间: 每天 9:00-20:00，除夕初一休息")
                .contains("项目与价格:\n洗牙 200-400 元\n种植牙 6800 元起")
                .contains("预约规则:")
                .doesNotContain("其他说明")        // 没填的项不出现
                .doesNotContain("|").doesNotContain("#");   // 电话里念不了表格和 markdown
        assertThat(text).as("必须有不许编造的硬约束").contains("不要编造");
    }

    @Test
    void emptyProfileRendersNothing() {
        MerchantProfile p = new MerchantProfile(null, 11, "5000", "", true, "", "", "", "", "",
                "", "", "", "", "", "", "", "", null, null);
        assertThat(p.renderProfile()).isEmpty();
        assertThat(p.label()).as("没名字就用接入号称呼").isEqualTo("5000");
    }

    @Test
    void normalizesNullsAndWhitespace() {
        MerchantProfile p = new MerchantProfile(null, 11, "  5000 ", null, true, null, "  ", null, null, null,
                " 地址 ", null, null, null, null, null, null, null, null, null);
        assertThat(p.number()).isEqualTo("5000");
        assertThat(p.name()).isEmpty();
        assertThat(p.systemPrompt()).isEmpty();
        assertThat(p.address()).isEqualTo("地址");
    }
}
