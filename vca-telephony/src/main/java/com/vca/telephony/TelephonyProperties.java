package com.vca.telephony;

import com.vca.domain.enums.VendorType;
import com.vca.orchestrator.vad.VadConfig;
import com.vca.telephony.provider.audiosocket.AudioSocketConfig;
import com.vca.telephony.session.CallConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 电话接入配置, 前缀 {@code vca.telephony}。<b>默认关闭</b>, 关闭时对现有 Web 链路零影响。
 */
@ConfigurationProperties(prefix = "vca.telephony")
public class TelephonyProperties {

    /** 接哪种媒体服务器 */
    public enum Provider {
        /** 默认。事件套接字(信令) + unicast UDP(媒体), 都是 FreeSWITCH 自带能力。配置见 {@link FreeSwitch} */
        FREESWITCH,
        /** 备选。AudioSocket(媒体) + AMI(外呼)。配置是本类顶层的 port/swapPayloadBytes/uuidWaitMs 与 {@link Ami} */
        ASTERISK
    }

    /** 总开关。关闭时不监听端口、不建任何 bean。 */
    private boolean enabled = false;

    /** 媒体服务器类型, 默认 FreeSWITCH */
    private Provider provider = Provider.FREESWITCH;

    /** FreeSWITCH 接入参数(provider=freeswitch 时生效) */
    private FreeSwitch freeswitch = new FreeSwitch();

    /**
     * 电话回合下发给模型的工具名白名单。<b>默认空 = 一个都不发</b>。
     *
     * <p>为什么电话默认不发: 工具声明每轮都要随 prompt 一起送进模型, 直接抬高首个 token 的延迟,
     * 而浏览器那套工具(点歌、天气、联网搜索、记忆)在电话客服里基本用不上 —— 电话对延迟远比浏览器敏感。
     * 等做了留资/转人工这类电话专用工具, 在这里按名字放行即可, 例如
     * {@code vca.telephony.tools=search_knowledge}。
     *
     * <p><b>电话专用工具(留资/转人工/挂机)不在这里配</b>: 它们是按通话建的, 由
     * {@code vca.telephony.agent-tools} 控制。
     */
    private List<String> tools = new ArrayList<>();

    /**
     * 电话专用工具开关(按通话建实例): {@code save_lead} 留资、{@code transfer_to_human} 转人工、
     * {@code end_call} 主动挂机。
     *
     * <p><b>默认三个全开</b> —— 它们正是"电话客服"与"能打电话的聊天机器人"的区别所在。
     * 每多一个工具, 工具声明都会随 prompt 进模型、抬高一点首字延迟, 所以只放这三个, 用不上的可以关。
     */
    private List<String> agentTools = new ArrayList<>(List.of("save_lead", "transfer_to_human", "end_call"));

    /**
     * 电话回合的人设。<b>留空则沿用浏览器那套</b>({@code vca.web.system-prompt}), 但不建议:
     * 浏览器人设六百多字, 还专门讲了怎么用工具 —— 电话这边工具是关的, 这些字每轮都要重新过一遍模型,
     * 直接抬高首字延迟。默认值见 {@code application.yml} 的 {@code prompts.phone-agent}。
     */
    private String systemPrompt = "";

    /**
     * 电话回合用的对话模型。留空 = 沿用 {@code vca.web.llm-model}。
     *
     * <p>电话上首字延迟比回答深度重要得多: 客服问答大多是"几点开门""怎么走"这类短问题,
     * 带思考链的大模型会先生成一段思考再出第一个字, 电话里这段就是纯等待。
     */
    private String llmModel = "";

    /**
     * 电话按谁的知识库作答(账号 id, 即 {@code app_user.id})。<b>留空 = 电话里没有知识库</b>。
     *
     * <p>为什么要单配一个: 知识库按账号隔离, 而电话对端是外部客户、没有登录身份。这里填的是<b>商家</b>的账号,
     * 商家用网页登录后把项目、价格、营业时间传进 {@code POST /api/knowledge}, 电话里就能据此作答。
     *
     * <p>多商家时这里会换成"按被叫号码({@link com.vca.telephony.spi.CallLeg#calledNumber})查商家"的映射,
     * 现在先支持一个 —— 单店试点够用, 也避免过早为没落地的产品形态建表。
     */
    private String knowledgeOwner = "";

    /**
     * 转人工时桥接到哪里。填<b>媒体服务器的拨号串</b>, 例如接中继时
     * {@code sofia/gateway/trunk/13800138000}(商家的手机), 本地联调时 {@code user/1000}。
     *
     * <p><b>留空 = 不下发转人工工具</b> —— 宁可 AI 说"我让同事回电给您", 也不能让客户在一通转不出去的
     * 电话里干等。
     */
    private String transferDialString = "";

    /** [Asterisk] AudioSocket 监听端口。Asterisk 的 dialplan 会连到这里。 */
    private int port = 9092;

    /** 线路采样率(Hz)。电话网窄带固定 8000; 高清语音线路可能是 16000。 */
    private int sampleRate = 8000;

    /**
     * [Asterisk] 是否翻转音频负载字节序。SLIN 在不同 Asterisk 构建上的线路字节序可能不同,
     * 而本项目全链路按小端解析。<b>联调时若听到刺耳噪声而不是人声, 把它打开。</b>
     */
    private boolean swapPayloadBytes = false;

    /**
     * [Asterisk] 建连后等 Asterisk 首帧 UUID 的时长(ms)。UUID 会当作 sessionId 落库, 也是跟 originate 侧
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

    /**
     * 听到线路信号音(忙音/拨号音/拥塞音)就挂机。
     *
     * <p>模拟线(FXO 网关、插卡盒)没有挂机信令, 客户挂断后线上只是开始放 450Hz 的忙音。网关本该听出来并拆线,
     * 但那要在网关上单独配、参数还得对上当地制式, 漏配是常态。线上实测一通客户已挂断的电话被忙音"撑"了
     * 221 秒, 期间线路被占、后面的来电全进不来。开着它, 忙音响起约 4 秒后本进程自己挂机。
     */
    private boolean toneHangup = true;

    /** 连续这么久有声音却一个字都识别不出, 判定为线路噪声并挂机(ms); <=0 关闭。兜住 450Hz 之外的情况。 */
    private int noSpeechHangupMs = 20_000;

    /** 开场白文本。启动时预合成并缓存, 接通瞬间直接出声(首包延迟≈0)。留空则接通后直接进聆听。 */
    private String greeting = "";

    /**
     * 回合彻底失败时的兜底话术, 与开场白一样在启动时预合成。
     *
     * <p>厂商熔断、密钥过期、网络抖动在电话里的表现都一样: AI 突然不吭声。客户不知道发生了什么,
     * 只会以为断线了直接挂断 —— 连重说一遍的机会都没有。预合成的原因也和开场白一致: 出事的时候
     * TTS 本身可能正是挂掉的那一环, 现合成等于没有兜底。留空则维持静默。
     */
    private String errorPrompt = "不好意思，我这边没太听清，您再说一遍好吗？";

    /**
     * 电话链路专用的识别模型。默认用阿里云的 8kHz 窄带模型。
     *
     * <p>电话线是 8kHz 采样、G.711 编码, 3.4kHz 以上的声音根本不存在。宽带模型
     * ({@code paraformer-realtime-v2}) 训练时见的是有高频的音频, 拿它听电话就会在缺失的那段上瞎猜 ——
     * 线上实测"洗牙多少钱"被听成"抵押多少钱""拿多少钱""压多少钱", 而"多少钱"三个字每次都对,
     * 错的正是声母 x 这种高频摩擦音。8k 模型训练时见的全是这种窄带音频, 对此有专门的分辨能力。
     *
     * <p>留空则用 {@code vca.providers.asr.aliyun.model} 的全局值(浏览器链路用的宽带模型)。
     */
    private String asrModel = "paraformer-realtime-8k-v2";

    /**
     * 电话链路专用的热词表 id。<b>窄带线路上这是提准的主要手段。</b>
     *
     * <p>实测同一段 8kHz 电话音频:「洗牙多少钱」不带热词被识别成「抵押多少钱」, 带上热词就对了;
     * 「种植牙」同理("中岁牙"→"种植牙")。换模型解决不了这个 —— 三个模型都栽在"洗牙"上,
     * 因为声母 x 的高频能量在电话线上本来就没传过来, 只能靠热词把候选词拉回来。
     *
     * <p>热词表要先在厂商那边注册, 且<b>与目标模型绑定</b>(建表时要指定 target_model),
     * 换模型就得重建。注册见 docs/12 §13 的"窄带线路要用窄带模型"。
     * 留空则退回 {@code vca.providers.asr.aliyun.vocabulary-id} 的全局值。
     */
    private String asrVocabularyId = "";

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

    /** 通话后小结(摘要 + 意向分级 + 推送)。 */
    private Summary summary = new Summary();

    /**
     * 多商家: 一套服务同时给多家店用, <b>按客户拨的号码区分</b>。每家一个接入号, 各自的开场白、知识库、
     * 坐席、推送地址互不相干; 没填的项回退到顶层那套配置。
     *
     * <p><b>不配就是单店</b>, 行为与以前完全一致 —— 顶层配置即默认商家, 号码没匹配上也走它。
     */
    private List<MerchantProps> merchants = new ArrayList<>();

    /** 一家商家的配置。留空的项回退到顶层同名配置。 */
    public static class MerchantProps {
        /** 接入号码: 客户拨的那个号。必填, 否则这条被忽略 */
        private String number = "";
        /** 商家名, 只用于日志和推送消息 */
        private String name = "";
        private String greeting = "";
        private String systemPrompt = "";
        private String knowledgeOwner = "";
        private String transferDialString = "";
        private String summaryWebhook = "";
        private String ttsVoice = "";

        public String getNumber() {
            return number;
        }

        public void setNumber(String v) {
            this.number = v == null ? "" : v;
        }

        public String getName() {
            return name;
        }

        public void setName(String v) {
            this.name = v == null ? "" : v;
        }

        public String getGreeting() {
            return greeting;
        }

        public void setGreeting(String v) {
            this.greeting = v == null ? "" : v;
        }

        public String getSystemPrompt() {
            return systemPrompt;
        }

        public void setSystemPrompt(String v) {
            this.systemPrompt = v == null ? "" : v;
        }

        public String getKnowledgeOwner() {
            return knowledgeOwner;
        }

        public void setKnowledgeOwner(String v) {
            this.knowledgeOwner = v == null ? "" : v;
        }

        public String getTransferDialString() {
            return transferDialString;
        }

        public void setTransferDialString(String v) {
            this.transferDialString = v == null ? "" : v;
        }

        public String getSummaryWebhook() {
            return summaryWebhook;
        }

        public void setSummaryWebhook(String v) {
            this.summaryWebhook = v == null ? "" : v;
        }

        public String getTtsVoice() {
            return ttsVoice;
        }

        public void setTtsVoice(String v) {
            this.ttsVoice = v == null ? "" : v;
        }
    }

    /**
     * 挂机后的事后处理: 用大模型把通话压成两三句摘要 + 意向等级, 落库并推给商家。
     *
     * <p>这是商家真正会看的东西 —— 没人会去听录音, 但群里弹出来的一条"意向 A, 想约周六种植牙面诊"会看。
     */
    public static class Summary {
        /** 开关。关掉则挂机后什么都不做(不调模型、不落库、不推送) */
        private boolean enabled = true;
        /**
         * 短于这个时长(秒)的通话不摘要。秒挂/拨错/彩铃占了呼入的一大半, 每通都调一次模型纯属烧钱,
         * 而"接通 3 秒就挂"本身已经说明了一切。
         */
        private int minDurationSec = 10;
        /**
         * 摘要用的厂商与模型。<b>都留空</b>时交给治理层按候选顺序选 —— 摘要不占通话时间, 不必像对话那样挑最快的。
         * 要钉死就一起填(只填模型、厂商留空的话, 模型名可能被发给别家)。
         */
        private String vendor = "";
        private String model = "";
        /**
         * 推送地址。企业微信/钉钉的群机器人 URL 直接填这里即可(报文按它们的文本消息格式发,
         * 多余字段它们会忽略); 自建后台用同一个请求里的 {@code call} 结构化字段。留空 = 只落库不推送。
         */
        private String webhookUrl = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean v) {
            this.enabled = v;
        }

        public int getMinDurationSec() {
            return minDurationSec;
        }

        public void setMinDurationSec(int v) {
            this.minDurationSec = v;
        }

        public String getVendor() {
            return vendor;
        }

        public void setVendor(String v) {
            this.vendor = v == null ? "" : v;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String v) {
            this.model = v == null ? "" : v;
        }

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String v) {
            this.webhookUrl = v == null ? "" : v;
        }
    }

    /** 电话专用 VAD 阈值 —— 不要复用浏览器那组(那是按 48k 麦克风调的)。 */
    private Vad vad = new Vad();

    /** [Asterisk] AMI: 外呼所需。不开只能接呼入。 */
    private Ami ami = new Ami();

    /**
     * 单拨外呼端点 {@code POST /telephony/calls} 的访问令牌。
     *
     * <p><b>留空 = 不注册该端点</b>。这个接口会真的打电话、真的花钱, 没有令牌就暴露出去等于
     * 把话费和号码信誉交给公网, 所以宁可不提供也不裸奔。
     */
    private String apiToken = "";

    /**
     * FreeSWITCH 接入。拨号计划里 {@code socket <本进程>:<port> async full} 每通电话连过来一次,
     * 媒体经 unicast UDP 双向传输。详见 {@code FreeSwitchCallLeg}。
     */
    public static class FreeSwitch {
        /**
         * socket 服务端绑定地址。默认只绑回环 —— 这个端口没有鉴权, 能连上就能冒充 FreeSWITCH。
         * FreeSWITCH 在别的机器上时才改成内网地址, 并用防火墙只放行 FreeSWITCH。
         */
        private String listenAddress = "127.0.0.1";
        /** socket 服务端端口(FreeSWITCH 惯例 8084) */
        private int port = 8084;
        /** 本进程 UDP 媒体口绑定地址。默认回环, 理由同上: 能往这个口发包就能往通话里灌音频 */
        private String mediaBindAddress = "127.0.0.1";
        /** 下发 unicast 后等第一个媒体包的上限(ms), 超时挂断并打出排查提示 */
        private int mediaWaitMs = 3000;
        /** 等 FreeSWITCH 应答握手命令的上限(ms) */
        private int handshakeTimeoutMs = 5000;
        /** 外呼: 经事件套接字发 originate。不开只能接呼入 */
        private Esl esl = new Esl();

        public static class Esl {
            /** 开关。关闭时不连 FreeSWITCH, 系统只能接呼入 */
            private boolean enabled = false;
            private String host = "127.0.0.1";
            private int port = 8021;
            /** event_socket.conf 里的密码 */
            private String password = "";
            /**
             * 拨号串模板, {@code {number}} 替换为被叫。接中继: {@code sofia/gateway/<网关名>/{number}};
             * 本地联调拨注册在 FreeSWITCH 上的软电话: {@code user/{number}}
             */
            private String endpoint = "sofia/gateway/trunk/{number}";
            /** 接通后进入的拨号计划 context */
            private String context = "ai-agent";
            /** context 里的 extension, 那里跑 socket 应用连回本进程 */
            private String exten = "vca-outbound";
            /** 振铃多久没人接就放弃(ms) */
            private int ringTimeoutMs = 30_000;
            /** 从发起到媒体连进来的总等待上限(ms), 应大于 ringTimeoutMs */
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

            public String getPassword() {
                return password;
            }

            public void setPassword(String v) {
                this.password = v;
            }

            public String getEndpoint() {
                return endpoint;
            }

            public void setEndpoint(String v) {
                this.endpoint = v;
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

        public String getListenAddress() {
            return listenAddress;
        }

        public void setListenAddress(String v) {
            this.listenAddress = v;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int v) {
            this.port = v;
        }

        public String getMediaBindAddress() {
            return mediaBindAddress;
        }

        public void setMediaBindAddress(String v) {
            this.mediaBindAddress = v;
        }

        public int getMediaWaitMs() {
            return mediaWaitMs;
        }

        public void setMediaWaitMs(int v) {
            this.mediaWaitMs = v;
        }

        public int getHandshakeTimeoutMs() {
            return handshakeTimeoutMs;
        }

        public void setHandshakeTimeoutMs(int v) {
            this.handshakeTimeoutMs = v;
        }

        public Esl getEsl() {
            return esl;
        }

        public void setEsl(Esl v) {
            this.esl = v;
        }
    }

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
        /**
         * 句尾静音判停(ms)。电话上人说话停顿更短, 比浏览器的 800 紧。
         * 500 是实测值: 这段等待完整计入体感延迟, 再短就容易把人说话中间的停顿判成说完。
         */
        private int silenceMs = 500;
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

    public com.vca.telephony.provider.freeswitch.FreeSwitchConfig toFreeSwitchConfig() {
        return new com.vca.telephony.provider.freeswitch.FreeSwitchConfig(
                freeswitch.getListenAddress(), freeswitch.getPort(), sampleRate,
                freeswitch.getMediaBindAddress(), freeswitch.getMediaWaitMs(),
                freeswitch.getHandshakeTimeoutMs(), 128);
    }

    public com.vca.telephony.provider.freeswitch.EslConfig toEslConfig() {
        FreeSwitch.Esl e = freeswitch.getEsl();
        return new com.vca.telephony.provider.freeswitch.EslConfig(
                e.getHost(), e.getPort(), e.getPassword(), e.getEndpoint(), e.getContext(), e.getExten(),
                e.getRingTimeoutMs(), e.getAnswerWaitMs(), e.getConnectTimeoutMs());
    }

    /** 当前媒体服务器的外呼等待上限(ms), 外呼端点据此夹 HTTP 超时 */
    public int outboundAnswerWaitMs() {
        return provider == Provider.ASTERISK ? ami.getAnswerWaitMs() : freeswitch.getEsl().getAnswerWaitMs();
    }

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
        return new CallConfig(pacingMs, maxBufferedMs, ttsSampleRate, maxCallSeconds, greetingBargeIn,
                toneHangup, noSpeechHangupMs);
    }

    /** VAD 目标采样率固定 16k: Silero 要求, ASR 也按 16k 送。上行 8k 会被升采样到此。 */
    public VadConfig toVadConfig() {
        return new VadConfig(
                vad.getSpeechThreshold(), vad.getOnsetMs(), vad.getSilenceMs(),
                vad.getBargeThreshold(), vad.getBargeMs(), vad.getPrerollMs(),
                // 检测器固定 16k(Silero 的要求), 但交给识别的是线路原生采样率:
                // 8k 上采样到 16k 再喂宽带模型, 模型会在本该有高频摩擦音的地方瞎猜
                16_000, vad.isUseSilero(), "", vad.getBargeGraceMs(),
                // halfDuplex=false: 电话线路本就是全双工, 打断照常判
                // echoAware=false: 回声判别依赖"服务端知道下行音频"的时序模型, 电话侧下行走
                //                  定速缓冲(PacingBuffer), 时间轴与 Web 不同, 未验证
                false, false, false, 400, 1600, sampleRate);
    }

    // ---- getters / setters ----

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    public List<String> getTools() {
        return tools;
    }

    public List<String> getAgentTools() {
        return agentTools;
    }

    public void setAgentTools(List<String> agentTools) {
        this.agentTools = agentTools == null ? new ArrayList<>() : agentTools;
    }

    public String getTransferDialString() {
        return transferDialString;
    }

    public void setTransferDialString(String transferDialString) {
        this.transferDialString = transferDialString == null ? "" : transferDialString;
    }

    public String getKnowledgeOwner() {
        return knowledgeOwner;
    }

    public void setKnowledgeOwner(String knowledgeOwner) {
        this.knowledgeOwner = knowledgeOwner == null ? "" : knowledgeOwner;
    }

    public String getLlmModel() {
        return llmModel;
    }

    public void setLlmModel(String llmModel) {
        this.llmModel = llmModel == null ? "" : llmModel;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
    }

    public void setTools(List<String> tools) {
        this.tools = tools == null ? new ArrayList<>() : tools;
    }

    public FreeSwitch getFreeswitch() {
        return freeswitch;
    }

    public void setFreeswitch(FreeSwitch freeswitch) {
        this.freeswitch = freeswitch;
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

    public String getAsrModel() {
        return asrModel;
    }

    public void setAsrModel(String asrModel) {
        this.asrModel = asrModel == null ? "" : asrModel.strip();
    }

    public boolean isToneHangup() {
        return toneHangup;
    }

    public void setToneHangup(boolean toneHangup) {
        this.toneHangup = toneHangup;
    }

    public int getNoSpeechHangupMs() {
        return noSpeechHangupMs;
    }

    public void setNoSpeechHangupMs(int noSpeechHangupMs) {
        this.noSpeechHangupMs = noSpeechHangupMs;
    }

    public String getAsrVocabularyId() {
        return asrVocabularyId;
    }

    public void setAsrVocabularyId(String v) {
        this.asrVocabularyId = v == null ? "" : v.strip();
    }

    public String getErrorPrompt() {
        return errorPrompt;
    }

    public void setErrorPrompt(String errorPrompt) {
        this.errorPrompt = errorPrompt == null ? "" : errorPrompt;
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

    public List<MerchantProps> getMerchants() {
        return merchants;
    }

    public void setMerchants(List<MerchantProps> merchants) {
        this.merchants = merchants == null ? new ArrayList<>() : merchants;
    }

    /**
     * 组装商家目录。顶层配置即"默认商家": 没配 {@code merchants} 时所有来电都走它(单店部署),
     * 配了但号码没匹配上时也走它。每家没填的项同样回退到顶层, 免得为了改一句开场白把整套配置抄一遍。
     */
    public com.vca.telephony.merchant.MerchantRegistry toMerchantRegistry() {
        com.vca.telephony.merchant.Merchant fallback = new com.vca.telephony.merchant.Merchant(
                "", "默认", greeting, systemPrompt, knowledgeOwner, transferDialString,
                summary.getWebhookUrl(), ttsVoice);
        List<com.vca.telephony.merchant.Merchant> list = merchants.stream()
                .map(m -> new com.vca.telephony.merchant.Merchant(
                        m.getNumber(), m.getName(),
                        orDefault(m.getGreeting(), greeting),
                        // 商家人设是<b>追加</b>不是替换, 见 mergePrompt
                        mergePrompt(systemPrompt, m.getSystemPrompt()),
                        orDefault(m.getKnowledgeOwner(), knowledgeOwner),
                        orDefault(m.getTransferDialString(), transferDialString),
                        orDefault(m.getSummaryWebhook(), summary.getWebhookUrl()),
                        orDefault(m.getTtsVoice(), ttsVoice)))
                .toList();
        return new com.vca.telephony.merchant.MerchantRegistry(fallback, list);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 电话人设 + 这家商家的人设, <b>拼接而不是替换</b>。
     *
     * <p>线上事故: 给商家配了人设之后, AI 一口气说了将近 30 秒, 把下行缓冲(上限 30s)撑爆,
     * 后半句被丢弃 —— 听感就是"说一半突然没声音了"。原因是商家人设把电话人设整个顶掉了,
     * 而"必须简短、先给结论、最多两句、完全口语化、不要 markdown"这些约束全在电话人设里。
     *
     * <p>那些约束是<b>电话这个通道</b>的硬性要求, 与是哪家店无关: 电话没有屏幕, 没法滚动,
     * 也没法跳读, 一段话超过十几秒对面就会挂。所以任何商家都不该有办法把它们去掉,
     * 商家能加的只是"我是谁、我卖什么、什么不许说"。
     */
    private static String mergePrompt(String phonePrompt, String merchantPrompt) {
        if (merchantPrompt == null || merchantPrompt.isBlank()) {
            return phonePrompt;
        }
        if (phonePrompt == null || phonePrompt.isBlank()) {
            return merchantPrompt.strip();
        }
        return phonePrompt.strip() + "\n\n" + merchantPrompt.strip();
    }

    public Summary getSummary() {
        return summary;
    }

    public void setSummary(Summary summary) {
        this.summary = summary == null ? new Summary() : summary;
    }

    /** 摘要用的 LLM 参数。厂商留空 = 交给治理层按候选顺序选。 */
    public com.vca.domain.model.LlmConfig toSummaryLlmConfig() {
        com.vca.domain.enums.VendorType v = null;
        if (!summary.getVendor().isBlank()) {
            v = com.vca.domain.enums.VendorType.valueOf(summary.getVendor().trim().toUpperCase(java.util.Locale.ROOT));
        }
        // 摘要只输出三行, 512 token 足够; 温度压低, 要的是稳定复述而不是创作
        return new com.vca.domain.model.LlmConfig(v, summary.getModel(),
                com.vca.telephony.summary.CallSummarizer.PROMPT, 0.2, 512);
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
