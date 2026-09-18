# 本地 FreeSWITCH（软电话呼入 / 外呼联调）

FreeSWITCH 在这里只当**协议转换器**：软电话的 SIP/RTP 进来，经事件套接字（信令）和 unicast（UDP 裸音频）接到 VCA。
业务逻辑一行都不在这里。用到的都是 FreeSWITCH 自带能力，不需要编译任何第三方模块。原理讲解见 [docs/12-freeswitch.md](../../docs/12-freeswitch.md)，整体方案见 [docs/10-telephony-outbound.md](../../docs/10-telephony-outbound.md)。

```
软电话(分机 1000) ──SIP/RTP──▶ FreeSWITCH(Docker) ──socket 应用(TCP)──▶ VCA :8084(宿主机)
                                      ⇅ unicast(UDP, L16 8k)
```

## 启动

**一行就够**（仓库根目录）:

```bash
./start-phone.sh          # 改过代码时: ./start-phone.sh --build
```

它检查 Docker、没起就把 FreeSWITCH 起起来、检查 8080/8084 有没有被占、加载参数、再启动 VCA。
参数默认值在脚本里; 要长期改开场白/知识库归属/坐席号码, `cp .env.phone.example .env.phone`(不进仓库)。

首次需要先生成 FreeSWITCH 的密码文件(只需一次, `.env` 已被 gitignore):

```bash
cd deploy/freeswitch
printf 'SIP_PASSWORD=%s\nESL_PASSWORD=%s\n' "$(openssl rand -hex 8)" "$(openssl rand -hex 12)" > .env
```

<details><summary>不用脚本的手工启动</summary>

```bash
cd deploy/freeswitch && docker compose up -d --build     # 首次构建约 1 分钟
cd ../..
VCA_TELEPHONY_ENABLED=true VCA_TELEPHONY_GREETING="您好，请问有什么可以帮您？" ./run.sh
# 看到 "电话接入已启用(FreeSWITCH): socket 127.0.0.1:8084" 即就绪
```
</details>

## 软电话（Linphone）

账号助手里选「使用 SIP 账号」：

| 项 | 值 |
|---|---|
| 用户名 | `1000` |
| SIP 域名 / 服务器 | `127.0.0.1` |
| 密码 | `deploy/freeswitch/.env` 里的 `SIP_PASSWORD` |
| 传输 | UDP（注册不上就换 TCP） |

设置里关掉 ICE / STUN / 媒体加密，音频编码只留 PCMA、PCMU。

| 拨号 | 作用 |
|---|---|
| `5000` | 接入 VCA 语音助手 |
| `6000` | 回声测试，不经过 VCA。能听到自己 = 软电话↔FreeSWITCH 这段没问题 |

**务必戴耳机。** 电脑外放时 AI 的声音会被麦克风收回去，VAD 会把它当成你在插话，表现为 AI 说两个字就自己停。
真实电话听筒没有这个问题。

## 外呼（拨回软电话）

软电话保持注册，开「自动接听」，VCA 多加几个环境变量启动：

```bash
VCA_TELEPHONY_ENABLED=true \
VCA_FS_ESL_ENABLED=true \
VCA_FS_ESL_PORT=18021 \
VCA_FS_ESL_PASSWORD=<deploy/freeswitch/.env 里的 ESL_PASSWORD> \
VCA_FS_ESL_ENDPOINT='user/{number}' \
VCA_TELEPHONY_API_TOKEN=local-test-token \
./run.sh

curl -X POST http://127.0.0.1:8080/telephony/calls \
  -H 'X-Telephony-Token: local-test-token' -H 'Content-Type: application/json' \
  -d '{"number":"1000","callerId":"01088886666"}'
```

接真实中继时，在 `conf/freeswitch.xml` 里加 sofia gateway，`VCA_FS_ESL_ENDPOINT` 改成 `sofia/gateway/<网关名>/{number}`。

## 接真实电话线路

在本目录的 `.env` 里加 `TRUNK_HOST` 等参数(见 [docs/12 §7](../../docs/12-freeswitch.md))，
重启容器后用体检脚本看状态:

```bash
./trunk-status.sh              # 通道/中继/白名单/最近的拒接
./trunk-status.sh --trace      # 打开 SIP 报文跟踪(排完用 --no-trace 关掉)
```

没配中继时一切照旧: 5080 端口开着但白名单是空的, 谁都打不进来, 只有软电话能用。

## 排查

```bash
docker logs -f vca-freeswitch                           # 通话过程(控制台日志)
P=$(grep ^ESL_PASSWORD .env | cut -d= -f2)
docker exec vca-freeswitch fs_cli -p "$P" -x "sofia status profile internal reg"   # 软电话注册上了吗
docker exec vca-freeswitch fs_cli -p "$P" -x "show channels"                       # 当前通话
```

每通接入 VCA 的电话都会在 `recordings/` 下留一个双声道录音 `<uuid>.wav`：**左声道 = 来电方说的，右声道 = VCA 回的**。
`<uuid>` 就是 VCA 日志和 `conversation_turn.session_id` 里的通话 id。

| 现象 | 原因 |
|---|---|
| 接通后立刻挂断，VCA 没有任何日志 | VCA 没起 / 没开 `VCA_TELEPHONY_ENABLED` / `VCA_TELEPHONY_PROVIDER` 不是 freeswitch |
| VCA 日志"N ms 内没收到 FreeSWITCH 的媒体包" | `dialplan.xml` 里 `vca_media_remote_host` 或 `vca_media_local_ip` 不对 |
| 能接通但软电话两边都没声音 | `EXTERNAL_IP` 不对。软电话在本机 = `127.0.0.1`；在手机上 = 这台电脑的局域网 IP |
| VCA 启动报 FreeSWITCH 拒绝了本机地址 | `event_socket.conf` 的 `apply-inbound-acl` 名单没放行连入地址 |
| 外呼报 `MANDATORY_IE_MISSING` | 分机目录缺 `dial-string` |
| 软电话注册不上 | Linphone 自己占了 5060：把它的 SIP 端口改成随机；或改用 TCP |

## 用手机上的软电话

```bash
EXTERNAL_IP=<这台电脑的局域网IP> SIP_BIND=0.0.0.0 docker compose up -d
```

`SIP_BIND=0.0.0.0` 会把 SIP 端口暴露到局域网，只在可信网络里用；公网上的 5060 会被持续扫号盗打。

## 几个刻意的配置

- **不用官方 vanilla 配置。** 那套一百多个文件；这里 `freeswitch.xml` + `dialplan.xml` + `directory.xml` 三个文件就是全部。
- **媒体不映射端口。** unicast 的 FreeSWITCH 侧端口交给系统分配，VCA 按首包来源回包，Docker 端口转发会原路送回容器。
- **`local-network-acl` 指向空名单。** 软电话经 Docker 转发进来源地址是网桥网关，不这么做 SDP 里会写容器内网 IP。
- **RTP 只开 16384–16402。** Docker 要逐个映射 UDP 端口；FreeSWITCH 只用偶数端口，约同时 10 路。
- **事件套接字在宿主机上是 18021。** macOS 上 8021 常被 launchd 占着。
- **部署到服务器（FreeSWITCH 与 VCA 同机、不用容器）时**，把 `dialplan.xml` 里 `vca_media_local_ip` 改回 `127.0.0.1`、
  `vca_media_remote_host` 和 socket 地址改成 `127.0.0.1`，免得 unicast 口暴露在网卡上被人灌音频。
