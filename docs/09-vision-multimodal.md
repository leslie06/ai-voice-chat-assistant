# 09 · 视觉多模态（发图提问 + 摄像头边看边聊）

对标一线语音产品的"边看边聊"（Gemini Live / ChatGPT 语音+视觉）。两条能力，共用一套前端采集：

| 能力 | 路径 | 模型 | 入口 |
|------|------|------|------|
| **发图提问** | 打字 / 三段式语音回合 | 视觉 LLM（默认 `qwen-vl-plus`，可配） | 📷 按钮选图/拍照 |
| **边看边聊** | 持久 S2S 全双工 | Qwen-Omni（原生多模态） | 📹 按钮开摄像头（1 fps 连续帧） |
| **看一眼再答** | 打字 / 三段式语音回合 | 视觉 LLM | 📹 摄像头开着提问，自动抓一帧随本句发出 |

📹 摄像头在三种模式下都能用：端到端免提=连续传帧真"边看边聊"；三段式/打字=每次提问自动带上当前画面（开口/发送瞬间抓帧）。

## 1. 发图提问（LLM 回合）

```
前端 📷 → canvas 压缩(长边≤1280 JPEG) → WS {"type":"image",data,mime}
  → VoiceWebSocketHandler.onImage → conversation.attachImage(dataURL 暂存)
  → 用户下一句(打字或三段式语音) → ConversationSession.respond
      ├ pendingImage.getAndSet(null) → Message.user(text, imageUrl) 入历史
      ├ working 列表含图 → 本轮自动切视觉模型(llmConfigFor)
      └ OpenAiCompatibleLlmProvider.messageOf: content 变数组 [{image_url},{text}]
```

设计要点：

- **一图一轮附加，随历史滑窗留存**：图片挂在那条 user 消息上进入历史；只要窗口内还有图，后续追问（"图里第二个人是谁"）自动继续用视觉模型；图随滑窗滑出后回到普通文本模型。
- **模型路由**：`vca.web.vision-vendor/vision-model`（`VCA_VISION_MODEL`，默认 `qwen-vl-plus`，走 DashScope qwen 客户端复用 `DASHSCOPE_API_KEY`）。留空 = 不切换（要求当前对话模型自身支持视觉）。
- **带图回合不走多步 Agent**（规划/反思用常规模型读不了图）；点歌正则快路径不吃图。
- 大小护栏：后端拒收 base64 > 8M 字符；前端压缩后一般 100–300KB。
- **WS 帧上限**：Reactor Netty 默认单帧 64KB，大图直接被服务端回 1009 掐断（表现为"小图能发、大图静默失败"）。修复在 `WebAutoConfiguration.webSocketFrameSizeConfigurer`：**Spring 7 下自定义 `WebSocketHandlerAdapter` @Bean 无效**（`WebFluxConfigurationSupport` 自带 `webFluxWebSocketHandlerAdapter`，自定义的不被使用），必须走 `WebFluxConfigurer.getWebSocketService()` 返回带 `WebsocketServerSpec.maxFramePayloadLength(12MB)` 的 `HandshakeWebSocketService`（帧上限经 Builder Supplier 构造注入，旧 setter 已移除）。已用 WS 客户端实测 400KB 帧通过。
- 图片消息不进 localStorage 会话历史（base64 太大），刷新后不恢复、也不回灌后端。

## 2. 边看边聊（持久 S2S）

```
前端 📹 → getUserMedia(video) → 1 fps canvas 抓帧(长边≤640 JPEG)
  → WS {"type":"video_frame",data} → live.pushVideoFrame(b64)
  → QwenOmniSession.pushVideoFrame → SDK appendVideo → input_image_buffer.append
  → 服务端 VAD 判停后, Omni 结合最近画面 + 语音一起作答
```

- 仅 **mode=s2s + s2s-persistent + 免提** 生效；其它状态前端不传帧、后端无长连也直接丢弃。
- 帧是即时性的：连接未就绪直接丢弃（不像音频那样缓冲补发，过期帧无用）。
- 持久 S2S 长连在跑时用户发的单张图片（📷）也直推为一帧，**不**暂存给 LLM 回合——避免过期图片在之后某个打字回合意外复活。

## 3. SPI 变更

- `Message` 加 `imageUrl` 组件 + `Message.user(text, imageUrl)` / `hasImage()`。
- `S2sSession.pushVideoFrame(String jpegBase64)` default 空实现；`S2sLiveSession` 透传。
- WS 协议新增上行：`{"type":"image","data":b64,"mime":"image/jpeg"}`、`{"type":"video_frame","data":b64}`。

## 4. 配置与验证

```yaml
vca:
  web:
    vision-vendor: qwen
    vision-model: ${VCA_VISION_MODEL:qwen-vl-plus}
```

真机验证清单：

1. 打字发图：📷 选图 → 打"图里有什么" → 日志应见 `带图回合: 改用视觉模型`；
2. 三段式语音发图：📷 选图 → 免提说"这张图里有什么"；
3. 边看边聊：设置切端到端 + 免提 + 📹 → 对着镜头问"你看到了什么"→ 日志应见 `已开始上行视觉帧`。

单测：`VisionTurnTest`（编排：带图切模型/追问续用/一图一轮/未配置不切换）、
`OpenAiCompatibleLlmProviderTest.buildsMultimodalContentForImageMessage`（请求体多模态数组）。
