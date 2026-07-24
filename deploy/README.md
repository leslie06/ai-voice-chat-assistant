# 公网部署（手机可实时语音）

> 硬性前提：**必须 HTTPS（wss）**，否则手机浏览器禁止访问麦克风。

## 路线 A · 隧道（最快，先用手机试）

本机跑 app（带真实 key），再用隧道暴露成 https：
```bash
cloudflared tunnel --url http://localhost:8080      # 或 ngrok http 8080
```
手机打开它给出的 `https://xxx.trycloudflare.com`。临时地址，适合演示/自用。

## 路线 B · 云服务器 + 域名 + 自动 HTTPS（长期）

### B1. 裸机 + systemd + Caddy
```bash
# 服务器上：装 JDK17 + Caddy；放行安全组 80/443
scp vca-bootstrap/target/vca-bootstrap-0.0.1-SNAPSHOT.jar  user@server:/opt/vca/app.jar
# 写 /etc/vca.env（见 vca.service 注释），然后：
sudo cp deploy/vca.service /etc/systemd/system/ && sudo systemctl enable --now vca
# 改 Caddyfile 的域名后：
caddy run --config deploy/Caddyfile        # 生产用 `caddy start` 或做成 systemd
```
手机打开 `https://你的域名`。

如需把用户/客服原始双轨及完整对话 WAV 直接保存到阿里云 OSS，在 `/etc/vca.env` 增加：
```bash
VCA_STORE_ENABLED=true
VCA_AUDIO_RECORDING_ENABLED=true
VCA_OSS_ENDPOINT=https://oss-cn-beijing.aliyuncs.com
VCA_OSS_BUCKET=你的私有Bucket
VCA_OSS_ACCESS_KEY_ID=RAM用户AccessKeyId
VCA_OSS_ACCESS_KEY_SECRET=RAM用户AccessKeySecret
VCA_OSS_PREFIX=recordings
```
应用使用内存缓冲和 OSS Multipart Upload，服务器不创建临时录音文件。Bucket 应设为私有，RAM 用户只授予
该 Bucket 前缀所需的 `oss:PutObject`、`oss:AbortMultipartUpload` 权限。MySQL 的
`conversation_recording` 表保存 Bucket、Object Key 和录音状态。
每段录音会生成 `user.wav`、`assistant.wav` 和可直接连续复听的 `conversation.wav`。

如需从 OSS 私有曲库播放整首音乐，把有合法使用权的音频上传到同一 Bucket 的 `music/`
前缀，并在 `/etc/vca.env` 增加：
```bash
VCA_MUSIC_OSS_ENABLED=true
VCA_MUSIC_OSS_ENDPOINT=https://oss-cn-beijing.aliyuncs.com
VCA_MUSIC_OSS_BUCKET=你的私有Bucket
VCA_MUSIC_OSS_ACCESS_KEY_ID=RAM用户AccessKeyId
VCA_MUSIC_OSS_ACCESS_KEY_SECRET=RAM用户AccessKeySecret
VCA_MUSIC_OSS_PREFIX=music
VCA_MUSIC_OSS_URL_MINUTES=120
```
音乐 endpoint 必须使用公网地址，不能使用 `-internal`，因为最终是用户手机浏览器直接从 OSS
播放。Bucket 可保持私有；RAM 用户需有 `oss:ListObjects` 和目标 `music/*` 的 `oss:GetObject`
权限。文件建议命名为 `歌手 - 歌名.mp3`。

### B2. Docker（一键，app + Caddy 一起拉起）
```bash
# 改好 Caddyfile 域名(并把 localhost:8080 改成 vca:8080)，建 deploy/.env 填 key：
docker compose -f deploy/docker-compose.yml up -d --build
```

## 手机端注意事项（重要）

| 事项 | 说明 |
|------|------|
| 先点「🔊 开启声音」 | iOS/安卓都要求 AudioContext 由用户手势启动，否则没声音/不录音 |
| 用 HTTPS 打开 | `http://IP` 一定无法用麦克风；必须 wss |
| 建议戴耳机 | 外放时麦克风会收到喇叭声，虽有回声消除，耳机体验更稳、打断更灵 |
| 别锁屏/切后台 | 手机锁屏或切到后台会暂停麦克风采集，回合会中断 |
| 允许麦克风权限 | 首次会弹权限，拒绝后需到浏览器站点设置里重新开启 |

## 安全与合规

- **Key 只放服务器**（环境变量），绝不写进前端页面。
- **不要把 8080 直接对公网开**，只让反代(443)对外，8080 留给本机/内网。
- 服务器在**中国大陆**时，域名走 80/443 通常需 **ICP 备案**；未备案可用境外服务器或隧道方案。
- 真实语音会产生**阿里云 ASR/TTS + DeepSeek 的调用费用**，公网暴露建议加访问控制（如 Basic Auth / 简单口令）避免被刷。

详尽配置项见 [../docs/05-deployment.md](../docs/05-deployment.md)。
