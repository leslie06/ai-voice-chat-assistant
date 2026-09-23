package com.vca.orchestrator.merchant;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一家商家(诊所/培训机构/其他门店)的完整资料 —— 电话客服按它"知道自己是谁、卖什么、几点开门"。
 *
 * <p><b>为什么要结构化字段, 而不是全靠上传文档做向量检索</b>: 营业时间、地址、价格、人员这类问题占来电的大头,
 * 向量检索有召回失败的风险, 还要多一次接口调用; 而线上真出过 AI 编造"5 家门店"的事,
 * 根子就是这类信息没有确定的来源。做成字段, 每通电话直接拼进提示词, 又准又快。上传文档只兜长尾。
 *
 * <p><b>行业只是一层"皮"</b>: 字段对所有行业都一样, {@link Industry} 决定它们叫什么、渲染给模型时怎么说。
 * 诊所的"项目与价格 / 医生团队"和培训机构的"课程与费用 / 师资"存的是同一列。
 *
 * <p>与 {@code vca-telephony} 里的 {@code Merchant} 的关系: 这是<b>持久化的资料</b>(商家在网页上填的东西),
 * 那是<b>一通电话运行时用的配置</b>(已经把资料渲染进人设、把留空的项回退成全局默认)。前者归商家维护,
 * 后者由注册表按需生成。
 *
 * @param id                 主键; 新建时为 null
 * @param ownerId            所属账号 id。知识库、线索、通话小结都按它归属, 也用它做接口鉴权
 * @param number             接入号: 客户拨的那个号码, 线路把它作为被叫号送上来。全局唯一
 * @param name               商家名
 * @param enabled            停用后该号码的来电按默认商家处理
 * @param industry           行业 code, 见 {@link Industry#of(String)}; 认不出的按其他商家
 * @param greeting           开场白; 为空按商家名生成
 * @param systemPrompt       商家自己的人设补充(语气、禁忌、特别要求); 追加在电话人设之后, 见 {@link #renderProfile()}
 * @param transferDialString 转人工桥接到哪(FreeSWITCH 拨号串, 如 user/8002@vca.local); 为空则不下发转人工工具
 * @param summaryWebhook     通话小结推送地址(企业微信/钉钉机器人); 为空只落库
 * @param ttsVoice           音色; 为空用全局
 * @param asrVocabularyId    识别热词表 id(运营配置); 为空用这家所属行业自动维护的那张表
 * @param address            地址与到店方式
 * @param businessHours      营业时间(含节假日安排)
 * @param phone              对外电话(AI 报给客户用的, 未必等于接入号)
 * @param transport          交通与停车
 * @param services           项目/课程/产品与价格(自由文本, 一行一项)
 * @param staff              医生/老师/团队成员(自由文本, 一行一人)
 * @param bookingRules       预约或报名规则(要留什么信息、提前多久、取消改期怎么办)
 * @param notes              其他要让 AI 知道的事(自由文本)
 * @param createdAt          创建时间
 * @param updatedAt          最近修改时间
 */
public record MerchantProfile(
        Long id,
        long ownerId,
        String number,
        String name,
        boolean enabled,
        String industry,
        String greeting,
        String systemPrompt,
        String transferDialString,
        String summaryWebhook,
        String ttsVoice,
        String asrVocabularyId,
        String address,
        String businessHours,
        String phone,
        String transport,
        String services,
        String staff,
        String bookingRules,
        String notes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** 每行开头的项目名/人名: 连续的汉字或字母, 到数字、空格、标点为止。"洗牙 200-400 元" → 洗牙 */
    private static final Pattern LEADING_TERM = Pattern.compile("^[\\p{IsHan}A-Za-z]{2,10}");

    public MerchantProfile {
        number = number == null ? "" : number.trim();
        name = nz(name);
        industry = Industry.of(industry).code();
        greeting = nz(greeting);
        systemPrompt = nz(systemPrompt);
        transferDialString = nz(transferDialString);
        summaryWebhook = nz(summaryWebhook);
        ttsVoice = nz(ttsVoice);
        asrVocabularyId = nz(asrVocabularyId);
        address = nz(address);
        businessHours = nz(businessHours);
        phone = nz(phone);
        transport = nz(transport);
        services = nz(services);
        staff = nz(staff);
        bookingRules = nz(bookingRules);
        notes = nz(notes);
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }

    /** 所属行业(枚举形式) */
    public Industry industryPreset() {
        return Industry.of(industry);
    }

    /**
     * 把结构化资料渲染成一段给模型看的文本: 先一句角色说明(我是谁、这家是干什么的、有意向往哪引),
     * 再是填了的资料项。什么都没填、名字也没有、行业又是"其他"时返回空串。
     *
     * <p>写法上刻意平铺直叙: 模型要照着念给客户听, 不要 markdown、不要表格。每项一行, 冒号分隔。
     * 结尾一句硬约束 —— 资料里没有的就别编, 这是电话客服最不能犯的错。
     */
    public String renderProfile() {
        Industry ind = industryPreset();
        StringBuilder facts = new StringBuilder();
        line(facts, "地址", address);
        line(facts, "营业时间", businessHours);
        line(facts, "对外电话", phone);
        line(facts, "交通与停车", transport);
        block(facts, ind.servicesLabel(), services);
        block(facts, ind.staffLabel(), staff);
        block(facts, ind.bookingLabel(), bookingRules);
        block(facts, "其他说明", notes);
        if (facts.isEmpty() && name.isEmpty() && ind == Industry.GENERIC) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(name.isEmpty() ? "你是这家店的电话客服。" : "你是「" + name + "」的电话客服。")
                .append(ind.roleNote()).append('\n');
        if (!facts.isEmpty()) {
            sb.append("以下是本店的资料, 回答客户时以此为准。资料里没有的信息(价格、时间、地址、人员等)一律不要编造,")
                    .append(" 如实说不清楚并提出帮客户转给同事或回电确认。\n").append(facts);
        }
        return sb.toString();
    }

    /**
     * 这家店该进识别热词表的词: 店名, 加上项目/课程/产品和人员每行开头的名字。
     * 客户在电话里最常念到的专有名词就是这些("你们是不是美好口腔""我想约张伟医生""洗牙多少钱"),
     * 而窄带线路上恰恰是专有名词最容易被听成别的。
     */
    public List<String> hotWords() {
        Set<String> words = new LinkedHashSet<>();
        if (name.length() >= 2 && name.length() <= 10) {
            words.add(name);
        }
        for (String text : List.of(services, staff)) {
            for (String raw : text.split("\n")) {
                Matcher m = LEADING_TERM.matcher(raw.strip());
                if (m.find()) {
                    words.add(m.group());
                }
            }
        }
        return new ArrayList<>(words);
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (!value.isEmpty()) {
            sb.append(label).append(": ").append(value.replace('\n', ' ')).append('\n');
        }
    }

    private static void block(StringBuilder sb, String label, String value) {
        if (!value.isEmpty()) {
            sb.append(label).append(":\n").append(value).append('\n');
        }
    }

    /** 日志与推送里怎么称呼这家店 */
    public String label() {
        return name.isEmpty() ? number : name;
    }
}
