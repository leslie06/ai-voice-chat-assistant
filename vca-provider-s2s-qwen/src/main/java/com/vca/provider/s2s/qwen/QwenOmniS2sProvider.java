package com.vca.provider.s2s.qwen;

import com.alibaba.dashscope.audio.omni.OmniRealtimeAudioFormat;
import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConstants;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.google.gson.JsonObject;
import com.vca.domain.enums.AudioFormat;
import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.exception.ProviderException;
import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.AudioFrame;
import com.vca.domain.model.Message;
import com.vca.domain.model.S2sConfig;
import com.vca.domain.spi.S2sProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通义千问 Qwen-Omni 实时端到端语音(Speech-to-Speech)。直接吞上行音频、吐下行音频,
 * 厂商内部融合 ASR+LLM+TTS —— 不经过文字中间态, 因此延迟更低、保留语气/情绪。
 *
 * <p>走 DashScope SDK 的 {@link OmniRealtimeConversation}(OpenAI Realtime 兼容协议):
 * {@code connect → updateSession → appendAudio* → commit → createResponse}, 服务端以
 * {@code response.audio.delta} 流式回吐 base64 PCM(24k), {@code response.done} 收尾。
 *
 * <p><b>每回合一条连接</b>: 契约 {@link #converse} 每次调用对应用户的一轮说话 —— 编排层的 VAD
 * 已在应用侧做了开口/句尾判定, 故这里<b>关闭服务端 turn detection</b>, 由"上行音频流结束"驱动 commit。
 * 打断时下游取消订阅 → {@link FluxSink#onDispose} 调 {@code cancelResponse + close} 立即止声。
 *
 * <p>线程: SDK 回调在 WebSocket 线程触发, 经线程安全的 {@link FluxSink} 桥接到 Reactor;
 * 建连(阻塞)放到 {@link Schedulers#boundedElastic()}, 不占用 Netty 事件循环。
 */
public class QwenOmniS2sProvider implements S2sProvider {

    private static final Logger log = LoggerFactory.getLogger(QwenOmniS2sProvider.class);

    /** 上行: 浏览器采集并经 VAD 过滤的 16k 单声道 PCM */
    private static final OmniRealtimeAudioFormat INPUT_FORMAT = OmniRealtimeAudioFormat.PCM_16000HZ_MONO_16BIT;
    /** 下行: Omni 输出 24k PCM(与现有 TTS 下行采样率一致, 前端无需改播放链路) */
    private static final OmniRealtimeAudioFormat OUTPUT_FORMAT = OmniRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT;

    private final QwenOmniProperties props;

    public QwenOmniS2sProvider(QwenOmniProperties props) {
        this.props = props;
    }

    @Override
    public VendorType vendor() {
        return VendorType.QWEN;
    }

    @Override
    public Flux<AudioChunk> converse(Flux<AudioFrame> audio, List<Message> history, S2sConfig cfg) {
        return Flux.<AudioChunk>create(sink -> startConversation(sink, audio, history, cfg),
                        FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 在 boundedElastic 线程上建连、配置会话、回灌历史、订阅上行音频, 并把下行事件桥接给 {@code sink}。 */
    private void startConversation(FluxSink<AudioChunk> sink, Flux<AudioFrame> audio,
                                   List<Message> history, S2sConfig cfg) {
        AtomicLong seq = new AtomicLong();
        AtomicBoolean responded = new AtomicBoolean(false);

        OmniRealtimeParam param = OmniRealtimeParam.builder()
                .model(value(cfg.model(), props.getModel()))
                .apikey(props.getApiKey())
                .build();

        OmniRealtimeConversation conv = new OmniRealtimeConversation(param,
                new OmniRealtimeCallback() {
                    @Override
                    public void onEvent(JsonObject event) {
                        handleEvent(event, sink, seq, responded);
                    }

                    @Override
                    public void onClose(int code, String reason) {
                        sink.complete();   // 连接关闭即视为本轮结束(重复 complete 无副作用)
                    }
                });

        try {
            conv.connect();
            conv.updateSession(sessionConfig(cfg));
        } catch (Exception e) {
            sink.error(ProviderException.fatal(VendorType.QWEN, Capability.S2S,
                    "Qwen-Omni 连接失败: " + e.getMessage(), e));
            safeClose(conv);
            return;
        }
        log.debug("Qwen-Omni 会话开启, model={}, voice={}, 历史={} 条",
                param.getModel(), value(cfg.voice(), props.getVoice()), history == null ? 0 : history.size());

        // 上行: 逐帧 base64 送入; 上行流结束(VAD 判停)→ 提交并请求一次回复。
        // 历史直接写进 instructions(每轮都会被模型读到), 比 conversation.item 更可靠:
        // 模型据此知道"已经聊过、已经打过招呼", 不再把每轮当对话开始而重复开场白。
        String instructions = buildInstructions(cfg, history);
        Disposable upstream = audio.filter(f -> f.size() > 0)
                .subscribe(
                        f -> conv.appendAudio(Base64.getEncoder().encodeToString(f.data())),
                        err -> sink.error(err),
                        () -> {
                            try {
                                conv.commit();
                                conv.createResponse(instructions, List.of(OmniRealtimeModality.TEXT,
                                        OmniRealtimeModality.AUDIO));
                            } catch (Exception e) {
                                sink.error(ProviderException.retryable(VendorType.QWEN, Capability.S2S,
                                        "提交音频/请求回复失败: " + e.getMessage(), e));
                            }
                        });

        // 收尾(正常结束/出错/被打断): 停上行, 未完成则 cancel 止声, 关连接。
        sink.onDispose(() -> {
            upstream.dispose();
            if (!responded.get()) {
                try {
                    conv.cancelResponse();
                } catch (Exception ignore) {
                    // 回复尚未开始或已结束, cancel 无意义, 忽略
                }
            }
            safeClose(conv);
        });
    }

    /** 分发服务端事件: 音频块→下行播放, 字幕→透传, done→收尾, error→报错。 */
    private void handleEvent(JsonObject event, FluxSink<AudioChunk> sink, AtomicLong seq, AtomicBoolean responded) {
        String type = event.has(OmniRealtimeConstants.PROTOCOL_TYPE)
                ? event.get(OmniRealtimeConstants.PROTOCOL_TYPE).getAsString() : "";
        switch (type) {
            case OmniRealtimeConstants.PROTOCOL_RESPONSE_TYPE_AUDIO_DELTA -> {
                String b64 = optString(event, "delta");
                if (b64 != null && !b64.isEmpty()) {
                    byte[] pcm = Base64.getDecoder().decode(b64);
                    sink.next(AudioChunk.of(pcm, AudioFormat.PCM, seq.getAndIncrement()));
                }
            }
            case OmniRealtimeConstants.PROTOCOL_RESPONSE_TYPE_AUDIO_TRANSCRIPT_DELTA -> {
                String text = optString(event, "delta");
                if (text != null && !text.isEmpty()) {
                    // 机器人回复的转写: 空音频 + 文本(ASSISTANT 侧字幕)
                    sink.next(new AudioChunk(new byte[0], AudioFormat.PCM, seq.getAndIncrement(), text, false));
                }
            }
            // 用户语音转写完成: 用于在页面显示"你说了什么"(需 enableInputAudioTranscription)
            case "conversation.item.input_audio_transcription.completed" -> {
                String text = optString(event, "transcript");
                if (text != null && !text.isBlank()) {
                    sink.next(AudioChunk.userTranscript(text, seq.getAndIncrement()));
                }
            }
            case OmniRealtimeConstants.PROTOCOL_RESPONSE_TYPE_RESPONSE_DONE,
                 OmniRealtimeConstants.PROTOCOL_RESPONSE_TYPE_SESSION_FINISHED -> {
                responded.set(true);
                sink.complete();
            }
            case "error" -> {
                String msg = event.has("error") ? event.get("error").toString() : event.toString();
                sink.error(ProviderException.retryable(VendorType.QWEN, Capability.S2S,
                        "Qwen-Omni 服务端错误: " + msg, null));
            }
            default -> {
                // session.created / response.created 等握手事件无需处理
            }
        }
    }

    /**
     * 会话配置: 纯语音对话, 关闭服务端 turn detection(由应用侧 VAD 驱动回合);
     * 开启输入音频转写, 让服务端回吐"用户说了什么", 前端可显示用户字幕。
     */
    private OmniRealtimeConfig sessionConfig(S2sConfig cfg) {
        return OmniRealtimeConfig.builder()
                .modalities(List.of(OmniRealtimeModality.TEXT, OmniRealtimeModality.AUDIO))
                .voice(value(cfg.voice(), props.getVoice()))
                .inputAudioFormat(INPUT_FORMAT)
                .outputAudioFormat(OUTPUT_FORMAT)
                .enableTurnDetection(false)
                .enableInputAudioTranscription(true)
                .build();
    }

    /**
     * 把人设 + 对话历史拼成本轮 instructions。Omni 每路连接无状态, 历史写进 instructions(每轮必被读到)
     * 比 conversation.item 更可靠地让模型"记得上文、已经打过招呼", 从而不再重复开场白。
     */
    private String buildInstructions(S2sConfig cfg, List<Message> history) {
        String base = cfg.systemPrompt() == null ? "" : cfg.systemPrompt();
        if (history == null) {
            return base;
        }
        StringBuilder convo = new StringBuilder();
        for (Message m : history) {
            if (m.role() == Message.Role.SYSTEM || m.content() == null || m.content().isBlank()) {
                continue;
            }
            convo.append(m.role() == Message.Role.USER ? "用户：" : "你：").append(m.content()).append('\n');
        }
        if (convo.isEmpty()) {
            return base;   // 首轮无历史: 按人设(可含开场白)正常开场
        }
        return base + "\n\n【你和用户已经在通话/对话中，以下是目前为止的记录。请基于它自然地接着说，"
                + "不要重复开场白或自我介绍，不要重复你已经说过的话】\n" + convo;
    }

    /** 取字符串字段, 缺失/为 null 返回 null。包级可见以便单测。 */
    static String optString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    /** 优先用会话/候选给的值, 为空则回退 provider 默认。包级可见以便单测。 */
    static String value(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private static void safeClose(OmniRealtimeConversation conv) {
        try {
            conv.close();
        } catch (Exception ignore) {
            // 关闭幂等, 忽略
        }
    }
}
