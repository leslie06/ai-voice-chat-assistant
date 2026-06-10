package com.vca.provider.tts.qwen;

import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtime;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeAudioFormat;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeCallback;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeConfig;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeParam;
import com.google.gson.JsonObject;
import com.vca.domain.enums.AudioFormat;
import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.exception.ProviderException;
import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.TtsConfig;
import com.vca.domain.spi.TtsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 阿里云 Qwen-TTS 流式合成(DashScope Realtime, WebSocket)。
 *
 * <p>与 {@code AliyunTtsProvider}(CosyVoice) 并列, 但用的是另一套 Realtime 协议:
 * connect → session.update(设音色/格式) → 逐句 append_text → 服务端边收边吐
 * {@code response.audio.delta}(base64 PCM) → finish。把这套回调式 WebSocket 桥接成
 * Reactor {@link Flux}<{@link AudioChunk}>, 订阅取消(打断)时取消响应并关闭连接。
 *
 * <p>输出固定 PCM 24kHz 单声道 16bit, 与前端播放一致。
 */
public class QwenTtsProvider implements TtsProvider {

    private static final Logger log = LoggerFactory.getLogger(QwenTtsProvider.class);
    private static final String EVT_AUDIO_DELTA = "response.audio.delta";
    private static final String EVT_RESPONSE_DONE = "response.done";

    private final QwenTtsProperties props;

    public QwenTtsProvider(QwenTtsProperties props) {
        this.props = props;
    }

    @Override
    public VendorType vendor() {
        return VendorType.QWEN;
    }

    @Override
    public Flux<AudioChunk> synthesize(Flux<String> textSegments, TtsConfig cfg) {
        return Flux.<AudioChunk>create(sink -> {
            AtomicLong seq = new AtomicLong();
            AtomicReference<Disposable> textSub = new AtomicReference<>();
            // 计时基准: 用于定位首音/逐句合成延迟
            long t0 = System.currentTimeMillis();
            AtomicBoolean firstAudio = new AtomicBoolean(false);
            AtomicReference<QwenTtsRealtime> clientRef = new AtomicReference<>();
            AtomicLong sentSeq = new AtomicLong();
            // 响应生命周期记账: 每句 commit() 产生一个 response。session.finish 会立刻关闭连接,
            // 若此时末句的 response 仍在合成, 其音频会被截断(表现为"最后一句不出声")。
            // 因此只有当文本流已结束(textDone) 且 所有已提交 response 都收到 response.done
            // (responsesDone >= committed) 时, 才真正 finish() 收尾。
            AtomicInteger committed = new AtomicInteger();
            AtomicInteger responsesDone = new AtomicInteger();
            AtomicBoolean textDone = new AtomicBoolean(false);
            AtomicBoolean finishing = new AtomicBoolean(false);
            Runnable maybeFinish = () -> {
                if (textDone.get() && responsesDone.get() >= committed.get()
                        && finishing.compareAndSet(false, true)) {
                    QwenTtsRealtime c = clientRef.get();
                    try {
                        if (c != null) {
                            c.finish();   // 缓冲已空, 仅触发 session.finish → onClose → sink.complete
                        }
                    } catch (Exception e) {
                        sink.error(ProviderException.retryable(VendorType.QWEN, Capability.TTS,
                                "Qwen-TTS finish 失败: " + e.getMessage(), e));
                    }
                }
            };

            QwenTtsRealtimeParam param = QwenTtsRealtimeParam.builder()
                    .model(props.getModel())
                    .apikey(props.getApiKey())
                    .build();

            QwenTtsRealtime client = new QwenTtsRealtime(param, new QwenTtsRealtimeCallback() {
                @Override
                public void onEvent(JsonObject msg) {
                    try {
                        String type = msg.has("type") ? msg.get("type").getAsString() : "";
                        if (EVT_AUDIO_DELTA.equals(type)) {
                            String b64 = field(msg, "delta");
                            if (b64 == null) {
                                b64 = field(msg, "audio");
                            }
                            if (b64 != null && !b64.isEmpty()) {
                                if (firstAudio.compareAndSet(false, true)) {
                                    QwenTtsRealtime c = clientRef.get();
                                    log.debug("Qwen-TTS 首音到达, +{}ms (SDK firstAudioDelay={}ms)",
                                            System.currentTimeMillis() - t0,
                                            c == null ? -1 : c.getFirstAudioDelay());
                                }
                                byte[] pcm = Base64.getDecoder().decode(b64);
                                sink.next(new AudioChunk(pcm, AudioFormat.PCM, seq.getAndIncrement(), null, false));
                            }
                        } else if (EVT_RESPONSE_DONE.equals(type)) {
                            // 一句合成完毕; 凑齐全部 response 且文本流已结束才收尾, 避免截断末句
                            responsesDone.incrementAndGet();
                            maybeFinish.run();
                        } else if (type.contains("error") || type.contains("failed")) {
                            sink.error(ProviderException.retryable(VendorType.QWEN, Capability.TTS,
                                    "Qwen-TTS 事件错误: " + msg, null));
                        }
                    } catch (Exception e) {
                        sink.error(ProviderException.retryable(VendorType.QWEN, Capability.TTS,
                                "Qwen-TTS 解析音频失败: " + e.getMessage(), e));
                    }
                }

                @Override
                public void onClose(int code, String reason) {
                    sink.complete();
                }
            });

            clientRef.set(client);

            try {
                client.connect();
                client.updateSession(QwenTtsRealtimeConfig.builder()
                        .voice(cfg.voice())
                        .responseFormat(QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT)
                        // commit(而非 server_commit): 上游 SentenceSplitter 已切好句, 每句显式
                        // commit 立即触发合成。server_commit 下服务端要等"下一单元文本到达"才 flush
                        // 上一单元, 导致末句被压到 finish() 才合成 —— 表现为最后一句迟迟不出声。
                        .mode("commit")
                        .build());
                log.debug("Qwen-TTS 会话开始, model={}, voice={}", props.getModel(), cfg.voice());
            } catch (Exception e) {
                safeClose(client);
                sink.error(ProviderException.fatal(VendorType.QWEN, Capability.TTS,
                        "Qwen-TTS 连接失败: " + e.getMessage(), e));
                return;
            }

            // 逐句喂文本: 每句 append+commit 立即合成。文本流结束只是标记 textDone,
            // 真正的 finish()(关连接) 交给 maybeFinish 等末句 response.done 后再做, 防截断。
            textSub.set(textSegments.subscribe(
                    seg -> {
                        if (seg != null && !seg.isEmpty()) {
                            client.appendText(seg);
                            client.commit();   // 每句立即提交合成, 不等后文/finish
                            committed.incrementAndGet();
                            log.debug("Qwen-TTS 提交第{}句, +{}ms: {}",
                                    sentSeq.incrementAndGet(), System.currentTimeMillis() - t0, seg);
                        }
                    },
                    err -> {
                        safeClose(client);
                        sink.error(err);
                    },
                    () -> {
                        textDone.set(true);
                        maybeFinish.run();   // 若末句已 done 则立即收尾, 否则等 response.done 触发
                    }));

            // 下游取消(打断): 取消响应并关闭连接, 释放上游
            sink.onCancel(() -> {
                Disposable d = textSub.get();
                if (d != null) {
                    d.dispose();
                }
                try {
                    client.cancelResponse();
                } catch (Exception ignore) {
                }
                safeClose(client);
            });
        }, FluxSink.OverflowStrategy.BUFFER)
                // connect() 是阻塞式 WebSocket 握手, 放到弹性线程避免占用响应式事件循环线程
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static String field(JsonObject msg, String name) {
        return msg.has(name) && !msg.get(name).isJsonNull() ? msg.get(name).getAsString() : null;
    }

    private static void safeClose(QwenTtsRealtime client) {
        try {
            client.close();
        } catch (Exception ignore) {
        }
    }
}
