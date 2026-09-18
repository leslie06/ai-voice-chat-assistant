package com.vca.telephony.summary;

import com.vca.domain.model.Message;

import java.util.List;

/**
 * 一通刚结束的电话。{@code CallSession} 收尾时给出, 交给事后处理(摘要 + 推送)。
 *
 * <p>为什么要带着 {@code history} 传出来: 会话马上就要关了, 关掉之后这段对话只剩数据库里的行,
 * 再去查一遍既慢又要等落库线程。收尾那一刻内存里就有, 直接快照走。
 *
 * @param callId       通话 id
 * @param peerNumber   来电号码; 线路没送号时为 null
 * @param calledNumber 客户拨打的号码
 * @param durationSec  通话时长(秒)
 * @param reason       结束原因(客户挂机 / AI 挂机 / 超时…), 与日志里的一致
 * @param history      对话快照(含人设 system 消息, 由摘要器自行过滤)
 */
public record EndedCall(String callId, String peerNumber, String calledNumber,
                        int durationSec, String reason, List<Message> history) {
}
