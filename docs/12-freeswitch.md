# 12 · FreeSWITCH 接入（电话怎么接进对话引擎）

一通电话打进来（或系统拨出去），FreeSWITCH 负责电话网那一侧的一切：SIP 信令、RTP 收发、G.711 编解码；
本项目只拿到**裸 PCM 音频 + 几个信令事件**，交给和浏览器完全相同的对话引擎。

| 事情 | 谁做 | 用的能力 |
|------|------|----------|
| SIP 注册、呼叫、RTP、G.711 编解码、录音 | FreeSWITCH | `mod_sofia`、内核 |
| 通话接进本项目（主叫/被叫、按键、挂机） | FreeSWITCH → 本项目 | 拨号计划里的 `socket` 应用（事件套接字 outbound 模式） |
| 双向音频 | FreeSWITCH ⇄ 本项目 | 内核自带的 `unicast`（UDP，L16 8k） |
| 发起外呼 | 本项目 → FreeSWITCH | 事件套接字 inbound 模式（8021）+ `bgapi originate` |
| 识别、大模型、合成、打断、落库 | 本项目 | 与浏览器共用的 `ConversationSession` |

> **全程不依赖任何第三方 FreeSWITCH 模块。** 社区常用的 `mod_audio_stream` 开源版只能单向推流，
> 实时回放在闭源商业版里（预编译包限 10 路并发）；`socket` 与 `unicast` 则是任何发行版的
> FreeSWITCH 包自带的。选型对比见 [10 · 电话接入](./10-telephony-outbound.md) §1。

实现进度：本机软电话**呼入、按键、打断、外呼、FreeSWITCH 重启后自动重连**均已实测通过；
**真实 SIP 中继还没有联调**（§7）。

---

## 1. 整条链路

```
            ┌──────────────────────── FreeSWITCH ─────────────────────────┐
软电话/中继 ─SIP/RTP─▶ mod_sofia ─▶ 拨号计划 ai-agent                        │
            │           ▲             answer → set 变量 → record_session     │
            │           │             → socket <VCA>:8084 async full ────────┼──TCP(事件套接字)──┐
            │           │                                                    │                   │
            │        RTP(G.711)    unicast: 读帧 ─UDP L16 8k──────────────────┼──────────────┐    │
            │                               写帧 ◀─UDP L16 8k─────────────────┼───────────┐  │    │
            └────────────────────────────────────────────────────────────────┘           │  │    │
                                                                                          │  ▼    ▼
本项目 vca-telephony                                                          FreeSwitchSocketServer
  FreeSwitchCallLeg  ── handshake: connect / myevents / linger / sendmsg unicast ◀─────────────┘
    ├─ pumpSignaling: DTMF → CallEvent.DTMF, CHANNEL_HANGUP → HANGUP
    ├─ pumpMedia:     首个 UDP 包 → ANSWERED; 之后每包 → inboundAudio
    └─ writeAudio:    UDP 发回首包来源地址
  CallSession(每 20ms 一拍)
    ├─ 上行: inboundAudio → HandsFreeVad(8k→16k) → ConversationSession(ASR→LLM→TTS)
    └─ 下行: TTS 24k → 降采样 8k → PacingBuffer → tick() 取一帧(没话说就补静音) → writeAudio
```

**信令和媒体是两条路**：信令走 `socket` 应用建的那条 TCP，媒体走 unicast 的 UDP。
两条路靠的是同一个 `FreeSwitchCallLeg`——它在 TCP 上下发 unicast 命令时，把自己刚开的 UDP 端口号告诉 FreeSWITCH。

---

## 2. 一通呼入电话的完整过程

以软电话拨 `5000` 为例，按时间顺序：

### 2.1 FreeSWITCH 拨号计划（`deploy/freeswitch/conf/dialplan.xml`）

```xml
<extension name="vca-inbound">
  <condition field="destination_number" expression="^5000$">
    <action application="answer"/>
    <action application="execute_extension" data="vca-connect XML ai-agent"/>
  </condition>
</extension>

<!-- 呼入与外呼共用的接入段 -->
<extension name="vca-connect">
  <condition field="destination_number" expression="^vca-connect$">
    <action application="set" data="vca_media_local_ip=0.0.0.0"/>
    <action application="set" data="vca_media_remote_host=host.docker.internal"/>
    <action application="set" data="park_timeout=900"/>
    <action application="set" data="RECORD_STEREO=true"/>
    <action application="record_session" data="/recordings/${uuid}.wav"/>
    <action application="socket" data="host.docker.internal:8084 async full"/>
    <action application="hangup"/>
  </condition>
</extension>
```

| 动作 | 作用 |
|------|------|
| `answer` | 先应答。unicast 要求通道已有媒体 |
| `vca_media_local_ip` | FreeSWITCH 这一侧 UDP 口绑哪个地址。**容器里必须 `0.0.0.0`**，绑 127.0.0.1 的套接字发不出容器 |
| `vca_media_remote_host` | FreeSWITCH 眼里本项目的地址。本项目从通道变量里读它，填进 unicast 命令 |
| `park_timeout=900` | 本项目崩溃、没发挂机指令时的兜底：停泊 15 分钟自动挂断，不留僵尸通道 |
| `record_session` + `RECORD_STEREO` | 双声道录音，左 = 来电方，右 = 本项目回的声音。文件名是通道 uuid，与日志、落库对得上 |
| `socket … async full` | 主动连本项目。`async` 模式下 FreeSWITCH 会**停泊**通道（unicast 只在停泊时工作），`full` 允许下发所有命令 |

### 2.2 握手（`FreeSwitchCallLeg.handshake`）

`FreeSwitchSocketServer` 每接到一条 TCP 就建一个 `FreeSwitchCallLeg`，同步完成握手：

```
本项目 → connect
FreeSWITCH ← command/reply, 头部平铺整份通道数据(值是 URL 编码的):
             Unique-ID: 0f08bd0c-…               → callId(也是落库的 sessionId)
             Caller-Caller-ID-Number: 1000       → peerNumber()
             Caller-Destination-Number: 5000     → calledNumber()
             Channel-Read-Codec-Rate: 8000       → 与配置的线路采样率比对, 不一致打告警
             variable_vca_media_local_ip: 0.0.0.0
             variable_vca_media_remote_host: host.docker.internal
本项目 → myevents                 订阅本通道事件(按键、挂机)
本项目 → linger 10                挂机后连接多留 10 秒, 否则挂机事件来不及送到
本项目    开 UDP 口(media-bind-address:随机端口)
本项目 → sendmsg
         call-command: unicast
         local-ip: 0.0.0.0             ← 取自通道变量
         local-port: 0                 ← 让系统分配(见 2.3)
         remote-ip: host.docker.internal
         remote-port: <刚开的 UDP 端口>
         transport: udp
```

任何一步失败（超时、`-ERR`、握手期间客户就挂了），这路通话直接放弃，**不建会话**。

握手成功后顺序固定：先回调上层建 `CallSession` 并订阅，**再**开信令泵和媒体泵——保证 `ANSWERED` 和第一个音频包都落在订阅之后。

### 2.3 媒体：unicast 的两个性质决定了实现

读 FreeSWITCH 源码（`switch_ivr.c` 的 `switch_ivr_activate_unicast` 与停泊循环）确认：

1. **只有 UDP。** `transport` 写 `tcp` 也会建 UDP 套接字。
2. **停泊循环里每 20ms 把通道读到的一帧解码成 L16 发出去；另起一个线程收包，收到一包就写进通道一帧。**
   FreeSWITCH 这一侧不做节流，所以本项目必须按实时节奏发（`PacingBuffer` + `CallSession.tick`）。

本项目据此做了三件事：

- **回包地址按首包来源锁定**（同对称 RTP）。FreeSWITCH 侧端口交给系统分配，不用维护端口池；
  在 Docker 里首包来源是端口转发后的地址，按它回包正好原路回到容器——**不需要映射任何媒体端口**。
  锁定之后别处来的包一律丢弃，否则谁知道端口谁就能往通话里灌音频。
- **收到第一个媒体包才算接通**，此时才 emit `ANSWERED`、开始播开场白。等不到（默认 3 秒）就挂断，
  并在日志里打出排查提示（多半是 `vca_media_remote_host` 配错）。
- **机器人没话说时也补静音帧**（`CallLeg.needsContinuousMedia()`）。媒体服务器只在有帧写入时才发 RTP，
  不补的话客户说话那段线路上一个包都没有：对端抖动缓冲容易吞掉机器人再开口的头几个字，部分运营商还会判媒体超时挂机。
  实测补之前软电话 35 秒只收到 248 个包，补之后是连续的每秒 50 包。

> ⚠️ **不能用 FreeSWITCH 的 `send_silence_when_idle` 代替补静音。** 停泊循环会**无条件**每 20ms 写一帧静音，
> 和 unicast 线程写入的语音叠在一起，发包速率翻倍，客户听到的是被搅乱的声音。

### 2.4 通话中与挂机

| 发生了什么 | 事件套接字上收到 | 本项目的反应 |
|------------|------------------|--------------|
| 客户按键 | `Event-Name: DTMF` + `DTMF-Digit` | `CallEvent.DTMF`，`CallSession` 目前只打日志 |
| 客户挂机 | `text/disconnect-notice`（linger）→ `CHANNEL_HANGUP` + `Hangup-Cause` | `HANGUP("hangup:NORMAL_CLEARING")`，会话收尾 |
| 连接被掐断 | EOF | `HANGUP("peer-closed")` |
| 本项目要挂（单通超时等） | — | 发 `sendmsg / call-command: hangup`，**半关**连接等 FreeSWITCH 自己断开，3 秒后强关 |

主动挂机**不能发完就 close**：接收缓冲里往往还有没读的事件，此时 close 会发 RST，
FreeSWITCH 可能在读到挂机指令之前就先收到 RST，通道挂在那里直到 `park_timeout`。

---

## 3. 一通外呼的完整过程

```
①  POST /telephony/calls {"number":"13800138000","callerId":"01088886666"}
      → FreeSwitchTelephonyProvider.originate
          号码白名单校验 [0-9+*#]
          PendingCalls.register(id)                   ← 先登记再发起
          EslClient.bgapi(originate …, Job-UUID=id)
②  FreeSWITCH 拨号; 客户真正接听后, 通道才进拨号计划 vca-outbound
      → execute_extension vca-connect → socket 连回本项目
③  FreeSwitchSocketServer 握手, 通道 Unique-ID == id
      → PendingCalls.attach 命中: 回填客户号码, 唤醒 ①, HTTP 返回 answered
      → 建 CallSession, 之后与呼入完全相同
```

发出去的命令（`FreeSwitchTelephonyProvider.originateCommand`）：

```
bgapi originate {origination_uuid=<id>,originate_timeout=30,ignore_early_media=true,absolute_codec_string=^^:PCMA:PCMU,origination_caller_id_number=01088886666}sofia/gateway/trunk/13800138000 vca-outbound XML ai-agent
Job-UUID: <id>
```

| 片段 | 为什么 |
|------|--------|
| `origination_uuid=<id>` | 回连时通道的 Unique-ID 就是它，接线台按它配对 |
| `Job-UUID: <id>` | 空号/关机/拒接时 `BACKGROUND_JOB` 事件带 `-ERR NO_ANSWER` 之类的结果和这个 id，立刻叫醒发起方（实测 30ms），不干等 45 秒 |
| `ignore_early_media=true` + 目标是拨号计划 extension | 真接听之后才进拨号计划，不会对着彩铃说话 |
| `absolute_codec_string=^^:PCMA:PCMU` | 锁 G.711。本项目按 8k 解释 unicast 音频，协商到宽带编码会整段变速。值里的逗号会被当变量分隔符，`^^:` 把分隔符换成冒号 |
| `sofia/gateway/trunk/{number}` | 拨号串模板，配置项 `endpoint`；本地拨软电话用 `user/{number}` |

**号码白名单是安全边界**：号码会被拼进 originate 命令，逗号能多塞一个通道变量，空格能改掉目标 extension，
换行能在同一条连接上多塞一条命令（比如 `api shutdown`）。一律拒绝，不做"清洗后放行"；
`EslMessage.command` 对任何带换行的字段再拦一道。

`EslClient` 断线自动重连（1s → 2s → 4s … 封顶 30s）。断开期间发起的外呼立刻失败，不排队；
只有**首次**连接失败会让应用启动失败，那通常是配置写错了。

---

## 4. 代码结构

```
vca-telephony/src/main/java/com/vca/telephony/
├── TelephonyProperties.java            配置(provider 二选一 + freeswitch.* / asterisk 相关项)
├── TelephonyAutoConfiguration.java     两个嵌套配置: FreeSwitchConfiguration(默认) / AsteriskConfiguration
├── spi/
│   ├── CallLeg.java                    一路通话: 上下行音频、事件、主叫/被叫、needsContinuousMedia
│   ├── CallEvent.java
│   └── TelephonyProvider.java          originate
├── session/
│   ├── CallSession.java                通话编排: VAD 接线、回合、epoch 门闸、打断、节流、补静音
│   ├── PacingBuffer.java               下行实时节流
│   └── PendingCalls.java               外呼接线台
├── media/PromptCache.java              开场白预合成
├── web/OutboundCallRoute.java          POST /telephony/calls
└── provider/freeswitch/
    ├── EslMessage.java                 报文编解码
    ├── FreeSwitchConfig.java           socket 服务端与媒体参数
    ├── FreeSwitchSocketServer.java     接 socket 应用连来的通话
    ├── FreeSwitchCallLeg.java          握手 + 信令泵 + 媒体泵
    ├── EslConfig.java                  外呼连接参数
    ├── EslClient.java                  连 8021: 认证、bgapi、事件、断线重连
    └── FreeSwitchTelephonyProvider.java originate + BACKGROUND_JOB 失败回调
```

几处容易写错、代码里已经处理的细节：

| 细节 | 处理 |
|------|------|
| 事件值 URL 编码，号码可能是 `+86…` | `EslMessage.percentDecode` 只解 `%XX`，**不能用 `URLDecoder`**（它把 `+` 当空格） |
| `Content-Length` 按字节计，正文可能有中文 | 全程按字节读，不套 Reader |
| 事件套接字应答不带关联 id | `EslClient` 把"写命令"和"排进等待队列"放在同一把锁里，按顺序配对应答 |
| async 模式下事件会穿插在命令应答之间 | 握手等应答时，中途到达的事件照常处理（握手期间挂机也能感知） |
| reactor 的 unicast sink 不允许并发 emit | 信令线程、媒体线程、挂机调用方三处 emit，各自加锁串行化 |

线程模型：每路通话两个阻塞读线程（信令、媒体），加 `CallSession` 一个 20ms 定时拍子。几百路并发没有问题，到数千路再换 NIO，只需替换服务端和两个泵。

与浏览器链路的依赖关系：`vca-telephony` 不依赖 `vca-web`，会话由 `vca-bootstrap` 的 `TelephonyWiring` 转接（复用浏览器那套会话装配）。

---

## 5. 配置

### 5.1 本项目（`vca.telephony.*`，默认关闭）

| 配置项 | 环境变量 | 默认 | 说明 |
|--------|----------|------|------|
| `enabled` | `VCA_TELEPHONY_ENABLED` | `false` | 总开关，关闭时不占端口、不建 bean |
| `provider` | `VCA_TELEPHONY_PROVIDER` | `freeswitch` | `asterisk` 为备选 |
| `sample-rate` | `VCA_TELEPHONY_SAMPLE_RATE` | `8000` | 线路采样率，G.711 即 8000 |
| `freeswitch.listen-address` | `VCA_FS_LISTEN_ADDRESS` | `127.0.0.1` | socket 服务端地址。**没有鉴权，只绑回环** |
| `freeswitch.port` | `VCA_FS_PORT` | `8084` | 拨号计划 `socket` 应用连这里 |
| `freeswitch.media-bind-address` | `VCA_FS_MEDIA_BIND_ADDRESS` | `127.0.0.1` | 本项目 UDP 媒体口地址 |
| `freeswitch.media-wait-ms` | `VCA_FS_MEDIA_WAIT_MS` | `3000` | 等首个媒体包的上限 |
| `freeswitch.handshake-timeout-ms` | — | `5000` | 等握手应答的上限 |
| `freeswitch.esl.enabled` | `VCA_FS_ESL_ENABLED` | `false` | 外呼开关，不开只能接呼入 |
| `freeswitch.esl.host` / `port` | `VCA_FS_ESL_HOST` / `VCA_FS_ESL_PORT` | `127.0.0.1` / `8021` | 本地 Docker 版映射在 18021 |
| `freeswitch.esl.password` | `VCA_FS_ESL_PASSWORD` | 空 | 与 `event_socket.conf` 一致 |
| `freeswitch.esl.endpoint` | `VCA_FS_ESL_ENDPOINT` | `sofia/gateway/trunk/{number}` | 必须含 `{number}` |
| `freeswitch.esl.context` / `exten` | `VCA_FS_ESL_CONTEXT` / `VCA_FS_ESL_EXTEN` | `ai-agent` / `vca-outbound` | 接通后进哪段拨号计划 |
| `freeswitch.esl.ring-timeout-ms` | `VCA_FS_ESL_RING_TIMEOUT_MS` | `30000` | 振铃超时 |
| `freeswitch.esl.answer-wait-ms` | `VCA_FS_ESL_ANSWER_WAIT_MS` | `45000` | 发起到媒体连入的总上限 |
| `api-token` | `VCA_TELEPHONY_API_TOKEN` | 空 | 留空则不注册外呼端点 |
| `greeting` | `VCA_TELEPHONY_GREETING` | 空 | 开场白，启动时预合成 |
| `max-call-seconds` | `VCA_TELEPHONY_MAX_CALL_SECONDS` | `300` | 单通上限 |
| `vad.speech-threshold` | `VCA_TELEPHONY_VAD_SPEECH` | `0.02` | 开口判定音量 |
| `vad.onset-ms` | `VCA_TELEPHONY_VAD_ONSETMS` | `150` | 持续多久算开口 |
| `vad.silence-ms` | `VCA_TELEPHONY_VAD_SILENCE_MS` | `500` | 句尾静音判停 |
| `vad.barge-threshold` / `barge-ms` | `VCA_TELEPHONY_VAD_BARGE` / `VCA_TELEPHONY_VAD_BARGE_MS` | `0.025` / `250` | 打断判定 |
| `system-prompt` | `VCA_TELEPHONY_SYSTEM_PROMPT` | `prompts.phone-agent` | 电话人设（短，见 §8） |
| `llm-model` | `VCA_TELEPHONY_LLM_MODEL` | 空 | 电话对话模型；用通义时建议 `qwen-flash` |
| `tools` | `VCA_TELEPHONY_TOOLS` | 空 | 电话回合下发的工具白名单，默认一个都不发 |
| `knowledge-owner` | `VCA_TELEPHONY_KNOWLEDGE_OWNER` | 空 | 按谁的知识库作答（商家账号 id），见 §9 |
| `agent-tools` | `VCA_TELEPHONY_AGENT_TOOLS` | 三个全开 | 电话专用工具，见 §10 |
| `transfer-dial-string` | `VCA_TELEPHONY_TRANSFER_DIAL_STRING` | 空 | 转人工桥接到哪里；留空则不下发该工具 |
| `summary.enabled` | `VCA_TELEPHONY_SUMMARY_ENABLED` | `true` | 通话后小结，见 §11 |
| `summary.min-duration-sec` | `VCA_TELEPHONY_SUMMARY_MIN_SEC` | `10` | 短于此的通话不摘要 |
| `summary.webhook-url` | `VCA_TELEPHONY_SUMMARY_WEBHOOK` | 空 | 小结推送地址（企业微信/钉钉群机器人 URL 直接填） |
| `merchants[n].*` | `VCA_TELEPHONY_MERCHANTS_n_*` | 空 | 多商家，按被叫号码路由，见 §12 |

完整项以 `TelephonyProperties` 和 `vca-bootstrap/src/main/resources/application.yml` 为准。

### 5.2 FreeSWITCH（`deploy/freeswitch/conf/`，三个文件就是全部）

| 文件 | 内容 |
|------|------|
| `freeswitch.xml` | 核心参数、加载的模块、控制台日志、事件套接字、访问名单、SIP profile |
| `dialplan.xml` | `5000` 接入本项目、`vca-outbound` 外呼回连、`vca-connect` 公共接入段、`6000` 回声测试 |
| `directory.xml` | 分机 `1000` 与域级 `dial-string` |

`@SIP_PASSWORD@` 这类占位符由 `entrypoint.sh` 在容器启动时用 `.env` 渲染，密码不进仓库。

必须知道的几个参数：

| 参数 | 值 | 不这么配的后果 |
|------|----|----------------|
| `event_socket.conf` → `apply-inbound-acl` | `esl-local`（回环 + 私网段） | 默认只放行回环，宿主机经 Docker 端口转发连入会收到 `text/rude-rejection`，外呼启动失败 |
| sofia → `local-network-acl` | `nobody`（谁都不匹配） | 软电话经 Docker 转发进来的源地址是网桥网关，被当成局域网，SDP 写容器内网 IP，接通但没声音 |
| sofia → `ext-sip-ip` / `ext-rtp-ip` | `@EXTERNAL_IP@`，本机 127.0.0.1 | 同上 |
| sofia → `inbound-codec-prefs` / `outbound-codec-prefs` | `PCMA,PCMU` | 协商到宽带编码，unicast 音频变速 |
| sofia → `force-register-domain` | `vca.local` | 认证域随软电话填的服务器地址变化 |
| 目录域参数 `dial-string` | `${sofia_contact(*/…)}` | `originate user/1000` 报 `MANDATORY_IE_MISSING` |
| `rtp-end-port` | 偶数 | 奇数会被取整并打告警 |

---

## 6. 本地跑起来

### 6.1 启动

```bash
cd deploy/freeswitch
[ -f .env ] || printf 'SIP_PASSWORD=%s\nESL_PASSWORD=%s\n' "$(openssl rand -hex 8)" "$(openssl rand -hex 12)" > .env
cd ..
./start-phone.sh            # 改过代码时: ./start-phone.sh --build
```

第 2 行只在第一次生成 FreeSWITCH 的密码（`.env` 已被 gitignore）。`start-phone.sh` 负责剩下的事：
检查 Docker、没起就把 FreeSWITCH 起起来、检查 8080/8084 是否被占、加载参数、启动本项目。

参数默认值写在脚本里（开场白、`qwen-flash`、软电话用的 VAD 阈值、堆上限 1G）。要长期改某一项：

```bash
cp .env.phone.example .env.phone    # 不进仓库
# 最常改的三项: 开场白、知识库归属(商家账号 id)、转人工拨号串
```

优先级是 脚本默认值 → `.env.phone` → 命令行环境变量，所以临时试某个参数直接写在命令前面：

```bash
VCA_TELEPHONY_LLM_MODEL=qwen3.7-plus ./start-phone.sh
```

日志出现 `电话接入已启用(FreeSWITCH): socket 127.0.0.1:8084` 即就绪。容器设置了 `restart: unless-stopped`，Docker 重启后会自动起来；不用时 `cd deploy/freeswitch && docker compose down`。

### 6.2 Linphone 添加账号（Linphone 6）

1. 点右上角头像 → **Add an account** → **Third-party SIP account** → 说明页点 **I understand**。
2. 填写：Username `1000`，Password 为 `deploy/freeswitch/.env` 里的 `SIP_PASSWORD`，Domain `127.0.0.1`，Transport `UDP`，点 **Log in**。
3. 头像上是绿点就是注册成功。如果还有别的账号，点头像**切换到这个账号**再拨号。
4. 点右上角拨号盘图标，输入号码，点绿色拨号键。

| 拨号 | 作用 |
|------|------|
| `6000` | 回声测试，不经过本项目。能听到自己 = 软电话 ↔ FreeSWITCH 这段没问题 |
| `5000` | 接入本项目 |

**务必戴耳机**：外放时 AI 的声音被麦克风收回去，会被当成插话，AI 说两个字就自己停。

### 6.3 外呼拨回软电话

软电话保持注册并开自动接听，本项目多加几个变量启动：

```bash
VCA_TELEPHONY_ENABLED=true VCA_FS_ESL_ENABLED=true VCA_FS_ESL_PORT=18021 \
VCA_FS_ESL_PASSWORD=<.env 里的 ESL_PASSWORD> VCA_FS_ESL_ENDPOINT='user/{number}' \
VCA_TELEPHONY_API_TOKEN=local-test-token ./run.sh

curl -X POST http://127.0.0.1:8080/telephony/calls \
  -H 'X-Telephony-Token: local-test-token' -H 'Content-Type: application/json' \
  -d '{"number":"1000","callerId":"01088886666"}'
```

### 6.4 不开软电话的自动化验证

`brew install pjproject` 装命令行软电话 `pjsua`，用 macOS 自带的 `say` 合成提问音频，就能不用人说话把整条链路跑一遍：

```bash
say -v Tingting -o q.aiff "你好，请问今天是星期几？"
ffmpeg -f lavfi -t 7 -i anullsrc=r=8000:cl=mono -i q.aiff -f lavfi -t 20 -i anullsrc=r=8000:cl=mono \
  -filter_complex "[1:a]aresample=8000[q];[0:a][q][2:a]concat=n=3:v=0:a=1" -ac 1 -c:a pcm_s16le in.wav

PW=$(grep ^SIP_PASSWORD deploy/freeswitch/.env | cut -d= -f2)
perl -e 'sleep 30; print "h\nq\n"' | pjsua --null-audio --no-vad --local-port 5080 \
  --id sip:1000@127.0.0.1 --registrar sip:127.0.0.1 --realm '*' --username 1000 --password "$PW" \
  --add-codec pcma --play-file in.wav --auto-play --rec-file out.wav --auto-rec sip:5000@127.0.0.1
```

然后看本项目日志里的 `ASR final` 和回复落库，或分析 `deploy/freeswitch/recordings/<uuid>.wav` 两个声道的发声时间段来量打断延迟。

---

## 7. 部署到服务器与接真实线路

> 配置与脚本都已就位并在本机验证过（空中继/假中继两种状态都能正常起、软电话链路不受影响），
> **但没有真实线路可联调**。第一通真实电话之前，先按 §7.4 的顺序验。

### 7.1 先决定走哪条路

| 路子 | 适合 | 要准备什么 | 成本 |
|---|---|---|---|
| **FXO 语音网关** | 单店试点、个人身份也能做 | 一个 FXO 网关（几百元），接商家现有的座机线 | 设备费，无资质门槛 |
| **SIP 中继** | 多店、要统一号码 | 企业资质（个体户不行，见 [10](./10-telephony-outbound.md)），向云通信厂商申请 | 号码月租 + 分钟费 |

两条路对 FreeSWITCH 来说是同一件事：**一个 SIP 对端**。区别只是地址在局域网还是公网、认证方式是账号还是 IP 白名单。
所以配置项是同一套。

### 7.2 配置

在 `deploy/freeswitch/.env` 里加（这个文件不进仓库）：

```bash
# 中继/网关地址。FXO 网关填它的局域网 IP，SIP 中继填厂商给的地址
TRUNK_HOST=192.168.1.88
TRUNK_NAME=trunk                # 拨号串里用它: sofia/gateway/trunk/<号码>
# 账号密码式(FXO 网关、给了账号的中继)。IP 白名单式就把这三项留空
TRUNK_USER=8001
TRUNK_PASSWORD=******
TRUNK_REALM=                    # 留空 = 用 TRUNK_HOST
# 放行哪些来源把电话送进来。必配, 不配则全部拒接
TRUNK_ACL=192.168.1.88/32
# 服务器的公网 IP(中继在公网时必填, 否则"能接通但没声音")
EXTERNAL_IP=47.95.248.104
# 5080 要能被中继访问到
SIP_BIND=0.0.0.0
```

外呼改走中继（`.env.phone`）：

```bash
VCA_FS_ESL_ENABLED=true
VCA_FS_ESL_ENDPOINT=sofia/gateway/trunk/{number}
```

改完 **重启容器**（不是 `reloadxml`）：`cd deploy/freeswitch && docker compose up -d --force-recreate`。

### 7.3 这些配置做了什么

- **中继走独立的 SIP 通道**（`external`，端口 5080），与软电话那条（`internal`，5060）彻底分开。
  中继按 IP 认、不做摘要认证；软电话必须认证。放一个通道里就得在"给中继开口子"和"不给扫号者开口子"之间二选一。
- **来电落在 `ai-inbound` context**，那里只有一条规则：不管被叫是哪个号码都交给 AI。
  号码本身随通道数据交给本项目，由它按 `merchants` 认领是哪家商家（§12）。
- **白名单是第一道防线**。5060/5080 一旦在公网上，几分钟内就会有人来扫号盗打。
  `TRUNK_ACL` 之外的来源一律拒接，云服务器安全组上再收一道（只对中继 IP 放行 5080 和 RTP 端口段）。

### 7.4 接上之后按这个顺序验

```bash
cd deploy/freeswitch && ./trunk-status.sh        # 通道/中继/白名单/最近的拒接, 一屏看完
```

1. **中继状态。** 账号密码式应为 `REGED`；IP 白名单式是 `NOREG`，那是正常的。
2. **打进来。** 用手机拨那个号码，听到开场白即通。没通就 `./trunk-status.sh --trace` 打开 SIP 报文跟踪，
   看 `docker logs -f vca-freeswitch`：收不到 INVITE 是线路/安全组的事；收到但被拒多半是白名单。
3. **听得见声音。** 能接通但双方无声，九成是 `EXTERNAL_IP` 没配成公网 IP。
4. **窄带识别率。** 真实线路的电平和噪声与软电话不同，先用几通真实通话看日志里的"开口诊断"，
   再决定要不要调 `VCA_TELEPHONY_VAD_SPEECH`（软电话那组 0.01 是偏低的，真实线路多半用得上默认 0.02）。
5. **打出去。** `POST /telephony/calls` 拨自己的手机（§3）。

### 7.5 用语音网关接诊所的固话线（HT813 这类 ATA）

这是**个人身份也能做、且长期合规**的一条路：网关放在诊所，一头接现有座机线，一头走宽带连云上的 FreeSWITCH。

```
诊所固话线 ──▶ HT813 的 LINE 口(FXO)     振铃时自动接起, 转成 SIP/RTP
                     │ 家用宽带 / 公网
                     ▼
              云服务器 FreeSWITCH ──socket + unicast──▶ 本项目(AI 听/想/说)
                     │  转人工时桥接回去
                     ▼
              HT813 的 PHONE 口(FXS) ──▶ 有绳电话机响, 前台接起来聊
```

**为什么两个口各注册一个分机**：网关在诊所路由器后面，家用宽带是动态 IP，按 IP 放行行不通，只能靠注册认证。
所以它走 `internal`（5060，要认证）那条通道，不是中继用的 `external`。

**FreeSWITCH 侧**，在 `deploy/freeswitch/.env` 里加：

```bash
ATA_LINE_USER=8001              # LINE 口(接电话线)用的分机
ATA_LINE_PASSWORD=<够长的随机串>
ATA_PHONE_USER=8002             # PHONE 口(接话机)用的分机
ATA_PHONE_PASSWORD=<够长的随机串>
SIP_BIND=0.0.0.0                # 网关要从公网连进来
EXTERNAL_IP=<服务器公网IP>
```

转人工就转到 PHONE 口那个分机（`.env.phone`）：

```bash
VCA_TELEPHONY_TRANSFER_DIAL_STRING=user/8002@vca.local
```

**HT813 侧**（Web 界面），按端口分别配：

| 页面 | 项 | 值 |
|---|---|---|
| FXS PORT（PHONE 口） | SIP Server / 账号 | 服务器公网 IP / `8002` + 密码 |
| FXO PORT（LINE 口） | SIP Server / 账号 | 服务器公网 IP / `8001` + 密码 |
| FXO PORT | Number of Rings Before Pickup | `2`（响两声自动接，别设 0） |
| FXO PORT | **Unconditional Call Forward to VOIP** | 填**商家的接入号**，例如 `01088886666` |
| FXO PORT | Enable Current Disconnect / Busy Tone Disconnect | 打开 |
| 两个口 | 语音编码 | 只留 PCMA（或 PCMU），关掉其它 |
| 两个口 | Caller ID Scheme | 按线路选（大陆多为 FSK Bellcore） |

第四项是关键：FXO 是模拟线，**没有被叫号码这个概念**，所以要在网关上写死一个号码送给 FreeSWITCH。
把它填成这家诊所的号码，多商家路由（§12）就自然对上了——AI 据此知道是哪家店打进来的。

第五项决定挂机检测：对端挂断后模拟线要靠极性反转或忙音才能察觉，不开的话通话会挂到单通上限
（默认 5 分钟）才断。它是最容易漏配、也最容易表现为"电话占线不放"的一项。

Caller ID 拿得到的话，线索表里就是客户的真实手机号；拿不到就只能靠 AI 在通话里问。

**测试顺序**：网关两个口都注册上（`./trunk-status.sh` 能看到）→ 用别的手机拨诊所号码，听到开场白 →
说话能识别 → 说"转人工"，有绳电话机响 → 挂断后看日志里的挂机原因是不是及时的。

> **插卡盒（SIM 卡转固话线）那一段要注意**：拿它代替真实固话线做联调没问题，但用 SIM 卡把手机来电
> 转成 SIP 送上公网，功能上等同于 GoIP，属于《反电信网络诈骗法》第十四条点名的设备，运营商风控也容易停卡。
> 换成诊所真实的固话线，上面的配置一行都不用改。

### 7.6 同机部署（FreeSWITCH 与本项目在一台服务器上）

不用容器、或容器用 host 网络时，把 `dialplan.xml` 里三处地址都改成 `127.0.0.1`：

```xml
<action application="set" data="vca_media_local_ip=127.0.0.1"/>
<action application="set" data="vca_media_remote_host=127.0.0.1"/>
<action application="socket" data="127.0.0.1:8084 async full"/>
```

`vca_media_local_ip` 改回 `127.0.0.1` 很重要：`0.0.0.0` 会让 unicast 口暴露在网卡上，被人往通话里灌音频。

---

## 8. 延迟：从 7.5 秒降到 2 秒

电话对延迟远比浏览器敏感——对面听不到回应，几秒钟就会"喂？喂？"。按"用户说完最后一个字"到"听见第一声回复"计时，
用 pjsua 自动拨号实测（同一段提问音频，每项跑 3~4 通取平均）：

| 环节 | 优化前 | 优化后 | 做了什么 |
|---|---|---|---|
| 判停 + 语音识别 | 0.9s | 0.8s | 句尾判停 700ms → 500ms（`vca.telephony.vad.silence-ms` 默认值已改） |
| 自动联网注入 | 2.3s | 0 | 电话侧关掉（`TelephonyWiring` 写死 false）。客服问答用不上，而它每轮都要等搜索结果 |
| 大模型首字 | 2.2s | 0.3s | 不下发工具（`vca.telephony.tools` 默认空）；换短人设（`prompts.phone-agent`）；换首字快的模型（`vca.telephony.llm-model=qwen-flash`） |
| 合成首声 | 2.1s | 0.7s | 提前建连（见下） |
| **合计（稳态）** | **7.5s** | **1.7~2.1s** | 进程刚启动的第一通约 3s，之后稳定 |

三处值得单独说明：

**合成提前建连。** 云厂商的合成每次都要新建 WebSocket，实测握手约 1.2 秒，而合成首帧只要 0.6 秒。
原来是"第一句生成出来了才去建连"，这 1.2 秒完整落在用户的等待里。现在回合一开始（用户刚说完、还在识别）
就调 `TtsProvider.prewarm` 把连接建好，握手与识别、生成完全重叠，第一句到了直接发文本。
实现见 `AliyunTtsProvider.WarmSession`：用 Qwen-Audio-3.0 的流式输入协议，文本流必须用会暂存的
`ReplayProcessor`——建连是异步的，大模型比握手快时用 `PublishProcessor` 会把这一句直接丢掉，
线上表现是服务端收到 run-task 紧跟 finish-task、回一个 `task_failed`，这一轮没有声音。
CosyVoice 不支持这个协议，自动退回逐句建连。

**首句更早切出去。** 一轮回复的体感延迟完全由第一句决定——它切出来才能开始合成。所以首句用更小的阈值
（`SentenceSplitterConfig` 的 `firstSoftCutMinChars=4` / `firstMaxChars=16`），后续句子仍按正常阈值，
保证语气连贯。中文回复的开头（"好的，"/"今天是星期四，"）本来就能独立成句。

**模型选型。** `qwen3.7-plus` 会先生成一段思考再吐第一个字，实测首字 8 秒以上（应用内因参数不同为 1~2 秒）；
`qwen-flash` 稳定 0.3 秒。电话客服的问题大多是"几点开门""怎么走"，深度不是瓶颈，首字才是。
换模型是配置项，不同厂商名字不同，所以默认留空（沿用浏览器的），用通义时建议设成 `qwen-flash`。

> 这些优化只作用于电话链路：浏览器那边工具、联网、人设、模型都没变。共用的只有"首句更早切"与"合成提前建连"，
> 两者对浏览器同样是纯收益。

---

## 9. 知识库：让它答得出诊所的价格和营业时间

电话客服 80% 的问题是"多少钱""几点上班""在哪"，靠人设是答不了的，必须查商家自己的资料。

**为什么要单配一个归属。** 知识库按账号隔离（`knowledge_chunk.user_id`），浏览器那边每个人查自己上传的资料；
而电话对端是外部客户，没有登录身份。所以电话侧由配置指定**按谁的知识库检索**：

```yaml
vca:
  telephony:
    knowledge-owner: ${VCA_TELEPHONY_KNOWLEDGE_OWNER:}   # 商家账号 id(app_user.id), 留空=电话里没有知识库
```

实现上把"知识库归属"和"登录用户"解耦了（`ConversationSession.setKnowledge(store, ownerId)`）：
电话因此**有知识库但没有个人记忆**——记忆仍按登录用户走，电话对端不是本系统用户，不该给他建记忆。

**商家怎么传资料。** 商家用自己的账号登录网页，把资料传进现成的接口，txt / md / pdf 都行：

```bash
curl -X POST http://<服务地址>/api/knowledge \
  -H "Authorization: Bearer <该账号的 token>" \
  -F "file=@诊所资料.txt"
# {"ok":true,"chunks":1}
```

资料写成"一问一答"或分条列清楚即可，检索是按语义召回 top-5 片段（相似度低于 0.30 的不注入）。

**实测（本机，一份诊所资料）：**

| 问题 | 回答 | 识别完成 → 出声 |
|---|---|---|
| 请问你们周日几点上班？ | 周日 9 点到 12 点上班。 | 2.15s |
| 洗牙多少钱？ | 普通洁牙 180 元，喷砂洁牙 380 元。 | 1.30s |

两处要注意：

- **检索给延迟加了约 0.5 秒**（向量化一次 query + 余弦召回），它在大模型之前，完整计入体感延迟。
  首次调用因为 HTTPS 握手要 1.6 秒，所以启动时会后台打一次 embedding 预热（`StoreAutoConfiguration.prewarm`），
  否则这一秒半会落在第一个打进来的客户身上。
- **人设里要求"具体信息说全"**。不加这条时，问"周日上班吗"它会答"正常上班"——对就诊的人毫无用处；
  加上之后答"周日 9 点到 12 点"。见 `prompts.phone-agent`。

多商家（按被叫号码路由到不同商家的知识库）还没做，现在是单个归属，单店试点够用。

---

## 10. 客服工具：留资、转人工、主动挂机

知识库解决"答得上来"，这三个工具解决"这通电话有产出"。它们**按通话建实例**，不是进程级单例——
通话 id、来电号码、转给谁都是这一路的事实，让模型去传只会填错。

| 工具 | 什么时候触发 | 做了什么 |
|---|---|---|
| `save_lead` | 客户要预约、要回电，或留了姓名/电话/项目/时间 | 写进 `phone_lead` 表（归属到商家账号），结果回灌给模型，由它用自己的话确认 |
| `transfer_to_human` | 客户要找真人，或问到投诉、退费、病情判断 | 让 FreeSWITCH `bridge` 到坐席，之后本进程不再参与对话 |
| `end_call` | 客户说"没别的了""再见" | **说完告别语再挂**，不是立刻挂 |

```yaml
vca:
  telephony:
    agent-tools: save_lead,transfer_to_human,end_call   # 默认全开
    transfer-dial-string: ${VCA_TELEPHONY_TRANSFER_DIAL_STRING:}  # 坐席拨号串, 留空=不下发转人工工具
```

**主动挂机为什么要等两个条件。** 告别语是在工具返回之后才生成、合成的，工具执行的那一刻下行缓冲本来就是空的。
只看"缓冲空了"就挂，客户一个字都听不到（实测复现过）。所以条件是**本轮已产完（`turnSubscription == null`）且缓冲已排空**。

**转人工用 `bridge` 而不是 `uuid_transfer`。** bridge 直接在本通道上执行，用的就是 socket 应用那条已建好的连接，
不需要另开一条 ESL——呼入场景可能根本没开外呼那条。桥接后通道离开停泊状态，unicast 随之停止，正是我们要的：
剩下的对话归坐席，而挂机事件仍从信令连接回来，会话照常收尾落库。

**没配坐席号码时不下发这个工具**，AI 会说"我让同事回电给您"，而不是假装转接、让客户对着静音等。

**实测（本机）：**

```
客户: 我想约个时间做种植牙，我姓王，这周六上午方便。
AI  : 王女士，您的种植牙面诊预约已登记，稍后会有专人联系您确认。   ← phone_lead 落库
客户: 好的，没有别的问题了，再见。
AI  : 好的，感谢您的来电，再见。                                 ← 播完后 4 秒挂断(agent-ended)
```

```sql
SELECT * FROM phone_lead ORDER BY id DESC LIMIT 1;
-- owner_id=11, peer_number=1000, name=王女士, intent=种植牙面诊, preferred_time=这周六上午
```

**代价：调工具的那一轮慢一倍。** 工具要多走一次"模型决定调用 → 执行 → 结果回灌 → 模型再组织回答"，
实测从 2 秒左右变成 3.7 秒。这是 function-calling 的固有成本，所以电话侧只放这三个工具。

**人设里要点名这几件事**（`prompts.phone-agent`）：客户说再见时调 `end_call`；全程只说中文
（qwen-flash 偶尔会蹦出 "goodbye"，电话里念出来很突兀）。不写清楚的话模型有时不调工具，只回一句话。

---

## 11. 通话后小结：让漏接的电话变成一条消息

商家不会去听录音。挂机后把这通电话压成"两三句摘要 + 一个意向等级"推到群里，才是他们真正会看的东西。

```
挂机 → CallSession 把对话快照交出去(此时内存里就有, 关了会话就只剩数据库的行)
     → CallAftermath(boundedElastic, 通话之外) → 大模型出小结 → 落库 phone_call_summary → 推 webhook
```

```yaml
vca:
  telephony:
    summary:
      enabled: true
      min-duration-sec: 10          # 秒挂/拨错/彩铃占呼入一大半, 短通话不调模型
      vendor: ${VCA_LLM_VENDOR:}    # 默认跟对话同一个厂商与模型
      model: ${VCA_TELEPHONY_LLM_MODEL:}
      webhook-url: ${VCA_TELEPHONY_SUMMARY_WEBHOOK:}   # 留空=只落库不推送
```

**推送用 webhook 而不是短信/微信。** 个人微信要公众号资质、短信要短信资质，而企业微信和钉钉的群机器人只要一个 URL、
当天就能用——小诊所把机器人拉进店长群即可。报文顶层是这两家认的文本消息格式（多余字段它们忽略），
同一个请求里另挂一个 `call` 对象给自建后台用结构化字段。

商家群里收到的样子：

```
【来电小结】意向 A
来电: 13800138000  时长: 32 秒
客户王女士预约周六上午进行种植牙面诊，客服已记录并承诺后续专人确认。
待跟进: 请尽快安排专人联系客户确认具体时间与面诊流程。
```

**意向分级**：A 已约时间或要求回电 / B 有兴趣没定下来 / C 只是问问 / D 无效（骚扰、拨错、没说话）。

**两个踩过的坑**（都有回归测试钉住）：

- **不要让模型输出 JSON**，只要三行 `摘要:/意向:/跟进:`。但**模型未必照做**：实测 deepseek 直接输出 markdown 分点，
  qwen-flash 把意向那行写成"明确表达种植牙面诊需求"这样一句中文。所以解析必须能兜住——取不到字母就按中文关键词推断，
  再取不到才退回 C；整行都不成格式就把模型原话当摘要，别丢信息。
- **厂商要钉死**。不指定时治理层按候选表顺序选，选到的未必听得懂这里的格式要求（实测就是这么出的 markdown）。
  默认跟对话用同一个厂商与模型。

**落库与推送各自兜异常**：两件事互不依赖，而群里那条消息比留档重要，数据库挂了不能连带把通知也吞掉。

---

## 12. 多商家：一套服务给多家店用

按**客户拨的号码**区分商家。呼入时这个号码就是"打给了哪一家"，FreeSWITCH 在 socket 握手时就给了我们
（`Caller-Destination-Number`），不需要额外对账——这是当初从 AudioSocket 换过来的收益之一。

```yaml
vca:
  telephony:
    # 顶层配置 = 默认商家: 没配 merchants、或号码没登记时都走它(单店部署完全不受影响)
    greeting: 您好，这里是智能语音助手，请问有什么可以帮您的吗？
    merchants:
      - number: "01088886666"          # 商家的接入号, 必填
        name: 美好口腔
        greeting: 您好，这里是美好口腔，请问有什么可以帮您的吗？
        knowledge-owner: "11"          # 这家自己的知识库
        transfer-dial-string: sofia/gateway/trunk/13800138000
        summary-webhook: https://qyapi.weixin.qq.com/...    # 推到这家自己的群
      - number: "01099998888"
        name: 启明少儿英语
        greeting: 您好，这里是启明少儿英语，请问有什么可以帮您的吗？
        knowledge-owner: "12"
```

每家可以只写要改的项，**没填的回退到顶层**，免得为了改一句开场白把整套配置抄一遍。
用环境变量写也行（`.env.phone` 里）：

```
VCA_TELEPHONY_MERCHANTS_0_NUMBER=5000
VCA_TELEPHONY_MERCHANTS_0_NAME=美好口腔
VCA_TELEPHONY_MERCHANTS_0_KNOWLEDGE_OWNER=11
```

按商家走的有：开场白、人设、知识库、音色、转人工号码、小结推送地址。启动时会把每家的开场白都预合成
（接通那一刻要立刻出声，那时才调 TTS 就是几秒静音）。

**实测**（本机用 5000/5001 两个分机演示，问同一句"周末几点上班"）：

| 拨打 | 认领商家 | 回答 |
|---|---|---|
| 5000 | 美好口腔 | 周六 9 点到 18 点，周日 9 点到 12 点 |
| 5001 | 启明少儿英语 | 早上九点到晚上六点 |

通话小结也各自推给各自的群，日志里带商家名：`通话小结(启明少儿英语): 意向=B…`。

**现在是配置驱动、启动时定死**，加一家要改配置重启。等商家多到需要自助开通，把 `MerchantRegistry`
换成查库 + 缓存即可，调用方只认这个接口。

> 改 `deploy/freeswitch/conf/` 下的拨号计划后要 **重启容器**，`reloadxml` 不够——
> 容器启动时才把 `/conf` 的模板渲染进 `/etc/freeswitch`，热重载读的是渲染后的那份。

---

## 13. 排查

| 现象 | 看哪里 / 原因 |
|------|---------------|
| 拨 5000 接通后立刻挂断，本项目没有任何日志 | 本项目没起，或没开 `VCA_TELEPHONY_ENABLED`，或 `provider` 不是 freeswitch |
| 本项目日志"N ms 内没收到 FreeSWITCH 的媒体包" | `vca_media_remote_host` / `vca_media_local_ip` 配错 |
| 软电话能接通但两边都没声音 | `EXTERNAL_IP` 不对（本机 127.0.0.1，手机软电话填电脑局域网 IP） |
| 本项目启动报"FreeSWITCH 拒绝了本机地址" | `apply-inbound-acl` 没放行连入地址 |
| 外呼报 `MANDATORY_IE_MISSING` | 目录缺 `dial-string` |
| Linphone 注册成功（绿点）但拨号卡住、报 Call could not be created | **账号 Domain 末尾多了空格**。注册请求带着空格碰巧认证通过，拨号时 Linphone 去掉了空格，找不到保存的密码，不再重发带认证的请求。删掉账号重新手输 `127.0.0.1` |
| Linphone 拨号报 Call could not be created，顶部挂着"Appel en cours" | 上一通卡住的呼叫还在，Linphone 不让新建。点进去挂断，或 Cmd+Q 重开 |
| 回复慢（说完到出声超过 3 秒） | 见 §8 的分段表，按日志里"判停+识别 / LLM 首 token / TTS 首音频"三个耗时定位是哪一段 |
| 说了话 AI 没反应，日志"开口诊断"峰值不到 0.02 | 麦克风音量太小，或一个字太短没撑够 `onset-ms`。实测 Linphone 采到的"喂"峰值只有 0.054、超过门槛只有 100ms。调大 macOS 输入音量；本地测试可临时 `VCA_TELEPHONY_VAD_SPEECH=0.01 VCA_TELEPHONY_VAD_ONSETMS=100` |
| AI 说两个字就自己停 | 外放回声被当成插话，戴耳机 |
| FreeSWITCH 重启后外呼失败 | 正常，`EslClient` 会在 30 秒内自动重连，日志"已重连" |

常用命令：

```bash
docker logs -f vca-freeswitch                                  # FreeSWITCH 控制台日志
P=$(grep ^ESL_PASSWORD deploy/freeswitch/.env | cut -d= -f2)
docker exec vca-freeswitch fs_cli -p "$P" -x "sofia status profile internal reg"   # 注册情况
docker exec vca-freeswitch fs_cli -p "$P" -x "show channels"                       # 当前通话
docker exec vca-freeswitch fs_cli -p "$P" -x "sofia global siptrace on"            # 打开 SIP 报文跟踪(用完 off)
```

---

## 14. 限制与待办

| 项 | 状态 |
|----|------|
| 真实线路 | 配置与体检脚本已就位（§7），但没有线路可联调 |
| 多商家自助开通 | 已实现按号码路由（§12），但配置驱动、加一家要重启；自助开通需要改成查库 |
| 电话里的知识库检索 | 已完成（§9），按商家隔离 |
| 按键进对话（例如按键输入手机号） | 事件已到 `CallSession`，只打日志 |
| 留资 / 转人工 / 主动挂机 | 已完成（§10） |
| 通话后小结 + 推送 | 已完成（§11）。邮件/短信通道未做，目前只有 webhook |
| 意向分级的准确率 | 只在本机用几通模拟通话看过，真实通话需要积累样本再调分级标准 |
| 并发路数上限 | 未实现，批量外呼前必须补 |
| 电话 VAD 阈值 | 默认值是经验起步值，真实线路需用录音回归。软电话麦克风偏小时用 `VCA_TELEPHONY_VAD_SPEECH=0.01 VCA_TELEPHONY_VAD_ONSETMS=100` |
| 首通电话偏慢 | 进程刚启动时到大模型/合成的连接是冷的，第一通约 3 秒，之后 1.7~2.1 秒 |

---

## 15. 关键文件索引

| 用途 | 文件 |
|------|------|
| 单路通话：握手、信令、媒体 | `vca-telephony/.../provider/freeswitch/FreeSwitchCallLeg.java` |
| 接 socket 应用的服务端 | `vca-telephony/.../provider/freeswitch/FreeSwitchSocketServer.java` |
| 报文编解码与防注入 | `vca-telephony/.../provider/freeswitch/EslMessage.java` |
| 外呼 | `vca-telephony/.../provider/freeswitch/FreeSwitchTelephonyProvider.java`、`EslClient.java` |
| 通话编排、补静音 | `vca-telephony/.../session/CallSession.java` |
| 装配与配置 | `vca-telephony/.../TelephonyAutoConfiguration.java`、`TelephonyProperties.java` |
| FreeSWITCH 配置 | `deploy/freeswitch/conf/`，接入本项目的地方在 `dialplan.xml` |
| 一键启动脚本 | `start-phone.sh`（参数样例 `.env.phone.example`） |
| 中继体检 | `deploy/freeswitch/trunk-status.sh` |
| 本地环境说明 | `deploy/freeswitch/README.md` |
| 单测（测试替身扮演 FreeSWITCH） | `vca-telephony/src/test/.../provider/freeswitch/` |
| 选型与整体方案 | [10 · 电话接入](./10-telephony-outbound.md) |
