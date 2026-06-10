# 06 · 音乐播放原理（大模型是怎么"放歌"的）

本文讲清一件容易被误解的事：**大模型本身并不会播放音乐**。它只能吐文本。所谓"让语音助手放首歌"，是把一个**动作**接到对话流程里完成的——本文拆解这条链路的每个环节、当前实现的取舍，以及如何升级。

---

## 0. 先纠正一个认知

LLM（大模型）的输出永远是**文本 token**，它不会、也不能直接产生音频流去"播放"。所以"放歌"从来不是大模型干的，而是三件事的配合：

```
① 意图识别   听懂"用户想听某首歌, 歌名是 X"      —— 可以用关键词, 也可以让大模型来判断
② 找到歌曲   把 X 解析成一个【可直接播放的音频地址】 —— 由音源(MusicProvider)负责
③ 前端播放   浏览器拿到地址用 <audio> 放出来        —— 与 TTS 语音是两条独立音轨
```

大模型最多只参与 **①**（判断这是不是点歌、歌名是什么）。**②③ 与大模型无关。** 本项目当前连 ① 都没用大模型，而是用更轻的**关键词路由**（见下）——大模型在点歌这一轮被**短路**掉了。

---

## 1. 整体数据流

```
用户(语音/文字)
   │  ASR final / 打字文本
   ▼
ConversationSession.respond() / respondTextOnly()
   │
   ├─ MusicIntent.parsePlay(text)  命中？
   │        │ 否 → 正常走 LLM → 分句 → TTS(原有链路)
   │        │ 是 ↓ 短路, 不进 LLM
   │        ▼
   │   musicTurn(query, speak)
   │        ├─ listener.onMusicRequest("play", query)   ← 把"动作"交给接入层
   │        └─ speak ? TTS念一句"好的,为你播放X" : 不发声
   ▼
VoiceWebSocketHandler.onMusicRequest()
   │   musicProvider.search(query)                       ← 调音源拿【可播放地址】
   │        本地曲库命中 → 整首; 否则回退 iTunes → 30秒试听
   ▼
   下发 {type:"music", action:"play", title, artist, url, full, ...}
   ▼
前端 index.html  addMusic()
   └─ <audio src=url autoplay>   ← 真正出声(与 TTS PCM 队列分开)
```

> 关键代码
> - 意图：`vca-orchestrator/.../skill/MusicIntent.java`
> - 编排：`vca-orchestrator/.../session/ConversationSession.java`（`respond` / `respondTextOnly` / `musicTurn`）
> - 回调契约：`vca-orchestrator/.../session/TurnListener.java`（`onMusicRequest`）
> - 接入层：`vca-web/.../ws/VoiceWebSocketHandler.java`（`onMusicRequest` → `musicPlayMessage`）
> - 音源 SPI：`vca-domain/.../spi/MusicProvider.java` + 模型 `MusicTrack`
> - 音源实现：`vca-web/.../music/LocalMusicProvider.java`、`ItunesMusicProvider.java`
> - 装配：`vca-web/.../WebAutoConfiguration.java`
> - 前端：`vca-bootstrap/src/main/resources/static/index.html`

---

## 2. 环节① 意图识别：当前用关键词，不用大模型

> 代码：`MusicIntent.java`，在 `ConversationSession.respond()/respondTextOnly()` 进 LLM **之前**调用。

```java
Optional<String> song = musicIntent.parsePlay(userText);
if (song.isPresent()) {
    return musicTurn(song.get(), /*speak=*/true);   // 短路, 不进 LLM
}
```

`parsePlay` 用正则匹配触发词后面的歌名，并清洗量词/语气词：

```
触发词: 播放|放一首|放首|来一首|来首|点歌|点一首|我想听|我要听
"放一首周杰伦的晴天" → 抽出 "周杰伦的晴天"
"我想听歌"           → 抽不出具体歌名 → 不当作点歌(回退给 LLM)
```

**为什么先用关键词而不是大模型？**

| | 关键词路由（当前） | 大模型 function-calling |
|---|---|---|
| 延迟 | 零额外延迟（本地正则） | 多一次模型调用/解析 |
| 确定性 | 高，命中即触发 | 由模型判断，偶有漏判 |
| 语义弹性 | 死板，要大致符合触发词 | 能懂"放首适合下雨天的歌" |
| 实现成本 | 低 | 需扩展流式 `tool_calls` 解析 |

MVP 选关键词：快、稳、好维护。

### 如果真要"让大模型自己决定播放"（升级路线）

这才是字面意义上"大模型播放音乐"的实现方式——**function calling / 工具调用**：

1. 给 LLM 注册一个工具 `play_music(song, artist)`（DeepSeek 兼容 OpenAI 的 `tools` 字段）；
2. 模型在对话中自行判断"该放歌了"，输出一个 `tool_call`，参数里带歌名；
3. 后端拦截这个 `tool_call`，**不把它当普通文本**，转而执行环节②③（与现在 `musicTurn` 之后完全一样）。

代价：现在的 `LlmProvider.chatStream` 只回**文本** token，要支持工具调用需扩展它去解析流式的 `tool_calls` 增量。**环节②③一行都不用改**——这正是下面"解耦"设计的价值。

---

## 3. 环节② 音源：把歌名变成"可播放地址"

> SPI：`MusicProvider.search(query) : Mono<MusicTrack>`（找不到返回空 `Mono`）。

```java
public record MusicTrack(String title, String artist, String playUrl,
                         String coverUrl, int durationSec, boolean full) {}
```

`playUrl` 必须是浏览器 `<audio>` 能直接放的地址（mp3/m4a…）。装配成**本地优先、在线兜底**：

```java
// WebAutoConfiguration
return query -> local.search(query).switchIfEmpty(itunes.search(query));
```

### 2.1 本地曲库 `LocalMusicProvider`（整首）

- 递归扫描配置目录（默认 `~/Music`，可用 `vca.web.music-dir` 改），按**文件名归一化匹配**（转小写、去"的"和符号，再比包含/字符重叠）；
- 匹配到的文件经一个**文件流路由**对外提供，前端用相对 URL 播放：

```java
// 把 /music/files/** 映射到曲库目录, 支持 Range 请求(可拖动进度、边下边播)
RouterFunctions.resources("/music/files/**", new FileSystemResource(musicDir));
```

- 文件系统扫描是**阻塞操作**，丢到 `Schedulers.boundedElastic()`，不占事件循环线程；
- 返回 `full=true`（整首）。合法、自用，不需要任何会员。

### 2.2 在线兜底 `ItunesMusicProvider`（30 秒试听）

- 调 iTunes Search API（**合法、免密钥、CORS 友好**），取首条结果的 `previewUrl`；
- 坑点：iTunes 返回 `Content-Type: text/javascript`，Jackson 解码器不认 → 故 `bodyToMono(String.class)` 取回文本再 `ObjectMapper.readTree` 自行解析；
- 返回 `full=false`（仅 30 秒预览）。

### 2.3 为什么没有"QQ 音乐整首"

QQ 音乐**没有面向第三方的合法播放 API/SDK**，会员权益只在其官方 App 内生效。因此：

- 自建助手里**无法合法**取到 QQ 整首音频流；
- 折中：在线试听卡片附一个「在 QQ 音乐听整首」按钮，跳转官方页面，让用户的 VIP 在**合法生效的地方**播放；
- 想在助手内直接放整首，只能用**有合法播放权的来源**：本地自有文件、或 Apple Music(MusicKit JS)/Spotify(Web Playback SDK) 这类提供第三方播放 SDK 的订阅服务。换来源时**只替换 `MusicProvider` 实现**，上层不动。

---

## 4. 环节③ 前端播放：与语音分两条音轨

> 代码：`index.html` 的 `addMusic()` 与 `ws.onmessage` 的 `case 'music'`。

- 收到 `{type:"music",action:"play",url,...}` → 创建一个独立的 `<audio>` 播放器自动播放；
- **音乐 ≠ TTS**：助手说话(TTS)走的是 24k PCM 块经 Web Audio 排播队列；音乐是 mp3/m4a 经 `<audio>`。两条音轨独立，互不串扰；
- 播音乐前先 `stopPlayback()` 停掉 TTS 残留，避免和确认语长时间重叠；
- 打断/出错时 `stopMusic()` 一并停掉音乐；
- 卡片按 `full` 显示「本地 · 完整播放」或「30秒试听」，仅试听片段才展示「在 QQ 音乐听整首」入口；
- 找不到歌曲时下发 `action:"notfound"`，前端提示"没找到《X》"。

---

## 5. 协议消息（服务端 → 客户端）

```jsonc
// 命中并解析到可播放曲目
{ "type":"music", "action":"play",
  "query":"光阴的故事", "title":"光阴的故事", "artist":"罗大佑",
  "url":"/music/files/...mp3", "cover":null, "duration":0, "full":true }

// 没找到
{ "type":"music", "action":"notfound", "query":"某首没有的歌" }
```

---

## 6. 三个值得记住的设计点

1. **编排层不感知厂商**：`ConversationSession` 只产出"用户想听 X"这个**意图**（`listener.onMusicRequest`），具体去哪个音源、拼什么 URL 由接入层决定。换音源不动编排。
2. **意图与音源解耦**：意图识别（环节①）和音源（环节②）各自独立。无论触发是关键词还是大模型 `tool_call`，后续完全复用；无论音源是本地、iTunes 还是未来的 Apple Music，前端完全复用。
3. **文字回合 vs 语音回合**：`musicTurn(query, speak)` 中 `speak` 区分——语音点歌用 TTS 念确认语，文字点歌不发声（卡片即反馈），与"文字进文字出/语音进语音出"的总原则一致。

---

## 7. 配置项

| 配置 | 默认 | 说明 |
|------|------|------|
| `vca.web.music-dir` | `${user.home}/Music` | 本地曲库目录，整首播放从这里按文件名匹配 |

**命名建议**：`歌手 - 歌名.mp3`（短横线两边带空格），卡片能正确拆出「歌手 / 歌名」。新加文件无需重启，每次点歌实时扫描。

> ⚠️ 安全：`/music/files/**` 会把曲库目录通过 HTTP 暴露（已有路径穿越防护）。本机自用无碍，公网部署时注意别开放整目录。

---

## 8. 局限与扩展

- **整首播放**仅限本地自有文件或有合法播放权的订阅服务；免费在线接口只给 30 秒试听。
- 想"让大模型自己决定放歌"——把环节①换成 **function calling**（见 §2 升级路线），②③不变。
- 想支持"暂停/下一首/音量"等——在 `MusicIntent` 加对应意图、协议加对应 `action`、前端控制 `<audio>` 即可（注意：跳转式来源如 QQ 无法被本助手控制）。
