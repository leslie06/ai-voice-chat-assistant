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
服务器上的 FreeSWITCH **已部署并运行**（§7.2）。还差三件手动的事才能接真实电话：
开安全组、在 `/etc/vca.env` 写商家配置、配 HT813（§7.3–§7.5）。

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
| `transfer-dial-string` | `VCA_TELEPHONY_TRANSFER_DIAL_STRING` | 空 | 转人工呼哪里；留空则不下发该工具。库里的门店不继承它 |
| `transfer-failed-prompt` | `VCA_TELEPHONY_TRANSFER_FAILED_PROMPT` | "同事这会儿没接到…" | 坐席没接时说的话（预合成），说完 AI 接着聊 |
| `vca.store.admin-user-ids` | `VCA_ADMIN_USER_IDS` | 空 | 运营管理员账号 id，见 §12.1 |
| `vca.store.phone-recordings-dir` | `VCA_PHONE_RECORDINGS_DIR` | 空 | FreeSWITCH 录音目录，商家后台据此回放，见 §11.1 |
| `summary.enabled` | `VCA_TELEPHONY_SUMMARY_ENABLED` | `true` | 通话后小结，见 §11 |
| `summary.min-duration-sec` | `VCA_TELEPHONY_SUMMARY_MIN_SEC` | `10` | 短于此的通话不摘要 |
| `summary.webhook-url` | `VCA_TELEPHONY_SUMMARY_WEBHOOK` | 空 | 小结推送地址（企业微信/钉钉群机器人 URL 直接填） |
| `merchants[n].*` | `VCA_TELEPHONY_MERCHANTS_n_*` | 空 | 多商家，按被叫号码路由，见 §12 |

完整项以 `TelephonyProperties` 和 `vca-bootstrap/src/main/resources/application.yml` 为准。

### 5.2 FreeSWITCH（`deploy/freeswitch/conf/`，三个文件就是全部）

| 文件 | 内容 |
|------|------|
| `freeswitch.xml` | 核心参数、加载的模块、控制台日志、事件套接字、访问名单、SIP profile |
| `dialplan.xml` | `6000` 回声测试、其余 3~20 位号码接入本项目、`vca-outbound` 外呼回连、`vca-connect` 公共接入段 |
| `directory.xml` | 分机 `1000`、域级 `dial-string`、`.env` 里的老网关分机，以及 include 进来的 `gateways/*.xml`（每台网关一个文件，见 §7.4） |

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

**线上现状（2026-09-19）**：FreeSWITCH 已经部署在阿里云那台服务器上并正常运行，
本项目也是 FreeSWITCH 模式。还差三件事才能接真实电话，都要在控制台或设备上手动做，见 §7.5、§7.3、§7.4。

### 7.1 先决定走哪条路

| 路子 | 适合 | 要准备什么 | 成本 |
|---|---|---|---|
| **FXO 语音网关** | 单店试点、个人身份也能做 | 一个 FXO 网关（几百元），接商家现有的座机线 | 设备费，无资质门槛 |
| **SIP 中继** | 多店、要统一号码 | 企业资质（个体户不行，见 [10](./10-telephony-outbound.md)），向云通信厂商申请 | 号码月租 + 分钟费 |

两条路对 FreeSWITCH 来说是同一件事：**一个 SIP 对端**。区别只是地址在局域网还是公网、认证方式是账号还是 IP 白名单。
所以配置项是同一套。当前走的是第一条。

### 7.2 在服务器上部署 FreeSWITCH

**先腾出 5060。** 早先那轮实验留下的 Asterisk 占着这个端口，两者不能共存：

```bash
systemctl stop asterisk && systemctl disable asterisk
ss -lnup | grep ":5060 " || echo "端口已释放"
```

**传文件并启动**（在开发机上执行）：

```bash
rsync -az --exclude .env --exclude recordings --exclude gateways \
  deploy/freeswitch/ root@<服务器>:/opt/vca/freeswitch/
```

**`gateways/` 一定要排除**：服务器上那份是各家店的网关分机（含密码，由 `add-gateway.sh` 在服务器上生成），
不排除的话会被开发机上的联调文件覆盖，线上网关全部注册失败。

服务器上写 `/opt/vca/freeswitch/.env`（**与开发机那份是两套独立密钥**，`chmod 600`）：

```bash
SIP_PASSWORD=$(openssl rand -hex 12)
ESL_PASSWORD=$(openssl rand -hex 12)
ATA_LINE_USER=8001
ATA_LINE_PASSWORD=$(openssl rand -hex 12)
ATA_PHONE_USER=8002
ATA_PHONE_PASSWORD=$(openssl rand -hex 12)
EXTERNAL_IP=<服务器公网 IP>
# 国内服务器连不上 Docker Hub, 基础镜像换个能通的站
ALPINE_IMAGE=docker.m.daocloud.io/library/alpine:3.22
```

然后：

```bash
cd /opt/vca/freeswitch
docker compose -f docker-compose.server.yml up -d --build
```

**服务器用的是另一份编排文件**，与本地那份只差一处：`network_mode: host`。别在 Mac 上用它，
Docker Desktop 的宿主机网络是模拟的，SIP/RTP 走不通。

用宿主机网络解决了三个真问题，代价是端口不再由 Docker 收口：

| | 本地（网桥） | 服务器（宿主机网络） |
|---|---|---|
| RTP 端口 | 二十个 UDP 口逐个映射，媒体多一层 NAT | 直接开在网卡上，不再出现"能接通但没声音" |
| `VCA_HOST` | `host.docker.internal`（只在 Docker Desktop 上好使） | `127.0.0.1` |
| `ESL_BIND` | `0.0.0.0`，宿主机只映射 `127.0.0.1:18021` | `127.0.0.1`，事件套接字等于完全控制权，绝不能露在公网 |
| `VCA_MEDIA_BIND` | `0.0.0.0`，容器的回环不是宿主机的回环 | `127.0.0.1`，否则谁都能往正在进行的通话里灌音频 |

后两项有默认值，`docker-compose.server.yml` 里已经写好，不用自己配。

**改过 `conf/` 或 `entrypoint.sh` 之后必须带 `--build`。** 模板是在容器启动时由 `entrypoint.sh` 渲染的，
而 `entrypoint.sh` 是打进镜像的。只 `--force-recreate` 会拿旧的 entrypoint 去渲染新模板，
结果是占位符原样留在配置里（表现为 `vca_media_local_ip=` 后面空着）。

**验收**：

```bash
P=$(grep ^ESL_PASSWORD /opt/vca/freeswitch/.env | cut -d= -f2)
docker exec vca-freeswitch fs_cli -p "$P" -x "sofia status"     # 两个 profile 都要 RUNNING
ss -lntp | grep ":8021"                                          # 必须是 127.0.0.1:8021
docker exec vca-freeswitch grep -oE 'vca_media_local_ip=[^"]*' /etc/freeswitch/dialplan.xml
```

`sofia status` 里两条 profile 的地址应该是**公网 IP**（`sip:mod_sofia@<公网IP>:5060`）。
网卡上绑的是内网地址，这是对的：阿里云的公网 IP 是 NAT 出去的，所以 `EXTERNAL_IP` 必须显式写公网 IP，
否则 SDP 里会写一个内网地址，对端把 RTP 发过去就石沉大海。

### 7.3 本项目侧的配置（`/etc/vca.env`）

线上这份和开发机的 `.env.phone` 是两套，**部署 jar 不会带过去**。把下面这段并进 `/etc/vca.env`，
然后 `systemctl restart vca`：

```bash
VCA_TELEPHONY_ENABLED=true
VCA_TELEPHONY_PROVIDER=freeswitch
VCA_TELEPHONY_GREETING=您好，这里是智能语音助手，请问有什么可以帮您的吗？
# 电话要首字快, 别用会先生成一段思考的模型
VCA_TELEPHONY_LLM_MODEL=qwen-flash
QWEN_ENABLE_SEARCH=false
# 转人工桥接到 HT813 的 PHONE 口, 前台那台有绳话机会响
VCA_TELEPHONY_TRANSFER_DIAL_STRING=user/8002@vca.local

# 多商家: 按 HT813 送过来的被叫号码认领
VCA_TELEPHONY_MERCHANTS_0_NUMBER=5000
VCA_TELEPHONY_MERCHANTS_0_NAME=美好口腔
VCA_TELEPHONY_MERCHANTS_0_GREETING=您好，这里是美好口腔，请问有什么可以帮您的吗？
VCA_TELEPHONY_MERCHANTS_1_NUMBER=5001
VCA_TELEPHONY_MERCHANTS_1_NAME=启明少儿英语
VCA_TELEPHONY_MERCHANTS_1_GREETING=您好，这里是启明少儿英语，请问有什么可以帮您的吗？
```

**知识库归属要自己填**。`VCA_TELEPHONY_MERCHANTS_n_KNOWLEDGE_OWNER` 填的是账号 id，
而线上库和开发机的库是两套，开发机上的 11 和 12 在线上很可能是别的人、或者根本没有那批资料。
先在线上确认哪个账号传了哪家的资料，再填对应的 id。不填就是电话里没有知识库，
商家资料类问题（价格、营业时间）答不上来。

**VAD 阈值别照抄开发机那组。** `start-phone.sh` 里的 `VCA_TELEPHONY_VAD_SPEECH=0.01` 是按软电话
偏小的麦克风电平调的，真实电话线的电平和底噪完全不同，线上先用代码默认值，再按 §7.6 第 4 条调。

### 7.4 用语音网关接诊所的固话线（HT813 这类 ATA）

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

**开通一家店的网关（2026-09-24 起）**：在服务器上执行

```bash
cd /opt/vca/freeswitch
./add-gateway.sh 5002 阳光口腔        # 接入号 + 店名(备注)
./add-gateway.sh --list               # 已开通的网关, 以及此刻谁注册上来了
./add-gateway.sh --remove 5002        # 撤销
```

脚本给这家店生成一对分机（LINE `8NN1` / PHONE `8NN2`）和随机密码，写进 `gateways/gw-8NN1.xml`，
然后 `reloadxml` —— **不用重启 FreeSWITCH，在途通话不受影响**。最后打印出 HT813 要填的账号密码，
以及网页门店表单里"转人工分机"该填的号（PHONE 那个）。

LINE 分机在目录里**绑定了接入号**（`vca_access_number`）：从它进来的电话一律按这家店接待，
本项目不再只看网关"转 VoIP"框里填的号码 —— 那是装机时手填的，填错了会把 A 店的来电当成 B 店。
拨号计划也不再写死号码（原来只认 `500[01]`，每开一家店都要改模板、重建容器）。

老的那台网关（`.env` 里的 `ATA_LINE_USER=8001` / `ATA_PHONE_USER=8002`）照常可用；想让它也按分机认领，
在 `.env` 里加 `ATA_LINE_NUMBER=5000` 后重建容器。

**AI 接不了时转前台座机（2026-09-24）**：LINE 分机在目录里还配了 `vca_fallback_dial`（这家店的 PHONE 分机）。
拨号计划里 `socket` 应用返回而通道还活着，说明 VCA 没接住这通电话——没起、正在发布重启、崩了、握手失败、
或通话中途连接断了——这时不再挂断，而是转到前台座机（放回铃音，等 40 秒）。正常结束的通话走不到这一步。
本机实测：VCA 没起时来电约 30ms 转到前台；通话中途强杀 VCA，通话立刻转到前台；正常挂机不会误触发。
老网关的兜底分机是 `.env` 里的 `ATA_PHONE_USER`。

**值守与备份**：网关掉线、服务挂了会推到群里，每天备份数据库与网关分机，见 [`deploy/ops/`](../deploy/ops/README.md)。

**HT813 侧**（Web 界面），按端口分别配：

| 页面 | 项 | 值 |
|---|---|---|
| FXS PORT（PHONE 口） | SIP Server | `<服务器公网 IP>:5060` |
| FXS PORT | SIP User ID / Authenticate ID | `add-gateway.sh` 打印的 PHONE 分机（老网关是 `8002`） |
| FXS PORT | Password | 同上打印的密码（老网关是 `.env` 里的 `ATA_PHONE_PASSWORD`） |
| FXO PORT（LINE 口） | SIP Server | `<服务器公网 IP>:5060` |
| FXO PORT | SIP User ID / Authenticate ID | 打印的 LINE 分机（老网关是 `8001`） |
| FXO PORT | Password | 同上打印的密码（老网关是 `.env` 里的 `ATA_LINE_PASSWORD`） |
| FXO PORT | Number of Rings | `2`（响两声自动接，别设 1，会影响来电号码检测） |
| FXO PORT | PSTN Ring Thru FXS | `No`（否则来电先让座机响，人一接就绕过了 AI） |
| **BASIC SETTINGS** | **Unconditional Call Forward to VOIP** | 三个框都要填：User ID 填这家店的接入号，Sip Server `<服务器公网 IP>`，端口 `5060` |
| FXO PORT | Enable Current Disconnect | `Yes` |
| FXO PORT | Enable PSTN Disconnect Tone Detection | `Yes`，Tone 填 `f1=450@-32,f2=450@-32,c=350/350;`（出厂是美国忙音，国内匹配不上） |
| 两个口 | 语音编码 | 只留 PCMA（或 PCMU），关掉其它 |
| 两个口 | Caller ID Scheme | 按线路选（大陆多为 FSK Bellcore） |
| 两个口 | NAT Traversal | `Keep-Alive`（家庭宽带是 NAT，映射一断服务器就呼不到这台设备，转人工时座机不响） |
| FXO PORT | **Disable Network Echo Suppressor** | **`Yes`**（即关掉抑制器）。它在 AI 说话时把客户的声音整个压成零，客户插话永远打不断，见 §13"不能打断" |
| FXO PORT | Disable Line Echo Canceller | `No`（保留回声消除，只关抑制） |

**SIP Server 填服务器公网 IP，不是局域网地址。** 网关在诊所、FreeSWITCH 在云上，中间隔着公网。

`Unconditional Call Forward to VOIP` 是关键：FXO 是模拟线，**没有被叫号码这个概念**，
所以要在网关上写死一个号码送给 FreeSWITCH，否则呼不出去。用 `add-gateway.sh` 开通的网关，
认领门店以分机绑定的接入号为准，这个框填错了也不会串店；老网关（没绑定）仍靠它认领。一台网关一家店。

`Current Disconnect / Busy Tone Disconnect` 决定挂机检测：对端挂断后模拟线要靠极性反转或忙音才能察觉，
不开的话通话会挂到单通上限（默认 5 分钟）才断。它是最容易漏配、也最容易表现为"电话占线不放"的一项。

Caller ID 拿得到的话，线索表里就是客户的真实手机号；拿不到就只能靠 AI 在通话里问。

> **插卡盒（SIM 卡转固话线）那一段要注意**：拿它代替真实固话线做联调没问题，但用 SIM 卡把手机来电
> 转成 SIP 送上公网，功能上等同于 GoIP，属于《反电信网络诈骗法》第十四条点名的设备，运营商风控也容易停卡。
> 换成诊所真实的固话线，上面的配置一行都不用改。

### 7.5 安全组：把口子开到刚好够用

宿主机网络下端口直接开在网卡上，能不能连进来由**云厂商的安全组**决定。实测当前 5060/udp 从公网**打不通**，
HT813 连不上，这一步必须在阿里云控制台做：

| 端口 | 协议 | 授权对象 | 给谁用 |
|---|---|---|---|
| 5060 | UDP | 诊所宽带的公网 IP；动态 IP 只能 `0.0.0.0/0` | HT813 注册与呼叫 |
| 5060 | TCP | 同上 | 大报文的退路，见下面那段 |
| 16384-16402 | UDP | 同上 | RTP 语音 |
| 5080 | UDP | 只在走运营商 SIP 中继时开，且只对中继 IP | SIP 中继 |

**8021 一个字节都不要开。** 事件套接字等于 FreeSWITCH 的完全控制权，它已经绑在回环上了，
安全组上再开就是自相矛盾。

开成 `0.0.0.0/0` 的风险和对策：几分钟内就会有人来扫号。这套配置下扫号者拿不到什么——
`internal` 通道 `auth-calls=true`、`accept-blind-reg=false`，必须摘要认证；
拨号计划里能拨的只有"接入本项目"和 `6000` 回声测试，**没有任何通向 PSTN 的出口**，
所以即使密码被猜中也盗打不了长途（最多打进来和 AI 聊天）。真正要守住的是各台网关的分机密码够长够随机
（`add-gateway.sh` 生成 24 位十六进制）。

验证开没开：

```bash
# 在任意一台外网机器上
python3 - <<'PY'
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(6)
s.sendto(b"OPTIONS sip:x SIP/2.0\r\nVia: SIP/2.0/UDP 0.0.0.0:5099;branch=z9hG4bK-p\r\n"
         b"From: <sip:p@x>;tag=p\r\nTo: <sip:x>\r\nCall-ID: p\r\nCSeq: 1 OPTIONS\r\n"
         b"Max-Forwards: 70\r\nContent-Length: 0\r\n\r\n", ("<服务器公网IP>", 5060))
try: print("通:", s.recvfrom(2048)[0].splitlines()[0])
except socket.timeout: print("不通, 安全组没放行")
PY
```

### 7.6 接上之后按这个顺序验

```bash
cd /opt/vca/freeswitch && ./trunk-status.sh      # 通道/中继/白名单/最近的拒接, 一屏看完
```

1. **两个口都注册上。** `docker exec vca-freeswitch fs_cli -p "$P" -x "sofia status profile internal reg"`
   里应该能看到 `8001` 和 `8002`。看不到就是安全组、SIP Server 地址或密码的问题，三者按这个顺序排。
2. **打进来。** 用别的手机拨诊所号码，听到开场白即通。没通就 `./trunk-status.sh --trace` 打开 SIP 报文跟踪，
   看 `docker logs -f vca-freeswitch`：收不到 INVITE 是线路/安全组的事；收到但被拒多半是认证。
3. **听得见声音。** 能接通但双方无声，九成是 `EXTERNAL_IP` 没配成公网 IP。
4. **窄带识别率。** 真实线路的电平和噪声与软电话不同，先用几通真实通话看日志里的"开口诊断"，
   再决定要不要调 `VCA_TELEPHONY_VAD_SPEECH`（软电话那组 0.01 是偏低的，真实线路多半用得上默认 0.02）。
5. **转人工。** 说"转人工"，有绳电话机应该响。不响先看 `VCA_TELEPHONY_TRANSFER_DIAL_STRING` 是不是 `user/8002@vca.local`。
6. **挂机及时。** 挂断后看日志里的挂机原因和时间，拖到 5 分钟上限才断就是网关的挂机检测没开。
7. **打出去。** `POST /telephony/calls` 拨自己的手机（§3）。

### 7.7 走运营商 SIP 中继（另一条路）

有企业资质、要统一号码时走这条。在 `.env` 里加：

```bash
TRUNK_HOST=<厂商给的地址>
TRUNK_NAME=trunk                # 拨号串里用它: sofia/gateway/trunk/<号码>
TRUNK_USER=<账号>               # IP 白名单式就把这三项留空
TRUNK_PASSWORD=******
TRUNK_REALM=                    # 留空 = 用 TRUNK_HOST
TRUNK_ACL=<中继的 IP>/32        # 必配, 不配则全部拒接
```

外呼改走中继（`.env.phone` 或 `/etc/vca.env`）：

```bash
VCA_FS_ESL_ENABLED=true
VCA_FS_ESL_ENDPOINT=sofia/gateway/trunk/{number}
```

**中继走独立的 SIP 通道**（`external`，端口 5080），与软电话和 ATA 那条（`internal`，5060）彻底分开。
中继按 IP 认、不做摘要认证；软电话和 ATA 必须认证。放一个通道里就得在"给中继开口子"和"不给扫号者开口子"之间二选一。

**来电落在 `ai-inbound` context**，那里只有一条规则：不管被叫是哪个号码都交给 AI。
号码本身随通道数据交给本项目，由它按 `merchants` 认领是哪家商家（§12）。

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
| `transfer_to_human` | 客户要找真人，或问到投诉、退费、病情判断 | 说完"请稍等"后呼坐席，接起来再接通两路；没人接就回到 AI 留资 |
| `end_call` | 客户说"没别的了""再见" | **说完告别语再挂**，不是立刻挂 |

```yaml
vca:
  telephony:
    agent-tools: save_lead,transfer_to_human,end_call   # 默认全开
    transfer-dial-string: ${VCA_TELEPHONY_TRANSFER_DIAL_STRING:}  # 坐席拨号串, 留空=不下发转人工工具
```

**主动挂机为什么要等两个条件。** 告别语是在工具返回之后才生成、合成的，工具执行的那一刻下行缓冲本来就是空的。
只看"缓冲空了"就挂，客户一个字都听不到（实测复现过）。所以条件是**本轮已产完（`turnSubscription == null`）且缓冲已排空**。

**转人工：先单独呼坐席，接起来再接通两路（2026-09-24 重做）。**

```
"好的，我帮您转接人工，请稍等"播完
  → bgapi uuid_setvar <客户通道> hangup_after_bridge true      坐席聊完挂机, 客户跟着挂
  → bgapi originate {origination_uuid=<坐席通道>,originate_timeout=20,…}<拨号串> &park()
       客户这一路仍停泊在我们手里, AI 给他放回铃音(450Hz 响 1 停 4)
  ├─ 坐席接了   → bgapi uuid_bridge <客户通道> <坐席通道>, 此后对话归坐席, 不计单通时长
  ├─ 坐席没接   → 客户这一路从头到尾没动过, AI 说"同事这会儿没接到, 留个称呼让他们回电", 接着聊(模型会调留资)
  └─ 客户先挂了 → bgapi uuid_kill <坐席通道>, 不让前台接起一个没人的电话
```

**为什么不直接在客户这一路上执行 `bridge`**（2026-09-18 的做法）。本机实测两个问题：

1. **坐席不在线时客户被直接挂断**。`bridge` 失败默认会把主叫一起挂掉（原因 `USER_NOT_REGISTERED`），
   要设 `continue_on_fail` 才会回来——但回来了也没用，见下一条。
2. **媒体接不回来**。任何让通道重置媒体的操作（`bridge`、中途改打包时长）都会拆掉 unicast；而
   `switch_ivr_park` 把 unicast 连接缓存在局部变量里、只在进入停泊时取一次，同一次停泊内重新下发的 unicast
   会被它拿着旧连接发包失败而立刻拆掉（FreeSWITCH 日志 `Created unicast connection` 紧跟
   `Attempting to join thread that does not exist`，读 `switch_ivr.c` 确认）。客户的电话就成了两头哑。
3. 另外，旧做法在桥接**成功**时也有问题：桥接后 unicast 停止，媒体泵把它当断流，重建三次后挂断——
   前台接起来聊不到十秒电话就断了。

现在的做法让客户这一路在接通坐席之前完全不动，这三个问题都不存在。坐席的结果经 `BACKGROUND_JOB` 回来：
socket 连接开了 `myevents` 之后只收本通道事件，但本连接发起的后台任务结果（`Job-Owner-UUID` 是本通道）照样投递。

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

### 11.1 商家后台：通话、线索、录音（2026-09-24）

群推送会被刷掉、没配推送地址的店干脆收不到，所以网页上也要能查。门店所属账号（或运营管理员）在
"设置 → 电话客服"的门店列表里点 📞，可以看：

| 页签 | 内容 | 接口 |
|---|---|---|
| 通话 | 最近 30 天的通话小结：时间、来电号码、时长、意向等级、摘要、待跟进；有录音的可以直接听 | `GET /api/merchants/{id}/calls?days=30` |
| 线索 | 最近 90 天 AI 记下的称呼、电话、意向、期望时间 | `GET /api/merchants/{id}/leads?days=90` |
| 录音 | 双声道 wav（左 = 来电方，右 = AI） | `GET /api/merchants/{id}/calls/{callId}/recording` |

几处取舍：

- **按"接入号 + 所属账号"两个条件取**。接入号会回收再分配，只按号码查，新店会看到旧店客户的电话和手机号。
- **录音只给这家店名下有记录的通话**，通话 id 必须是 uuid 形状（它会拼进文件路径）。浏览器的 audio 标签带不了登录令牌，
  网页用 fetch 带令牌取回再播放，不开"凭链接就能听"的口子。
- 只有 10 秒以上的通话有小结，所以列表里看不到秒挂、拨错的电话。
- 页面里的摘要和线索来自客户原话和模型输出，一律按纯文本渲染。
- 录音目录由 `VCA_PHONE_RECORDINGS_DIR` 指定（服务器上 `/opt/vca/freeswitch/recordings`），VCA 以 `vca` 用户运行，要能读这个目录；
  启动日志会打"录音回放目录 …"或"不可读"。
- **录音保留期**：`deploy/ops/vca-cleanup.sh` 每天删除超过 `RECORDING_KEEP_DAYS`（默认 90 天）的录音。录音里是客户的声音和手机号，
  留存要有上限；小结与线索是文字，不受影响。

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

### 12.1 商家进库：诊所自己填资料（2026-09-23）

上面的配置文件方式留着给单店和联调用；产品形态是**诊所用自己的账号登录网页，自己维护门店资料**，
存在 `phone_merchant` 表里，改完下一通电话就生效，不用重启。

**两个来源，库里优先**：`MerchantRegistry.resolve(被叫号)` 先查库（`MerchantStore`），没有再看配置文件，
都没有回退到顶层默认商家。库里的结果缓存 30 秒（查不到的号也缓存，扫号机器人拨的随机号不会每个都打库）；
资料一有增删改，存储层通过变更通知把缓存整体作废。查库失败不让电话失败：退回配置文件里的商家，记一行 warn。

**资料字段**（`MerchantProfile`）分两层：

| 层 | 字段 | 给谁用 |
|---|---|---|
| 接线参数 | `number`（接入号，全局唯一）、`enabled`、`industry`、`greeting`、`ttsVoice`、`transferDialString`、`summaryWebhook`、`asrVocabularyId` | 留空回退：开场白按店名生成，其余回退顶层默认 |
| 机构资料 | `address`、`businessHours`、`phone`、`transport`、`services`、`staff`、`bookingRules`、`notes`、`systemPrompt`（补充要求） | 渲染成一段"角色说明 + 资料"文本 |

人设的拼法是 **电话人设 + 机构资料 + 商家补充**（`TelephonyProperties.toMerchant`）：电话人设里"简短、
一次只问一件事"这些约束不能丢——早先商家人设整段替换电话人设，结果 AI 一开口就是长篇大论，说到一半被判停。
机构资料只渲染填了的项，结尾固定加一句"资料里没有的信息一律不要编造"。知识库归属直接取门店所属账号
（`knowledgeOwner = ownerId`），所以诊所在同一个账号下传的资料，电话里就能查到。

**行业是一层"皮"**（`Industry`：`dental` 口腔诊所 / `education` 培训机构 / `generic` 其他商家）。
字段对所有行业一样，行业只决定四件事，加一个行业就是加一个枚举值：

| 行业决定的 | 口腔诊所 | 培训机构 | 其他商家 |
|---|---|---|---|
| 字段叫法（网页与渲染文本） | 项目与价格 / 医生团队 / 预约规则 | 课程与费用 / 师资 / 试听与报名 | 产品/服务与价格 / 团队成员 / 预约与办理 |
| 人设里的角色说明 | 来电多问价格、营业时间、能不能约面诊；有意向就邀约到店面诊；疗效只说以面诊为准 | 来电多是家长问课程、学费、师资、试听；有意向就邀约免费试听；不承诺提分 | 问产品/价格/时间/地址；有意向记下称呼与需求交同事回电 |
| 留资/小结的措辞 | "想做的项目" | "想学的课程" | "需求" |
| 识别热词的基础词 | 洗牙、种植牙、正畸…… | 试听、课时、雅思、少儿英语…… | 营业时间、优惠、退款…… |

**热词表按行业自动维护**（`HotWordSync`，开关 `VCA_TELEPHONY_HOT_WORD_SYNC`，默认开）。每个行业一张表，
词 = 行业基础词 + 该行业下所有启用门店的店名和项目/人员每行开头的名字（"洗牙 200-400 元"→洗牙，
"李老师，剑桥考官"→李老师），封顶 400 词。为什么按行业不按门店：厂商对每个账号能建的表数有上限，
按门店建撑不了几家；热词只是加权，同行业共用一张表互不妨碍。启动时按固定前缀（环境前缀 `VCA_TELEPHONY_HOT_WORD_PREFIX`（默认 `vca`）+ 行业：`vcadental`/`vcaedu`/`vcaother`）
从厂商那边找回自己的表，比对词集，一样就直接用，不一样才改，所以重启不重建、库里也不用存映射
（**本机联调要把前缀改成别的，比如 `dev`**——和线上共用一个厂商账号时，两边会拿各自库里的门店互相覆盖对方的表）；
门店资料一改，3 秒后同步一次（连续改合并）。识别时取表的顺序：门店自己填的 `asrVocabularyId` →
所属行业的表 → 全局 `VCA_TELEPHONY_ASR_VOCABULARY_ID`。厂商接口不通只记 warn，旧表继续用。
换识别模型（`asr-model`）会自动重建：热词表与模型绑定，绑错模型的旧表会被删掉。

**两种角色（2026-09-24）**。注册是开放的，而门店资料里有几项直通电话线路，不能让注册用户自助：

| 字段 | 谁能改 | 为什么 |
|---|---|---|
| 接入号 `number` | 仅运营管理员（新建门店也只有管理员能做） | 库里优先于配置文件：谁能认领 5000，打给那家店的电话就归谁 |
| 转人工 `transferDialString` | 仅管理员；只收分机号（`8002`，自动补成 `user/8002@vca.local`）、`user/…@…`、`sofia/gateway/…/号码` | 原样进 FreeSWITCH 命令：带 `{变量}` 的串能在新通道上执行命令、能呼到任意 SIP 地址 |
| 音色 `ttsVoice`、热词表 `asrVocabularyId` | 仅管理员 | 音色可能是别人复刻的真人声音 |
| 小结推送 `summaryWebhook` | 门店自己，只收企业微信 / 钉钉机器人的 https 地址 | 否则服务器会替人往任意地址（含本机管理口）发请求 |
| 其余资料 | 门店自己 | |

管理员是配置出来的：`VCA_ADMIN_USER_IDS=<账号 id,…>`（`app_user.id`）。门店更新资料时上表前四项沿用库里的原值，
请求里改了也不算；规则见 `MerchantRules`，电话侧从库里取资料时会再校验一遍（库里可能存着校验上线前的值）。
另外，**库里的门店没配转人工时不再回退到顶层那个分机**——那是别家店的前台。

开通一家店的流程：商家自己注册账号 → 运营装好网关、在网页上新建门店（填商家的手机号作归属账号、接入号、
转人工分机）→ 商家登录后自己维护资料和推送地址。

**接口**（`MerchantRoutes`，与知识库接口同样的 `Authorization: Bearer <token>` 鉴权）：

```
GET    /api/merchants              我名下的门店; 管理员看全部
POST   /api/merchants              新建 —— 仅管理员; ownerAccount(手机号/邮箱)指定归属账号; 号被占返回 409
GET    /api/merchants/{id}
PUT    /api/merchants/{id}         整体覆盖; 非管理员改不了运营字段
DELETE /api/merchants/{id}
GET    /api/merchants/{id}/preview AI 实际拿到的机构资料文本, 调试用
```

网页端在"设置 → 电话客服（我的门店）"里有对应的表单。新加载到一家商家时会预合成它的开场白
（`MerchantRegistry` 的 `onLoaded` 回调），这家店的第一通电话不用等合成。

**实测**（本机，账号 13 在网页上登记接入号 5000 为"美好口腔"，配置文件里没配这家）：拨 5000，
日志 `建立通话会话: … 被叫=5000, 商家=美好口腔`，开场白是库里那句，知识库按 `user_id = 13` 查，
通话小结带着商家名。改资料后下一通即生效，改的是哪一家不重要——缓存整体重填的代价只是几次查库。

再登记一家培训机构"启明少儿英语"（接入号 5001，行业 education，不填开场白）：保存 3 秒后日志
`热词表已建: 行业=培训机构, id=vocab-vcaedu-…, 词数=49`；拨 5001，开场白是按店名生成的"您好，这里是启明少儿英语，
请问有什么可以帮您？"，`阿里云 ASR 开始 … vocabulary=vocab-vcaedu-…`，问"你们是干什么的"答"负责课程解答及预约试听"。

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
| **答了一两轮之后 AI 再也不出声**，日志"熔断打开, 跳过候选 ASR:ALIYUN" | 见下面的"熔断锁死" |
| **注册成功但一拨号就 408 超时**，服务器日志只有一条 `receiving invite` 后跟 `Abandoned` | 见下面的"认证后的 INVITE 太大" |
| **有些词一直识别不准**，比如"洗牙"被听成抵押、拿、压 | 见下面的"窄带线路要用窄带模型" |
| **客户挂断后通话不结束**，线路被占几分钟，后面的电话打不进来 | 见下面的"模拟线没有挂机信令" |
| **客户接通后听不到开场白**，十几秒后说"听不见你说话" | 见下面的"摘机冲击把开场白清掉了" |
| **开场白前几个字被吞** | 见下面的"三个体验问题" |
| **AI 说话时客户插不进话** | 见下面的"三个体验问题" |
| **每轮停顿明显**，说完要等一秒多 | 见下面的"三个体验问题" |
| 日志刷 `Failed to connect to /127.0.0.1:7890` | 本机代理（Clash 这类）被 JVM 当成了全局代理，见下面的"系统代理" |

### 三个体验问题（已改，2026-09-22）

链路通了之后，真实线路上暴露的三个体验问题，都是先量再改的。

**1. 开场白前几个字被吞**

录音显示我们从接通第 0 帧就在发，吞掉发生在网关→电话线→手机网络那一段：摘机瞬间模拟线路和
移动网络的语音通道都还没完全建好，头几百毫秒的音频到不了对方耳朵。IVR 系统的通行做法就是接通后
先停一下再放提示音。加了 `VCA_TELEPHONY_GREETING_DELAY_MS`，默认 `800`；客户在这 800ms 内就开口的话
（外呼常见的"喂？"），开场白不再放，直接进对话。软电话联调时可以设 0。

**2. 不能打断**

把 25 个"AI 正在说话"的窗口的诊断日志摊开看：

```
全程峰值人声=0.000  打断累计=0ms      ← 大多数窗口: 精确的零, 不是"小"
全程峰值人声=0.196  打断累计=160ms    ← 客户真的插话了: 电平够高, 但累计不到 250ms
全程峰值人声=0.284  打断累计=200ms
```

精确的 0.000 说明客户的声音在 AI 说话期间被**网关的回声抑制器**整个压掉了（HT813 的
"Network Echo Suppressor"，出厂开启）。客户喊得够大声时能漏一点过来，但断断续续，累计够不上
250ms 的门槛。两处一起改：

- **网关**：FXO PORT 页 `Disable Network Echo Suppressor` 设为 `Yes`。回声消除器（LEC）照旧开着，
  只关抑制。关了之后 AI 自己的回声会多一点，日志里 AI 说话时的峰值从 0.000 变成零点零零几是正常的；
  若出现峰值很低（<0.05）的"打断: 客户插话"，说明回声在触发，把 `VCA_TELEPHONY_VAD_BARGE_MS` 调回 200。
- **服务器**：`VCA_TELEPHONY_VAD_BARGE_MS` 默认 250 → 150，能接住上面那种断续的插话。

**3. 每轮停顿明显**

用录音量"来电方闭嘴 → AI 出声"，中位 **1.4 秒**；服务器侧指标 `vca.turn.perceived.first_audio`
平均 1.47 秒，两边对得上。拆开（以识别出最终文本为界）：

| 段 | 耗时 | 改法 |
|---|---|---|
| 判停等静音 | 500ms 固定 | 开语义判停：中间转写带句末标点或以"吗/了/多少"收尾 → 400ms；以"然后/那个/的"收尾 → 800ms，少切断人 |
| 识别收尾 | 以前没计量 | 加了日志 `ASR final: … (识别收尾 N ms)` |
| 查知识库 | 100~200ms，一次向量接口 | **判停那一刻就用中间转写预取**，与识别收尾并行；最终文本去掉标点后一致就复用，不一致就重查 |
| 大模型首字 | 165~300ms | 未动 |
| 合成首音频 | 约 200ms | 未动（已有预热） |

三项加起来预期省 200~300ms。手机网络那一段（每方向一两百毫秒）在我们之外。
效果看部署后的 `vca.turn.perceived.first_audio` 均值，以及日志里"知识库检索复用判停时的预取结果"出现的比例。

### 摘机冲击把开场白清掉了（已修，2026-09-21）

**现象**：真实线路上，客户接通后一片寂静，等十几秒说"喂喂""听不见你说话呀"。日志里接通 0.7 秒后
就出现 `状态迁移: IDLE → LISTENING`——那时客户根本还没开口。

**证据**：把六通电话的录音头 4 秒按 100ms 一格画出来（上排来电方，下排 AI）：

```
成功那通  来电方: ········································    AI: ▓██▓▓▓▓▓▓··▓▓▓▓▓▓▓▓▓▓▓▓█▓···
其余五通  来电方: ···▓█▓▓░░·······························    AI: ▓▓██▓█··························
```

五通都在接通后 0.2~0.9 秒有一个峰值 0.16~0.22 的响脉冲，紧接着 AI 的开场白在 0.6 秒处戛然而止。
那是 FXO 口摘机瞬间的线路冲击，不是人声，但响度、时长都够得上 VAD 的"开口"标准；而客户一开口就会
清掉正在播的开场白（这本是给外呼设计的，客户常在开场白中途说"不需要"）。

**改法**：接通后头 1.5 秒的上行音频不交给 VAD（`VCA_TELEPHONY_ANSWER_GUARD_MS`，默认 `1500`）。
冲击在 0.9 秒前就结束了；这段时间开场白本来就在播，挡掉它的代价几乎为零。外呼同样受益：
接通瞬间那声"喂？"不该把开场白打断。

排查这类问题，**录音比日志有用**：`recordings/<通话id>.wav` 左声道是来电方、右声道是 AI，
两边一对照，谁在什么时候出了声一目了然。

### 模拟线没有挂机信令（已加兜底，2026-09-21）

**现象**：第一通真实线路的电话一切正常，紧接着第二通"打不进 AI"。查日志发现第二通其实接通了，
客户说了句"喂喂"就挂了，可这通电话在服务器上又挂了 221 秒，最后一条日志是一次"客户插话"，之后一片空白。

**原因**：FXO 网关接的固话线、插卡盒这类模拟线**没有挂机信令**。客户挂断后，线上只是开始放忙音，
网关得靠"听忙音"来判断该不该拆线。这一步要在网关上单独开、参数还得对上当地制式，漏配是常态。
把那通电话的录音拿出来量过，卡住的三分多钟里线上是：**450Hz 纯音，响 380ms 停 320ms**，标准的国内忙音。

本进程这边，VAD 把循环的忙音当成"有人在不停说话"：忙音的间隔 320ms 够不上句尾静音要求的 800ms，
这一轮永远等不到"说完了"，一直挂到单通上限（300 秒）。期间线路被占着，后面的来电全进不来。

**网关上要配的**（HT813 的 FXO PORT 页最下面）：

| 字段 | 值 |
|---|---|
| Enable PSTN Disconnect Tone Detection | `Yes` |
| PSTN Disconnect Tone | `f1=450@-32,f2=450@-32,c=350/350;` |
| AC Termination Model → Country-based | `CHINA` 开头的那项 |

出厂填的是美国忙音（480+620Hz，500/500），国内线路上永远匹配不上。

**服务器上的两道兜底**（网关漏检时靠它，默认都开着）：

| 配置 | 默认 | 作用 |
|---|---|---|
| `VCA_TELEPHONY_TONE_HANGUP` | `true` | 听出线路信号音就挂机。纯音的过零间隔几乎恒定、人声忽长忽短，据此区分；最近 4 秒里响帧绝大多数是 380~520Hz 的纯音即判定。忙音、连续拨号音、700/700 的拥塞音都认得，回铃音（响 1 秒停 4 秒）凑不够帧数不会误判 |
| `VCA_TELEPHONY_NO_SPEECH_HANGUP_MS` | `20000` | 连续这么久"有人在说话"却一个字都没识别出来，判定为线路噪声并挂机。兜住 450Hz 之外的信号音、传真音、串线 |

拿线上两通真实录音验证过：101 秒的真人对话全程不误判；卡死的那通在忙音响起后约 3 秒被认出来
（实际它挂了 221 秒）。挂机原因在日志里是 `line-tone` 或 `no-speech`，看到它们就说明网关那边的
忙音检测没生效，该回头去配网关——兜底能止损，但每通电话白占几秒线路，根治还得靠网关。

### 窄带线路要用窄带模型（已修，2026-09-19）

**现象**：电话里问"洗牙多少钱"，识别出来是"抵押多少钱""拿多少钱""压多少钱""喜达多少钱"。
注意"多少钱"三个字每次都对，**错的永远是"洗牙"**。

**原因**：声母 x 是高频摩擦音，能量集中在 4kHz 以上。电话线是 8kHz 采样、G.711 编码，
3.4kHz 以上的内容根本没传过来。这不是听错，是那段声音不存在。

**放大问题的是采样率与模型不匹配。** VAD 要 16kHz（Silero 的硬要求），所以上行音频被重采样到 16kHz，
然后连同 16kHz 一起送进了宽带模型 `paraformer-realtime-v2`。上采样补不回丢掉的高频，
等于让一个"见过高频"的模型在一片空白上猜。

**改法**：检测采样率和识别采样率拆成两个配置项。检测器仍跑 16kHz，交给识别的是线路原生的 8kHz，
中间一次重采样都不做；模型换成阿里云的电话专用模型 `paraformer-realtime-8k-v2`，
它的训练数据全是这种窄带音频。浏览器那条链路不受影响，仍是 16kHz + 宽带模型。

```bash
# /etc/vca.env, 留空则退回全局的宽带模型
VCA_TELEPHONY_ASR_MODEL=paraformer-realtime-8k-v2
```

**先量再改。** 判断是不是这个问题，看日志里 `阿里云 ASR 开始, model=..., sr=...` 那行：
线路是 8000Hz 而这里是 16000，就是它。

**但换模型只解决一半。** 同一段 8kHz 音频离线比过三个模型，全都栽在"洗牙"上：

| 模型 | 「洗牙多少钱」 | 「种植牙多少钱」 |
|---|---|---|
| paraformer-realtime-v2 | 挤压多少钱 | 种肥牙多少钱 |
| paraformer-realtime-8k-v2 | 抵押多少钱 | 中岁牙多少钱 |
| paraformer-realtime-8k-v1 | 抵押多少钱 | 种植牙多少钱 |

**真正管用的是热词表。** 同一段音频、同一个 8k 模型，只加热词：

| | 「洗牙多少钱」 | 「种植牙多少钱」 |
|---|---|---|
| 不带热词 | 抵押多少钱 | 中岁牙多少钱 |
| 带热词 | **洗牙多少钱** | **种植牙多少钱** |

道理很直白：声母 x 的高频能量在电话线上本来就没传过来，换哪个模型都猜不回来；
热词把这些词的先验拉高，模型才能从一堆同样"像"的候选里选对。窄带线路上这是提准的主要手段，
不是锦上添花。

热词表要先在厂商那边注册，**并与目标模型绑定**，换模型就得重建：

```bash
curl -X POST https://dashscope.aliyuncs.com/api/v1/services/audio/asr/customization \
  -H "Authorization: bearer $DASHSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -d '{"model":"speech-biasing","input":{"action":"create_vocabulary",
       "target_model":"paraformer-realtime-8k-v2","prefix":"vca",
       "vocabulary":[{"text":"洗牙","weight":4,"lang":"zh"},
                     {"text":"种植牙","weight":4,"lang":"zh"}]}}'
```

> 2026-09-23 起热词表按行业自动维护（见 §12.1），库里登记了行业的门店不用再手工建表；下面的手工步骤
> 只对"配置文件里的商家 / 默认商家"仍然需要——它们没有行业，用的是全局那张表。

拿到 `vocabulary_id` 后配进去，改词用 `update_vocabulary`：

```bash
# /etc/vca.env
VCA_TELEPHONY_ASR_VOCABULARY_ID=vocab-xxxx
```

词表里该放什么：每家商家的项目名（洗牙、种植牙、正畸、根管治疗、窝沟封闭）、商家名、
以及客服流程词（转人工、预约、回电）。多商家可以共用一张表，热词只是加权，不会互相排斥。

**测试音频要用真人声。** 我用 macOS `say` 合成的语音在 16kHz 原始音质下同样识别错
（洗牙→洗了，种植牙→用嘴牙），拿它衡量不出改动的效果。评估识别准确率必须用真实通话录音，
`recordings/` 下每通电话都留了双声道 wav，左声道就是来电方说的。

### 认证后的 INVITE 太大（走公网才会遇到）

**现象**：软电话注册得好好的，一拨号就 `408 Request Timeout`。服务器日志里只有一条
`receiving invite`，十秒后 `Abandoned` + `WRONG_CALL_STATE`，VCA 那边什么都没有。

**原因不是配置，是报文长度。** 开着 `auth-calls` 时一次呼叫有两个 INVITE：第一个被 `407` 挑战，
第二个带上 `Proxy-Authorization` 重发。第二个比第一个大两百来字节，而这恰好是压垮骆驼的最后一根稻草：

| | 字节 |
|---|---|
| 第一个 INVITE（11 个编码的 SDP） | 1269 |
| 认证后的 INVITE | 1459（域名短时）～ 1480（域名是 IP 时） |
| 加 IP + UDP 头 | ＋28 |
| 以太网 MTU | 1500 |
| 家用宽带 PPPoE 的 MTU | 1492 |

超了就得 IP 分片，而分片的 UDP 在 NAT 和防火墙上被丢掉是家常便饭。于是服务器只收到第一个 INVITE，
为它建了通道，然后等第二个等到超时——`Abandoned` 说的就是"通道建好了但没人来接手"。

本机联调碰不到这个，回环和 Docker 网桥的 MTU 是 65536 和 1500，怎么发都通。**它只在走公网时才出现。**

**两个办法，都要做：**

1. **把编码列表砍短。** 少一个编码就少几十字节。HT813 本来就该只留 PCMA（§7.4 那张表里有），
   实测只留一个编码后同一通电话立刻打通。软电话联调时用 `pjsua --dis-codec speex --dis-codec ilbc ...`。
2. **防火墙放行 5060/TCP。** RFC 3261 规定报文接近 MTU 时客户端应自己改用 TCP，
   FreeSWITCH 这边 TCP 一直在监听（`sofia status profile internal` 的 `BIND-URL` 里有 `transport=udp,tcp`），
   缺的只是防火墙那条规则。放行之后客户端就有退路，不必指望每个设备的编码列表都够短。

**怎么确认是这个问题**：服务器上开 `./trunk-status.sh --trace`，看 `docker logs -f vca-freeswitch` 里
`recv NNN bytes` 的数字。只看到一条 INVITE、没有第二条，且第一条接近 1300 字节，就是它。

### 熔断锁死（已修，2026-09-18）

熔断器只在流**正常结束**时记成功，而识别的消费方拿到 final 就取走并**取消**整条流，永远走不到正常结束。
于是一阵真故障把熔断打开之后就再也关不回去：每次半开试探都因"被取消"而无人报回，状态长期停在半开，
每 10 秒只放行一次，其余回合全被跳过，最后报"所有候选厂商均不可用"。识别只有阿里云一家候选，没有故障
转移可言，电话那头就是一片安静。现在"吐过元素后被取消"也算成功（`GovernanceExecutor`）。

判断方法：日志里**识别成功**（`ASR final: …`）和**熔断打开**交替出现，就是这个问题；真的厂商挂了不会有成功。

### 系统代理（已修，2026-09-18）

macOS 的 JVM **启动时就会把操作系统的网络代理读成系统属性**，不需要任何 `-D` 参数。本机开着 Clash 这类
工具的"系统代理"时，阿里云识别/合成的 WebSocket 也被送进代理，代理一关（或重启、切节点）整条语音链路
立刻全挂，而报错信息里完全看不出跟代理有关。

现在启动时会让阿里云域名直连，并打一行 `检测到系统代理 …`。其余境外厂商照旧走代理。
要让阿里云也走代理：`-Dvca.proxy.aliyun=true`。自查：

```bash
networksetup -getwebproxy Wi-Fi                     # 系统代理开着吗
java -XshowSettings:properties -version 2>&1 | grep proxyHost   # JVM 看到的代理
```

### 回合失败时的兜底

任何一轮彻底失败（厂商全熔断、密钥过期、网络抖动）都会播一句预合成的兜底话术，默认
"不好意思，我这边没太听清，您再说一遍好吗？"，用 `vca.telephony.error-prompt` 改，留空则维持静默。
已经播出一部分再出错的不补，免得话说到一半插进来一句道歉。

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
| 真实线路 | 服务器上的 FreeSWITCH 已部署运行（§7.2）；还差安全组、商家配置、HT813 三步，且没有线路可联调 |
| 多商家开通 | 门店资料在库里（§12.1），网关分机用 `add-gateway.sh` 热加载（§7.4），开一家店不改配置、不重启 |
| 并发路数 | RTP 端口 16384-16402（安全组也只开了这段），约 10 路；转人工一通占两路。超过十来家店要一起放宽 |
| AI 故障兜底 | VCA 接不了时来电转前台座机（§7.4）；网关掉线/服务异常推群、每日备份（`deploy/ops/`） |
| 电话里的知识库检索 | 已完成（§9），按商家隔离 |
| 按键进对话（例如按键输入手机号） | 事件已到 `CallSession`，只打日志 |
| 留资 / 转人工 / 主动挂机 | 已完成（§10）。转人工 2026-09-24 改为先呼坐席再接通 |
| 媒体中途断流 | 同一次停泊内重建不了（§10），只能三次后挂断；靠网关打包时长固定 20ms 避免 |
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
