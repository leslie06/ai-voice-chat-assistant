# 03 · WebSocket 协议

端点：`ws(s)://<host>/ws/voice`（路径可由 `vca.web.path` 配置）。
一条连接 = 一路会话（多回合）。设计原则：**浏览器是哑客户端，持续上传原始 PCM，所有判定在后端**。

```
binaryType = 'arraybuffer'
```

---

## 1. 客户端 → 服务端

### 1.1 二进制帧：原始上行音频
- 内容：**小端 16bit 单声道 PCM**，采样率 = 由 `mic` 控制消息声明的值（通常是浏览器 `AudioContext.sampleRate`，如 48000）。
- 何时发：进入采集模式（免提 / 按住说话）后**持续**发送每一帧；后端负责重采样到 16k、算电平、跑 VAD。

### 1.2 文本帧（JSON 控制消息）

| 消息 | 含义 | 后端动作 |
|------|------|---------|
| `{"type":"mic","sampleRate":48000}` | 声明后续二进制帧的采样率 | 记录采样率 |
| `{"type":"mode","value":"handsfree"}` | 开启免提 | 启动后端 VAD；若正在播音则先打断 |
| `{"type":"mode","value":"idle"}` | 退出免提 | 停止 VAD |
| `{"type":"ptt","value":"start"}` | 按住说话·按下 | 立即开启一轮，后续帧直接喂入 |
| `{"type":"ptt","value":"stop"}` | 按住说话·松开 | 提交本轮 |
| `{"type":"text","text":"你好"}` | 文本输入 | 直接作为一轮注入，**绕过 ASR** |
| `{"type":"barge_in"}` | 手动打断 | 取消当前回合，停播 |

---

## 2. 服务端 → 客户端

### 2.1 二进制帧：TTS 音频块
- 内容：**PCM 24kHz 单声道 16bit**，边合成边下发，前端排入 Web Audio 播放队列。

### 2.2 文本帧（JSON 事件）

| 事件 | 含义 | 前端典型处理 |
|------|------|-------------|
| `{"type":"asr","text":...}` | 本轮识别出的用户文本（字幕）| 显示；**开闸**允许播放本轮音频 |
| `{"type":"reply","text":...}` | 本轮 LLM 完整回复文本 | 显示 |
| `{"type":"chunk","text":...}` | 某音频块对应的文本（桩/字幕同步）| 显示 |
| `{"type":"turn_end"}` | 本轮正常结束 | 标记回合结束 |
| `{"type":"interrupted"}` | 已打断 | **停播 + 关闸**丢弃在途残留 |
| `{"type":"error","message":...}` | 出错 | 提示 + 停播 |
| `{"type":"state","value":...,"label":...}` | 后端状态推送 | 直接显示 `label`（界面提示文字也由后端决定）|

`state.value` 取值：`idle` / `await` / `speak` / `wait` / `ptt`，`label` 是配套的中文提示（如"免提·请说…""录音中…""处理中…"）。

---

## 3. 典型时序

### 免提一轮（含自动断句）
```
C→S  {mic,48000}
C→S  {mode,handsfree}
S→C  {state,await,"免提·请说…"}
C→S  (持续) PCM 二进制帧 ...
        后端VAD: 检测到开口
S→C  {state,speak,"录音中…"}
        后端VAD: 句尾静音 → 提交
S→C  {state,wait,"处理中…"}
S→C  {asr,"今天天气怎么样"}
S→C  二进制音频块 ... (边收边播)
S→C  {chunk,...} / {reply,...}
S→C  {turn_end}
S→C  {state,await,"免提·请说…"}
```

### 打断
```
（机器人正在说，二进制音频块持续下发）
C→S  {barge_in}            // 或后端VAD自动检测到插话
S→C  {interrupted}         // 前端立刻停播 + 关闸
        // 后端 epoch++，旧轮残留块一律不再下发
（新一轮开始后）
S→C  {asr,...}             // 前端据此开闸，恢复播放
S→C  二进制音频块 ...
```

> 设计要点：`asr` 既是字幕，也是前端"本轮音频可以播了"的开闸信号；`interrupted` 是"立刻停、关闸"。配合后端 epoch 门闸，打断后**确定性**无残留、无延迟回声。详见 [技术实现 §3](./02-tech-implementation.md#3-打断barge-in的实现)。
