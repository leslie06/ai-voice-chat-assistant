package com.vca.telephony;

import com.vca.domain.enums.AudioFormat;
import com.vca.domain.model.TtsConfig;
import com.vca.gateway.GatewayAutoConfiguration;
import com.vca.gateway.ProviderGateway;
import com.vca.orchestrator.vad.EnergyVad;
import com.vca.orchestrator.vad.SileroVadModel;
import com.vca.orchestrator.vad.VoiceActivityDetector;
import com.vca.telephony.media.PromptCache;
import com.vca.telephony.provider.ami.AmiClient;
import com.vca.telephony.provider.ami.AmiTelephonyProvider;
import com.vca.telephony.provider.audiosocket.AudioSocketServer;
import com.vca.telephony.provider.freeswitch.EslClient;
import com.vca.telephony.provider.freeswitch.FreeSwitchSocketServer;
import com.vca.telephony.provider.freeswitch.FreeSwitchTelephonyProvider;
import com.vca.telephony.session.CallConversationFactory;
import com.vca.telephony.session.CallSession;
import com.vca.orchestrator.call.CallSummaryStore;
import com.vca.telephony.merchant.Merchant;
import com.vca.telephony.merchant.MerchantRegistry;
import com.vca.telephony.session.PendingCalls;
import com.vca.telephony.summary.CallAftermath;
import com.vca.telephony.summary.CallNotifier;
import com.vca.telephony.summary.CallSummarizer;
import com.vca.telephony.summary.WebhookCallNotifier;
import com.vca.telephony.spi.CallLeg;
import com.vca.telephony.spi.TelephonyProvider;
import com.vca.telephony.web.OutboundCallRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 电话接入自动装配。与 {@code WebAutoConfiguration} 平级 —— 两个接入层共用治理层与编排层,
 * 但互不依赖。
 *
 * <p><b>默认不生效</b>: 需要 {@code vca.telephony.enabled=true}, 且容器里得有一个
 * {@link CallConversationFactory}(由 {@code vca-bootstrap} 提供, 见该接口的注释)。
 * 两个条件任一不满足就完全不建 bean、不占端口。
 *
 * <p>媒体服务器二选一({@code vca.telephony.provider}), 各自的接入与外呼 bean 放在下面两个嵌套配置里;
 * 与媒体服务器无关的(开场白、VAD、外呼接线台、外呼端点)放在外层共用。
 */
@AutoConfiguration(after = GatewayAutoConfiguration.class)
@EnableConfigurationProperties(TelephonyProperties.class)
@ConditionalOnProperty(prefix = "vca.telephony", name = "enabled", havingValue = "true")
@ConditionalOnBean({ProviderGateway.class, CallConversationFactory.class})
public class TelephonyAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TelephonyAutoConfiguration.class);

    /**
     * 固定话术预合成。走治理层的 TTS(自动获得熔断/配额/故障转移),
     * 强制 PCM 输出 —— MP3 没法直接降采样后灌进节流缓冲。
     */
    @Bean
    PromptCache telephonyPromptCache(ProviderGateway gateway, TelephonyProperties props) {
        TtsConfig cfg = new TtsConfig(props.getTtsVendor(), props.getTtsVoice(),
                AudioFormat.PCM, props.getTtsSampleRate(), 1.0f);
        PromptCache cache = new PromptCache(gateway.tts(), cfg, props.getSampleRate(), Duration.ofSeconds(15));
        if (!props.getGreeting().isBlank()) {
            cache.preload(props.getGreeting());   // 启动时就合成好, 别等第一通电话
        }
        return cache;
    }

    /**
     * 每路通话一个 VAD 打分器实例(Silero 的 RNN 状态不可跨会话共享)。
     * Silero 模型是进程级共享的, 由 {@code vca.web.vad.use-silero} 那个 bean 提供;
     * 电话侧要求用 Silero 但模型不在场时降级回能量法, 不让启动失败。
     */
    @Bean
    Supplier<VoiceActivityDetector> telephonyVadDetectorFactory(TelephonyProperties props,
                                                                ObjectProvider<SileroVadModel> model) {
        if (!props.getVad().isUseSilero()) {
            log.info("电话 VAD: 能量阈值法");
            return EnergyVad::new;
        }
        SileroVadModel m = model.getIfAvailable();
        if (m == null) {
            log.warn("电话 VAD 要求 Silero 但共享模型未加载(需同时置 vca.web.vad.use-silero=true), 降级回能量法");
            return EnergyVad::new;
        }
        log.info("电话 VAD: Silero(ONNX) —— 注意它是 16k 模型, 8k 上采样后精度会掉, 上线前用真实通话回归");
        return m::newDetector;
    }

    /**
     * 通话事后处理(摘要 + 落库 + 推送)。关掉开关时给一个什么都不做的实现, 上层无需判空。
     *
     * <p>{@code CallSummaryStore} 由 {@code vca-store} 提供, 没开落库时取不到 —— 那就只推送、不留档。
     */
    /**
     * 商家目录: 按客户拨的号码决定用哪家的开场白/知识库/坐席/推送地址。
     * 没配 {@code merchants} 时只有一个默认商家(顶层配置), 与单店部署完全一致。
     */
    @Bean
    MerchantRegistry merchantRegistry(TelephonyProperties props) {
        MerchantRegistry registry = props.toMerchantRegistry();
        if (registry.size() == 0) {
            log.info("单商家模式: 所有来电都用顶层配置");
        } else {
            log.info("多商家模式: 已登记 {} 家 —— {}", registry.size(),
                    registry.all().stream().map(m -> m.number() + "=" + m.label()).toList());
        }
        return registry;
    }

    @Bean
    CallAftermath callAftermath(ProviderGateway gateway, TelephonyProperties props,
                               ObjectProvider<CallSummaryStore> stores) {
        TelephonyProperties.Summary cfg = props.getSummary();
        if (!cfg.isEnabled()) {
            log.info("通话后小结: 已关闭(vca.telephony.summary.enabled=false)");
            return new CallAftermath(null, null, null, Integer.MAX_VALUE);
        }
        // 每个推送地址一个通知器, 建好就留着: 它持有 HTTP 客户端, 不该每通电话新建
        Map<String, CallNotifier> notifiers = new ConcurrentHashMap<>();
        log.info("通话后小结: 已启用(短于 {}s 的通话跳过)", cfg.getMinDurationSec());
        return new CallAftermath(new CallSummarizer(gateway.llm(), props.toSummaryLlmConfig()),
                stores.getIfAvailable(() -> CallSummaryStore.NOOP),
                url -> url == null || url.isBlank()
                        ? CallNotifier.NOOP
                        : notifiers.computeIfAbsent(url, WebhookCallNotifier::new),
                cfg.getMinDurationSec());
    }

    /** 外呼接线台: 把发起的呼叫和连进来的媒体按 id 对上。呼入不经过它。 */
    @Bean
    PendingCalls pendingCalls() {
        return new PendingCalls();
    }

    /**
     * 单拨外呼端点。<b>只在配了令牌时才注册</b> —— 这个接口会真的打电话、真的花钱,
     * 没令牌就暴露出去等于把话费和号码信誉交给公网。宁可不提供, 也不裸奔。
     *
     * <p>{@link TelephonyProvider} 由下面某个嵌套配置提供; 嵌套配置先于外层 bean 方法注册, 所以这里的条件看得到它。
     */
    @Bean
    @ConditionalOnBean(TelephonyProvider.class)
    RouterFunction<ServerResponse> outboundCallRoute(TelephonyProvider provider, TelephonyProperties props) {
        if (props.getApiToken().isBlank()) {
            log.warn("未配 vca.telephony.api-token, 不注册外呼端点 POST /telephony/calls "
                    + "(外呼能力仍在, 只是没有 HTTP 触发入口)");
            return RouterFunctions.route().build();   // 空路由: 不匹配任何请求
        }
        log.info("外呼端点已注册: POST /telephony/calls (需 X-Telephony-Token)");
        return OutboundCallRoute.create(provider, props.getApiToken(),
                Duration.ofMillis(props.outboundAnswerWaitMs() + 5_000L));
    }

    // ================= FreeSWITCH(默认) =================

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "vca.telephony", name = "provider", havingValue = "freeswitch", matchIfMissing = true)
    static class FreeSwitchConfiguration {

        /**
         * 接 FreeSWITCH 拨号计划里 socket 应用连过来的通话, 呼入与外呼都从这里进。
         * 顺序由服务端保证: 握手(拿 callId/号码、下发 unicast) → 建会话并订阅 → 开信令/媒体泵。
         */
        @Bean(destroyMethod = "close")
        FreeSwitchSocketServer freeSwitchSocketServer(TelephonyProperties props,
                                                      CallConversationFactory conversations,
                                                      PromptCache prompts,
                                                      PendingCalls pendingCalls,
                                                      Supplier<VoiceActivityDetector> vadDetectorFactory,
                                                      CallAftermath aftermath,
                                                      MerchantRegistry merchants) throws IOException {
            preloadGreetings(prompts, merchants, props);
            FreeSwitchSocketServer server = new FreeSwitchSocketServer(props.toFreeSwitchConfig(),
                    leg -> startCall(leg, props, conversations, pendingCalls, vadDetectorFactory, prompts,
                            aftermath, merchants));
            server.start();
            log.info("电话接入已启用(FreeSWITCH): socket {}:{}, 线路 {}Hz, 单通上限 {}s",
                    props.getFreeswitch().getListenAddress(), props.getFreeswitch().getPort(),
                    props.getSampleRate(), props.getMaxCallSeconds());
            return server;
        }

        /** ESL 连接。只在 {@code vca.telephony.freeswitch.esl.enabled=true} 时建 —— 不开就只能接呼入。 */
        @Bean(destroyMethod = "close")
        @ConditionalOnProperty(prefix = "vca.telephony.freeswitch.esl", name = "enabled", havingValue = "true")
        EslClient eslClient(TelephonyProperties props) throws IOException {
            EslClient client = new EslClient(props.toEslConfig());
            client.connect();   // 连不上就让启动失败: 外呼服务拨不出去没有意义
            return client;
        }

        @Bean
        @ConditionalOnBean(EslClient.class)
        FreeSwitchTelephonyProvider freeSwitchTelephonyProvider(EslClient client, TelephonyProperties props,
                                                                PendingCalls pending) {
            log.info("外呼已启用(FreeSWITCH): 拨号串={}, 目标 {}@{}, 振铃超时={}ms",
                    props.getFreeswitch().getEsl().getEndpoint(), props.getFreeswitch().getEsl().getExten(),
                    props.getFreeswitch().getEsl().getContext(), props.getFreeswitch().getEsl().getRingTimeoutMs());
            return new FreeSwitchTelephonyProvider(client, props.toEslConfig(), pending);
        }
    }

    // ================= Asterisk(备选) =================

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "vca.telephony", name = "provider", havingValue = "asterisk")
    static class AsteriskConfiguration {

        /**
         * AudioSocket 服务端。Asterisk 每接通一路就连过来一条 TCP。
         * 顺序由服务端保证: 先建 leg → 建会话并订阅 → 才开读泵, 因此首帧一定落在订阅之后。
         */
        @Bean(destroyMethod = "close")
        AudioSocketServer audioSocketServer(TelephonyProperties props,
                                            CallConversationFactory conversations,
                                            PromptCache prompts,
                                            PendingCalls pendingCalls,
                                            Supplier<VoiceActivityDetector> vadDetectorFactory,
                                            CallAftermath aftermath,
                                            MerchantRegistry merchants) throws IOException {
            preloadGreetings(prompts, merchants, props);
            AudioSocketServer server = new AudioSocketServer(props.toAudioSocketConfig(),
                    leg -> startCall(leg, props, conversations, pendingCalls, vadDetectorFactory, prompts,
                            aftermath, merchants));
            server.start();
            log.info("电话接入已启用(Asterisk): AudioSocket :{}, 线路 {}Hz, 单通上限 {}s",
                    props.getPort(), props.getSampleRate(), props.getMaxCallSeconds());
            return server;
        }

        /** AMI 连接。只在 {@code vca.telephony.ami.enabled=true} 时建 —— 不开就只能接呼入。 */
        @Bean(destroyMethod = "close")
        @ConditionalOnProperty(prefix = "vca.telephony.ami", name = "enabled", havingValue = "true")
        AmiClient amiClient(TelephonyProperties props) throws IOException {
            AmiClient client = new AmiClient(props.toAmiConfig());
            client.connect();   // 连不上就让启动失败: 外呼服务拨不出去没有意义
            return client;
        }

        @Bean
        @ConditionalOnBean(AmiClient.class)
        AmiTelephonyProvider amiTelephonyProvider(AmiClient client, TelephonyProperties props, PendingCalls pending) {
            log.info("外呼已启用(Asterisk): 中继={}, context={}, 振铃超时={}ms",
                    props.getAmi().getTrunk(), props.getAmi().getContext(), props.getAmi().getRingTimeoutMs());
            return new AmiTelephonyProvider(client, props.toAmiConfig(), pending);
        }
    }

    // ================= 共用 =================

    private static void startCall(CallLeg leg, TelephonyProperties props,
                                  CallConversationFactory conversations, PendingCalls pendingCalls,
                                  Supplier<VoiceActivityDetector> vadDetectorFactory, PromptCache prompts,
                                  CallAftermath aftermath, MerchantRegistry merchants) {
        // 先配对: 命中说明这是我们拨出去的电话(顺带回填被叫号码), 没命中就是呼入 —— 都照常建会话
        boolean outbound = pendingCalls.attach(leg);
        // 按客户拨的号码认领商家: 呼入时它就是"打给了哪一家"
        Merchant merchant = merchants.resolve(leg.calledNumber());
        byte[] greeting = prompts.get(merchant.greeting());
        log.info("[{}] 建立通话会话: 方向={}, 对端={}, 被叫={}, 商家={}",
                leg.callId(), outbound ? "外呼" : "呼入", leg.peerNumber(), leg.calledNumber(), merchant.label());
        // 先占位再回填: 会话要在 CallSession 之前建好(它是构造参数), 而 end_call 工具又要能挂这通电话。
        // 一个 holder 打破这个循环, 工具真正被调用时 CallSession 早已就位。
        AtomicReference<CallSession> self = new AtomicReference<>();
        CallConversationFactory.CallContext ctx = new CallConversationFactory.CallContext(
                leg.callId(), leg.peerNumber(), leg.calledNumber(), leg,
                () -> {
                    CallSession s = self.get();
                    if (s != null) {
                        s.hangupAfterPlayback();
                    }
                },
                merchant);
        CallSession call = new CallSession(leg, conversations.create(ctx),
                props.toVadConfig(), vadDetectorFactory.get(), props.toCallConfig(), greeting);
        self.set(call);
        call.onEnded(ended -> aftermath.onCallEnded(ended, merchant));
        call.start();
    }

    /**
     * 把每家商家的开场白都预合成好。接通那一刻要立刻出声, 这时才去调 TTS 就是几秒的静音;
     * 多商家时每家一句, 都在启动时合成完。
     */
    private static void preloadGreetings(PromptCache prompts, MerchantRegistry merchants,
                                         TelephonyProperties props) {
        List<String> texts = new ArrayList<>();
        texts.add(props.getGreeting());
        merchants.all().forEach(m -> texts.add(m.greeting()));
        for (String text : texts) {
            if (text != null && !text.isBlank()) {
                byte[] pcm = prompts.get(text);
                log.info("开场白已预合成({}ms): {}", pcm.length * 500 / props.getSampleRate(),
                        text.length() > 20 ? text.substring(0, 20) + "…" : text);
            }
        }
    }
}
