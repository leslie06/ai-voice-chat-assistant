package com.vca.telephony.merchant;

import com.vca.orchestrator.merchant.Industry;

/**
 * 一个商家的电话客服配置。<b>按客户拨打的号码区分</b> —— 一套服务可以同时给多家店用,
 * 每家一个接入号, 各自的话术、知识库、坐席、推送地址互不相干。
 *
 * @param number             商家的接入号(客户拨的那个号码); 默认商家为空
 * @param name               商家名, 只用于日志和推送消息, 便于人看
 * @param greeting           接通后的开场白; 启动时预合成
 * @param systemPrompt       人设; 留空则用电话默认人设
 * @param knowledgeOwner     知识库归属(账号 id); 留空则这家没有知识库
 * @param transferDialString 转人工桥接到哪; 留空则不给这家下发转人工工具
 * @param summaryWebhook     通话小结推给谁; 留空则只落库不推送
 * @param ttsVoice           音色; 留空则用全局配置
 * @param industry           行业预设; 为 null 表示没登记行业(配置文件里的商家与默认商家), 措辞按其他商家、热词表用全局的
 * @param asrVocabularyId    这家自己指定的识别热词表; 留空则用行业自动维护的表, 再没有用全局配置
 */
public record Merchant(String number, String name, String greeting, String systemPrompt,
                       String knowledgeOwner, String transferDialString, String summaryWebhook,
                       String ttsVoice, Industry industry, String asrVocabularyId) {

    /** 什么都没配的空商家: 只在上游没给商家时兜底, 免得到处判空 */
    public static final Merchant NONE = new Merchant("", "", "", "", "", "", "", "", null, "");

    /** 只有配置项、没登记行业的商家(配置文件与默认商家) */
    public Merchant(String number, String name, String greeting, String systemPrompt, String knowledgeOwner,
                    String transferDialString, String summaryWebhook, String ttsVoice) {
        this(number, name, greeting, systemPrompt, knowledgeOwner, transferDialString, summaryWebhook, ttsVoice, null, "");
    }

    /** 行业预设; 没登记的按其他商家的措辞 */
    public Industry industryOrGeneric() {
        return industry == null ? Industry.GENERIC : industry;
    }

    /** 日志与推送里怎么称呼这家店 */
    public String label() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return number == null || number.isBlank() ? "默认" : number;
    }
}
