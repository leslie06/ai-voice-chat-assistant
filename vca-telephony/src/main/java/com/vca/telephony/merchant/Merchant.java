package com.vca.telephony.merchant;

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
 */
public record Merchant(String number, String name, String greeting, String systemPrompt,
                       String knowledgeOwner, String transferDialString, String summaryWebhook,
                       String ttsVoice) {

    /** 什么都没配的空商家: 只在上游没给商家时兜底, 免得到处判空 */
    public static final Merchant NONE = new Merchant("", "", "", "", "", "", "", "");

    /** 日志与推送里怎么称呼这家店 */
    public String label() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return number == null || number.isBlank() ? "默认" : number;
    }
}
