package com.vca.bootstrap;

import com.vca.orchestrator.session.TurnListener;
import com.vca.orchestrator.skill.Skill;
import com.vca.orchestrator.skill.SkillRegistry;
import com.vca.orchestrator.lead.LeadStore;
import com.vca.telephony.TelephonyProperties;
import com.vca.telephony.skill.EndCallSkill;
import com.vca.telephony.skill.SaveLeadSkill;
import com.vca.telephony.skill.TransferToHumanSkill;
import com.vca.telephony.session.CallConversationFactory;
import com.vca.web.session.ConversationSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

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

    private static final Logger log = LoggerFactory.getLogger(TelephonyWiring.class);

    /**
     * 一路通话一个编排会话。{@code callId} 用的是媒体服务器的通道 id(FreeSWITCH 的 Unique-ID /
     * Asterisk AudioSocket 的 UUID), 同时当 sessionId 落库, 因此通话记录能和媒体服务器侧的通道、录音对上。
     *
     * <p>{@code userId} 为 null: 电话对端是外部客户, 不是本系统的登录用户, 不该启用跨会话个人记忆。
     * {@code TurnListener} 暂用 NOOP —— 等做意向分级时, 这里换成把 ASR/回复文本喂给打分器的实现。
     *
     * <p>与浏览器的两处差异经 {@link ConversationSessionFactory.Overrides} 传入:
     * <ul>
     *   <li><b>工具按 {@code vca.telephony.tools} 白名单过滤, 默认一个都不发</b> —— 工具声明每轮都随 prompt
     *       送进模型, 直接抬高首个 token 的延迟, 而浏览器那套工具在电话客服里用不上;</li>
     *   <li>音色取 {@code vca.telephony.tts-voice}(留空则沿用浏览器的), 与开场白预合成用的是同一个值;</li>
     *   <li>人设取 {@code vca.telephony.system-prompt} —— 浏览器那套六百多字且专讲工具用法, 电话上是纯开销;</li>
     *   <li>对话模型取 {@code vca.telephony.llm-model} —— 电话要的是首字快, 不是想得深;</li>
     *   <li><b>关掉自动联网注入</b> —— 实测一次注入 2.3 秒, 是体感延迟里最大的一块;</li>
     *   <li>知识库按 {@code vca.telephony.knowledge-owner}(商家账号)检索, 但<b>不启用个人记忆</b> ——
     *       电话对端是外部客户, 不是本系统的登录用户。</li>
     * </ul>
     */
    @Bean
    CallConversationFactory callConversationFactory(ConversationSessionFactory factory,
                                                    TelephonyProperties props,
                                                    ObjectProvider<Skill> skills,
                                                    ObjectProvider<LeadStore> leads) {
        List<Skill> shared = telephonySkills(props.getTools(), skills);
        logKnowledge(props.getKnowledgeOwner());
        logAgentTools(props);
        LeadStore leadStore = leads.getIfAvailable(() -> LeadStore.NOOP);
        return call -> {
            // 电话专用工具按通话建: 通话 id、来电号码、转给谁都是这一路的事实, 不该让模型去传
            List<Skill> all = new ArrayList<>(shared);
            all.addAll(agentTools(props, call, leadStore));
            ConversationSessionFactory.Overrides overrides = new ConversationSessionFactory.Overrides(
                    new SkillRegistry(all), props.getTtsVoice(), props.getSystemPrompt(), props.getLlmModel(),
                    false,    // 电话不做自动联网注入: 实测一次 2.3 秒, 是体感延迟里最大的一块
                    props.getKnowledgeOwner());
            return factory.create(call.callId(), null, TurnListener.NOOP, overrides);
        };
    }

    /** 按 {@code vca.telephony.agent-tools} 建这一路通话的电话专用工具。 */
    private static List<Skill> agentTools(TelephonyProperties props, CallConversationFactory.CallContext call,
                                          LeadStore leadStore) {
        List<String> on = props.getAgentTools();
        List<Skill> tools = new ArrayList<>(3);
        if (on.contains(SaveLeadSkill.NAME)) {
            tools.add(new SaveLeadSkill(leadStore, call, props.getKnowledgeOwner()));
        }
        // 没配坐席号码就不下发: 宁可 AI 说"我让同事回电", 也不能让客户在转不出去的电话里干等
        if (on.contains(TransferToHumanSkill.NAME) && !props.getTransferDialString().isBlank()) {
            tools.add(new TransferToHumanSkill(call, props.getTransferDialString()));
        }
        if (on.contains(EndCallSkill.NAME)) {
            tools.add(new EndCallSkill(call.callId(), call.endCall()));
        }
        return tools;
    }

    private static void logAgentTools(TelephonyProperties props) {
        List<String> on = new ArrayList<>(props.getAgentTools());
        if (props.getTransferDialString().isBlank()) {
            on.remove(TransferToHumanSkill.NAME);
        }
        log.info("电话专用工具: {}{}", on.isEmpty() ? "无" : on,
                props.getTransferDialString().isBlank() ? " (未配坐席号码, 转人工不下发)" : "");
    }

    private static void logKnowledge(String owner) {
        if (owner == null || owner.isBlank()) {
            log.info("电话未接知识库(vca.telephony.knowledge-owner 为空) —— 商家资料类问题只能靠人设兜底");
        } else {
            log.info("电话知识库归属: 账号 {}", owner);
        }
    }

    /** 按名字过滤出电话侧允许用的<b>通用</b>工具(浏览器那套里挑)。名字写错不静默, 否则会变成"配了但没生效"。 */
    private static List<Skill> telephonySkills(List<String> allowed, ObjectProvider<Skill> skills) {
        if (allowed == null || allowed.isEmpty()) {
            log.info("电话不下发浏览器那套通用工具(vca.telephony.tools 为空) —— 为压低首字延迟");
            return List.of();
        }
        List<Skill> picked = skills.orderedStream()
                .filter(s -> allowed.contains(s.name()))
                .toList();
        List<String> missing = allowed.stream()
                .filter(name -> picked.stream().noneMatch(s -> s.name().equals(name)))
                .toList();
        if (!missing.isEmpty()) {
            log.warn("vca.telephony.tools 里这些工具不存在, 已忽略: {}", missing);
        }
        log.info("电话额外下发的通用工具: {}", picked.stream().map(Skill::name).toList());
        return picked;
    }
}
