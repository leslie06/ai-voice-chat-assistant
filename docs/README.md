# VCA 实时语音对话助手 · 文档中心

VCA（Voice Chat Assistant）是一个**实时、可打断**的语音对话系统：用户说话 → 流式识别（ASR）→ 大模型生成（LLM）→ 流式合成（TTS）→ 边合成边播放，全链路句子级流水线，机器人说话时用户随时可以开口打断。

后端是 **Java 17 + Spring Boot 4 + WebFlux（响应式）** 的模块化单体；前端是一个零依赖的"瘦客户端"网页，只负责采集麦克风、播放音频、连 WebSocket，**所有对话逻辑（断句、打断、状态机）都在后端**。

## 文档导航

| 文档 | 内容 |
|------|------|
| [01 · 系统架构](./01-architecture.md) | 架构图、模块划分、依赖方向、一轮对话的数据流 |
| [02 · 主要技术实现](./02-tech-implementation.md) | 响应式流水线、后端 VAD 与打断（epoch 门闸）、治理层、Provider SPI |
| [03 · WebSocket 协议](./03-websocket-protocol.md) | 前后端消息协议（控制消息 / 音频帧 / 事件） |
| [04 · 快速开始（浏览器使用）](./04-getting-started.md) | 本地构建、启动、用浏览器打开与操作 |
| [05 · 部署指南](./05-deployment.md) | 打包、配置项、Profile、反向代理、**云服务器一步步公网部署（手机可用）**、Docker、多副本、可观测 |
| [06 · 音乐播放原理](./06-music-playback.md) | **大模型是怎么"放歌"的**：意图识别（关键词 / function-calling）、音源 SPI（本地整首 / iTunes 试听 / 为何 QQ 不行）、前端双音轨播放 |
| [07 · 端到端 S2S 全双工](./07-s2s-fullduplex.md) | **持久 S2S 全双工**：前端入口→后端调用链（激活/上行/下行/打断四条链）、服务端 VAD、新 SPI（`S2sSession`/`S2sEvent`）、与每轮 S2S 的区别、关键文件索引 |

## 一句话技术栈

```
Java 17 · Spring Boot 4.0.6 · Spring WebFlux(Reactor Netty) · Project Reactor
阿里云 DashScope SDK 2.22.18 (ASR=paraformer / TTS=CosyVoice) · DeepSeek(OpenAI 兼容 SSE)
Web Audio API + WebSocket (前端纯原生 JS, 无框架)
```

## 30 秒跑起来（纯桩模式，零外部依赖）

```bash
cd ai-voice-chat-assistant
SPRING_PROFILES_ACTIVE=default ./mvnw -pl vca-bootstrap -am spring-boot:run \
  -Dspring-boot.run.mainClass=com.vca.bootstrap.Application
# 或先打包再跑:
./mvnw -pl vca-bootstrap -am package -DskipTests
SPRING_PROFILES_ACTIVE=default DEEPSEEK_ENABLED=false \
  java -jar vca-bootstrap/target/vca-bootstrap-0.0.1-SNAPSHOT.jar
```

浏览器打开 **http://localhost:8080** ，点「开启声音」→「免提对话」即可。详见 [快速开始](./04-getting-started.md)。
