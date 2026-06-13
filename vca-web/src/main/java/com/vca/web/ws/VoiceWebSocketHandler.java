package com.vca.web.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vca.domain.enums.VendorType;
import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.AudioFrame;
import com.vca.domain.model.MusicTrack;
import com.vca.domain.model.SessionContext;
import com.vca.domain.spi.MusicProvider;
import com.vca.orchestrator.session.ConversationSession;
import com.vca.orchestrator.session.TurnListener;
import com.vca.orchestrator.vad.HandsFreeVad;
import com.vca.orchestrator.vad.PcmAudio;
import com.vca.orchestrator.vad.VadConfig;
import com.vca.orchestrator.vad.VoiceActivityDetector;
import com.vca.web.session.ConversationSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 语音会话 WebSocket 处理器(响应式)。
 *
 * <p><b>设计要点</b>: 所有"对话流程逻辑"都在后端 —— 浏览器只负责采集麦克风、播放音频、连 WS。
 * 开口检测、句尾断句(自动提交)、插话打断、免提状态机、预滚、降采样, 统统由后端
 * {@link HandsFreeVad} + 本类驱动。前端只是"哑终端": 持续上传原始 PCM, 收到事件照做。
 *
 * <p>协议(单连接, 多回合):
 * <ul>
 *   <li>客户端 → 服务端
 *     <ul>
 *       <li><b>二进制帧</b>: 原始上行 PCM(小端 16bit 单声道, 采样率由 {@code mic} 消息声明);</li>
 *       <li><b>文本帧(JSON)</b>:
 *         {@code {"type":"mic","sampleRate":48000}} 声明上行采样率;
 *         {@code {"type":"mode","value":"handsfree"|"idle"}} 开/关免提;
 *         {@code {"type":"ptt","value":"start"|"stop"}} 按住说话起止;
 *         {@code {"type":"text","text":...}} 文本输入;
 *         {@code {"type":"model","vendor":...,"model":...}} 切换打字所用大模型;
 *         {@code {"type":"voice","vendor":"aliyun"|"qwen","value":"龙安欢/Cherry…"}} 切换三段式 TTS 厂商+音色;
 *         {@code {"type":"engine","value":"pipeline"|"s2s"}} 切换对话模式(三段式/端到端);
 *         {@code {"type":"barge_in"}} 手动打断。</li>
 *     </ul></li>
 *   <li>服务端 → 客户端
 *     <ul>
 *       <li><b>二进制帧</b>: TTS 合成的音频块(24k PCM);</li>
 *       <li><b>文本帧(JSON)</b>: {@code asr}/{@code reply}/{@code chunk} 字幕,
 *         {@code turn_end}, {@code interrupted}, {@code error},
 *         {@code {"type":"music","action":"play","title":...,"artist":...,"url":...,"cover":...}}
 *         点歌(前端用 &lt;audio&gt; 播放), 找不到时 {@code action:"notfound"},
 *         以及 {@code {"type":"state","value":...,"label":...}} 让前端显示当前状态。</li>
 *     </ul></li>
 * </ul>
 */
public class VoiceWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VoiceWebSocketHandler.class);

    private final ConversationSessionFactory sessionFactory;
    private final ObjectMapper mapper;
    private final VadConfig vadConfig;
    /** 逐路会话的 VAD 打分器工厂: Silero 检测器带 RNN 状态, 必须每连接一个实例, 故用工厂而非单例。 */
    private final Supplier<VoiceActivityDetector> vadDetectorFactory;
    private final MusicProvider musicProvider;

    /** 共享访问令牌; 空=不校验。 */
    private final String authToken;
    /** 单会话最长存活秒数; <=0=不限。 */
    private final int maxSessionSeconds;
    /** 同时在线连接上限; <=0=不限。 */
    private final int maxConnections;
    /** 当前在线连接数。handler 是单例 Bean, 此计数全局生效。 */
    private final AtomicInteger activeConnections = new AtomicInteger();

    public VoiceWebSocketHandler(ConversationSessionFactory sessionFactory, ObjectMapper mapper, VadConfig vadConfig,
                                 Supplier<VoiceActivityDetector> vadDetectorFactory, MusicProvider musicProvider,
                                 String authToken, int maxSessionSeconds, int maxConnections) {
        this.sessionFactory = sessionFactory;
        this.mapper = mapper;
        this.vadConfig = vadConfig;
        this.vadDetectorFactory = vadDetectorFactory;
        this.musicProvider = musicProvider;
        this.authToken = authToken == null ? "" : authToken;
        this.maxSessionSeconds = maxSessionSeconds;
        this.maxConnections = maxConnections;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        // 1) 鉴权: 配置了共享 token 才校验, 不匹配直接关闭。
        if (!authToken.isEmpty() && !authToken.equals(queryParam(session, "token"))) {
            log.warn("WS 鉴权失败, 拒绝连接: {}", session.getId());
            return session.close(CloseStatus.POLICY_VIOLATION.withReason("invalid token"));
        }
        // 2) 连接数上限: 先占坑, 超限则立刻退坑并拒绝。
        if (maxConnections > 0 && activeConnections.incrementAndGet() > maxConnections) {
            activeConnections.decrementAndGet();
            log.warn("WS 连接数达上限({}), 拒绝: {}", maxConnections, session.getId());
            return session.close(CloseStatus.SERVICE_OVERLOAD.withReason("too many connections"));
        }

        Sinks.Many<WebSocketMessage> outbound = Sinks.many().unicast().onBackpressureBuffer();
        // 把 ASR 识别文本 / LLM 回复文本透传给前端做字幕
        TurnListener listener = new TurnListener() {
            @Override
            public void onAsrFinal(String text) {
                pushJson(session, outbound, Map.of("type", "asr", "text", text));
            }

            @Override
            public void onAssistantDelta(String delta) {
                pushJson(session, outbound, Map.of("type", "reply_delta", "text", delta));
            }

            @Override
            public void onAssistantText(String fullText) {
                pushJson(session, outbound, Map.of("type", "reply", "text", fullText));
            }

            @Override
            public void onMusicRequest(String action, String query) {
                // 调音源拿可直接播放的地址, 下发给前端用 <audio> 播放; 找不到则告知前端。
                musicProvider.search(query)
                        .map(track -> musicPlayMessage(track, query))
                        .defaultIfEmpty(Map.of("type", "music", "action", "notfound", "query", query))
                        .onErrorReturn(Map.of("type", "music", "action", "notfound", "query", query))
                        .subscribe(msg -> pushJson(session, outbound, msg));
            }
        };
        ConversationSession conversation = sessionFactory.create(session.getId(), listener);
        Connection conn = new Connection(session, conversation, outbound);
        log.debug("WS 连接建立: {}", session.getId());

        Mono<Void> receive = session.receive()
                .doOnNext(conn::onInbound)
                .then()
                .doFinally(sig -> conn.shutdown());

        Mono<Void> send = session.send(outbound.asFlux());
        Mono<Void> pipeline = Mono.when(receive, send);

        // 3) 单会话时长上限: 到点(管道仍未结束)主动关闭连接, 优雅收尾。
        if (maxSessionSeconds > 0) {
            pipeline = pipeline.timeout(Duration.ofSeconds(maxSessionSeconds))
                    .onErrorResume(TimeoutException.class, e -> {
                        log.info("WS 会话达时长上限({}s), 关闭: {}", maxSessionSeconds, session.getId());
                        return session.close(CloseStatus.GOING_AWAY.withReason("session time limit"));
                    });
        }
        // 无论正常/异常/超时结束, 都释放连接计数。
        return pipeline.doFinally(sig -> {
            if (maxConnections > 0) {
                activeConnections.decrementAndGet();
            }
        });
    }

    /** 从握手 URI 的查询串里取参数(已 URL 解码); 无则返回空串。 */
    private String queryParam(WebSocketSession session, String name) {
        String query = session.getHandshakeInfo().getUri().getQuery();
        if (query == null || query.isEmpty()) {
            return "";
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && name.equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    /** 组装一条"播放"音乐消息(cover 可能为 null, 故用可放 null 的 LinkedHashMap 而非 Map.of) */
    private Map<String, Object> musicPlayMessage(MusicTrack track, String query) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "music");
        msg.put("action", "play");
        msg.put("query", query);
        msg.put("title", track.title());
        msg.put("artist", track.artist());
        msg.put("url", track.playUrl());
        msg.put("cover", track.coverUrl());
        msg.put("duration", track.durationSec());
        msg.put("full", track.full());
        return msg;
    }

    /** 序列化并推送一条 JSON 文本消息 */
    private void pushJson(WebSocketSession session, Sinks.Many<WebSocketMessage> outbound, Map<String, Object> obj) {
        try {
            outbound.tryEmitNext(session.textMessage(mapper.writeValueAsString(obj)));
        } catch (Exception e) {
            log.warn("序列化出站消息失败: {}", e.toString());
        }
    }

    private enum Mode { IDLE, HANDSFREE, PTT }

    /** 单连接状态: 维护免提 VAD、上行采样率、当前回合的音频汇与订阅。 */
    private final class Connection {
        private final WebSocketSession session;
        private final ConversationSession conversation;
        private final Sinks.Many<WebSocketMessage> outbound;
        private final HandsFreeVad vad;

        private volatile Mode mode = Mode.IDLE;
        private volatile int inputSampleRate = 48000;
        /** 后端当前是否正在向用户播放音频(供打断判定) */
        private volatile boolean botSpeaking = false;

        private Sinks.Many<AudioFrame> turnSink;
        private Disposable turnSubscription;
        private final AtomicLong seq = new AtomicLong();
        /** 回合代号: 每开启一轮 +1, 打断时也 +1。只转发"当前代号"的音频块, 旧轮残留一律丢弃。 */
        private volatile long epoch = 0;
        /** 文本消息排队: 当前轮未结束时新输入入队, 本轮结束后按序处理, 避免丢消息/答错问题。 */
        private final java.util.Deque<String> pendingText = new java.util.ArrayDeque<>();
        private static final int MAX_PENDING_TEXT = 16;

        Connection(WebSocketSession session, ConversationSession conversation,
                   Sinks.Many<WebSocketMessage> outbound) {
            this.session = session;
            this.conversation = conversation;
            this.outbound = outbound;
            // VAD 决策回调接到回合管理。回调在持有 this 锁的上下文里被同步调用。
            // 每连接一个 detector 实例(Silero 的 RNN 状态不可跨会话共享)。
            this.vad = new HandsFreeVad(vadConfig, new HandsFreeVad.Listener() {
                @Override
                public void onSpeechStart() {
                    ensureTurnStarted();
                    pushState("speak");
                }

                @Override
                public void onAudio(byte[] pcm16le) {
                    emitFrame(pcm16le);
                }

                @Override
                public void onSpeechEnd() {
                    commitTurn();
                    pushState("wait");
                }

                @Override
                public void onBargeIn() {
                    bargeIn();
                }
            }, vadDetectorFactory.get());
        }

        void onInbound(WebSocketMessage message) {
            switch (message.getType()) {
                case BINARY -> onAudio(readBytes(message));
                case TEXT -> onControl(message.getPayloadAsText());
                default -> { /* PING/PONG 等忽略 */ }
            }
        }

        /** 上行音频: 免提交给 VAD 决策; 按住说话直接降采样后喂本轮; 空闲丢弃。 */
        private synchronized void onAudio(byte[] data) {
            switch (mode) {
                case HANDSFREE -> vad.accept(data, botSpeaking);
                case PTT -> {
                    ensureTurnStarted();
                    emitFrame(toTargetRate(data));
                }
                case IDLE -> { /* 未进入任何采集模式, 丢弃 */ }
            }
        }

        private synchronized void onControl(String json) {
            Map<?, ?> msg = parse(json);
            String type = str(msg.get("type"));
            switch (type) {
                case "mic" -> inputSampleRate = intOr(msg.get("sampleRate"), inputSampleRate);
                case "mode" -> onMode(str(msg.get("value")));
                case "ptt" -> onPtt(str(msg.get("value")));
                case "text" -> onText(str(msg.get("text")));
                case "model" -> onModel(str(msg.get("vendor")), str(msg.get("model")));
                case "voice" -> conversation.selectVoice(parseVendor(str(msg.get("vendor"))), str(msg.get("value")));
                case "engine" -> onEngine(str(msg.get("value")));
                case "barge_in" -> manualBarge();
                default -> log.debug("未知控制消息: {}", json);
            }
        }

        private void onMode(String value) {
            if ("handsfree".equals(value)) {
                mode = Mode.HANDSFREE;
                if (botSpeaking) {
                    bargeIn();
                }
                vad.start(inputSampleRate);
                pushState("await");
            } else { // idle / 其它一律视为退出免提
                mode = Mode.IDLE;
                vad.stop();
                pushState("idle");
            }
        }

        private void onPtt(String value) {
            if ("start".equals(value)) {
                mode = Mode.PTT;
                if (botSpeaking) {
                    bargeIn();
                }
                ensureTurnStarted();
                pushState("ptt");
            } else { // stop
                commitTurn();
                mode = Mode.IDLE;
                pushState("idle");
            }
        }

        /** 文本输入: 直接作为一轮注入, 绕过 ASR(真实/桩 ASR 都适用)。本轮忙则入队, 不丢弃。 */
        private void onText(String text) {
            if (text == null || text.isBlank()) {
                return;
            }
            if (turnSubscription != null) {
                if (pendingText.size() < MAX_PENDING_TEXT) {
                    pendingText.addLast(text);
                }
                return;
            }
            startTextTurn(text);
        }

        /** 前端切换打字所用大模型(厂商+模型)。仅影响打字回合, 语音回合不变。 */
        private void onModel(String vendor, String model) {
            VendorType v = parseVendor(vendor);
            if (v == null) {
                log.debug("忽略未知 LLM 厂商: {}", vendor);
                return;
            }
            conversation.selectLlm(v, model == null || model.isBlank() ? null : model);
            log.debug("切换打字模型: vendor={}, model={}", v, model);
        }

        /** 前端切换对话模式: {@code pipeline}(三段式) 或 {@code s2s}(端到端语音大模型)。下一回合生效。 */
        private void onEngine(String value) {
            SessionContext.Mode target;
            if ("s2s".equalsIgnoreCase(value)) {
                target = SessionContext.Mode.SPEECH_TO_SPEECH;
            } else if ("pipeline".equalsIgnoreCase(value)) {
                target = SessionContext.Mode.PIPELINE;
            } else {
                log.debug("忽略未知对话模式: {}", value);
                return;
            }
            conversation.switchMode(target);
        }

        private VendorType parseVendor(String code) {
            if (code == null || code.isBlank()) {
                return null;
            }
            try {
                return VendorType.fromCode(code.trim());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        /** 开启一轮文本回合 */
        private void startTextTurn(String text) {
            seq.set(0);
            final long myEpoch = ++epoch;
            turnSubscription = conversation.handleTextTurn(text)
                    .subscribe(chunk -> sendChunk(chunk, myEpoch),
                            err -> onTurnFinished(myEpoch, err),
                            () -> onTurnFinished(myEpoch, null));
        }

        /** 手动打断按钮: 打断当前回合; 若在免提中则回到"等你开口"。 */
        private void manualBarge() {
            bargeIn();
            if (vad.isActive()) {
                vad.resumeListening();
                pushState("await");
            }
        }

        /** 懒启动一轮: 订阅编排管道, 把产出的音频块回传客户端 */
        private synchronized void ensureTurnStarted() {
            if (turnSubscription != null) {
                return;
            }
            seq.set(0);
            final long myEpoch = ++epoch;
            turnSink = Sinks.many().unicast().onBackpressureBuffer();
            turnSubscription = conversation.handleUserTurn(turnSink.asFlux())
                    .subscribe(chunk -> sendChunk(chunk, myEpoch),
                            err -> onTurnFinished(myEpoch, err),
                            () -> onTurnFinished(myEpoch, null));
        }

        private void emitFrame(byte[] pcm16le) {
            if (turnSink != null) {
                turnSink.tryEmitNext(AudioFrame.of(pcm16le, seq.getAndIncrement(), System.currentTimeMillis()));
            }
        }

        /** 用户说完: 补一帧 endOfSpeech 并结束音频流, 触发 ASR 出 final → 后续流水线 */
        private synchronized void commitTurn() {
            if (turnSink == null) {
                return;
            }
            turnSink.tryEmitNext(AudioFrame.endOfSpeech(seq.getAndIncrement(), System.currentTimeMillis()));
            turnSink.tryEmitComplete();
        }

        private synchronized void bargeIn() {
            // 先翻代号: 既让旧轮残留块在 sendChunk 被丢弃(即便上游 TTS 取消有延迟),
            // 也让 conversation.bargeIn() 同步触发的旧轮收尾被 onTurnFinished 的代号守卫挡掉,
            // 避免误发 turn_end / 在语音打断时误清预滚。
            epoch++;
            conversation.bargeIn();
            if (turnSubscription != null) {
                turnSubscription.dispose();
            }
            pendingText.clear();   // 打断意味着改变意图, 丢掉排队中的旧文本
            resetTurn();
            emitJson(Map.of("type", "interrupted"));
        }

        /** 回合正常结束/出错: 复位回合, 免提则回到"等你开口"。运行在 reactor 线程, 故加锁。 */
        private synchronized void onTurnFinished(long turnEpoch, Throwable err) {
            if (turnEpoch != epoch) {
                return;   // 旧轮的收尾信号(已被打断/换轮), 忽略, 避免误发 turn_end
            }
            if (err != null) {
                log.warn("回合出错: {}", err.toString());
                emitJson(Map.of("type", "error", "message", String.valueOf(err.getMessage())));
            } else {
                emitJson(Map.of("type", "turn_end"));
            }
            resetTurn();
            // 优先处理排队中的文本(按序), 没有再回到"等你开口"
            String next = pendingText.pollFirst();
            if (next != null) {
                startTextTurn(next);
                return;
            }
            if (vad.isActive()) {
                vad.resumeListening();
                pushState("await");
            }
        }

        private synchronized void resetTurn() {
            turnSink = null;
            turnSubscription = null;
            botSpeaking = false;
        }

        private void sendChunk(AudioChunk chunk, long chunkEpoch) {
            if (chunkEpoch != epoch) {
                return;   // 已打断/换轮, 丢弃这一轮的残留音频, 绝不再发给前端
            }
            // 二进制: 音频块; 文本: 字幕(便于浏览器 demo 直接看到)
            if (chunk.size() > 0) {
                botSpeaking = true;
                outbound.tryEmitNext(session.binaryMessage(factory -> factory.wrap(chunk.data())));
            }
            if (chunk.text() != null) {
                emitJson(Map.of("type", "chunk", "text", chunk.text()));
            }
        }

        /** 把上行原始 PCM 降采样到 VAD/ASR 目标采样率 */
        private byte[] toTargetRate(byte[] pcm16leNative) {
            return PcmAudio.encodeLe(PcmAudio.resample(
                    PcmAudio.decodeLe(pcm16leNative), inputSampleRate, vadConfig.targetSampleRate()));
        }

        /** 推送一个状态事件, 附带给前端直接显示的中文标签 */
        private void pushState(String value) {
            emitJson(Map.of("type", "state", "value", value, "label", stateLabel(value)));
        }

        private String stateLabel(String value) {
            return switch (value) {
                case "await" -> "免提·请说…";
                case "speak" -> "录音中…";
                case "wait" -> "处理中…";
                case "ptt" -> "按住录音…";
                default -> "空闲";
            };
        }

        private void emitJson(Map<String, Object> obj) {
            pushJson(session, outbound, obj);
        }

        synchronized void shutdown() {
            if (turnSubscription != null) {
                turnSubscription.dispose();
            }
            vad.stop();
            conversation.close();
            outbound.tryEmitComplete();
            log.debug("WS 连接关闭: {}", session.getId());
        }

        private byte[] readBytes(WebSocketMessage message) {
            DataBuffer buf = message.getPayload();
            byte[] bytes = new byte[buf.readableByteCount()];
            buf.read(bytes);
            return bytes;
        }

        private Map<?, ?> parse(String json) {
            try {
                return mapper.readValue(json, LinkedHashMap.class);
            } catch (Exception e) {
                return Map.of();
            }
        }

        private String str(Object o) {
            return o == null ? "" : o.toString();
        }

        private int intOr(Object o, int fallback) {
            if (o instanceof Number n) {
                return n.intValue();
            }
            try {
                return Integer.parseInt(str(o));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
