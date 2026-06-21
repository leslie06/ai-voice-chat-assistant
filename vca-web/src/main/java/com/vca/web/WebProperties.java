package com.vca.web;

import com.vca.domain.enums.VendorType;
import com.vca.orchestrator.vad.VadConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * 接入层默认会话参数。每条新 WebSocket 连接据此构建一路三段式会话。
 * 这里只选"主厂商", 备选与配额由 vca-gateway 的候选配置接管。
 */
@ConfigurationProperties(prefix = "vca.web")
public class WebProperties {

    /** WebSocket 端点路径 */
    private String path = "/ws/voice";

    /** 对话模式: {@code pipeline}(三段式 ASR→LLM→TTS) 或 {@code s2s}(原生端到端语音大模型)。 */
    private String mode = "pipeline";

    private VendorType asrVendor = VendorType.ALIYUN;
    private VendorType llmVendor = VendorType.DEEPSEEK;
    /** LLM 模型; 留空则用 gateway 候选里配置的 model */
    private String llmModel = "";
    private VendorType ttsVendor = VendorType.ALIYUN;
    private String ttsVoice = "longxiaochun";

    /** 端到端模式厂商(mode=s2s 时生效) */
    private VendorType s2sVendor = VendorType.QWEN;
    /** 端到端模型; 留空用 provider 默认 */
    private String s2sModel = "";
    /** 端到端音色; 留空用 provider 默认 */
    private String s2sVoice = "";

    /**
     * 持久 S2S(P2): 端到端模式下免提是否用<b>一条长连 + 服务端 VAD</b>(真全双工/原生打断), 取代每轮一连接、
     * 应用侧 VAD 判停的伪级联用法。仅在 {@code mode=s2s} + 免提下生效; PTT/三段式不受影响。
     * 灰度开关 —— 真机若发现服务端 VAD 在当前网络/回声条件下不稳, 关掉即回退每轮 S2S。
     */
    private boolean s2sPersistent = false;

    /**
     * function-calling(技能/工具)总开关。开则三段式语音与打字回合给模型下发已注册工具
     * (点歌、查时间……), 模型可按需调用; 关则退回纯文本对话(正则点歌快路径不受影响)。
     * 仅作用于走 LLM 的回合; 端到端 s2s 语音不读它。
     */
    private boolean toolsEnabled = true;

//    private String systemPrompt = "You are a voice assistant. Always reply in English, "
//            + "in a short, conversational, spoken style. Avoid long paragraphs and lists. "
//            + "Only answer the user's current sentence; do not restate, repeat or continue "
//            + "what was already said.";

    private String systemPrompt = "你是一个语音助手，用口语化、简短的中文对话，避免长段落和列表。"
            + "你和用户正处于一段持续的对话中，上面的历史消息只是已经发生过的上下文，"
            + "请只回答用户【当前这一句】、自然地接着往下说。"
            + "【重要】绝对不要复述、重复或续写你之前已经说过的内容，也不要重复打招呼或自我介绍。";



    /** 共享访问令牌: 连 WebSocket 时用 {@code ?token=} 传入, 不匹配直接拒绝。
     *  留空=不校验(本地开发)。生产用环境变量 {@code VCA_AUTH_TOKEN} 提供, 切勿写进仓库。 */
    private String authToken = "";

    /** 单条 WebSocket 会话最长存活秒数, 到点强制关闭(防"连着不挂"持续烧 API 账单)。0=不限。 */
    private int maxSessionSeconds = 6000;

    /** 同时在线 WebSocket 连接数上限, 超出直接拒绝新连接。0=不限。 */
    private int maxConnections = 8;

    /** 历史滑动窗口: 仅保留最近这么多条 user/assistant 消息(system 提示始终保留)。
     *  防止历史无限膨胀诱导模型把上一轮回复也带出来。≈8 轮对话。 */
    private int historyMaxMessages = 16;

    /** 本地曲库目录: 点歌时先在这里按文件名匹配整首播放, 找不到再回退 iTunes 试听。
     *  默认用户主目录下的 Music 文件夹。 */
    private String musicDir = System.getProperty("user.home") + "/Music";

    /** 高德开放平台 Web 服务 key(查天气技能用)。留空则 get_weather 工具不注册。
     *  申请: https://console.amap.com → 应用管理 → Key(类型选"Web服务")。 */
    private String amapKey = "";

    /** 免提 VAD/断句参数(原先在前端, 现收口到后端) */
    @NestedConfigurationProperty
    private Vad vad = new Vad();

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    /** 是否端到端模式 */
    public boolean isS2sMode() {
        return "s2s".equalsIgnoreCase(mode);
    }

    public VendorType getAsrVendor() {
        return asrVendor;
    }

    public void setAsrVendor(VendorType asrVendor) {
        this.asrVendor = asrVendor;
    }

    public VendorType getLlmVendor() {
        return llmVendor;
    }

    public void setLlmVendor(VendorType llmVendor) {
        this.llmVendor = llmVendor;
    }

    public String getLlmModel() {
        return llmModel;
    }

    public void setLlmModel(String llmModel) {
        this.llmModel = llmModel;
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

    public VendorType getS2sVendor() {
        return s2sVendor;
    }

    public void setS2sVendor(VendorType s2sVendor) {
        this.s2sVendor = s2sVendor;
    }

    public String getS2sModel() {
        return s2sModel;
    }

    public void setS2sModel(String s2sModel) {
        this.s2sModel = s2sModel;
    }

    public String getS2sVoice() {
        return s2sVoice;
    }

    public void setS2sVoice(String s2sVoice) {
        this.s2sVoice = s2sVoice;
    }

    public boolean isS2sPersistent() {
        return s2sPersistent;
    }

    public void setS2sPersistent(boolean s2sPersistent) {
        this.s2sPersistent = s2sPersistent;
    }

    public boolean isToolsEnabled() {
        return toolsEnabled;
    }

    public void setToolsEnabled(boolean toolsEnabled) {
        this.toolsEnabled = toolsEnabled;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public int getMaxSessionSeconds() {
        return maxSessionSeconds;
    }

    public void setMaxSessionSeconds(int maxSessionSeconds) {
        this.maxSessionSeconds = maxSessionSeconds;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getHistoryMaxMessages() {
        return historyMaxMessages;
    }

    public void setHistoryMaxMessages(int historyMaxMessages) {
        this.historyMaxMessages = historyMaxMessages;
    }

    public String getMusicDir() {
        return musicDir;
    }

    public void setMusicDir(String musicDir) {
        this.musicDir = musicDir;
    }

    public String getAmapKey() {
        return amapKey;
    }

    public void setAmapKey(String amapKey) {
        this.amapKey = amapKey;
    }

    public Vad getVad() {
        return vad;
    }

    public void setVad(Vad vad) {
        this.vad = vad;
    }

    /**
     * 免提 VAD 可调参数, 默认值与 {@link VadConfig#defaults()} 一致。
     * 通过 {@code vca.web.vad.*} 覆盖。
     */
    public static class Vad {
        private double speechThreshold = 0.015;
        private int onsetMs = 150;
        private int silenceMs = 800;
        private double bargeThreshold = 0.020;
        private int bargeMs = 250;
        // 预滚默认 > barge-ms: 打断确认的实际墙钟时间会超过 barge-ms(音量起伏致累减),
        // 预滚不留余量就会切掉打断时的头一两个字。详见 application.yml 注释。
        private int prerollMs = 700;
        private int targetSampleRate = 16000;
        /** 启用 Silero(ONNX)VAD 替代能量阈值法。模型加载失败会自动降级回能量法。 */
        private boolean useSilero = false;
        /** Silero 模型路径; 空则用打包进 classpath 的默认 silero_vad.onnx。 */
        private String sileroModelPath = "";
        /** 起播保护期(ms): 机器人开口后这么久内不判打断, 抗"自己回声掐断自己"。0=不保护。 */
        private int bargeGraceMs = 0;
        /** 半双工: 机器人说话时不收麦/不语音打断, 外放无回声消除时靠它断掉自打断死循环。默认开, 戴耳机可关。 */
        private boolean halfDuplex = true;

        public VadConfig toConfig() {
            // 阈值有两套尺度: 能量法是 RMS(≈0.01~0.1), Silero 是人声概率(0~1)。
            // 开了 Silero 但阈值仍停留在能量尺度(≤0.1)时, 自动换成概率尺度的合理默认(0.5/0.6),
            // 让"只翻一个开关"就能用; 显式配了概率尺度阈值(>0.1)则尊重用户取值。
            double speech = speechThreshold;
            double barge = bargeThreshold;
            // 逐项改写(不再整体): 开了 Silero 但某个阈值仍是能量尺度(≤0.1)时, 单独换成概率尺度默认,
            // 这样单独调开口阈值(如 0.3)不会把打断阈值连带留在能量尺度。显式配概率尺度(>0.1)则尊重取值。
            if (useSilero) {
                if (speech <= 0.1) {
                    speech = 0.5;
                }
                if (barge <= 0.1) {
                    // 0.45 而非 0.6: 实测 Silero 对正常说话的人声概率常在 0.6~0.8 抖动, 阈值贴到 0.6 会让
                    // bargeMs 涨跌相抵、凑不满判定时长; 0.45 留足余量(静音≈0, 不会误触发), 打断才积累得起来。
                    barge = 0.45;
                }
            }
            return new VadConfig(speech, onsetMs, silenceMs, barge, bargeMs, prerollMs, targetSampleRate,
                    useSilero, sileroModelPath, bargeGraceMs, halfDuplex);
        }

        public double getSpeechThreshold() {
            return speechThreshold;
        }

        public void setSpeechThreshold(double speechThreshold) {
            this.speechThreshold = speechThreshold;
        }

        public int getOnsetMs() {
            return onsetMs;
        }

        public void setOnsetMs(int onsetMs) {
            this.onsetMs = onsetMs;
        }

        public int getSilenceMs() {
            return silenceMs;
        }

        public void setSilenceMs(int silenceMs) {
            this.silenceMs = silenceMs;
        }

        public double getBargeThreshold() {
            return bargeThreshold;
        }

        public void setBargeThreshold(double bargeThreshold) {
            this.bargeThreshold = bargeThreshold;
        }

        public int getBargeMs() {
            return bargeMs;
        }

        public void setBargeMs(int bargeMs) {
            this.bargeMs = bargeMs;
        }

        public int getPrerollMs() {
            return prerollMs;
        }

        public void setPrerollMs(int prerollMs) {
            this.prerollMs = prerollMs;
        }

        public int getTargetSampleRate() {
            return targetSampleRate;
        }

        public void setTargetSampleRate(int targetSampleRate) {
            this.targetSampleRate = targetSampleRate;
        }

        public boolean isUseSilero() {
            return useSilero;
        }

        public void setUseSilero(boolean useSilero) {
            this.useSilero = useSilero;
        }

        public String getSileroModelPath() {
            return sileroModelPath;
        }

        public void setSileroModelPath(String sileroModelPath) {
            this.sileroModelPath = sileroModelPath;
        }

        public int getBargeGraceMs() {
            return bargeGraceMs;
        }

        public void setBargeGraceMs(int bargeGraceMs) {
            this.bargeGraceMs = bargeGraceMs;
        }

        public boolean isHalfDuplex() {
            return halfDuplex;
        }

        public void setHalfDuplex(boolean halfDuplex) {
            this.halfDuplex = halfDuplex;
        }
    }
}
