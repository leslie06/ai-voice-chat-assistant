package com.vca.orchestrator.merchant;

import java.util.List;
import java.util.Locale;

/**
 * 商家所属行业的预设: 同一套电话客服给口腔诊所、培训机构、其他商家用, 差别全收在这里。
 *
 * <p>行业决定的东西:
 * <ul>
 *   <li>资料字段的叫法 —— 诊所填"项目与价格 / 医生团队 / 预约规则", 培训机构填"课程与费用 / 师资 / 试听与报名";
 *       底下是同一列, 只是标签和渲染给模型看的措辞不同;</li>
 *   <li>人设里的角色说明 —— 这家是干什么的、来电多半问什么、客户有意向时该往哪引(约面诊 / 约试听 / 留资回电);</li>
 *   <li>留资工具与通话小结的措辞 —— "想做的项目"对培训机构就是"想学的课程";</li>
 *   <li>识别热词的基础词 —— 诊所的「洗牙、种植牙」对培训机构毫无用处, 培训机构要的是「试听、课时、雅思」。</li>
 * </ul>
 * 加一个行业 = 加一个枚举值, 其它地方不用改。库里存的是 {@link #code()}, 认不出的 code 按 {@link #GENERIC} 处理,
 * 所以以后删掉某个行业也不会让老数据炸掉。
 */
public enum Industry {

    DENTAL("dental", "口腔诊所", "vcadental",
            "项目与价格", "医生团队", "预约规则",
            "这是一家口腔诊所。来电多是问项目价格、营业时间、地址、能不能约面诊。"
                    + "客户有意向时主动邀约到店面诊, 并记下称呼、想做的项目、方便的时间。"
                    + "诊疗方案和效果只能说以医生面诊为准, 不做任何承诺。",
            "想做的项目或诉求, 如“种植牙面诊”",
            "这是口腔诊所的电话。意向等级只看客户自己的表态: 客户定下了面诊或到店的时间才算 A;"
                    + " 客服邀约而客户没答应、客户只是问问, 都不算。",
            List.of("洗牙", "洁牙", "种植牙", "种植体", "正畸", "矫正", "隐形矫正", "牙套", "根管治疗",
                    "窝沟封闭", "涂氟", "烤瓷牙", "全瓷牙", "牙冠", "贴面", "智齿", "拔牙", "补牙",
                    "牙周炎", "牙龈", "牙疼", "蛀牙", "龋齿", "冷光美白", "美白", "面诊", "拍片",
                    "儿童牙科", "口腔", "牙科", "医保", "复诊", "挂号")),

    EDUCATION("education", "培训机构", "vcaedu",
            "课程与费用", "师资", "试听与报名",
            "这是一家培训机构。来电多是家长或学员问课程、学费、上课时间、师资、能不能试听。"
                    + "客户有意向时主动邀约免费试听或到校咨询, 并记下称呼、学员年龄或年级、想学的课程、方便的时间。"
                    + "不承诺提分、通过率这类效果。",
            "想学的课程或诉求, 如“少儿英语试听”",
            "这是培训机构的电话。意向等级只看客户自己的表态: 客户定下了试听或到校的时间才算 A;"
                    + " 客服邀约而客户没答应、客户只是问问, 都不算。",
            List.of("试听", "报名", "学费", "课时", "课程", "班型", "一对一", "小班", "大班", "寒假班",
                    "暑假班", "周末班", "晚班", "退费", "补课", "教材", "少儿英语", "雅思", "托福", "口语",
                    "编程", "少儿编程", "奥数", "作文", "书法", "钢琴", "美术", "舞蹈", "跆拳道", "围棋",
                    "乐高", "机器人", "考研", "公务员", "会计", "师资", "校区", "年级")),

    GENERIC("generic", "其他商家", "vcaother",
            "产品/服务与价格", "团队成员", "预约与办理",
            "来电多是问产品或服务、价格、营业时间、地址。"
                    + "客户有意向时记下称呼、需求和方便联系的时间, 交给同事回电。",
            "需求或诉求, 如“咨询报价”",
            "",
            List.of("营业时间", "多少钱", "优惠", "退款", "发票", "停车", "售后", "会员", "订单", "微信"));

    /** 所有行业都用得上的客服流程词 */
    static final List<String> COMMON_HOT_WORDS = List.of("预约", "转人工", "回电", "地址", "价格");

    private final String code;
    private final String label;
    private final String vocabularyPrefix;
    private final String servicesLabel;
    private final String staffLabel;
    private final String bookingLabel;
    private final String roleNote;
    private final String leadIntentHint;
    private final String summaryHint;
    private final List<String> baseHotWords;

    Industry(String code, String label, String vocabularyPrefix,
             String servicesLabel, String staffLabel, String bookingLabel,
             String roleNote, String leadIntentHint, String summaryHint, List<String> baseHotWords) {
        this.code = code;
        this.label = label;
        this.vocabularyPrefix = vocabularyPrefix;
        this.servicesLabel = servicesLabel;
        this.staffLabel = staffLabel;
        this.bookingLabel = bookingLabel;
        this.roleNote = roleNote;
        this.leadIntentHint = leadIntentHint;
        this.summaryHint = summaryHint;
        this.baseHotWords = baseHotWords;
    }

    /** 库里存的标识; 认不出的一律按 {@link #GENERIC} */
    public static Industry of(String code) {
        if (code == null) {
            return GENERIC;
        }
        String c = code.trim().toLowerCase(Locale.ROOT);
        for (Industry i : values()) {
            if (i.code.equals(c)) {
                return i;
            }
        }
        return GENERIC;
    }

    public String code() {
        return code;
    }

    /** 给人看的名字: 口腔诊所 / 培训机构 / 其他商家 */
    public String label() {
        return label;
    }

    /** 厂商侧热词表的前缀, 每个行业一张表; 仅小写字母数字、少于 10 个字符 */
    public String vocabularyPrefix() {
        return vocabularyPrefix;
    }

    /** "项目与价格"那一列在这个行业里叫什么 */
    public String servicesLabel() {
        return servicesLabel;
    }

    /** "医生团队"那一列在这个行业里叫什么 */
    public String staffLabel() {
        return staffLabel;
    }

    /** "预约规则"那一列在这个行业里叫什么 */
    public String bookingLabel() {
        return bookingLabel;
    }

    /** 人设里的角色说明: 这家是干什么的、来电多半问什么、有意向时往哪引 */
    public String roleNote() {
        return roleNote;
    }

    /** 留资工具里"意向"字段的说明, 决定模型往里填什么 */
    public String leadIntentHint() {
        return leadIntentHint;
    }

    /** 通话小结给模型的行业提示; 空串表示没有特别要说的 */
    public String summaryHint() {
        return summaryHint;
    }

    /** 这个行业的基础热词(不含各家自己的名字与项目名), 已并入通用流程词 */
    public List<String> baseHotWords() {
        List<String> all = new java.util.ArrayList<>(COMMON_HOT_WORDS);
        all.addAll(baseHotWords);
        return List.copyOf(all);
    }
}
