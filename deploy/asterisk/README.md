# 本地 Asterisk（备选媒体服务器）

> **默认媒体服务器已改为 FreeSWITCH，见 [deploy/freeswitch/](../freeswitch/README.md)。** 这里保留 Asterisk 版作为备选，
> 启动 VCA 时必须加 `VCA_TELEPHONY_PROVIDER=asterisk`。两者都占宿主机 5060，不能同时运行。

Asterisk 在这里只当**协议转换器**：软电话的 SIP/RTP 进来，转成 AudioSocket 连到 VCA 的 9092 端口。
业务逻辑一行都不在这里。整体方案见 [docs/10-telephony-outbound.md](../../docs/10-telephony-outbound.md)。

```
软电话(分机 1000) ──SIP/RTP──▶ Asterisk(Docker) ──AudioSocket──▶ VCA :9092(宿主机)
```

## 启动

```bash
# 1. Asterisk。首次构建约 4 分钟(装 Ubuntu 的 asterisk 包)。
cd deploy/asterisk
echo "SIP_PASSWORD=$(openssl rand -hex 8)" > .env     # 只需一次; .env 已被 gitignore
docker compose up -d --build

# 2. VCA, 打开电话接入并指定 Asterisk(在仓库根目录)
VCA_TELEPHONY_ENABLED=true \
VCA_TELEPHONY_PROVIDER=asterisk \
VCA_TELEPHONY_GREETING="您好，这里是智能语音助手，请问有什么可以帮您？" \
./run.sh
# 看到 "电话接入已启用(Asterisk): AudioSocket :9092" 即就绪
```

## 软电话（Linphone）

账号助手里选「使用 SIP 账号」：

| 项 | 值 |
|---|---|
| 用户名 | `1000` |
| SIP 域名 / 服务器 | `127.0.0.1` |
| 密码 | `deploy/asterisk/.env` 里的 `SIP_PASSWORD` |
| 传输 | UDP（注册不上就换 TCP） |

设置里关掉 ICE / STUN / 媒体加密，音频编码只留 PCMA、PCMU。

| 拨号 | 作用 |
|---|---|
| `5000` | 接入 VCA 语音助手 |
| `6000` | 回声测试，不经过 VCA。能听到自己 = 软电话↔Asterisk 这段没问题 |

**务必戴耳机。** 电脑外放时 AI 的声音会被麦克风收回去，VAD 会把它当成客户插话，表现为 AI 说两个字就自己停。
真实电话听筒没有这个问题。

## 排查

```bash
docker logs -f vca-asterisk                                   # 通话过程
docker exec vca-asterisk asterisk -rx "pjsip show contacts"   # 软电话注册上了吗
docker exec vca-asterisk asterisk -rx "core show channels"    # 当前通话
```

每通 `5000` 的电话都会在 `recordings/` 下留三个文件：`<uuid>-rx.wav`（来电方说的）、
`<uuid>-tx.wav`（VCA 回的）、`<uuid>.wav`（混音）。`<uuid>` 就是 VCA 日志和 `conversation_turn.session_id` 里的通话 id。

| 现象 | 原因 |
|---|---|
| 接通后立刻挂断，VCA 没有任何连接日志 | VCA 没起 / 没开 `VCA_TELEPHONY_ENABLED` / 没设 `VCA_TELEPHONY_PROVIDER=asterisk`；或 UUID 不合法 |
| 能接通但两边都没声音 | `EXTERNAL_IP` 不对。软电话在本机 = `127.0.0.1`；在手机上 = 这台电脑的局域网 IP |
| 听到刺耳噪声而不是人声 | SLIN 字节序反了，VCA 侧设 `VCA_TELEPHONY_SWAP_BYTES=true`（本镜像实测**不需要**） |
| 软电话注册不上 | Linphone 自己占了 5060：把它的 SIP 端口改成随机；或改用 TCP |

## 用手机上的软电话

```bash
EXTERNAL_IP=<这台电脑的局域网IP> SIP_BIND=0.0.0.0 docker compose up -d
```

`SIP_BIND=0.0.0.0` 会把 SIP 端口暴露到局域网，只在可信网络里用；公网上的 5060 会被持续扫号盗打。

## 几个刻意的配置

- **RTP 只开 10000–10019。** Docker 要逐个映射 UDP 端口，范围大了启动极慢；20 个端口够同时 5 路。
- **不配 `local_net`，`rtp_symmetric=yes`，`strictrtp=no`。** 软电话的包经 Docker 转发进来，源地址是网桥网关，
  这三项合起来才能让回程 RTP 找到路。部署到有公网 IP 的 Linux 主机上时用 `network_mode: host`，这些都可以收紧。
- **UUID 向内核要**（`/proc/sys/kernel/random/uuid`）。Ubuntu 的 Asterisk 20.6 没有 `func_uuid`，`${UUID()}` 不存在。
