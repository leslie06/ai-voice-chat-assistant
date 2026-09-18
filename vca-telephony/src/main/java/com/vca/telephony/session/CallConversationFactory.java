package com.vca.telephony.session;

import com.vca.orchestrator.session.ConversationSession;
import com.vca.telephony.spi.CallLeg;

/**
 * 为一路通话建一个编排会话。
 *
 * <p>存在的意义是<b>保持依赖方向</b>: 现成的 {@code ConversationSessionFactory} 住在 {@code vca-web}
 * 里(它还顺带装配了记忆/知识库/落库/Agent 等一堆东西), 但电话接入层与浏览器接入层是平级的,
 * 不该互相依赖。于是这里只声明"给我一路会话"这一件事, 由同时依赖两者的 {@code vca-bootstrap}
 * 提供实现 —— 电话因此白拿浏览器那套已经调好的装配, 而两个接入层仍互不知情。
 */
@FunctionalInterface
public interface CallConversationFactory {

    /**
     * @param call 这一路通话。传整条 {@code CallLeg} 而不只是 id, 是因为电话专用工具(转人工、主动挂机、
     *             留资)需要知道"这通电话是谁打来的、要转到哪去" —— 它们是<b>按通话</b>建的, 不是进程级单例
     */
    ConversationSession create(CallContext call);

    /**
     * 建会话时需要的通话上下文。
     *
     * @param callId      通话 id(媒体服务器的通道 id), 同时用作 sessionId, 落库后能与通话记录、录音对账
     * @param peerNumber  对端号码(呼入=主叫, 外呼=被叫); 拿不到时为 null
     * @param calledNumber 这通电话打的是哪个号码(呼入=商家接入号); 拿不到时为 null
     * @param session     这一路通话本身, 供转人工工具桥接
     * @param endCall     "说完这句就挂机": end_call 工具用。<b>不能直接调 {@code session.hangup}</b> ——
     *                    告别语还在下行缓冲里, 立刻挂客户只能听到半句; 由 {@code CallSession} 等排空后执行
     */
    record CallContext(String callId, String peerNumber, String calledNumber, CallLeg session, Runnable endCall) {
    }
}
