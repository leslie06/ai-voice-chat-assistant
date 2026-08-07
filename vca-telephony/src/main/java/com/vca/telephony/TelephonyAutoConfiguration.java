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
import com.vca.telephony.provider.audiosocket.AudioSocketCallLeg;
import com.vca.telephony.provider.audiosocket.AudioSocketServer;
import com.vca.telephony.session.CallConversationFactory;
import com.vca.telephony.session.CallSession;
import com.vca.telephony.session.PendingCalls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * 电话接入自动装配。与 {@code WebAutoConfiguration} 平级 —— 两个接入层共用治理层与编排层,
 * 但互不依赖。
 *
 * <p><b>默认不生效</b>: 需要 {@code vca.telephony.enabled=true}, 且容器里得有一个
 * {@link CallConversationFactory}(由 {@code vca-bootstrap} 提供, 见该接口的注释)。
 * 两个条件任一不满足就完全不建 bean、不占端口。
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
     * AudioSocket 服务端。Asterisk 每接通一路就连过来一条 TCP, 这里为它装配一路 {@link CallSession}。
     *
     * <p>顺序是有意的: 先建 leg → 建会话并 {@code attach()}(订阅) → {@code AudioSocketServer} 才开读泵,
     * 因此首帧一定落在订阅之后。
     */
    /** 外呼接线台: 把 AMI 发起的呼叫和 AudioSocket 连进来的媒体按 id 对上。呼入不经过它。 */
    @Bean
    PendingCalls pendingCalls() {
        return new PendingCalls();
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
        log.info("外呼已启用: 中继={}, context={}, 振铃超时={}ms",
                props.getAmi().getTrunk(), props.getAmi().getContext(), props.getAmi().getRingTimeoutMs());
        return new AmiTelephonyProvider(client, props.toAmiConfig(), pending);
    }

    @Bean(destroyMethod = "close")
    AudioSocketServer audioSocketServer(TelephonyProperties props,
                                        CallConversationFactory conversations,
                                        PromptCache prompts,
                                        PendingCalls pendingCalls,
                                        Supplier<VoiceActivityDetector> vadDetectorFactory) throws IOException {
        byte[] greeting = prompts.get(props.getGreeting());
        AudioSocketServer server = new AudioSocketServer(props.toAudioSocketConfig(),
                leg -> startCall(leg, props, conversations, pendingCalls, vadDetectorFactory, greeting));
        server.start();
        log.info("电话接入已启用: AudioSocket :{}, 线路 {}Hz, 单通上限 {}s, 开场白 {}",
                props.getPort(), props.getSampleRate(), props.getMaxCallSeconds(),
                greeting.length > 0 ? (greeting.length * 500 / props.getSampleRate()) + "ms" : "无");
        return server;
    }

    private void startCall(AudioSocketCallLeg leg, TelephonyProperties props,
                           CallConversationFactory conversations, PendingCalls pendingCalls,
                           Supplier<VoiceActivityDetector> vadDetectorFactory, byte[] greeting) {
        // 先配对: 命中说明这是我们拨出去的电话(顺带回填被叫号码), 没命中就是呼入 —— 都照常建会话
        boolean outbound = pendingCalls.attach(leg);
        log.debug("建立通话会话: callId={}, 方向={}", leg.callId(), outbound ? "外呼" : "呼入");
        CallSession call = new CallSession(leg, conversations.create(leg.callId()),
                props.toVadConfig(), vadDetectorFactory.get(), props.toCallConfig(), greeting);
        call.start();
    }
}
