package com.vca.telephony.session;

import com.vca.orchestrator.session.ConversationSession;

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
     * @param callId 通话 id(AudioSocket 的 UUID), 同时用作 sessionId, 便于落库后与通话记录对账
     */
    ConversationSession create(String callId);
}
