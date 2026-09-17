# 11 · 声音复刻（用自己的声音说话 + 方言）

用户录 10~20 秒自己的声音，换回一个专属音色，之后三段式对话就用它说话；还能让它改说粤语、四川话等 12 种方言。

| 能力 | 谁做的 | 入口 |
|------|--------|------|
| **音色复刻** | 阿里云 `qwen-audio-3.0-tts-flash`（免费创建，音色长期有效） | 设置面板「我的音色」→ 录音 |
| **方言输出** | 同一个合成模型的**指令控制**（`TtsConfig.instruction`） | 设置面板「口音 / 方言」下拉 |
| **归属与配额** | 本项目（厂商不管这一层） | 自动 |

> **真正的克隆是厂商模型做的，本项目实现的是它周围那一整套管道。** 调用厂商克隆能力的只有
> `VoiceCloneService.create` 里那十几行；其余全是采集、校验、配额、归属、模型路由——厂商一概不管，
> 但少任何一样都会出事。

## 1. 整条链路

```
前端 🎙 录 15 秒(关闭 AEC/降噪/AGC) → 线性重采样到 16kHz → 手写 44 字节 WAV 头
  → POST /api/voices  multipart(file, name, consent)
  → VoiceCloneRoute.doCreate    四道关卡, 顺序即设计:
      ① validate(wav)              格式/时长/大小 —— 不合规不花厂商调用
      ② store.countByUser          总量配额(默认 3/人)
      ③ store.countCreatedSince    频次配额(默认 5/天)
      ④ cloner.create(prefix, wav) ← 唯一真正"克隆"的一步
  → VoiceCloneService: WAV → data:audio/wav;base64,... → VoiceEnrollmentService.createVoice
  → 返回 voiceId = qwen-audio-3.0-tts-flash-u1a-<32位hex>
  → store.save(Clone{voiceId, userId, targetModel, ...})   落库: 归属 + 配额 + 过期提醒

之后每次合成:
  前端选音色 → WS {"type":"voice", value, dialect}
    → VoiceWebSocketHandler.onVoice   复刻音色必须验归属
    → ConversationSession.selectVoice(vendor, voice, instruction)
    → AliyunTtsProvider: model = props.modelFor(voice)   ← 由音色反推模型
```

## 2. 前端采集：为什么要自己编 WAV

`recordSample`（`index.html`）做三件浏览器默认不会替你做的事：

- **关掉 `echoCancellation` / `noiseSuppression` / `autoGainControl`**。这三件套会改变音色本身，
  而复刻要的恰恰是原始音色——开着它们等于先把声音"修"一遍再去复刻。注意这与麦克风对话链路相反
  （那边是开着的，见 `openMicStream`）。
- **自己编码 WAV**。浏览器的 `MediaRecorder` 只产出 webm/opus，厂商不收。于是采浮点帧 →
  线性插值重采样到 16kHz → 转 16bit 整数 → 手写 44 字节 RIFF 头。15 秒的量级下线性插值足够，
  不值得为它引一个重采样库。
- **可取消**。倒计时切成 100ms 一小步并检查取消标志，否则点了取消最多还要再等一秒才停；
  资源释放放在 `finally`，取消路径同样要停音轨、断节点、关 `AudioContext`，
  否则浏览器标签页会一直亮着录音指示。

另有一段**朗读文本**（`CLONE_SCRIPT`）先于计时显示。不给文本人容易卡壳、说半句停半句，
而复刻恰恰要「连续清晰的朗读」（厂商要求至少 5 秒连续人声），断断续续会直接拉低相似度。

## 3. 后端校验：为什么要在本地拦一遍

`VoiceCloneRoute.validate` 检查：未压缩 WAV(PCM) / 16bit / ≥16kHz / 8~60 秒 / ≤10MB。
解析用 `WavAudio.parse`（逐块扫 `fmt `与`data`，跳过某些录音器插入的 LIST/fact 块）。

这层存在的唯一理由是**给出人话报错**：厂商失败只回一句 `detect audio failed`，用户看了不知道该改什么；
顺带也避免为一段注定失败的样本白花一次厂商调用。

## 4. 克隆调用：样本不落对象存储（实测）

```java
String uri = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav);
Voice voice = service().createVoice(model, sanitize(prefix), uri);
```

官方文档只演示公网 URL，但 **实测 Qwen-Audio 与 CosyVoice 的 `create_voice` 都接受内联 base64**：
喂一段坏数据时报的是 `Audio.DecoderError / detect audio failed`（已在解码内联内容），
而给不存在的 URL 报的是 `BadRequest.InputDownloadFailed / download audio failed`。
**据此省掉了一整套 OSS 依赖**——浏览器录完直接转手给厂商。

`prefix` 用 `"u" + userId 的 36 进制`：厂商要求前缀仅小写字母数字且 < 10 字符（`sanitize` 负责兜底）。

## 5. 模型路由：最容易踩的一处

厂商要求**合成时的模型必须与创建音色时的 `target_model` 完全一致**，配错直接
`InvalidParameter / Engine return error code: 418`。而音色是前端**逐句选**的、模型却是**进程级配置**，
所以只能由音色反推模型——`AliyunTtsProperties.modelFor`：

1. 命中 Qwen-Audio-3.0 的 12 个 flash 系统音色 → flash 模型；
2. 命中 2 个 plus 系统音色 → plus 模型；
3. **复刻音色按前缀匹配**：id 以创建时的 target_model 打头（实测格式
   `qwen-audio-3.0-tts-flash-u1a-9528a83e439a428eb1b202e307f1eb24`），拿配置里的模型名当前缀比即可；
4. 都不命中 → 退回 `model`（CosyVoice），保持升级前行为。

> **比前缀要先比长的**（`longestFirst`）。模型名换成快照版（`…-flash-2026-07-20`）后，
> 短名就成了长名的前缀，不先比长的会把快照版的复刻音色判给短名。

## 6. 归属校验：厂商不管，只能自己把关

厂商的音色表是**账号级**的、不区分用户，而 `voiceId` 在前端是**明文**。不落库就拦不住
A 猜到 B 的 id 拿来合成。两条路都要验：

- REST（试听/删除）：`store.find(voiceId).filter(c -> c.userId() == uid)`；
- WebSocket 切音色：`VoiceWebSocketHandler.onVoice` → `ownsVoice`，不过则回
  `{"type":"voice_rejected"}`，前端退回默认音色。

> **踩过的坑**：试听接口原本写成 `blocking(() -> ...orElse(null))`。
> `Mono.fromCallable(() -> null)` 得到的是**空 Mono**，后面的 `flatMap` 整个不执行，
> 处理器一个 `ServerResponse` 都不产出，框架兜底回 **200**——归属校验就这么被绕过去了，
> 任何登录用户都能试听别人的音色。改用 `Optional` 贯穿链路。
> 这个分支单测覆盖不到，是 `VoiceCloneRouteWebTest` 用真实请求打路由才逼出来的。

## 7. 方言：靠指令，不靠原音口音

复刻只负责**音色**；说方言是合成时另加一句自然语言指令（`TtsConfig.instruction`，如「请用陕西话表达。」）。
即：**复刻一个说陕西话的人，不等于这个音色天生就说陕西话**。

- 支持 12 种方言，复刻音色与 Qwen-Audio-3.0 系统音色都吃；CosyVoice 老音色不吃，
  前端 `syncDialectVisibility` 会把方言选择器自动隐藏，免得用户以为坏了。
- **指令走后端白名单**（`VoiceCloneRoute.dialectInstruction`），前端只能传方言名。
  instruction 是原样送进合成模型的，放开等于开了个往模型里塞任意话术的口子。
- 跨厂商故障转移时**丢掉 instruction**（`ManagedProviders`）：参数名各家不同
  （CosyVoice 是 `instruction`、Qwen-TTS 是 `instructions`），原样转发只会让整句合成失败。

> ⚠️ **方言"说得出"不等于"听得懂"**：识别侧（ASR）是另一条链路，指定不了具体方言，
> 只能把语种钉死成中文（`vca.web.asr-language`，转成 `language_hints` 下发），
> 由模型自己从声学特征判断是哪种口音。

## 8. 配置与限制

| 配置 | 默认 | 说明 |
|------|------|------|
| `vca.web.voice-clone.enabled` | `true` | 缺复刻能力/归属存储/登录校验中任何一个，整条路由不注册，只打一行 warn |
| `vca.web.voice-clone.max-per-user` | `3` | 厂商账号总上限 1000 个，不按用户分配额会被单个用户占满 |
| `vca.web.voice-clone.create-per-day` | `5` | 含失败重试 |

厂商侧限制：创建**免费**；每账号每模型族最多 1000 个音色；**过去 1 年未用于任何合成的音色会被自动删除**
（`user_voice_clone.last_used_at` 就是为提前提醒留的）。

**合规**：上传时必须勾选「这是本人声音」（`consent`，后端强校验），落库记 `rights_confirmed`。

## 9. 接口

```
POST   /api/voices               multipart(file, name, consent) → {voiceId,name,seconds,status,createdAt}
GET    /api/voices                                              → {voices:[...], quota:{used,max}}
POST   /api/voices/{id}/preview  ?dialect=粤语                   → audio/wav 试听
DELETE /api/voices/{id}                                         → {ok:true}
```

鉴权同 `KnowledgeRoutes`：`Authorization: Bearer <token>` → userId，缺失/无效 401；所有操作按 userId 隔离。

## 10. 关键文件索引

| 文件 | 职责 |
|------|------|
| `static/index.html` | 录音（可取消）、编 WAV、音色列表、方言联动 |
| `web/voice/VoiceCloneRoute.java` | REST、样本校验、配额、归属、方言白名单 |
| `web/ws/VoiceWebSocketHandler.java` | 切音色时的归属校验（`onVoice` / `ownsVoice`） |
| `provider/tts/aliyun/VoiceCloneService.java` | 包 DashScope `VoiceEnrollmentService`（唯一真正克隆的一步） |
| `provider/tts/aliyun/AliyunTtsProperties.java` | `modelFor` 音色→模型路由、`supportsInstruction` |
| `domain/model/WavAudio.java` | WAV 头解析与封装（不引 javax.sound） |
| `domain/spi/VoiceCloner.java` / `VoiceCloneStore.java` | 复刻能力与归属存储的 SPI |
| `store/voice/MyBatisVoiceCloneStore.java` + `schema.sql` | `user_voice_clone` 表 |

测试：`WavAudioTest`（WAV 解析）、`AliyunTtsPropertiesTest`（模型路由，含快照版前缀）、
`VoiceCloneRouteTest`（方言白名单）、`VoiceCloneRouteWebTest`（真实请求打路由：未登录/未勾同意/样本不合规/配额/**拿不到别人的音色**）。
