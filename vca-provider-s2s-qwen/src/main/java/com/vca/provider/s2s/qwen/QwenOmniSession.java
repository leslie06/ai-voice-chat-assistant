package com.vca.provider.s2s.qwen;

import com.alibaba.dashscope.audio.omni.OmniRealtimeAudioFormat;
import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConstants;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.exception.ProviderException;
import com.vca.domain.model.AudioFrame;
import com.vca.domain.model.Message;
import com.vca.domain.model.S2sConfig;
import com.vca.domain.model.S2sEvent;
import com.vca.domain.spi.S2sSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.Base64;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Qwen-Omni 的<b>持久全双工会话</b>: 一条 WebSocket 长连贯穿整段对话(多轮), 由<b>服务端 turn detection
 * (server VAD)</b>接管"谁说完、谁该说、何时打断", 而非应用侧 VAD 逐轮 commit。这是把 Omni 用出原生能力
 * (全双工 / 原生打断 / 低延迟地板)的形态, 取代 {@link QwenOmniS2sProvider#converse} 每轮一连接的伪级联用法。
 *
 * <p>流程: 订阅 {@link #events()} 触发建连 → {@code updateSession}(开 server VAD) → 回灌历史(conversation
 * items) → 持续 {@code appendAudio}; 服务端自动 commit + createResponse, 以 {@code response.audio.delta}
 * 流式回吐音频、{@code response.done} 收尾(<b>会话不关</b>); 服务端 VAD 判定用户开口时发
 * {@code input_audio_buffer.speech_started} —— 据此发 {@link S2sEvent.UserSpeechStarted} 并截断当前回复。
 *
 * <p>线程: {@code appendAudio}/SDK 回调可能跨线程; 用线程安全的 {@link FluxSink} 桥接, 建连(阻塞)放
 * {@link Schedulers#boundedElastic()}。建连完成前到达的音频先入 {@link #pending} 缓冲, 就绪后补发。
 */
public class QwenOmniSession implements S2sSession {

    private static final Logger log = LoggerFactory.getLogger(QwenOmniSession.class);

    /** 上行: 浏览器采集并降采样的 16k 单声道 PCM */
    private static final OmniRealtimeAudioFormat INPUT_FORMAT = OmniRealtimeAudioFormat.PCM_16000HZ_MONO_16BIT;
    /** 下行: Omni 输出 24k PCM(与三段式 TTS 下行采样率一致, 前端无需改播放链路) */
    private static final OmniRealtimeAudioFormat OUTPUT_FORMAT = OmniRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT;
    /** 服务端 VAD 类型(OpenAI Realtime 兼容) */
    private static final String SERVER_VAD = "server_vad";
    /** 建连前最多缓冲多少帧上行音频(约几百 ms), 超出丢最旧 —— 防止建连慢时无限堆积 */
    private static final int MAX_PENDING_FRAMES = 256;

    private final QwenOmniProperties props;
    private final S2sConfig cfg;
    private final List<Message> history;

    /** 下行事件汇; 订阅 events() 时赋值 */
    private final AtomicReference<FluxSink<S2sEvent>> sinkRef = new AtomicReference<>();
    /** 底层长连; 建连成功后赋值 */
    private volatile OmniRealtimeConversation conv;
    /** 是否已就绪(连接+配置+回灌历史完成), 就绪后上行音频直发、否则入 pending */
    private volatile boolean ready;
    /** 建连前到达的上行音频(base64), 就绪后补发 */
    private final Queue<String> pending = new ConcurrentLinkedQueue<>();
    /** 下行音频块序号 */
    private final AtomicLong seq = new AtomicLong();
    /** 关闭标志, 保证 close 幂等、阻止已关会话继续发音频 */
    private final AtomicBoolean closed = new AtomicBoolean();
    /** 诊断: 首帧上行是否已记日志 */
    private final AtomicBoolean firstAudioLogged = new AtomicBoolean();
    /** 诊断: 已记过日志的服务端事件类型(每类只记首次), 用于定位"说话没反应"卡在哪一步 */
    private final java.util.Set<String> loggedTypes = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public QwenOmniSession(QwenOmniProperties props, S2sConfig cfg, List<Message> history) {
        this.props = props;
        this.cfg = cfg;
        this.history = history;
    }

    @Override
    public Flux<S2sEvent> events() {
        return Flux.<S2sEvent>create(sink -> {
            sinkRef.set(sink);
            sink.onDispose(() -> {
                closed.set(true);
                safeClose(conv);
            });
            // 建连阻塞, 放到弹性线程, 不占 Netty 事件循环
            Schedulers.boundedElastic().schedule(this::connectAndConfigure);
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /** 在弹性线程上: 建连 → 开 server VAD → 回灌历史 → 置就绪并补发缓冲音频。 */
    private void connectAndConfigure() {
        FluxSink<S2sEvent> sink = sinkRef.get();
        if (closed.get() || sink == null) {
            return;
        }
        OmniRealtimeParam param = OmniRealtimeParam.builder()
                .model(QwenOmniS2sProvider.value(cfg == null ? null : cfg.model(), props.getModel()))
                .apikey(props.getApiKey())
                .build();
        OmniRealtimeConversation c = new OmniRealtimeConversation(param, new OmniRealtimeCallback() {
            @Override
            public void onEvent(JsonObject event) {
                handleEvent(event);
            }

            @Override
            public void onClose(int code, String reason) {
                FluxSink<S2sEvent> s = sinkRef.get();
                if (s != null) {
                    s.complete();   // 连接关闭即会话结束(重复 complete 无副作用)
                }
            }
        });
        try {
            c.connect();
            c.updateSession(sessionConfig());
            seedHistory(c, history);
            this.conv = c;
            this.ready = true;
            flushPending(c);
            log.info("Qwen-Omni 持久会话就绪(服务端 VAD 接管), model={}, voice={}, 历史={} 条, turnDetection(threshold={},silenceMs={},prefixMs={})",
                    param.getModel(), QwenOmniS2sProvider.value(cfg == null ? null : cfg.voice(), props.getVoice()),
                    history == null ? 0 : history.size(), props.getTurnDetectionThreshold(),
                    props.getTurnDetectionSilenceMs(), props.getTurnDetectionPrefixPaddingMs());
        } catch (Exception e) {
            sink.error(ProviderException.fatal(VendorType.QWEN, Capability.S2S,
                    "Qwen-Omni 持久会话建连失败: " + e.getMessage(), e));
            safeClose(c);
        }
    }

    @Override
    public void pushAudio(AudioFrame frame) {
        if (closed.get() || frame == null || frame.size() == 0) {
            return;
        }
        String b64 = Base64.getEncoder().encodeToString(frame.data());
        OmniRealtimeConversation c = conv;
        if (ready && c != null) {
            try {
                c.appendAudio(b64);
                if (firstAudioLogged.compareAndSet(false, true)) {
                    log.info("Qwen-Omni 持久会话: 已开始上行麦克风音频(等待服务端 VAD 判停并回复)");
                }
            } catch (Exception e) {
                log.warn("appendAudio 失败: {}", e.toString());
            }
        } else if (pending.size() < MAX_PENDING_FRAMES) {
            pending.add(b64);   // 建连尚未就绪, 先缓冲
        }
    }

    private void flushPending(OmniRealtimeConversation c) {
        String b64;
        while ((b64 = pending.poll()) != null) {
            try {
                c.appendAudio(b64);
            } catch (Exception e) {
                log.warn("补发缓冲音频失败: {}", e.toString());
            }
        }
    }

    /** 分发服务端事件 → 下行 {@link S2sEvent}; server VAD 开口信号先截断当前回复再上报。 */
    private void handleEvent(JsonObject event) {
        FluxSink<S2sEvent> sink = sinkRef.get();
        if (sink == null) {
            return;
        }
        String type = QwenOmniS2sProvider.optString(event, OmniRealtimeConstants.PROTOCOL_TYPE);
        if (type == null) {
            return;
        }
        // 诊断: 每类服务端事件首次出现时记一条 —— 据此判断"说话没反应"卡在哪:
        // 只见 session.created/无 speech_started=音频/VAD 没生效; 见 speech_stopped 却无 response.*=服务端未自动回复。
        if (loggedTypes.add(type)) {
            log.info("Qwen-Omni 持久会话事件(首次): {}", type);
        }
        switch (type) {
            case "error" -> sink.error(ProviderException.retryable(VendorType.QWEN, Capability.S2S,
                    "Qwen-Omni 服务端错误: "
                            + (event.has("error") ? event.get("error").toString() : event.toString()), null));
            case OmniRealtimeConstants.PROTOCOL_RESPONSE_TYPE_SESSION_FINISHED -> sink.complete();
            // 服务端 VAD 判定用户开口: 全双工打断 —— 先截断机器人当前回复, 再上报让接入层冲掉前端播放缓冲
            case "input_audio_buffer.speech_started" -> {
                cancelResponse();
                sink.next(new S2sEvent.UserSpeechStarted());
            }
            default -> {
                S2sEvent ev = mapEvent(event, type, seq);
                if (ev != null) {
                    sink.next(ev);
                }
            }
        }
    }

    @Override
    public void cancelResponse() {
        OmniRealtimeConversation c = conv;
        if (c != null) {
            try {
                c.cancelResponse();
            } catch (Exception ignore) {
                // 当前无进行中的回复, cancel 无意义, 忽略
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        safeClose(conv);
        FluxSink<S2sEvent> sink = sinkRef.get();
        if (sink != null) {
            sink.complete();
        }
    }

    // ---- 纯逻辑(包级可见, 便于单测) ----

    /**
     * 把一个服务端事件映射成下行 {@link S2sEvent}; 不可识别/握手类事件返回 {@code null}。
     * {@code error / session.finished / speech_started} 这类带副作用或终结语义的在 {@link #handleEvent} 里单独处理。
     *
     * @param seq 音频块序号源(仅 audio.delta 会自增取号)
     */
    static S2sEvent mapEvent(JsonObject event, String type, AtomicLong seq) {
        switch (type) {
            case OmniRealtimeConstants.PROTOCOL_RESPONSE_TYPE_AUDIO_DELTA -> {
                String b64 = QwenOmniS2sProvider.optString(event, "delta");
                if (b64 == null || b64.isEmpty()) {
                    return null;
                }
                return new S2sEvent.AudioDelta(Base64.getDecoder().decode(b64), seq.getAndIncrement());
            }
            case OmniRealtimeConstants.PROTOCOL_RESPONSE_TYPE_AUDIO_TRANSCRIPT_DELTA -> {
                String text = QwenOmniS2sProvider.optString(event, "delta");
                return text == null || text.isEmpty() ? null : new S2sEvent.AssistantText(text);
            }
            case "conversation.item.input_audio_transcription.completed" -> {
                String text = QwenOmniS2sProvider.optString(event, "transcript");
                return text == null || text.isBlank() ? null : new S2sEvent.UserTranscript(text);
            }
            case OmniRealtimeConstants.PROTOCOL_RESPONSE_TYPE_RESPONSE_DONE -> {
                return new S2sEvent.ResponseDone();   // 本次回复结束, 会话继续
            }
            default -> {
                return null;   // session.created / response.created 等握手事件无需处理
            }
        }
    }

    /** 会话配置: 纯语音, <b>开启服务端 turn detection</b>(server VAD)+ 输入转写。 */
    private OmniRealtimeConfig sessionConfig() {
        return OmniRealtimeConfig.builder()
                .modalities(List.of(OmniRealtimeModality.TEXT, OmniRealtimeModality.AUDIO))
                .voice(QwenOmniS2sProvider.value(cfg == null ? null : cfg.voice(), props.getVoice()))
                .inputAudioFormat(INPUT_FORMAT)
                .outputAudioFormat(OUTPUT_FORMAT)
                .enableInputAudioTranscription(true)
                .enableTurnDetection(true)
                .turnDetectionType(SERVER_VAD)
                .turnDetectionThreshold(props.getTurnDetectionThreshold())
                .prefixPaddingMs(props.getTurnDetectionPrefixPaddingMs())
                .turnDetectionSilenceDurationMs(props.getTurnDetectionSilenceMs())
                .build();
    }

    /** 回灌历史: 持久会话靠 conversation items 让模型记住上文(含人设 system), 而非每轮塞 instructions。 */
    private void seedHistory(OmniRealtimeConversation c, List<Message> history) {
        String persona = cfg == null ? null : cfg.systemPrompt();
        if (persona != null && !persona.isBlank()) {
            c.createItem(historyItem(Message.system(persona)));
        }
        if (history == null) {
            return;
        }
        for (Message m : history) {
            if (m == null || m.content() == null || m.content().isBlank()) {
                continue;
            }
            // 人设若已在 systemPrompt 里给过, 历史里的 system 不重复注入
            if (m.role() == Message.Role.SYSTEM && persona != null && !persona.isBlank()) {
                continue;
            }
            c.createItem(historyItem(m));
        }
    }

    /**
     * 把一条历史消息构造成 OpenAI Realtime 的 conversation item:
     * {@code {type:"message", role:..., content:[{type:..., text:...}]}}。
     * 用户/系统文本用 {@code input_text}, 助手文本用 {@code text}(协议区分输入/输出内容类型)。包级可见以便单测。
     */
    static JsonObject historyItem(Message m) {
        String role = switch (m.role()) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
        };
        String contentType = m.role() == Message.Role.ASSISTANT ? "text" : "input_text";
        JsonObject content = new JsonObject();
        content.addProperty("type", contentType);
        content.addProperty("text", m.content());
        JsonArray contents = new JsonArray();
        contents.add(content);
        JsonObject item = new JsonObject();
        item.addProperty("type", "message");
        item.addProperty("role", role);
        item.add("content", contents);
        return item;
    }

    private static void safeClose(OmniRealtimeConversation conv) {
        if (conv == null) {
            return;
        }
        try {
            conv.close();
        } catch (Exception ignore) {
            // 关闭幂等, 忽略
        }
    }
}
