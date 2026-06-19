# 07 · 端到端 S2S 全双工实现

本文讲清 **持久 S2S（Speech-to-Speech）全双工** 这条链路：从前端哪个按钮进、后端哪个方法接、一路调用链怎么走、用了哪些核心技术、关键代码在哪。

> 阅读前置：[01 架构](./01-architecture.md)、[03 WebSocket 协议](./03-websocket-protocol.md)。

---

## 1. 它是什么，什么时候生效

VCA 有**三种**对话模式，逐级"更端到端"：

| 模式 | 架构 | 谁判"说完了" | 打断 | 连接 |
|------|------|------------|------|------|
| 三段式 `pipeline` | ASR→LLM→TTS 级联 | 本地 VAD（`HandsFreeVad`） | 应用取消流 | 每轮新建 |
| 每轮 S2S | Qwen-Omni，每轮一连接 | 本地 VAD | 应用取消流（伪打断） | 每轮新建 |
| **持久 S2S 全双工** | Qwen-Omni，一条长连 | **服务端 VAD** | **服务端原生打断** | **整段对话一条长连** |

**持久全双工的激活条件**（三者同时满足）：

```
vca.web.mode = s2s                 (VCA_MODE=s2s)
vca.web.s2s-persistent = true      (VCA_S2S_PERSISTENT=true)
前端处于「免提」模式
```

任一不满足就回落到每轮 S2S / 三段式。这是一个灰度开关——真机不稳一行配置即可退回。

**核心思想**：持久会话把一条 WebSocket 长连贯穿整段对话，把"谁说完、谁该说、何时打断"交还给 **Qwen-Omni 的服务端 turn detection（server VAD）**。前端只管连续上传麦克风、播放下行音频；本地 VAD 在这条路径上完全不参与。

---

## 2. 全景图

```
┌────────────── 浏览器（瘦客户端，原生 JS） ──────────────┐
│  🎙免提  →  mode=handsfree                              │
│  麦克风  →  ScriptProcessor  →  ws.send(PCM 二进制帧)   │  上行
│  扬声器  ←  playPCM(24k)     ←  ws 二进制帧             │  下行
│  止声    ←  stopPlayback()   ←  {type:"flush_playback"}│  打断
└───────────────────────────┬────────────────────────────┘
                            │ WebSocket  /ws/voice
┌───────────────────────────┴──────────── 后端（Spring WebFlux 响应式） ─────────────┐
│ vca-web      VoiceWebSocketHandler.Connection                                       │
│                ├ onMode(handsfree) → persistentS2s()? → startLive()                 │
│                ├ onAudio(BINARY)   → live.pushAudio(16k 帧)         ← 上行           │
│                └ audioOut().subscribe(sendChunk)                    → 下行           │
│ vca-orchestrator  ConversationSession.openS2sLive() → S2sLiveSession                │
│                     events().handle(onS2sLiveEvent) → AudioChunk + 字幕(listener)   │
│ vca-gateway    ManagedS2s.open()  （按厂商解析委派，持久会话不套熔断/转移）          │
│ vca-domain     SPI: S2sProvider.open() / S2sSession / S2sEvent(sealed)              │
│ vca-provider-s2s-qwen  QwenOmniSession                                              │
│                  connect → updateSession(server VAD on) → seedHistory               │
│                  pushAudio→appendAudio  /  服务端事件→handleEvent→S2sEvent           │
└───────────────────────────┬────────────────────────────────────────────────────────┘
                            │ WebSocket（DashScope OmniRealtime，OpenAI-Realtime 兼容）
                     ┌──────┴───────┐
                     │  Qwen-Omni   │  服务端 VAD + 端到端语音大模型（ASR+LLM+TTS 融合）
                     └──────────────┘
```

---

## 3. 前端入口与采集

文件：`vca-bootstrap/src/main/resources/static/index.html`

### 3.1 入口：免提按钮

```js
// 🎙免提：进入持续采集 + 声明采样率 + 告诉后端进入免提
hfBtn.onclick = async () => {
  await ensureMic();
  announceMic();                          // {type:'mic', sampleRate}
  send({type:'mode', value:'handsfree'}); // ← 后端据此 startLive()
  streaming = true;
};
```

模式切换（三段式 ↔ s2s）：`{type:'engine', value:'s2s'|'pipeline'}`（`engine` 下拉）。
**前端不感知是不是"持久"全双工**——它只发 `engine=s2s` + `mode=handsfree`，是否走持久路径由后端 `vca.web.s2s-persistent` 决定。

### 3.2 采集：麦克风 → 连续上传 PCM

```js
async function ensureMic(){
  micStream = await openMicStream();      // getUserMedia(echoCancellation/noiseSuppression/AGC)
  micSrc  = audioCtx.createMediaStreamSource(micStream);
  micNode = audioCtx.createScriptProcessor(4096,1,1);
  micSrc.connect(micNode); micNode.connect(audioCtx.destination);
  micNode.onaudioprocess = (e) => {
    if(!streaming || !ws || ws.readyState!==1) return;
    ws.send(f2i16(e.inputBuffer.getChannelData(0)).buffer);  // Float32 → Int16 小端 PCM
  };
}
```

**麦克风自愈**（长会话防"音轨悄悄哑掉"）：音轨 `mute/ended` 事件 + 每秒巡检 `track.muted/readyState` + 静音看门狗（连续 4s 数字静音）→ `reacquireMic()` 重新 `getUserMedia`。详见 [记忆/排查] 与代码注释。

### 3.3 下行：播放 + 打断止声

```js
ws.onmessage = (ev) => {
  if (typeof ev.data === 'string') {
    const m = JSON.parse(ev.data);
    switch (m.type) {
      case 'flush_playback':   // ← 全双工打断：冲掉缓冲止声，但继续接收随后的新回复
        stopPlayback(); acceptAudio = true; break;
      case 'asr':  /* 你说的字幕 */
      case 'reply_delta': /* 机器人字幕（打字机） */ ...
    }
  } else {
    playPCM(ev.data);   // 24k 单声道 16bit，按 nextTime 排队播放
  }
};
```

---

## 4. 后端调用链

### 4.1 激活链（进入持久会话）

```
{type:mode,handsfree}
  → VoiceWebSocketHandler.Connection.onMode("handsfree")          [vca-web]
      → persistentS2s()  == s2sPersistent && conversation.currentMode()==SPEECH_TO_SPEECH
      → startLive()
          → ConversationSession.openS2sLive()                     [vca-orchestrator]
              → s2s.open(historySnapshot(), s2sConfig)
                  → ManagedS2s.open()                             [vca-gateway 治理门面]
                      → registry.s2s(QWEN).open()
                          → QwenOmniS2sProvider.open()            [vca-provider-s2s-qwen]
                              → new QwenOmniSession(props,cfg,history)
          → live.audioOut().subscribe(chunk -> sendChunk(...))    ← 订阅触发建连
```

`audioOut()` 是冷流（`Flux.create`）；**订阅那一刻**才真正去连 DashScope：

```
QwenOmniSession.events() 被订阅
  → connectAndConfigure()  (在 Schedulers.boundedElastic 上，不占 Netty 事件循环)
      → conv.connect()
      → conv.updateSession(sessionConfig)   // enableTurnDetection(true) = server VAD 开
      → seedHistory(conv, history)          // 历史回灌成 conversation.item.create
      → ready = true; flushPending()         // 补发建连前缓冲的上行音频
```

### 4.2 上行链（麦克风 → 模型）

```
ws 二进制帧
  → Connection.onAudio(BINARY)                                    [vca-web]
      → case HANDSFREE: live != null
          → live.pushAudio(AudioFrame.of(toTargetRate(data)...))  // 降采样到 16k，不经本地 VAD/不分轮
              → S2sLiveSession.pushAudio()                        [vca-orchestrator]
                  → QwenOmniSession.pushAudio()                   [vca-provider-s2s-qwen]
                      → conv.appendAudio(base64(pcm))             // 连续推，无"轮"
```

服务端 VAD 自行判停、自动 `commit` + 自动 `createResponse`——**应用侧不再 commit**。

### 4.3 下行链（模型 → 扬声器/字幕）

```
DashScope 服务端事件
  → OmniRealtimeCallback.onEvent(JsonObject)                      [SDK 回调线程]
      → QwenOmniSession.handleEvent(event)
          → mapEvent(event) → S2sEvent.{AudioDelta|AssistantText|UserTranscript|ResponseDone}
          → FluxSink.next(event)                                  // 线程安全桥接到 Reactor
  → ConversationSession.openS2sLive() 内 events().handle(onS2sLiveEvent)
      → AudioDelta    → sink.next(AudioChunk.of(pcm))  + 状态机 THINKING/SPEAKING
      → AssistantText → listener.onAssistantDelta(字幕) + 累计
      → UserTranscript→ appendHistory(user) + listener.onAsrFinal(字幕)
      → ResponseDone  → appendHistory(assistant 整段) + 回 LISTENING
  → S2sLiveSession.audioOut()
      → Connection.sendChunk(chunk, epoch)                        [vca-web]
          → chunk.size()>0 → ws.binaryMessage(pcm)   // 音频
          → chunk.text()!=null → {type:"chunk", text} // 调试字幕
```

### 4.4 打断链（全双工原生打断）

```
（机器人正在出声，你开口）
DashScope 服务端 VAD 检测到你说话
  → 事件 input_audio_buffer.speech_started
      → QwenOmniSession.handleEvent
          → conv.cancelResponse()                    // 截断机器人当前回复
          → FluxSink.next(S2sEvent.UserSpeechStarted)
  → ConversationSession.onS2sLiveEvent(UserSpeechStarted)
      → flushAssistant()（落已说出的部分）
      → 状态机 INTERRUPTED → LISTENING
      → listener.onUserSpeechStarted()
  → VoiceWebSocketHandler listener.onUserSpeechStarted()
      → pushJson {type:"flush_playback"}
  → 前端 stopPlayback()（清播放队列止声，acceptAudio 保持 true 接新回复）
```

---

## 5. SPI 与核心模型

持久会话用一组**新 SPI**，与旧的每轮 `converse` 并存（`vca-domain`）：

```java
// S2sProvider.java —— 旧 converse 保留作回退；新增 open
default S2sSession open(List<Message> history, S2sConfig cfg);

// S2sSession.java —— 持久全双工会话句柄
interface S2sSession {
  void pushAudio(AudioFrame frame);   // 持续上行，无"轮"
  Flux<S2sEvent> events();            // 订阅即建连；长连期间多次发射
  void cancelResponse();              // 手动打断
  void close();
}

// S2sEvent.java —— 带类型的下行事件（sealed），取代旧的"空音频块塞字幕"约定
sealed interface S2sEvent {
  record AudioDelta(byte[] pcm, long sequence) {}      // 下行音频
  record AssistantText(String delta) {}                // 机器人字幕
  record UserTranscript(String text) {}                // 你说的转写
  record UserSpeechStarted() {}                         // ★服务端 VAD 判定你开口=打断
  record ResponseDone() {}                              // 本次回复结束（会话不关）
}
```

编排层句柄 `S2sLiveSession`（`vca-orchestrator`）把底层 `S2sSession` 包成接入层友好的形态：`audioOut()` / `pushAudio()` / `cancelResponse()` / `close()`。

---

## 6. 核心技术点

| 技术 | 用在哪 | 解决什么 |
|------|--------|---------|
| **服务端 VAD**（turn_detection=server_vad） | `QwenOmniSession.sessionConfig()` `enableTurnDetection(true)` | 回合切分/判停/原生打断交还给模型，区别于本地 VAD 的半双工回合制 |
| **持久长连** | `QwenOmniSession` 一条连接贯穿多轮，`response.done` **不关连接** | 全双工、低延迟地板、跨轮原生上下文 |
| **`Flux.create` + `FluxSink`** | `QwenOmniSession.events()` | 把 SDK 的 **WebSocket 回调线程**（多线程）安全桥接到 Reactor 流 |
| **`Schedulers.boundedElastic`** | `connectAndConfigure()` | 阻塞式建连放到弹性线程，不占 Netty 事件循环 |
| **建连前缓冲 `pending`** | `pushAudio()`（cap 256 帧） | 订阅→建连有时延，期间到达的麦克风音频先缓冲，就绪后 `flushPending` 补发 |
| **`conversation.item.create`** | `seedHistory()`（user/system→`input_text`、assistant→`text`） | 跨轮记忆：历史回灌成正规对话条目，而非"塞进 instructions 的文字" |
| **`enableInputAudioTranscription`** | `sessionConfig()` | 服务端回吐"你说了什么"，前端显示用户字幕 |
| **sealed `S2sEvent`** | 下行事件建模 | 类型安全地区分音频/字幕/打断，取代"空音频块塞字幕"的脆弱约定 |
| **`cancelResponse()`** | `speech_started` 时 | 服务端原生打断：截断机器人当前回复 |
| **`flush_playback`** | 前端 | 打断瞬间冲掉已下发到浏览器的播放缓冲（仅 cancel 服务端不够，浏览器还缓着音频） |

**音频规格**：上行 16k 单声道 16bit PCM（`PCM_16000HZ_MONO_16BIT`）；下行 24k（`PCM_24000HZ_MONO_16BIT`，与三段式 TTS 一致，前端播放链路不变）。

---

## 7. 配置项

```yaml
# vca-bootstrap/.../application.yml
vca:
  web:
    mode: ${VCA_MODE:s2s}                       # s2s 才有端到端
    s2s-persistent: ${VCA_S2S_PERSISTENT:false} # ← 持久全双工总开关
    s2s-vendor: qwen
    s2s-voice: ${VCA_S2S_VOICE:}                # 留空用 provider 默认(Chelsie)
  providers:
    s2s:
      qwen:
        enabled: ${QWEN_S2S_ENABLED:true}
        api-key: ${DASHSCOPE_API_KEY}
        model: ${VCA_S2S_MODEL:qwen3.5-omni-plus-realtime}
        # 服务端 VAD 调参（持久会话专用，每轮 converse 不用）
        turn-detection-threshold: 0.5           # 越高越不易被噪声/回声误触发
        turn-detection-silence-ms: 800          # 多久静音算"说完"
        turn-detection-prefix-padding-ms: 300   # 开口前回补音频，防切首字
```

---

## 8. 与每轮 S2S 的关键区别（为什么持久才是"真全双工"）

| | 每轮 S2S（`converse`） | 持久 S2S（`open`/`QwenOmniSession`） |
|---|---|---|
| 连接 | 每轮新建一条，用完即弃 | 一条长连贯穿整段 |
| turn detection | **关**（`enableTurnDetection(false)`），靠本地 VAD `commit` | **开**（server VAD 自动 commit + createResponse） |
| 历史 | 拼进 `instructions` 文字（易"两个问题答第一个"） | `conversation.item.create` 正规条目 |
| 打断 | 应用取消上行流 + 开新回合（伪打断） | 服务端 `speech_started` 原生打断 |
| 本质 | 半双工回合制 | 全双工 |

诊断日志（`QwenOmniSession`）：`持久会话就绪` / `已开始上行麦克风音频` / `持久会话事件(首次): <type>`——后者按事件类型首次出现各记一条，用来确认 `speech_started → speech_stopped → response.created → response.audio.delta` 这条全双工序列是否打通。

---

## 9. 已知限制（待办）

- **打断上下文对齐（P3，未做）**：你插嘴时机器人已下发的音频可能没播完，服务端却以为整段说完了 → 下一轮上下文与你听到的错位。修法：前端回报实际播放毫秒数，provider 用 `sendRaw` 发 `conversation.item.truncate` 对齐。
- **无 AEC**：外放靠浏览器 `echoCancellation` 硬扛，全双工打断在外放场景仍不够干净，建议耳机。
- **治理简化**：`ManagedS2s.open()` 按厂商直接委派，**不套熔断/故障转移**（长连中途切厂商=重开会话，不适合每调一次的治理执行器）。
- **单厂商**：目前仅 Qwen-Omni 实现了 `open`；其它 `S2sProvider` 用默认实现会抛 `UnsupportedOperationException`。

---

## 10. 关键文件索引

| 层 | 文件 | 关键方法 |
|----|------|---------|
| 前端 | `static/index.html` | `hfBtn.onclick` / `ensureMic` / `ws.onmessage`(flush_playback) |
| 接入 | `vca-web/.../ws/VoiceWebSocketHandler.java` | `Connection.onMode` / `persistentS2s` / `startLive` / `onAudio` / `sendChunk` / listener `onUserSpeechStarted` |
| 接入 | `vca-web/.../WebProperties.java` | `s2sPersistent` |
| 编排 | `vca-orchestrator/.../session/ConversationSession.java` | `openS2sLive` / `onS2sLiveEvent` / `flushAssistant` |
| 编排 | `vca-orchestrator/.../session/S2sLiveSession.java` | `audioOut` / `pushAudio` |
| 契约 | `vca-domain/.../spi/S2sProvider.java` · `S2sSession.java` · `model/S2sEvent.java` | `open` / sealed events |
| 治理 | `vca-gateway/.../managed/ManagedProviders.java` | `ManagedS2s.open` |
| 厂商 | `vca-provider-s2s-qwen/.../QwenOmniSession.java` | `connectAndConfigure` / `pushAudio` / `handleEvent` / `mapEvent` / `seedHistory` / `sessionConfig` |
| 厂商 | `vca-provider-s2s-qwen/.../QwenOmniProperties.java` | server VAD 三参 |
