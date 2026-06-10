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
