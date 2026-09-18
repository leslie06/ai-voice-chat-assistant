package com.vca.orchestrator.lead;

import java.time.LocalDateTime;

/**
 * 一条电话线索: 客户在通话里留下的预约/联系信息。
 *
 * <p>这是电话客服真正的产出 —— 对商家来说, "漏接的电话变成一条线索"就是这套系统的价值所在。
 *
 * @param callId        通话 id, 与 {@code conversation_turn.session_id} 和录音文件名一致, 便于回听核对
 * @param ownerId       商家账号 id(与知识库归属同一个), 决定这条线索属于谁
 * @param peerNumber    来电号码; 线路没送号时为 null
 * @param calledNumber  客户拨打的号码(商家接入号)
 * @param name          客户称呼, 如"王先生"
 * @param phone         客户留的回电号码; 没留时为 null(此时 peerNumber 就是唯一联系方式)
 * @param intent        意向, 如"种植牙面诊"
 * @param preferredTime 期望到店时间(原话即可, 如"这周六上午")
 * @param note          备注
 * @param createdAt     登记时间
 */
public record Lead(String callId, String ownerId, String peerNumber, String calledNumber,
                   String name, String phone, String intent, String preferredTime, String note,
                   LocalDateTime createdAt) {
}
