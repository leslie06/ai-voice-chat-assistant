package com.vca.orchestrator.call;

import java.time.LocalDateTime;

/**
 * 一通电话的事后小结: 摘要 + 意向分级。挂机后生成, 推给商家、同时落库供后台翻看。
 *
 * <p>对商家来说这是"这通电话到底说了什么"的唯一入口 —— 没人会去听 30 秒以上的录音,
 * 但一条两句话的摘要 + 一个意向等级, 是会看的。
 *
 * @param callId       通话 id; 与 {@code conversation_turn.session_id}、录音文件名、线索表一致
 * @param ownerId      商家账号 id
 * @param peerNumber   来电号码; 线路没送号时为 null
 * @param calledNumber 客户拨打的号码
 * @param durationSec  通话时长(秒)
 * @param turns        对话轮数(一问一答算一轮)
 * @param summary      两三句话的摘要
 * @param intent       意向等级: A 马上要办 / B 有意向待跟进 / C 只是咨询 / D 无效(骚扰、拨错、没说话)
 * @param followUp     建议的跟进动作; 无需跟进时为 null
 */
public record CallSummary(String callId, String ownerId, String peerNumber, String calledNumber,
                          int durationSec, int turns, String summary, String intent, String followUp,
                          LocalDateTime createdAt) {
}
