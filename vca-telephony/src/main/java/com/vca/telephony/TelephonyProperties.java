package com.vca.telephony;

import com.vca.domain.enums.VendorType;
import com.vca.orchestrator.vad.VadConfig;
import com.vca.telephony.provider.audiosocket.AudioSocketConfig;
import com.vca.telephony.session.CallConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 电话接入配置, 前缀 {@code vca.telephony}。<b>默认关闭</b>, 关闭时对现有 Web 链路零影响。
 */
@ConfigurationProperties(prefix = "vca.telephony")
public class TelephonyProperties {

    /** 总开关。关闭时不监听端口、不建任何 bean。 */
    private boolean enabled = false;

    /** AudioSocket 监听端口。Asterisk 的 dialplan 会连到这里。 */
    private int port = 9092;

    /** 线路采样率(Hz)。电话网窄带固定 8000; 高清语音线路可能是 16000。 */
    private int sampleRate = 8000;

    /**
     * 是否翻转音频负载字节序。SLIN 在不同 Asterisk 构建上的线路字节序可能不同,
     * 而本项目全链路按小端解析。<b>联调时若听到刺耳噪声而不是人声, 把它打开。</b>
     */
    private boolean swapPayloadBytes = false;

    /**
     * 建连后等 Asterisk 首帧 UUID 的时长(ms)。UUID 会当作 sessionId 落库, 也是跟 originate 侧
     * 对账被叫号码的唯一键; 等不到就用占位 id 继续, 不阻断通话。
     */
    private int uuidWaitMs = 2000;

    /** 下行节流粒度(ms)。20 与 RTP 包长一致, 不建议改大。 */
    private int pacingMs = 20;

    /** 下行缓冲上限(ms)。 */
    private int maxBufferedMs = 30_000;

    /** 单通最长时长(s), 到点主动挂机。外呼必须设, 否则一通挂死的电话会一直烧钱。 */
    private int maxCallSeconds = 300;

    /** 开场白是否可被打断。外呼应为 true —— 客户常在开场白中途就说"不需要"。 */
    private boolean greetingBargeIn = true;

    /** 开场白文本。启动时预合成并缓存, 接通瞬间直接出声(首包延迟≈0)。留空则接通后直接进聆听。 */
    private String greeting = "";

    /** 开场白合成用的 TTS 厂商与采样率。 */
    private VendorType ttsVendor = VendorType.ALIYUN;

    /**
     * 开场白音色。<b>默认留空, 交给治理层候选决定</b>({@code vca.gateway.tts.candidates})。
     *
     * <p>别在这里写死音色: 按 {@code ManagedProviders} 的规则, 同厂商时"会话指定的音色"会<b>顶掉</b>
     * 候选自带的音色。一旦这里的默认值和部署实际用的 CosyVoice 模型不配套(例如把 v1 的音色喂给 v3 模型),
     * 合成就会以 {@code InvalidParameter / Engine return error code: 418} 失败 —— 而浏览器那条链路
     * 因为把音色留给候选决定, 完全不受影响, 于是很难联想到是音色的问题。
     */
    private String ttsVoice = "";
    private int ttsSampleRate = 24_000;

    /** 电话专用 VAD 阈值 —— 不要复用浏览器那组(那是按 48k 麦克风调的)。 */
    private Vad vad = new Vad();

    /** AMI: 外呼所需。不开只能接呼入。 */
    private Ami ami = new Ami();

    /**
     * 单拨外呼端点 {@code POST /telephony/calls} 的访问令牌。
     *
     * <p><b>留空 = 不注册该端点</b>。这个接口会真的打电话、真的花钱, 没有令牌就暴露出去等于
     * 把话费和号码信誉交给公网, 所以宁可不提供也不裸奔。
     */
    private String apiToken = "";

    /** Asterisk Manager Interface —— 发起外呼的控制通道。 */
    public static class Ami {
        /** 开关。关闭时不连 Asterisk, 系统只能接呼入。 */
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 5038;
        private String username = "";
        private String secret = "";
        /** SIP 中继名(PJSIP endpoint), 拨号串拼成 {@code PJSIP/<号码>@<trunk>}。由客户提供。 */
        private String trunk = "trunk";
        /** 接通后进入的 dialplan context —— 那里跑 AudioSocket。 */
        private String context = "ai-agent";
        private String exten = "s";
        /** 振铃多久没人接就放弃(ms)。 */
        private int ringTimeoutMs = 30_000;
        /** 从发起到媒体连进来的总等待上限(ms), 应大于 ringTimeoutMs。 */
        private int answerWaitMs = 45_000;
        private int connectTimeoutMs = 5_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean v) {
            this.enabled = v;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String v) {
            this.host = v;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int v) {
            this.port = v;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String v) {
            this.username = v;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String v) {
            this.secret = v;
        }

        public String getTrunk() {
            return trunk;
        }

        public void setTrunk(String v) {
            this.trunk = v;
        }

        public String getContext() {
            return context;
        }

        public void setContext(String v) {
            this.context = v;
        }

        public String getExten() {
            return exten;
        }

        public void setExten(String v) {
            this.exten = v;
        }

        public int getRingTimeoutMs() {
            return ringTimeoutMs;
        }

        public void setRingTimeoutMs(int v) {
            this.ringTimeoutMs = v;
        }

        public int getAnswerWaitMs() {
            return answerWaitMs;
        }

        public void setAnswerWaitMs(int v) {
            this.answerWaitMs = v;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int v) {
            this.connectTimeoutMs = v;
        }
    }

    /** VAD/断句阈值。含义见 {@link VadConfig}, 这里只是电话场景的一组起步值。 */
    public static class Vad {
        /** 人声判定阈值。窄带 + 线路底噪下比浏览器略高。 */
        private double speechThreshold = 0.02;
        private int onsetMs = 150;
        /** 句尾静音判停(ms)。电话上人说话停顿更短, 比浏览器的 800 略紧。 */
        private int silenceMs = 700;
        private double bargeThreshold = 0.025;
        private int bargeMs = 250;
        private int prerollMs = 400;
        /** 起播保护期(ms): 机器人刚开口这段不判打断, 挡线路回声导致的自打断。 */
        private int bargeGraceMs = 200;
        /**
         * 是否用 Silero。需要同时启用 {@code vca.web.vad.use-silero=true} 以加载共享模型,
         * 否则自动降级回能量法。<b>注意 Silero 是 16k 模型, 8k 上采样后精度会掉, 上线前用真实通话回归。</b>
         */
        private boolean useSilero = false;

        public double getSpeechThreshold() {
            return speechThreshold;
        }

        public void setSpeechThreshold(double v) {
            this.speechThreshold = v;
        }

        public int getOnsetMs() {
            return onsetMs;
        }

        public void setOnsetMs(int v) {
            this.onsetMs = v;
        }

        public int getSilenceMs() {
            return silenceMs;
        }

        public void setSilenceMs(int v) {
            this.silenceMs = v;
        }

        public double getBargeThreshold() {
            return bargeThreshold;
        }

        public void setBargeThreshold(double v) {
            this.bargeThreshold = v;
        }

        public int getBargeMs() {
            return bargeMs;
        }

        public void setBargeMs(int v) {
            this.bargeMs = v;
        }

        public int getPrerollMs() {
            return prerollMs;
        }

        public void setPrerollMs(int v) {
            this.prerollMs = v;
        }

        public int getBargeGraceMs() {
            return bargeGraceMs;
        }

        public void setBargeGraceMs(int v) {
            this.bargeGraceMs = v;
        }

        public boolean isUseSilero() {
            return useSilero;
        }

        public void setUseSilero(boolean v) {
            this.useSilero = v;
        }
    }

    // ---- 组装成各层自己的配置对象 ----

    public AudioSocketConfig toAudioSocketConfig() {
        return new AudioSocketConfig(port, sampleRate, swapPayloadBytes, 128, uuidWaitMs);
    }

    public com.vca.telephony.provider.ami.AmiConfig toAmiConfig() {
        return new com.vca.telephony.provider.ami.AmiConfig(
                ami.getHost(), ami.getPort(), ami.getUsername(), ami.getSecret(),
                ami.getTrunk(), ami.getContext(), ami.getExten(),
                ami.getRingTimeoutMs(), ami.getAnswerWaitMs(), ami.getConnectTimeoutMs());
    }

    public CallConfig toCallConfig() {
        return new CallConfig(pacingMs, maxBufferedMs, ttsSampleRate, maxCallSeconds, greetingBargeIn);
    }

    /** VAD 目标采样率固定 16k: Silero 要求, ASR 也按 16k 送。上行 8k 会被升采样到此。 */
    public VadConfig toVadConfig() {
        return new VadConfig(
                vad.getSpeechThreshold(), vad.getOnsetMs(), vad.getSilenceMs(),
                vad.getBargeThreshold(), vad.getBargeMs(), vad.getPrerollMs(),
                16_000, vad.isUseSilero(), "", vad.getBargeGraceMs(),
                // halfDuplex=false: 电话线路本就是全双工, 打断照常判
                // echoAware=false: 回声判别依赖"服务端知道下行音频"的时序模型, 电话侧下行走
                //                  AudioSocket 的定速缓冲(PacingBuffer), 时间轴与 Web 不同, 未验证
                false, false, false, 400, 1600);
    }

    // ---- getters / setters ----

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public boolean isSwapPayloadBytes() {
        return swapPayloadBytes;
    }

    public void setSwapPayloadBytes(boolean swapPayloadBytes) {
        this.swapPayloadBytes = swapPayloadBytes;
    }

    public int getUuidWaitMs() {
        return uuidWaitMs;
    }

    public void setUuidWaitMs(int uuidWaitMs) {
        this.uuidWaitMs = uuidWaitMs;
    }

    public int getPacingMs() {
        return pacingMs;
    }

    public void setPacingMs(int pacingMs) {
        this.pacingMs = pacingMs;
    }

    public int getMaxBufferedMs() {
        return maxBufferedMs;
    }

    public void setMaxBufferedMs(int maxBufferedMs) {
        this.maxBufferedMs = maxBufferedMs;
    }

    public int getMaxCallSeconds() {
        return maxCallSeconds;
    }

    public void setMaxCallSeconds(int maxCallSeconds) {
        this.maxCallSeconds = maxCallSeconds;
    }

    public boolean isGreetingBargeIn() {
        return greetingBargeIn;
    }

    public void setGreetingBargeIn(boolean greetingBargeIn) {
        this.greetingBargeIn = greetingBargeIn;
    }

    public String getGreeting() {
        return greeting;
    }

    public void setGreeting(String greeting) {
        this.greeting = greeting;
    }

    public VendorType getTtsVendor() {
        return ttsVendor;
    }

    public void setTtsVendor(VendorType ttsVendor) {
        this.ttsVendor = ttsVendor;
    }

    public String getTtsVoice() {
        return ttsVoice;
    }

    public void setTtsVoice(String ttsVoice) {
        this.ttsVoice = ttsVoice;
    }

    public int getTtsSampleRate() {
        return ttsSampleRate;
    }

    public void setTtsSampleRate(int ttsSampleRate) {
        this.ttsSampleRate = ttsSampleRate;
    }

    public Vad getVad() {
        return vad;
    }

    public void setVad(Vad vad) {
        this.vad = vad;
    }

    public Ami getAmi() {
        return ami;
    }

    public void setAmi(Ami ami) {
        this.ami = ami;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }
}
