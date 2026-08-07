# 10 · 外呼接入方案（电话链路跑通）

目标：把现有对话引擎接到电话网，实现「系统拨号 → 客户接听 → AI 对话 → 意向判定 → 挂机落库」的闭环。

本文只解决**链路跑通**。名单管理、话术编辑器、意向分级后台属于产品层，另文再议。

> **实现进度**（Phase 0）
>
> | 状态 | 内容 |
> |---|---|
> | ✅ | `vca-telephony` 模块骨架、SPI（`CallLeg` / `CallEvent` / `TelephonyProvider`） |
> | ✅ | `PacingBuffer` 下行实时节流（§4） |
> | ✅ | `CallSession` 通话编排：接通/开场白/回合/epoch 门闸/打断/排空后回聆听 |
> | ✅ | `PcmAudio` 升采样改线性插值（§3）+ 回归测试 |
> | ✅ | **AudioSocket 接入**：`AudioSocketCodec` / `AudioSocketCallLeg` / `AudioSocketServer` |
> | ✅ | **Spring 装配**：`TelephonyProperties` / `TelephonyAutoConfiguration` / bootstrap 的 `TelephonyWiring`，默认关 |
> | ✅ | **开场白预合成** `PromptCache`（TTS 失败自动降级为无开场白，不阻断通话） |
> | ✅ | 32 个单测，**不需要装 Asterisk**（测试里的客户端扮演 Asterisk 跑真实 TCP） |
> | ✅ | 真实启动验证：应用起来、端口监听、模拟 Asterisk 接入→UUID→接通→VAD 成轮→挂机 全通 |
> | ✅ | **外呼（AMI）**：`AmiPacket` / `AmiClient` / `AmiTelephonyProvider` / `PendingCalls` 接线台 |
> | ⬜ | Asterisk 侧配置（PJSIP trunk / dialplan）与真机联调 |
> | ⬜ | DTMF 事件注入（钩子 `CallLeg.injectEvent` 已就位，缺 AMI 事件路由） |
> | ⬜ | CPA 音频特征兜底、转人工 |
> | ⬜ | 开场白预合成的生成与缓存（`PromptCache`，目前由调用方传入现成 PCM） |

---

## 0. 一个前提：引擎已经是传输无关的

这是本方案成本可控的根本原因。

```
ConversationSession.handleUserTurn(Flux<AudioFrame>) → Flux<AudioChunk>
```

`ConversationSession`（`vca-orchestrator`）不知道音频从哪来、往哪去。真正绑浏览器的只有一个类：

- `vca-web/.../ws/VoiceWebSocketHandler.java` —— 里面的内部类 `Connection` 负责三件事：
  1. 接 WebSocket 二进制帧 → 喂 `HandsFreeVad`
  2. 回合管理（`ensureTurnStarted` / `commitTurn` / `bargeIn`）+ **epoch 门闸**
  3. `AudioChunk` → WebSocket 二进制帧下发

**电话接入 = 写一个和 `Connection` 对等的 `CallSession`，换掉第 1、3 步的 IO，第 2 步原样复用。**
VAD、打断、Skill、RAG、记忆、落库全部零改动。

---

## 1. 媒体接入方式选型

电话侧要解决两件事：**信令**（拨号、接通、挂机）和**媒体**（双向音频流）。不要自己写 SIP 协议栈，用成熟的媒体服务器把这两件事挡在外面。

**选型的决定性约束是「双向」**：语音助手不只要把客户的声音送出去（那是 ASR 类应用的单向需求），还要把 TTS 音频**送回通话**。很多"媒体外接"方案是为单向转写设计的，回灌音频要么是附加功能、要么根本没有。

| 方案 | 媒体通道 | 信令控制 | 评价 |
|---|---|---|---|
| **Asterisk + AudioSocket** | TCP（1B 类型 + 2B 长度 + 负载） | AMI / ARI | **推荐**。**官方内置**（Asterisk 16/18+），协议极简，原生双向，负载就是 **8kHz 16bit 单声道 SLIN**——和 `CallLeg` 的契约逐字对上，零转换 |
| FreeSWITCH + `mod_audio_fork` / `mod_audio_stream` | WebSocket（L16 PCM） | ESL | 媒体走 WS 能复用现有栈，但这两个都是**社区模块、需自行编译**，且以单向转写为主要设计目标，**回灌音频的支持各版本差异很大** |
| 云呼叫中心（阿里云/腾讯云/容联） | 各家私有 | 各家 API | 最省事，但多数厂商只给"整套机器人"，**不一定开放裸音频流**——签约前必须确认，否则你的引擎接不进去 |

> 先前版本推荐 FreeSWITCH（理由是复用 WebSocket 栈）。把「双向」这条约束摆正之后结论变了：**复用 WS 栈省下的那点代码，远不值得拿整条链路的可行性去赌一个第三方模块的回灌能力。** AudioSocket 是纯 TCP，用 `java.net.Socket` 或 Reactor Netty 都是几十行。

两个方案共同的好处：G.711（PCMA/PCMU）编解码由媒体服务器内部完成，你拿到的直接是 PCM，**不用自己实现 G.711**。

---

## 2. 模块划分

新增一个模块，不动现有模块的依赖方向：

```
vca-telephony/
├── spi/
│   ├── TelephonyProvider.java   // originate(号码, 主叫) → Mono<CallLeg>；挂断；查状态
│   ├── CallLeg.java             // 一路通话: 上行 Flux<byte[]> / 下行 write(byte[]) / hangup() / 事件流
│   └── CallEvent.java           // RINGING / EARLY_MEDIA / ANSWERED / DTMF / HANGUP
├── session/
│   ├── CallSession.java         // 通话编排(对应 Connection)：VAD 接线 + 回合 + epoch 门闸
│   ├── CallStateMachine.java    // 拨号/振铃/彩铃/接通/对话中/转人工/挂机
│   └── PacingBuffer.java        // ★ 下行实时节流(见 §4)
├── media/
│   ├── CallProgress.java        // CPA: 彩铃/空号/关机/语音信箱判定
│   └── PromptCache.java         // 开场白预合成缓存(见 §5)
└── provider/freeswitch/
    ├── FsMediaWebSocketHandler.java  // 媒体: FreeSWITCH → /ws/call
    └── FsEslClient.java              // 信令: originate / hangup / 事件订阅
```

依赖方向：`vca-telephony → vca-orchestrator → vca-domain`。**不依赖 `vca-web`**（浏览器和电话是平级的两个接入层）。

根 `pom.xml` 的 `<modules>` 和 `<dependencyManagement>` 各加一条，照现有模块的样子写。

---

## 3. 采样率链路

电话是 **8kHz 窄带**，你现在整条链路按 16k（VAD/ASR）+ 24k（TTS 输出）设计。转换点如下：

```
上行:  FreeSWITCH ──8k PCM──▶ resample 8k→16k ──▶ HandsFreeVad ──▶ ASR
下行:  TTS ──24k PCM──▶ resample 24k→8k ──▶ PacingBuffer ──▶ FreeSWITCH
```

`PcmAudio.resample`（`vca-orchestrator/.../vad/PcmAudio.java:60`）两个方向都已支持，但有一处要改：

> **升采样目前是最近邻**（见该方法注释）。8k→16k 走最近邻会产生阶梯状波形，**Silero VAD 依赖波形结构，精度会掉**。建议改成线性插值——改动约 10 行，收益直接体现在误打断率上。

另外 `VadConfig` 的阈值是按浏览器 48k 麦克风调的。电话窄带 + 线路底噪的电平分布完全不同，**必须为电话场景配一组独立阈值**（`vca.telephony.vad.*`），先按经验值起步，用真实通话录音回归。

---

## 4. ★ 下行必须节流（和浏览器最大的差异）

浏览器路径是"后端尽快发、前端缓冲慢慢播"。**电话不行**：RTP 必须按实时节奏送，8k 单声道 16bit 下每 20ms 一包 = 320 字节。一次性灌进去会被媒体服务器丢弃或造成语音撕裂。

所以 `CallSession` 和 `Connection` 的关键结构差异是多一个 `PacingBuffer`：

```
AudioChunk(24k) → 降采样 8k → 入队 PacingBuffer
                                    │
                          每 20ms 定时器取 320 字节 → CallLeg.write()
```

**这带来一个意外的好处：打断变简单了。**

浏览器版为了知道"机器人还在不在出声"，要维护 `playbackEndsAtMs` 去估算前端播放进度（`VoiceWebSocketHandler.java:328` 那段注释解释了为什么不能用"后端是否还在发"）。电话版不需要估算——**队列里还有没有数据，就是机器人还在不在说话**：

```java
boolean botPlaying() { return !pacing.isEmpty(); }   // 精确, 不用估
void bargeIn()       { pacing.clear(); /* 然后照搬 Connection 的 epoch++ → conversation.bargeIn() */ }
```

epoch 门闸的逻辑（`epoch++` 必须在 `conversation.bargeIn()` 之前）原样照搬，那条约束在电话上同样成立。

---

## 5. 开场白预合成：直接省掉首包延迟

外呼接通后前 3 秒是挂机高发区，客户"喂?"一声没人应就挂。而**开场白是固定文本**：

- 启动时把每套话术的开场白用 TTS 合成一次，转成 8k PCM 缓存在 `PromptCache`
- 接通事件到达的瞬间，直接把缓存 PCM 灌进 `PacingBuffer`，**首包延迟 ≈ 0**
- 同时省掉每通电话的开场白合成费用（按十万通量级，这笔钱不小）

同理适用于高频固定话术："稍等一下"、"您说"、挂机语。

---

## 6. CPA：彩铃是最大的坑

**必须处理，否则烧钱且答非所问。** 彩铃音乐会被 VAD 判成人声、被 ASR 识别成乱七八糟的文本，机器人就开始对着彩铃说话，一通电话白烧 ASR + LLM + TTS。

三层判定，从可靠到兜底：

1. **SIP 信令层（最可靠）**：`183 Session Progress` = 早期媒体（彩铃/运营商提示音），`200 OK` = 真接通。FreeSWITCH 的 `ignore_early_media=true` 可以让 originate 只在真接通时才回调——**优先用这个，能挡掉绝大部分**。
2. **音频特征**：接通后前 N 秒若持续有声、无自然停顿（人不会连续说 6 秒不换气），判为彩铃/录音。
3. **文本特征**：ASR 首个 final 命中"您拨打的电话暂时无法接通/已关机/正在通话中"等模板 → 判空号/关机，立即挂机并打标。

判定结果写进通话记录的 `outcome`：`answered` / `ringback` / `empty_number` / `power_off` / `busy` / `voicemail` / `no_answer`。**接通率统计直接依赖这个字段的准确性**，而接通率是你整个商业模型的分母，值得认真做。

---

## 7. 复用 vs 重构：第一版建议先复制

`CallSession` 和 `VoiceWebSocketHandler.Connection` 会有一大块重复逻辑（VAD 接线、`ensureTurnStarted` / `commitTurn`、epoch 门闸）。

**第一版建议直接复制一份，不要急着抽公共基类。** 理由：

- 电话场景的回合语义还会变（CPA、转人工、DTMF、静音超时都要往回合状态机里插东西），过早抽象会抽错
- 浏览器路径是已经跑稳的资产，重构它去迁就一个还没跑通的新场景，风险不对称

等电话链路跑稳、需求收敛后，再把稳定下来的公共部分抽成 `orchestrator` 里的 `TurnPump`，两边同时切过去。

---

## 8. 最短跑通路径

### Phase 0 · 本地闭环（不需要任何资质和线路，目标 1~2 天）

**这一步就是"让流程跑通"，不碰任何监管和费用。**

```
Linphone/Zoiper (软电话)
      │ SIP 注册, 拨内线 1000
      ▼
FreeSWITCH ──WebSocket(L16 8k)──▶ /ws/call ──▶ CallSession ──▶ ConversationSession
      ◀──────────8k PCM──────────                                    │ (VAD/LLM/TTS 全部原样)
```

验收标准：软电话拨通后听到开场白 → 说话能被识别 → AI 有回复 → **说话能打断 AI** → 挂机后 `conversation_turn` 表有记录。

跑通这一步，最难的技术风险（媒体外接、采样率、节流、打断）就全部出清了。

### Phase 1 · 真实外呼（依赖客户提供 SIP 中继）

- 接 ESL，实现 `TelephonyProvider.originate(手机号)`
- `ignore_early_media` + CPA，把 outcome 打准
- 挂机检测、最大通话时长、并发路数上限

### Phase 2 · 批量与线索

- 名单导入、任务启停、时段/频次控制、黑名单过滤
- 意向 A/B/C/D 分级 + 线索导出
- 转人工（把 `CallLeg` 桥接到坐席分机，上下文摘要推给坐席）

### Phase 3 · 规模化

- 治理态外置 Redis（现在熔断/配额是单进程内存，**几百路并发外呼会成为瓶颈**，这条本来就在 roadmap 上）
- 号码池轮换与封号监控
- 录音质检（复用 `OssAudioRecordingService` + `/eval/report` 骨架）

---

## 9. 配置

沿用现有风格，`vca.telephony.*`，**默认关闭**，关闭时对现有 Web 链路零影响：

```yaml
vca:
  telephony:
    enabled: false
    media-path: /ws/call          # FreeSWITCH 推流目标
    sample-rate: 8000
    pacing-ms: 20                 # 下行节流粒度
    max-call-seconds: 300
    max-concurrent-calls: 50
    esl:
      host: 127.0.0.1
      port: 8021
      password: ***
    cpa:
      ignore-early-media: true
      ringback-detect-ms: 6000    # 连续有声超过此值判彩铃
    vad:                          # 电话专用阈值, 与浏览器分开
      speech-threshold: 0.02
      silence-ms: 700
      barge-threshold: 0.025
```

---

## 9.1 Asterisk 侧配置（Phase 0 本地闭环）

> 以下按 Asterisk 18+ / PJSIP 写。**先确认你这个版本带 AudioSocket**：`asterisk -rx "module show like audiosocket"`，看到 `res_audiosocket.so` / `app_audiosocket.so` 才能继续。没有就得装 `asterisk-modules` 或自行编译。

**`pjsip.conf`** —— 给软电话一个分机：

```ini
[transport-udp]
type=transport
protocol=udp
bind=0.0.0.0:5060
; Asterisk 在内网、对端在公网时必须配, 否则"能接通但听不到声音"
; external_media_address=<公网IP>
; external_signaling_address=<公网IP>

[1000]
type=endpoint
context=ai-agent
disallow=all
allow=alaw            ; 锁 G.711A, 别让它协商到别的编码
auth=1000-auth
aors=1000

[1000-auth]
type=auth
auth_type=userpass
username=1000
password=<改成你的>

[1000]
type=aor
max_contacts=1
```

**`extensions.conf`** —— 拨 5000 进 AI：

```ini
[ai-agent]
exten => 5000,1,NoOp(接入 VCA 语音助手)
 same => n,Answer()
 same => n,AudioSocket(${UUIDGEN},127.0.0.1:9092)   ; 必须在 Answer() 之后
 same => n,Hangup()
```

**启动 VCA**：

```bash
VCA_TELEPHONY_ENABLED=true \
VCA_TELEPHONY_GREETING="您好，这边是贷款咨询，方便耽误您一分钟吗？" \
java -jar vca-bootstrap/target/vca-bootstrap-0.0.1-SNAPSHOT.jar
```

看到 `电话接入已启用: AudioSocket :9092` 就绪。软电话（Zoiper/Linphone）注册 1000，拨 5000。

**验收清单**：听到开场白 → 说话能识别 → 有回复 → **能打断** → 挂机后 `conversation_turn` 有记录（需另开 `vca.store.enabled`）。

**第一件要听的事**：如果听到的是刺耳噪声而不是人声，设 `VCA_TELEPHONY_SWAP_BYTES=true` 重启——SLIN 字节序在不同构建上不一致，这个开关就是为它准备的。

## 9.2 外呼是怎么拨出去的

一次外呼是**两条互不相干的通道**，这是理解这块代码的关键：

```
①  本进程 ──AMI Originate──▶ Asterisk ──SIP──▶ 客户手机     (出方向, 我们连 Asterisk)
②                            Asterisk ──AudioSocket──▶ 本进程  (入方向, Asterisk 连我们)
```

两条路唯一的共同信息，是我们自己生成的一个 id。它**同时**当 AMI 的 `ActionID` 和 `Variable: CALLUUID`：

```
Action: Originate
ActionID: <id>
Channel: PJSIP/<被叫号码>@<trunk>
Context: ai-agent          ; 接通后进这里, 那里跑 AudioSocket
Async: true                ; 同步 Originate 会把 AMI 连接阻塞到通话结束
Variable: CALLUUID=<id>    ; dialplan 里 AudioSocket(${CALLUUID},host:port) 用它
Variable: __SIP_CODEC=alaw ; 锁死 G.711A
```

媒体连进来时带的 UUID 就是这个 id，`PendingCalls` 一查即可配对，并把被叫号码回填进 `CallLeg`（AudioSocket 自己拿不到号码）。**用同一个 id 兼任两职是刻意的**——失败事件 `OriginateResponse` 只带 `ActionID`，两者若是不同的 id，拿到失败通知也不知道该叫醒谁。

三个值得注意的设计点：

- **不会对着彩铃说话**。Originate 指定了 `Context/Exten`，Asterisk 只有在对端**真正接听**后才把通道送进 dialplan，`AudioSocket()` 根本不会在彩铃阶段执行。这比任何音频特征判定都可靠。
- **失败立刻报错**。`Response: Success` 只表示"指令已受理"，真正结果在 `OriginateResponse` 事件里。空号/关机/拒接会立刻叫醒发起方，不必干等 `answerWaitMs`——批量外呼时这点等待会直接吃掉并发。
- **先登记再发起**。反过来的话，快线路上媒体可能比登记还早连进来，那一路会被当成呼入，而发起方一直等到超时。

外呼的 dialplan 与 Phase 0 共用同一个 context，只是 `AudioSocket()` 的第一个参数换成变量：

```ini
[ai-agent]
exten => s,1,NoOp(外呼接通: ${CALLUUID})
 same => n,AudioSocket(${CALLUUID},127.0.0.1:9092)
 same => n,Hangup()
```

## 10. 风险清单

| 风险 | 处置 |
|---|---|
| **媒体外接模块不可用** | 唯一的外部不确定项。Phase 0 第一天就验证；不行则切 Asterisk AudioSocket |
| 8k 下 ASR 识别率下降 | 用真实通话录音评测，必要时换电话专用 ASR 模型（各家都有 8k 电话模型，别用通用模型） |
| Silero VAD 在窄带上误判 | 先修线性插值升采样；仍不行则电话路径回退 `EnergyVad` + 调阈值 |
| 打断在高延迟线路上迟钝 | 端到端延迟预算要单独测：线路 RTT + VAD 判决 + 取消上游，目标 < 500ms |
| 并发上不去 | 治理态外置 Redis（Phase 3）；先用 `max-concurrent-calls` 硬限保命 |

---

## 附：关键文件索引

| 用途 | 文件 |
|---|---|
| 传输无关的编排入口 | `vca-orchestrator/.../session/ConversationSession.java:467` |
| 要对照抄的浏览器接入层 | `vca-web/.../ws/VoiceWebSocketHandler.java`（内部类 `Connection`，L313 起） |
| epoch 门闸的三层打断说明 | `docs/02-tech-implementation.md` §3 |
| 重采样（需改升采样插值） | `vca-orchestrator/.../vad/PcmAudio.java:60` |
| VAD 状态机（原样复用） | `vca-orchestrator/.../vad/HandsFreeVad.java` |
| 通话落库（原样复用） | `vca-orchestrator/.../recorder/ConversationRecorder.java` |
| 录音落 OSS（质检用） | `vca-store/.../OssAudioRecordingService.java` |
