package com.vca.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vca.domain.spi.MusicProvider;
import com.vca.gateway.GatewayAutoConfiguration;
import com.vca.gateway.ProviderGateway;
import com.vca.web.music.ItunesMusicProvider;
import com.vca.web.music.LocalMusicProvider;
import com.vca.web.music.LocalMusicRoute;
import com.vca.orchestrator.metrics.TurnMetrics;
import com.vca.orchestrator.skill.PlayMusicSkill;
import com.vca.orchestrator.skill.Skill;
import com.vca.orchestrator.skill.SkillRegistry;
import com.vca.orchestrator.skill.TimeSkill;
import com.vca.orchestrator.vad.EnergyVad;
import com.vca.orchestrator.vad.SileroVadModel;
import com.vca.orchestrator.vad.VoiceActivityDetector;
import com.vca.web.session.ConversationSessionFactory;
import com.vca.web.ws.VoiceWebSocketHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

/**
 * 接入层自动装配。需要治理层的 {@link ProviderGateway} 在场才生效。
 * 注册一个把 {@code vca.web.path} 映射到语音 handler 的 {@link HandlerMapping}。
 */
@AutoConfiguration(after = GatewayAutoConfiguration.class)
@EnableConfigurationProperties(WebProperties.class)
@ConditionalOnBean(ProviderGateway.class)
public class WebAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WebAutoConfiguration.class);

    /**
     * Silero VAD 共享模型: 仅当 {@code vca.web.vad.use-silero=true} 时加载, 整个进程一份。
     * 加载失败(模型缺失/不合法)时返回 null —— {@link #vadDetectorFactory} 会据此降级回能量法,
     * 不让启动失败。AutoCloseable, Spring 关闭时释放 ONNX session。
     */
    @Bean
    @ConditionalOnProperty(prefix = "vca.web.vad", name = "use-silero", havingValue = "true")
    SileroVadModel sileroVadModel(WebProperties props) {
        try {
            return SileroVadModel.load(props.getVad().getSileroModelPath());
        } catch (Exception e) {
            log.warn("Silero VAD 模型加载失败, 降级回能量阈值法 VAD: {}", e.toString());
            return null;
        }
    }

    /**
     * 逐路会话的 VAD 打分器工厂。有 Silero 模型就每路新建带状态的 Silero 检测器;
     * 没有(未启用或加载失败)则用无状态的能量法。
     */
    @Bean
    @ConditionalOnMissingBean
    java.util.function.Supplier<VoiceActivityDetector> vadDetectorFactory(ObjectProvider<SileroVadModel> model) {
        SileroVadModel m = model.getIfAvailable();
        if (m != null) {
            log.info("VAD: 启用 Silero(ONNX)");
            return m::newDetector;
        }
        log.info("VAD: 使用能量阈值法(未启用 Silero)");
        return EnergyVad::new;
    }

    /** 延迟埋点: 用宿主的 MeterRegistry(actuator 自动装配); 缺失时退回内存态, 不影响功能。 */
    @Bean
    @ConditionalOnMissingBean
    TurnMetrics turnMetrics(ObjectProvider<MeterRegistry> registry) {
        return new TurnMetrics(registry.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    @ConditionalOnMissingBean
    ConversationSessionFactory conversationSessionFactory(ProviderGateway gateway, WebProperties props,
                                                          TurnMetrics turnMetrics, SkillRegistry skillRegistry) {
        return new ConversationSessionFactory(gateway, props, turnMetrics, skillRegistry);
    }

    /**
     * function-calling 技能目录。把所有 {@link Skill} Bean 汇总成注册表交给编排层下发给模型;
     * 新增技能只需再声明一个 Skill Bean。{@code vca.web.tools-enabled=false} 时给空注册表(退回纯文本对话)。
     */
    @Bean
    @ConditionalOnMissingBean
    SkillRegistry skillRegistry(ObjectProvider<Skill> skills, WebProperties props) {
        if (!props.isToolsEnabled()) {
            return SkillRegistry.empty();
        }
        return new SkillRegistry(skills.orderedStream().toList());
    }

    /** 点歌技能(动作型): 模型理解到模糊点歌时调用; 明确点歌仍走编排层正则快路径。 */
    @Bean
    @ConditionalOnMissingBean
    PlayMusicSkill playMusicSkill() {
        return new PlayMusicSkill();
    }

    /** 当前时间技能(数据型): 演示"工具结果回灌模型组织口语答复"的完整往返, 零外部依赖。 */
    @Bean
    @ConditionalOnMissingBean
    TimeSkill timeSkill() {
        return new TimeSkill();
    }

    /**
     * 音乐检索: 先查本地曲库(整首播放), 没有再回退 iTunes(30 秒试听)。
     * 换曲库只需提供别的 MusicProvider Bean 覆盖即可。
     */
    @Bean
    @ConditionalOnMissingBean
    MusicProvider musicProvider(ObjectMapper objectMapper, WebProperties props) {
        LocalMusicProvider local = new LocalMusicProvider(props.getMusicDir());
        ItunesMusicProvider itunes = new ItunesMusicProvider(objectMapper);
        return query -> local.search(query).switchIfEmpty(itunes.search(query));
    }

    /** 本地曲库文件流服务: 把 {@code /music/files/**} 映射到曲库目录, 支持 Range(可拖动)。 */
    @Bean
    RouterFunction<ServerResponse> localMusicRoute(WebProperties props) {
        return LocalMusicRoute.create(props.getMusicDir());
    }

    @Bean
    @ConditionalOnMissingBean
    VoiceWebSocketHandler voiceWebSocketHandler(ConversationSessionFactory factory, ObjectMapper objectMapper,
                                                WebProperties props, MusicProvider musicProvider,
                                                java.util.function.Supplier<VoiceActivityDetector> vadDetectorFactory) {
        return new VoiceWebSocketHandler(factory, objectMapper, props.getVad().toConfig(), vadDetectorFactory,
                musicProvider, props.getAuthToken(), props.getMaxSessionSeconds(), props.getMaxConnections(),
                props.isS2sPersistent());
    }

    /** 把 WS 端点路径映射到 handler。order 取较高优先级, 先于注解控制器匹配。 */
    @Bean
    HandlerMapping voiceWebSocketMapping(VoiceWebSocketHandler handler, WebProperties props) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of(props.getPath(), (WebSocketHandler) handler));
        mapping.setOrder(-1);
        return mapping;
    }

    @Bean
    @ConditionalOnMissingBean
    WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }

    /** 兜底 ObjectMapper(若宿主未提供) */
    @Bean
    @ConditionalOnMissingBean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
