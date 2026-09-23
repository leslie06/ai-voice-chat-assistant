package com.vca.orchestrator.merchant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构化资料 → 给模型看的文本。只输出填了的项; 末尾必须带"没有的别编"这条硬约束;
 * 行业决定字段叫法与角色说明。
 */
class MerchantProfileTest {

    private static MerchantProfile clinic() {
        return new MerchantProfile(1L, 11, "5000", "美好口腔", true, "dental", "", "", "", "", "", "",
                "东城区东直门南大街 12 号 2 层", "每天 9:00-20:00，除夕初一休息", "010-8888-6666",
                "地铁 2 号线东直门 C 口步行 5 分钟", "洗牙 200-400 元\n种植牙 6800 元起", "张伟 种植 15 年",
                "留称呼和手机号，前台 1 小时内回电确认", "", null, null);
    }

    private static MerchantProfile school() {
        return new MerchantProfile(2L, 12, "5001", "启明少儿英语", true, "education", "", "", "", "", "", "",
                "", "周一到周五 15:00-20:00", "", "",
                "少儿英语启蒙班（3-6 岁）每课时 150 元\n雅思冲刺班 12000 元", "李老师，剑桥少儿英语考官，8 年教龄",
                "", "", null, null);
    }

    @Test
    void rendersOnlyFilledFieldsAsPlainLines() {
        String text = clinic().renderProfile();
        assertThat(text).startsWith("你是「美好口腔」的电话客服。这是一家口腔诊所。")
                .contains("营业时间: 每天 9:00-20:00，除夕初一休息")
                .contains("项目与价格:\n洗牙 200-400 元\n种植牙 6800 元起")
                .contains("医生团队:\n张伟")
                .contains("预约规则:")
                .doesNotContain("其他说明")        // 没填的项不出现
                .doesNotContain("|").doesNotContain("#");   // 电话里念不了表格和 markdown
        assertThat(text).as("必须有不许编造的硬约束").contains("不要编造");
    }

    @Test
    void industryChangesLabelsAndRoleNote() {
        String text = school().renderProfile();
        assertThat(text).contains("这是一家培训机构")
                .contains("课程与费用:\n少儿英语启蒙班")
                .contains("师资:\n李老师")
                .contains("试听")                       // 有意向时往试听引
                .doesNotContain("项目与价格").doesNotContain("医生");
    }

    @Test
    void unknownIndustryFallsBackToGeneric() {
        MerchantProfile p = new MerchantProfile(null, 11, "5000", "老王修车", true, "auto-repair", "", "", "", "", "", "",
                "", "", "", "", "补胎 30 元", "", "", "", null, null);
        assertThat(p.industry()).isEqualTo("generic");
        assertThat(p.industryPreset()).isEqualTo(Industry.GENERIC);
        assertThat(p.renderProfile()).contains("你是「老王修车」的电话客服").contains("产品/服务与价格:\n补胎 30 元")
                .doesNotContain("口腔").doesNotContain("培训");
    }

    @Test
    void emptyProfileRendersNothing() {
        MerchantProfile p = new MerchantProfile(null, 11, "5000", "", true, "", "", "", "", "", "", "",
                "", "", "", "", "", "", "", "", null, null);
        assertThat(p.renderProfile()).isEmpty();
        assertThat(p.label()).as("没名字就用接入号称呼").isEqualTo("5000");
    }

    @Test
    void nameAloneStillTellsTheModelWhoItIs() {
        MerchantProfile p = new MerchantProfile(null, 11, "5000", "美好口腔", true, "dental", "", "", "", "", "", "",
                "", "", "", "", "", "", "", "", null, null);
        assertThat(p.renderProfile()).as("只填了名字和行业也要有角色说明")
                .contains("你是「美好口腔」的电话客服").contains("口腔诊所")
                .doesNotContain("以下是本店的资料");   // 没资料就别说"以下是资料"
    }

    @Test
    void normalizesNullsAndWhitespace() {
        MerchantProfile p = new MerchantProfile(null, 11, "  5000 ", null, true, null, null, "  ", null, null, null, null,
                " 地址 ", null, null, null, null, null, null, null, null, null);
        assertThat(p.number()).isEqualTo("5000");
        assertThat(p.name()).isEmpty();
        assertThat(p.industry()).isEqualTo("generic");
        assertThat(p.systemPrompt()).isEmpty();
        assertThat(p.address()).isEqualTo("地址");
    }

    @Test
    void hotWordsAreTheNameAndTheLeadingTermOfEachLine() {
        assertThat(clinic().hotWords()).containsExactly("美好口腔", "洗牙", "种植牙", "张伟");
        assertThat(school().hotWords()).as("课程名到括号为止, 人名到逗号为止")
                .containsExactly("启明少儿英语", "少儿英语启蒙班", "雅思冲刺班", "李老师");
    }

    @Test
    void hotWordsSkipUnusableLines() {
        MerchantProfile p = new MerchantProfile(null, 11, "5000", "这个名字实在是太长了超过十个字", true, "dental",
                "", "", "", "", "", "", "", "", "", "",
                "200 元起\n  \n补牙 300\n补牙 500(复诊)", "", "", "", null, null);
        assertThat(p.hotWords()).as("数字开头的行没有词; 空行跳过; 重复只留一个; 超长店名不进表")
                .containsExactly("补牙");
    }
}
