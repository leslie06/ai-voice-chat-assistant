package com.vca.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vca.domain.model.MusicPlaylist;
import com.vca.domain.spi.MusicProvider;
import com.vca.gateway.GatewayAutoConfiguration;
import com.vca.gateway.ProviderGateway;
import com.vca.web.music.ItunesMusicProvider;
import com.vca.web.music.LocalMusicProvider;
import com.vca.web.music.LocalMusicRoute;
import com.vca.web.music.MusicCatalogRoute;
import com.vca.web.music.OssMusicProvider;
import com.vca.orchestrator.knowledge.KnowledgeStore;
import com.vca.orchestrator.memory.MemoryStore;
import com.vca.orchestrator.metrics.TurnMetrics;
import com.vca.orchestrator.recorder.ConversationRecorder;
import com.vca.orchestrator.recorder.AudioRecordingService;
import com.vca.orchestrator.search.WebSearchProvider;
import com.vca.orchestrator.skill.PlayMusicSkill;
import com.vca.orchestrator.skill.RememberSkill;
import com.vca.orchestrator.skill.SearchKnowledgeSkill;
import com.vca.orchestrator.skill.Skill;
import com.vca.orchestrator.skill.SkillRegistry;
import com.vca.web.search.BochaWebSearchProvider;
import com.vca.web.skill.WeatherSkill;
import com.vca.web.skill.WebSearchSkill;
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
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import reactor.core.publisher.Mono;

import java.time.Duration;
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
                                                          TurnMetrics turnMetrics, SkillRegistry skillRegistry,
                                                          ObjectProvider<ConversationRecorder> recorder,
                                                          ObjectProvider<MemoryStore> memory,
                                                          ObjectProvider<KnowledgeStore> knowledge,
                                                          ObjectProvider<WebSearchProvider> webSearch) {
        // 落库模块(vca-store)在场且启用时注入其 recorder / 长期记忆 / 知识库; 联网搜索配了 key 才注入; 否则 NOOP
        return new ConversationSessionFactory(gateway, props, turnMetrics, skillRegistry,
                recorder.getIfAvailable(() -> ConversationRecorder.NOOP),
                memory.getIfAvailable(() -> MemoryStore.NOOP),
                knowledge.getIfAvailable(() -> KnowledgeStore.NOOP),
                webSearch.getIfAvailable(() -> WebSearchProvider.NOOP));
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

    /**
     * 记忆技能(动作型): 用户透露个人信息/偏好等值得长期记住的内容时, 模型调用它写入长期记忆。
     * 仅当落库模块(vca-store)提供了真实 {@link MemoryStore} 时才注册 —— 否则返回 null, 不给模型下发
     * 一个写进 NOOP 的工具。userId 由会话在调用时注入(见 ConversationSession#runSkill)。
     */
    @Bean
    @ConditionalOnMissingBean
    RememberSkill rememberSkill(ObjectProvider<MemoryStore> memory) {
        MemoryStore store = memory.getIfAvailable();
        if (store == null) {
            log.info("未启用长期记忆(无 MemoryStore), 跳过 remember 工具");
            return null;
        }
        return new RememberSkill(store);
    }

    /**
     * 知识库检索技能(数据型, RAG): 模型据用户问题判断是否要查其上传的资料。仅当落库模块提供了真实
     * {@link KnowledgeStore} 时注册(否则不给模型下发查不到东西的工具)。userId 由会话调用时注入。
     */
    @Bean
    @ConditionalOnMissingBean
    SearchKnowledgeSkill searchKnowledgeSkill(ObjectProvider<KnowledgeStore> knowledge) {
        KnowledgeStore store = knowledge.getIfAvailable();
        if (store == null) {
            log.info("未启用知识库(无 KnowledgeStore), 跳过 search_knowledge 工具");
            return null;
        }
        return new SearchKnowledgeSkill(store);
    }

    /**
     * 查天气技能(数据型): 模型据用户问的城市调用, 高德实况回灌后由模型组织口语答复。
     * 没配 {@code vca.web.amap-key}(env {@code AMAP_API_KEY})就不注册——返回 null, Spring 跳过该 Bean,
     * {@code ObjectProvider} 也不会收进 SkillRegistry, 避免给模型下发一个注定失败的工具。
     */
    @Bean
    @ConditionalOnMissingBean
    WeatherSkill weatherSkill(ObjectMapper objectMapper, WebProperties props) {
        String key = props.getAmapKey();
        if (key == null || key.isBlank()) {
            log.info("未配置 vca.web.amap-key, 跳过查天气工具 get_weather");
            return null;
        }
        return new WeatherSkill(objectMapper, key);
    }

    /**
     * 联网搜索 provider(博查 Bocha)。配了 {@code vca.web.bocha-key}(env {@code BOCHA_API_KEY})才建,
     * 否则返回 null —— web_search 工具不注册、编排自动注入降级为空, 联网能力静默关闭。
     */
    @Bean
    @ConditionalOnMissingBean(WebSearchProvider.class)
    WebSearchProvider webSearchProvider(ObjectMapper objectMapper, WebProperties props) {
        String key = props.getBochaKey();
        if (key == null || key.isBlank()) {
            log.info("未配置 vca.web.bocha-key, 联网搜索关闭(web_search 工具与自动注入均不启用)");
            return null;
        }
        log.info("联网搜索已启用(博查 Bocha): auto={}, count={}", props.isWebSearchAuto(), props.getWebSearchCount());
        return new BochaWebSearchProvider(objectMapper, key, props.getWebSearchFreshness());
    }

    /** 联网搜索技能(数据型): 模型判断需要最新/实时信息时调用。仅当 provider 在场(配了 key)时注册。 */
    @Bean
    @ConditionalOnMissingBean
    WebSearchSkill webSearchSkill(ObjectProvider<WebSearchProvider> webSearch, WebProperties props) {
        WebSearchProvider provider = webSearch.getIfAvailable();
        if (provider == null) {
            return null;
        }
        return new WebSearchSkill(provider, props.getWebSearchCount());
    }

    // 时间/日期不再做成工具: 改为每轮把当前真实时间注入 LLM 上下文(见 ConversationSession#currentTimeContext),
    // 模型据此直接答对、零延迟, 比靠 tool_choice=auto 偶发不调工具更可靠。新增数据型工具仍声明 Skill Bean 即可。

    /** OSS 私有整首曲库；未启用时不创建客户端。 */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "vca.web", name = "music-oss-enabled", havingValue = "true")
    OssMusicProvider ossMusicProvider(WebProperties props) {
        requireMusicOss(props.getMusicOssEndpoint(), "VCA_MUSIC_OSS_ENDPOINT");
        requireMusicOss(props.getMusicOssBucket(), "VCA_MUSIC_OSS_BUCKET");
        requireMusicOss(props.getMusicOssAccessKeyId(), "VCA_MUSIC_OSS_ACCESS_KEY_ID");
        requireMusicOss(props.getMusicOssAccessKeySecret(), "VCA_MUSIC_OSS_ACCESS_KEY_SECRET");
        com.aliyun.oss.OSS client = new com.aliyun.oss.OSSClientBuilder().build(
                props.getMusicOssEndpoint(), props.getMusicOssAccessKeyId(),
                props.getMusicOssAccessKeySecret());
        log.info("OSS 音乐曲库已启用: oss://{}/{}", props.getMusicOssBucket(), props.getMusicOssPrefix());
        return new OssMusicProvider(client, props.getMusicOssBucket(), props.getMusicOssPrefix(),
                Duration.ofMinutes(Math.max(1, props.getMusicOssUrlMinutes())),
                Duration.ofSeconds(Math.max(0, props.getMusicOssCatalogCacheSeconds())));
    }

    /**
     * 音乐检索: 本地整首 → OSS 私有整首 → iTunes 30 秒试听。
     */
    @Bean
    @Primary
    MusicProvider musicProvider(ObjectMapper objectMapper, WebProperties props,
                                ObjectProvider<OssMusicProvider> ossProvider) {
        LocalMusicProvider local = new LocalMusicProvider(props.getMusicDir());
        ItunesMusicProvider itunes = new ItunesMusicProvider(objectMapper);
        OssMusicProvider oss = ossProvider.getIfAvailable();
        return new MusicProvider() {
            @Override
            public Mono<com.vca.domain.model.MusicTrack> search(String query) {
                return local.search(query)
                        .switchIfEmpty(oss == null ? Mono.empty() : oss.search(query))
                        .switchIfEmpty(itunes.search(query));
            }

            @Override
            public Mono<MusicPlaylist> playlist(String query) {
                return local.search(query).map(MusicPlaylist::single)
                        .switchIfEmpty(oss == null ? Mono.empty() : oss.playlist(query))
                        .switchIfEmpty(itunes.search(query).map(MusicPlaylist::single));
            }

            @Override
            public Mono<java.util.List<com.vca.domain.model.MusicTrack>> catalog() {
                return oss == null ? Mono.just(java.util.List.of()) : oss.catalog();
            }
        };
    }

    private static void requireMusicOss(String value, String envName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("启用 OSS 音乐曲库时必须配置 " + envName);
        }
    }

    /** 本地曲库文件流服务: 把 {@code /music/files/**} 映射到曲库目录, 支持 Range(可拖动)。 */
    @Bean
    RouterFunction<ServerResponse> localMusicRoute(WebProperties props) {
        return LocalMusicRoute.create(props.getMusicDir());
    }

    /** KTV 点歌面板曲库接口；账号启用时复用登录令牌鉴权。 */
    @Bean
    RouterFunction<ServerResponse> musicCatalogRoute(
            MusicProvider musicProvider, WebProperties props,
            ObjectProvider<com.vca.orchestrator.auth.TokenAuthenticator> authenticator) {
        return MusicCatalogRoute.create(
                musicProvider, authenticator.getIfAvailable(), props.getAuthToken());
    }

    @Bean
    @ConditionalOnMissingBean
    VoiceWebSocketHandler voiceWebSocketHandler(ConversationSessionFactory factory, ObjectMapper objectMapper,
                                                WebProperties props, MusicProvider musicProvider,
                                                java.util.function.Supplier<VoiceActivityDetector> vadDetectorFactory,
                                                ObjectProvider<com.vca.orchestrator.auth.TokenAuthenticator> authenticator,
                                                ObjectProvider<AudioRecordingService> audioRecordingService) {
        // 账号系统(vca-store)在场时注入用户令牌校验器, WS 即用用户登录令牌鉴权; 否则回退共享 token。
        return new VoiceWebSocketHandler(factory, objectMapper, props.getVad().toConfig(), vadDetectorFactory,
                musicProvider, props.getAuthToken(), props.getMaxSessionSeconds(), props.getMaxConnections(),
                props.isS2sPersistent(), authenticator.getIfAvailable(),
                audioRecordingService.getIfAvailable(() -> AudioRecordingService.NOOP));
    }

    /** 把 WS 端点路径映射到 handler。order 取较高优先级, 先于注解控制器匹配。 */
    @Bean
    HandlerMapping voiceWebSocketMapping(VoiceWebSocketHandler handler, WebProperties props) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of(props.getPath(), (WebSocketHandler) handler));
        mapping.setOrder(-1);
        return mapping;
    }

    /**
     * 放宽 WS 单帧上限(Reactor Netty 默认 64KB): 视觉多模态的图片经 JSON 文本帧上行, 压缩后仍常有
     * 几百 KB —— 默认值下服务端直接回 1009 掐断连接(前端表现为"发大图静默失败、小图正常")。
     * 上限与 {@code VoiceWebSocketHandler.MAX_IMAGE_BASE64_CHARS}(8M chars)对齐并留出 JSON 包装余量。
     *
     * <p><b>Spring 7 注意</b>: {@code WebFluxConfigurationSupport} 自带 {@code webFluxWebSocketHandlerAdapter}
     * bean, 自定义 {@code WebSocketHandlerAdapter} @Bean 会与之并存且不被使用(实测 64KB 仍生效)——
     * 官方定制口是 {@link WebFluxConfigurer#getWebSocketService()}; 帧上限经 WebsocketServerSpec.Builder
     * 供给(旧 setMaxFramePayloadLength 已移除)。
     */
    @Bean
    WebFluxConfigurer webSocketFrameSizeConfigurer() {
        return new WebFluxConfigurer() {
            @Override
            public org.springframework.web.reactive.socket.server.WebSocketService getWebSocketService() {
                ReactorNettyRequestUpgradeStrategy strategy = new ReactorNettyRequestUpgradeStrategy(
                        () -> reactor.netty.http.server.WebsocketServerSpec.builder()
                                .maxFramePayloadLength(12 * 1024 * 1024));
                return new HandshakeWebSocketService(strategy);
            }
        };
    }

    /** 兜底 ObjectMapper(若宿主未提供) */
    @Bean
    @ConditionalOnMissingBean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
