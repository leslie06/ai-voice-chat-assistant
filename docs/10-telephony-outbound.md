# 10 · 电话接入方案（呼入 / 外呼）

目标：把现有对话引擎接到电话网。呼入是「客户来电（或呼叫转移）→ AI 接听对话 → 挂机落库」，外呼是「系统拨号 → 客户接听 → AI 对话 → 意向判定 → 挂机落库」。

本文只解决**链路跑通**。名单管理、话术编辑器、意向分级后台属于产品层，另文再议。

> **实现进度**
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
> | ✅ | **单拨端点** `POST /telephony/calls`（强制令牌鉴权，号码做 AMI 注入防护） |
> | ✅ | **软电话呼入闭环（Asterisk）**（2026-09-17）：Docker 版 Asterisk 20.6 + pjsua，开场白/识别/回复/打断/落库全通，配置在 [`deploy/asterisk/`](../deploy/asterisk/README.md) |
> | ✅ | **改用 FreeSWITCH 为默认**（2026-09-17）：`provider/freeswitch/` —— 事件套接字（信令）+ 内核 unicast（UDP 媒体），不依赖第三方模块 |
> | ✅ | FreeSWITCH 呼入实测：主叫/被叫号码直接拿到、开场白/识别/回复/打断（插话后 0.4s 停声）/按键/挂机原因/落库全通，配置在 [`deploy/freeswitch/`](../deploy/freeswitch/README.md) |
> | ✅ | FreeSWITCH 外呼实测：`POST /telephony/calls` → ESL `bgapi originate` → 软电话自动接听 → 回连配对 → 对话落库；失败 30ms 内返回；FreeSWITCH 重启后自动重连 |
> | ✅ | 空闲补静音帧，线路 RTP 不断流（§4） |
> | ✅ | 电话模块 86 个单测，**不需要装 FreeSWITCH/Asterisk**（测试替身按真机抓包扮演媒体服务器，跑真实 TCP/UDP） |
> | ⬜ | SIP 中继（sofia gateway）与真实电话线路联调 |
> | ⬜ | 多商家：按被叫号码（`CallLeg.calledNumber()`，FreeSWITCH 已提供）路由话术/知识库 |
> | ⬜ | 按键进对话（事件已到 `CallSession`，目前只打日志）；Asterisk 侧的按键事件路由 |
> | ⬜ | CPA 音频特征兜底、转人工 |

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
| **FreeSWITCH + socket 应用 + unicast** | UDP（L16 裸 PCM，20ms 一包） | 事件套接字（同一条 TCP） | **默认**。两样都是 FreeSWITCH **内核/自带模块的能力**，Alpine 等发行版的包直接能用。信令连接里直接带主叫、被叫、按键、挂机原因 |
| Asterisk + AudioSocket | TCP（1B 类型 + 2B 长度 + 负载） | AMI | **备选**，已实现并实测。协议极简；但 AudioSocket 只有 UUID，**拿不到号码**，按键要另走 AMI 事件 |
| FreeSWITCH + `mod_audio_stream` | WebSocket | 事件套接字 | 不采用。开源社区版**只能单向推流**；实时回放在闭源商业版里（预编译包限 10 路并发，只有 Debian 包） |
| 云呼叫中心（阿里云/腾讯云/容联） | 各家私有 | 各家 API | 最省事，但多数厂商只给"整套机器人"，**不一定开放裸音频流**；号码与线路要企业资质（个体户也不行） |

**为什么从 Asterisk 改成 FreeSWITCH**：国内电话机器人、呼叫中心基本都跑在 FreeSWITCH 上，中继厂商对接资料和运维经验多；事件套接字一条连接就拿到号码和按键，多商家（按被叫号码区分）不需要额外的对账通道。

**unicast 的两个限制与对策**（读源码 `switch_ivr.c` 确认）：

1. **只有 UDP**。`transport` 参数写 `tcp` 也会建 UDP 套接字。本机回环上丢包可忽略；FreeSWITCH 侧端口交给系统分配，VCA 按首包来源回包（同对称 RTP），所以在 Docker 里**不需要映射任何媒体端口**。
2. **只在通道停泊时生效**。`socket` 应用的 async 模式本身就会停泊通道，正好满足。

两个方案共同的好处：G.711（PCMA/PCMU）编解码由媒体服务器内部完成，你拿到的直接是 PCM，**不用自己实现 G.711**。

---

## 2. 模块划分

```
vca-telephony/
├── spi/
│   ├── TelephonyProvider.java   // originate(号码, 主叫) → Mono<CallLeg>
│   ├── CallLeg.java             // 一路通话: 上行 Flux<byte[]> / 下行 writeAudio / hangup / 事件流 / 主叫被叫
│   └── CallEvent.java           // RINGING / EARLY_MEDIA / ANSWERED / DTMF / HANGUP
├── session/
│   ├── CallSession.java         // 通话编排(对应浏览器的 Connection)：VAD 接线 + 回合 + epoch 门闸
│   ├── PacingBuffer.java        // ★ 下行实时节流(见 §4)
│   └── PendingCalls.java        // 外呼接线台: 发起的呼叫 ↔ 连进来的媒体, 按 id 配对
├── media/
│   └── PromptCache.java         // 开场白预合成缓存(见 §5)
├── provider/freeswitch/         // 默认
│   ├── EslMessage.java          //   事件套接字报文编解码(URL 编码、字节级 Content-Length、防命令注入)
│   ├── FreeSwitchSocketServer.java  // 接 socket 应用连来的通话(呼入/外呼都从这进)
│   ├── FreeSwitchCallLeg.java   //   握手 + 信令泵 + unicast UDP 媒体泵
│   ├── EslClient.java           //   外呼用: 连 8021、认证、bgapi、断线重连
│   └── FreeSwitchTelephonyProvider.java  // originate + BACKGROUND_JOB 失败回调
├── provider/audiosocket/        // 备选: Asterisk 媒体
├── provider/ami/                // 备选: Asterisk 外呼
└── web/OutboundCallRoute.java   // POST /telephony/calls 冒烟端点
```

依赖方向：`vca-telephony → vca-orchestrator → vca-domain`。**不依赖 `vca-web`**（浏览器和电话是平级的两个接入层），会话装配由 `vca-bootstrap` 的 `TelephonyWiring` 转接。

`vca.telephony.provider` 二选一，`TelephonyAutoConfiguration` 里两个嵌套配置各管各的 bean。

---

## 3. 采样率链路

电话是 **8kHz 窄带**，你现在整条链路按 16k（VAD/ASR）+ 24k（TTS 输出）设计。转换点如下：

```
上行:  FreeSWITCH ──8k PCM──▶ resample 8k→16k ──▶ HandsFreeVad ──▶ ASR
下行:  TTS ──24k PCM──▶ resample 24k→8k ──▶ PacingBuffer ──▶ FreeSWITCH
```

`PcmAudio.resample`（`vca-orchestrator/.../vad/PcmAudio.java`）两个方向都已支持。升采样原先是最近邻（8k→16k 会产生阶梯状波形，Silero VAD 精度会掉），**已改成线性插值**。

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

**机器人没话说时也要补静音帧**（`CallLeg.needsContinuousMedia()`，FreeSWITCH 接入开启）。媒体服务器只在有帧写入时才发 RTP，不补的话客户说话那段线路上一个包都没有：对端抖动缓冲在机器人再开口时容易吞掉开头几个字，部分运营商的边界控制器还会按收不到 RTP 判媒体超时挂机。实测补之前软电话 35 秒只收到 248 个包，补之后是连续的每秒 50 包。

> 不能改用 FreeSWITCH 的 `send_silence_when_idle`：停泊循环会**无条件**每 20ms 写一帧静音，和 unicast 线程写入的语音叠在一起，发包速率翻倍，客户听到的是被搅乱的声音。

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

### Phase 0 · 本地闭环（不需要任何资质和线路）✅ 已完成

**这一步就是"让流程跑通"，不碰任何监管和费用。**

```
Linphone / pjsua (软电话, 分机 1000)
      │ SIP 注册, 拨 5000
      ▼
FreeSWITCH(Docker) ──socket 应用(TCP, 事件套接字)──▶ FreeSwitchSocketServer :8084
      ⇅ unicast(UDP, L16 8k 裸 PCM)                        │
                                                    CallSession ──▶ ConversationSession
                                                                     (VAD/LLM/TTS 全部原样)
```

验收标准：软电话拨通后听到开场白 → 说话能被识别 → AI 有回复 → **说话能打断 AI** → 挂机后 `conversation_turn` 表有记录。以上全部实测通过，外呼（拨回软电话）也已跑通。

### Phase 1 · 真实线路（依赖 SIP 中继或语音网关）

- FreeSWITCH 配 sofia gateway，`endpoint` 改成 `sofia/gateway/<网关名>/{number}`
- 呼入：中继号码进 `ai-agent` context；多商家按 `calledNumber()` 路由
- CPA，把 outcome 打准（`ignore_early_media` 已默认带上）
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
    enabled: ${VCA_TELEPHONY_ENABLED:false}
    provider: freeswitch          # freeswitch(默认) / asterisk
    sample-rate: 8000             # 电话网窄带; 高清语音线路可能是 16000
    max-call-seconds: 300         # 单通上限, 到点主动挂机
    greeting: 您好，这里是…        # 启动预合成, 接通瞬间出声
    greeting-barge-in: true
    tts-voice: ''                 # 留空 = 用 gateway 候选的音色(别写死)
    api-token: ${VCA_TELEPHONY_API_TOKEN:}   # 留空 = 不注册外呼端点
    vad:                          # 电话专用阈值, 与 vca.web.vad 分开
      speech-threshold: 0.02
      silence-ms: 700
      barge-threshold: 0.025
      barge-ms: 250
      barge-grace-ms: 200
    freeswitch:
      listen-address: 127.0.0.1   # socket 应用连这里; 没有鉴权, 只绑回环
      port: 8084
      media-bind-address: 127.0.0.1
      media-wait-ms: 3000         # 下发 unicast 后多久没媒体就挂断并打排查提示
      esl:                        # 外呼所需; 不开只能接呼入
        enabled: false
        host: 127.0.0.1
        port: 8021                # 本地 Docker 版映射在 18021
        password: ***
        endpoint: sofia/gateway/trunk/{number}   # 本地拨软电话: user/{number}
        context: ai-agent
        exten: vca-outbound
        ring-timeout-ms: 30000
        answer-wait-ms: 45000
    # provider=asterisk 时才用: port(9092) / swap-payload-bytes / uuid-wait-ms / ami.*
```

完整项与默认值以 `TelephonyProperties` 和 `vca-bootstrap/src/main/resources/application.yml` 为准。

> ⚠️ **还没有并发路数上限。** 批量外呼之前必须补上，否则名单一灌就会同时打爆内存和中继。

---

## 9.1 FreeSWITCH 侧（Phase 0 本地闭环）

> **现成可跑的版本在 [`deploy/freeswitch/`](../deploy/freeswitch/README.md)**（Docker，Alpine 包自带 arm64，一分钟装好）。
> 按代码逐步讲解的专篇见 [12 · FreeSWITCH 接入](./12-freeswitch.md)，下面是原理与踩过的坑的摘要。

一通电话接进 VCA 的拨号计划（节选）：

```xml
<action application="answer"/>
<!-- 告诉 VCA 媒体怎么走: VCA 从 socket 连接的通道变量里读 -->
<action application="set" data="vca_media_local_ip=0.0.0.0"/>          <!-- 容器里必须 0.0.0.0 -->
<action application="set" data="vca_media_remote_host=host.docker.internal"/>
<action application="set" data="park_timeout=900"/>                    <!-- VCA 挂了时的兜底 -->
<action application="socket" data="host.docker.internal:8084 async full"/>
```

VCA 收到连接后的握手（`FreeSwitchCallLeg.handshake`）：

```
→ connect                      ← 通道数据: Unique-ID(当 callId)、主叫、被叫、编码、上面 set 的变量
→ myevents                     ← 订阅本通道事件: DTMF / CHANNEL_HANGUP
→ linger 10                    ← 挂机后连接多留 10s, 否则挂机事件来不及送到
→ sendmsg                      ← FreeSWITCH 开始按 20ms 一包往 VCA 发 UDP, 并把 VCA 发回的包写进通话
  call-command: unicast
  local-ip: 0.0.0.0  local-port: 0  remote-ip: host.docker.internal  remote-port: <VCA 的 UDP 口>
  transport: udp
```

收到第一个 UDP 包才算接通（emit `ANSWERED`，开始播开场白），回包地址锁定为首包来源，之后别处来的包一律丢弃。

**踩过的坑**（都已写进配置与代码注释）：

| 现象 | 原因 | 处理 |
|---|---|---|
| 能接通但软电话没声音 | 软电话经 Docker 转发进来，源地址是网桥网关，默认 `localnet.auto` 把它当局域网，SDP 里写了容器内网 IP | `local-network-acl` 指向一个谁都不匹配的名单，强制用 `ext-rtp-ip` |
| 外呼启动失败，对端回 `text/rude-rejection` | 事件套接字不配 ACL 时默认只放行回环，宿主机经端口转发进来是网桥地址 | `apply-inbound-acl` 放行回环与私网段（8021 只发布到宿主机回环，不外露） |
| 外呼报 `MANDATORY_IE_MISSING` | 精简的分机目录里没有 `dial-string`，`user/1000` 找不到注册地址 | 域级参数补上 `dial-string` |
| unicast 下发成功但没有媒体 | 容器里 `local-ip` 绑了 127.0.0.1，套接字发不出容器；或 `remote-ip` 不是 FreeSWITCH 能到达的地址 | VCA 等 `media-wait-ms` 后挂断并打出排查提示 |
| 宿主机 8021 端口被占 | macOS 上 launchd 占着 8021 | 本地映射到 18021 |
| FreeSWITCH 重启后外呼不可用 | ESL 连接断开 | `EslClient` 1s→30s 退避自动重连，断开期间外呼立刻失败 |

## 9.2 外呼是怎么拨出去的（FreeSWITCH）

一次外呼是**两条互不相干的通道**：

```
①  本进程 ──ESL bgapi originate──▶ FreeSWITCH ──SIP──▶ 客户手机      (我们连 FreeSWITCH :8021)
②  客户接听 → 通道进拨号计划 vca-outbound ──socket 应用──▶ 本进程     (FreeSWITCH 连我们 :8084)
```

两条路靠我们生成的一个 id 对上，它**身兼三职**：

```
bgapi originate {origination_uuid=<id>,originate_timeout=30,ignore_early_media=true,
                 absolute_codec_string=^^:PCMA:PCMU,origination_caller_id_number=<号显>}
                sofia/gateway/<网关>/<号码> vca-outbound XML ai-agent
Job-UUID: <id>
```

- `origination_uuid` → ② 连进来时通道的 Unique-ID 就是它，`PendingCalls` 一查即配对并回填客户号码；
- `Job-UUID` → 空号/关机/拒接时 `BACKGROUND_JOB` 事件带着它，立刻叫醒发起方（实测 30ms），不干等 `answer-wait-ms`；
- 同时当落库的 sessionId。

三个值得注意的设计点：

- **不会对着彩铃说话**。originate 的目标是拨号计划里的 extension，FreeSWITCH 只有在对端**真正接听**后才把通道送进拨号计划；再加 `ignore_early_media=true`。
- **先登记再发起**。反过来的话，快线路上媒体可能比登记还早连进来，那一路会被当成呼入。
- **锁 G.711**。VCA 按 8k 解释 unicast 音频，协商到宽带编码会整段变速。变量值里的逗号会被当成分隔符，所以用 `^^:` 语法换成冒号。

### 怎么拨第一通电话

```bash
curl -X POST http://localhost:8080/telephony/calls \
  -H 'X-Telephony-Token: <你的令牌>' \
  -H 'Content-Type: application/json' \
  -d '{"number":"13800138000","callerId":"01088886666"}'

# 接通: {"callId":"...","outcome":"answered","peerNumber":"13800138000","elapsedMs":8123}
# 未通: {"callId":null,"outcome":"failed","reason":"外呼失败: NO_ANSWER","elapsedMs":30012}
```

这是**冒烟工具，不是批量入口**——它会一直等到接通或失败才返回。批量外呼需要异步发起 + 并发控制 + 重呼策略，那是名单/任务系统的事。

两条安全约束写死在代码里：

- **未配 `vca.telephony.api-token` 就不注册这个端点。** 它会真的打电话、真的花钱。
- **号码只放行 `[0-9+*#]`。** 号码会被拼进 originate 命令：逗号能多塞一个通道变量，空格能改掉目标 extension，换行能在同一条连接上多塞一条命令（`api shutdown`）。一律不做"清洗后放行"；`EslMessage.command` 对任何带换行的字段再拦一道。

## 9.3 备选：Asterisk（AudioSocket + AMI）

> 设 `VCA_TELEPHONY_PROVIDER=asterisk`。现成可跑的版本在 [`deploy/asterisk/`](../deploy/asterisk/README.md)。以下按 Asterisk 18+ / PJSIP 写。**先确认你这个版本带 AudioSocket**：`asterisk -rx "module show like audiosocket"`。

**`extensions.conf`** —— 拨 5000 进 AI：

```ini
[ai-agent]
exten => 5000,1,NoOp(接入 VCA 语音助手)
 same => n,Answer()
 same => n,Set(CALLUUID=${UUID()})                  ; 见下方警告
 same => n,AudioSocket(${CALLUUID},127.0.0.1:9092)  ; 必须在 Answer() 之后
 same => n,Hangup()
```

> ⚠️ **第一个参数必须是合法 UUID。** 别拿 `${UNIQUEID}` 顶替——它是"时间戳.序号"，会直接报
> `Failed to parse UUID`，症状是**接通后立刻挂断，而 VCA 侧连一条连接日志都没有**。
> **Ubuntu 24.04 自带的 Asterisk 20.6 没有 `func_uuid`**，用
> `${SHELL(cat /proc/sys/kernel/random/uuid | tr -d '\n')}` —— `tr` 不能省。

如果听到的是刺耳噪声而不是人声，设 `VCA_TELEPHONY_SWAP_BYTES=true`（Docker 版实测不需要）。

外呼经 AMI：`Action: Originate`，`ActionID` 与 `Variable: CALLUUID` 用同一个 id，接通后进 `s` extension 跑 `AudioSocket(${CALLUUID},…)`，`PendingCalls` 按 UUID 配对。AMI 客户端没有断线重连，AudioSocket 拿不到号码，按键要另走 AMI 事件——这也是默认改成 FreeSWITCH 的原因之一。

## 10. 风险清单

| 风险 | 处置 |
|---|---|
| 媒体外接不可用 | 已排除：FreeSWITCH unicast 与 Asterisk AudioSocket 均已实测双向通 |
| 真实中继的 NAT/编码差异 | 本地只验证了软电话；接中继时先拨 6000 回声测试，再看 `media-wait-ms` 超时日志 |
| 8k 下 ASR 识别率下降 | 用真实通话录音评测，必要时换电话专用 ASR 模型（各家都有 8k 电话模型，别用通用模型） |
| Silero VAD 在窄带上误判 | 升采样已改线性插值；电话路径默认 `EnergyVad`，开 Silero 前用真实通话回归 |
| 打断在高延迟线路上迟钝 | 端到端延迟预算要单独测：线路 RTT + VAD 判决 + 取消上游，目标 < 500ms |
| 并发上不去 | 治理态外置 Redis（Phase 3）。**并发路数上限尚未实现**，批量外呼前必须先补 |

---

## 附：关键文件索引

| 用途 | 文件 |
|---|---|
| FreeSWITCH 单路通话(握手/信令/媒体) | `vca-telephony/.../provider/freeswitch/FreeSwitchCallLeg.java` |
| FreeSWITCH 外呼 | `vca-telephony/.../provider/freeswitch/FreeSwitchTelephonyProvider.java` |
| 本地 FreeSWITCH 配置 | `deploy/freeswitch/conf/`（`dialplan.xml` 是接入 VCA 的地方） |
| 电话通话编排 | `vca-telephony/.../session/CallSession.java` |
| 传输无关的编排入口 | `vca-orchestrator/.../session/ConversationSession.java:467` |
| 要对照抄的浏览器接入层 | `vca-web/.../ws/VoiceWebSocketHandler.java`（内部类 `Connection`，L313 起） |
| epoch 门闸的三层打断说明 | `docs/02-tech-implementation.md` §3 |
| 重采样 | `vca-orchestrator/.../vad/PcmAudio.java` |
| VAD 状态机（原样复用） | `vca-orchestrator/.../vad/HandsFreeVad.java` |
| 通话落库（原样复用） | `vca-orchestrator/.../recorder/ConversationRecorder.java` |
| 录音落 OSS（质检用） | `vca-store/.../OssAudioRecordingService.java` |
