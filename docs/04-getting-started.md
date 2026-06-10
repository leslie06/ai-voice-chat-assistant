# 04 · 快速开始（浏览器使用）

## 1. 环境要求

- **JDK 17+**（项目用 Java 17 编译）
- **Maven**：直接用仓库自带的 `./mvnw`（Maven Wrapper），无需本机装 Maven
- 现代浏览器（Chrome / Edge / Safari 等，需支持 Web Audio + WebSocket）
- 真实语音模式还需：阿里云 DashScope API Key、DeepSeek API Key（纯桩模式不需要）

## 2. 构建

```bash
cd ai-voice-chat-assistant

# 跑测试(可选)
./mvnw -pl vca-orchestrator,vca-web -am test -Dsurefire.failIfNoSpecifiedTests=false

# 打可执行 jar（产物在 vca-bootstrap/target/）
./mvnw -pl vca-bootstrap -am package -DskipTests
```

产物：`vca-bootstrap/target/vca-bootstrap-0.0.1-SNAPSHOT.jar`

## 3. 启动

项目有两个 Profile：

| Profile | 说明 | 外部依赖 |
|---------|------|---------|
| `default` | 纯开发桩（ASR/LLM/TTS 都是桩）| **无**，零配置即跑通全链路 |
| `real`（默认激活）| 真实阿里云 ASR/TTS；LLM 默认仍是 echo 桩，可切真实 DeepSeek | 需 DashScope Key（+ 可选 DeepSeek Key）|

### 方式 A：纯桩模式（推荐先用这个验证链路）

```bash
SPRING_PROFILES_ACTIVE=default DEEPSEEK_ENABLED=false \
  java -jar vca-bootstrap/target/vca-bootstrap-0.0.1-SNAPSHOT.jar
```
- 文本输入会得到"回声"回复并合成出（桩）音频块；
- 不需要任何 Key 和网络。

### 方式 B：真实语音模式

```bash
SPRING_PROFILES_ACTIVE=real \
DASHSCOPE_API_KEY=sk-你的阿里云key \
DEEPSEEK_ENABLED=true DEEPSEEK_API_KEY=sk-你的deepseek-key \
  java -jar vca-bootstrap/target/vca-bootstrap-0.0.1-SNAPSHOT.jar
```
- `real` profile 已自动 `stub-asr=false / stub-tts=false`、启用真实阿里云 ASR/TTS；
- 想让大模型真正走 DeepSeek，需带上 `DEEPSEEK_ENABLED=true` + key（否则用 echo 桩当大脑）；
- 本机若开了代理（Clash 等）导致 DeepSeek 直连 TLS 超时，`real` 默认走 `http://127.0.0.1:7890`，可用 `DEEPSEEK_PROXY=` 置空改直连。

### 改端口

```bash
java -jar ...jar --server.port=8099
```

启动成功日志：`Started Application in X seconds`。默认监听 **8080**。

## 4. 用浏览器打开

1. 浏览器访问 **http://localhost:8080**（改了端口就用对应端口）。
   - 静态页面 `index.html` 由后端直接托管，**改了后端要重新 `package` 并重启**，并在浏览器 **强制刷新（Cmd/Ctrl+Shift+R）** 避免缓存旧页面（页面右上角徽标应为 `界面 v4`）。
2. 操作步骤（页面顶部也有提示）：

   | 按钮 | 作用 |
   |------|------|
   | 🔊 **开启声音** | 创建/恢复 AudioContext（浏览器要求必须由用户手势触发）|
   | ▶ **测试音(1秒)** | 播放 440Hz 测试音，确认"能出声"（与后端无关）|
   | 💬 **免提对话** | 开启后持续聆听：说完停顿自动提交；机器人说话时直接开口即可打断；再点退出 |
   | 🎙️ **按住说话** | 按住录音、松开提交（PTT）|
   | 文本框 + **发送** | 打字发送（回车也行），绕过 ASR 直接进大模型 |
   | **打断** | 手动打断当前回复 |

3. **诊断面板**（页面顶部黑底绿字）实时显示：WS 连接状态、后端推送的状态、AudioContext/采样率、收到的音频帧/字节/已排播数、最近事件——排查问题时先看它。

## 5. 麦克风权限与 HTTPS 注意事项

- 浏览器只在**安全上下文**才允许 `getUserMedia` 访问麦克风：
  - `http://localhost`（本机）**例外允许**；
  - 但通过 IP / 域名远程访问时，**必须 HTTPS**，否则麦克风按钮会报权限错误。
- 远程部署请在反向代理上配 TLS（见 [部署指南](./05-deployment.md)），用 `wss://` 自动随页面协议切换（前端已按 `location.protocol` 自动选择 ws/wss）。

## 6. 常见问题速查

| 现象 | 可能原因 | 处理 |
|------|---------|------|
| 点了按钮没声音 | 没先点「开启声音」/ 系统静音 | 先点开启声音，再点测试音确认 |
| 文本不进大模型 | 后端是旧版没重启 | 重新 `package` + 重启 + 强刷浏览器 |
| 打断没反应 | ① 后端没重启 ② 语音 RMS 没过阈值 | 看日志有无「— 已打断 —」；调低 `vca.web.vad.barge-threshold` |
| 麦克风报错 | 非 localhost 且非 HTTPS | 用 localhost 或配 TLS |
| 远程连不上 WS | 反代未开启 WebSocket 升级 | 见部署指南的 Nginx 配置 |
