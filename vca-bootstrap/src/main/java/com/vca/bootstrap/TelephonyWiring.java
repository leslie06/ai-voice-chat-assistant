package com.vca.bootstrap;

import com.vca.orchestrator.session.TurnListener;
import com.vca.telephony.session.CallConversationFactory;
import com.vca.web.session.ConversationSessionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把电话接入层接到编排层。
 *
 * <p><b>为什么这段代码住在 bootstrap 而不是任何一个接入层里</b>: {@code vca-web}(浏览器)与
 * {@code vca-telephony}(电话)是平级的两个接入层, 互不依赖。但现成的
 * {@link ConversationSessionFactory} 住在 web 里 —— 它已经把记忆、知识库、落库、Agent、
 * 联网搜索全装配好了, 电话没理由再抄一遍。bootstrap 是唯一同时依赖两者的模块, 于是这一根
 * 转接线放在这里: 电话白拿 web 那套装配, 两个接入层仍然互相不知道对方存在。
 *
 * <p>只在 {@code vca.telephony.enabled=true} 时才建, 否则连
 * {@code TelephonyAutoConfiguration} 的 {@code @ConditionalOnBean} 都不会满足, 电话模块整个不生效。
 *
 * <p><b>这里不能用 {@code @ConditionalOnBean}</b>: 普通 {@code @Configuration} 的条件在自动装配
 * <i>之前</i>求值, 那时 {@link ConversationSessionFactory}(由 {@code WebAutoConfiguration} 建)
 * 还不存在, 条件会永远不满足。反过来 {@code TelephonyAutoConfiguration} 用
 * {@code @ConditionalOnBean} 检测本类的产物是成立的 —— 用户 bean 先于自动装配注册。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "vca.telephony", name = "enabled", havingValue = "true")
public class TelephonyWiring {

    /**
     * 一路通话一个编排会话。{@code callId} 用的是 AudioSocket 握手拿到的 Asterisk UUID,
     * 同时当 sessionId 落库, 因此通话记录能和 Asterisk 侧的通道对上。
     *
     * <p>{@code userId} 为 null: 电话对端是外部客户, 不是本系统的登录用户, 不该启用跨会话个人记忆。
     * {@code TurnListener} 暂用 NOOP —— 等做意向分级时, 这里换成把 ASR/回复文本喂给打分器的实现。
     */
    @Bean
    CallConversationFactory callConversationFactory(ConversationSessionFactory factory) {
        return callId -> factory.create(callId, null, TurnListener.NOOP);
    }
}
