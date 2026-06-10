# 05 · 部署指南

## 1. 部署形态

模块化单体：编译期分多模块，**运行期只有 `vca-bootstrap` 一个可执行 jar**，部署成**多副本**横向扩容。每条 WebSocket 连接的会话状态（历史、回合、VAD）都在该连接所在实例的**内存**里，连接断开即释放——没有跨实例共享状态，因此扩容只需在前面放一个支持 WebSocket 的负载均衡器。

```
                         ┌─────────────────────────┐
   浏览器(wss) ──────────►│  反向代理 / LB (TLS+WS)  │
                         └───────────┬─────────────┘
                    ┌────────────────┼────────────────┐
                    ▼                ▼                ▼
              vca jar 副本1     vca jar 副本2     vca jar 副本N   （无状态共享, 各自连厂商）
                    └────────────────┴────────────────┘
                                     │
                        阿里云 DashScope / DeepSeek（外部 API）
```

## 2. 打包

```bash
./mvnw -pl vca-bootstrap -am package -DskipTests
# 产物: vca-bootstrap/target/vca-bootstrap-0.0.1-SNAPSHOT.jar （Spring Boot 可执行 fat jar）
```

## 3. 运行

```bash
java -jar vca-bootstrap-0.0.1-SNAPSHOT.jar \
  --server.port=8080
# 生产建议显式给 JVM 资源与时区
java -Xms512m -Xmx1g -Duser.timezone=Asia/Shanghai -jar ...jar
```

## 4. 配置项参考

通过**环境变量**或 `--key=value` 命令行参数覆盖。核心项：

### Profile / 桩开关
| 配置 | 默认 | 说明 |
|------|------|------|
| `SPRING_PROFILES_ACTIVE` | `real` | `real`=真实厂商；`default`=纯桩 |
| `VCA_DEV_STUB_ASR` / `_TTS` / `_LLM` | 见 profile | 分能力桩开关 |

### 厂商凭据 / 端点
| 配置 | 说明 |
|------|------|
| `DASHSCOPE_API_KEY` | 阿里云 ASR + TTS 共用 Key |
| `ALIYUN_ASR_ENABLED` / `ALIYUN_TTS_ENABLED` | 启用真实阿里云能力（`real` 已置 true）|
| `DEEPSEEK_ENABLED` | 启用真实 DeepSeek（否则用 echo 桩当大脑）|
| `DEEPSEEK_API_KEY` | DeepSeek Key（支持多个，`ApiKeyPool` 轮询）|
| `DEEPSEEK_PROXY` | DeepSeek 出口代理；置空=直连 |

### 接入层 / 默认会话（`vca.web.*`）
| 配置 | 默认 | 说明 |
|------|------|------|
| `vca.web.path` | `/ws/voice` | WebSocket 端点路径 |
| `vca.web.asr-vendor` / `llm-vendor` / `tts-vendor` | aliyun / deepseek / aliyun | 主选厂商 |
| `vca.web.tts-voice` | longxiaochun | 发音人 |
| `vca.web.system-prompt` | （内置口语化提示）| LLM 系统提示 |

### VAD / 断句打断（`vca.web.vad.*`）
见 [技术实现 §2](./02-tech-implementation.md#2-后端-vad-与免提状态机)。环境吵 → 调高 `speech-threshold`；打断不灵敏 → 调低 `barge-threshold` / `barge-ms`：
```yaml
vca:
  web:
    vad:
      speech-threshold: 0.015
      barge-threshold: 0.020
      barge-ms: 250
```

### 治理（`vca.gateway.*`）
```yaml
vca:
  gateway:
    circuit: { failure-threshold: 5, open-duration: 10s }
    llm:
      candidates:
        - { vendor: deepseek, model: deepseek-chat, max-concurrency: 50 }  # 主
        - { vendor: qwen,     model: qwen-plus,     max-concurrency: 50 }  # 备
```
`max-concurrency` 是**单副本**的每厂商并发上限；总并发 = 副本数 × 配额。配合厂商侧 QPS/并发限制设置。

## 5. 反向代理（Nginx，TLS + WebSocket 升级）

远程访问必须 HTTPS（否则浏览器禁止访问麦克风）。WebSocket 需要透传 `Upgrade` 头：

```nginx
server {
    listen 443 ssl;
    server_name vca.example.com;
    ssl_certificate     /etc/ssl/vca.crt;
    ssl_certificate_key /etc/ssl/vca.key;

    location / {
        proxy_pass http://vca_backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade    $http_upgrade;   # WebSocket 升级
        proxy_set_header Connection "upgrade";
        proxy_set_header Host       $host;
        proxy_read_timeout 3600s;                    # 长连接, 拉长超时
        proxy_send_timeout 3600s;
    }
}
upstream vca_backend {
    # 会话状态在连接内, 同一连接天然落在同一副本; 无需跨请求会话亲和
    server 10.0.0.11:8080;
    server 10.0.0.12:8080;
}
```

> 前端已按页面协议自动选择 `ws/wss`，HTTPS 页面会自动用 `wss://`。

## 6. 云服务器一步步部署（推荐：Ubuntu + Caddy 自动 HTTPS）

面向"买了一台云服务器、想让手机也能公网实时语音"的场景。整套所需文件已在仓库 `deploy/` 下：

| 文件 | 用途 |
|------|------|
| `deploy/setup-ubuntu.sh` | Ubuntu/Debian 一键脚本：建 swap、装 JDK17、装 Caddy、建运行用户、装 systemd 服务、配自动 HTTPS |
| `deploy/vca.service` | systemd 单元（已按 2 核 2G 调好 JVM 堆）|
| `deploy/Caddyfile` | Caddy 反代模板（自动 TLS + 自动支持 WebSocket）|
| `deploy/README.md` | 公网部署速查 |

### 6.0 服务器规格与前置

本服务自身很轻（I/O 密集，重活在外部 API），但**免提时手机会持续上传原始 PCM**，带宽是主要约束。

| 项 | 推荐 | 最低 | 说明 |
|----|------|------|------|
| CPU / 内存 | 2 vCPU / 2–4 GB | 1 vCPU / 2 GB | **2 核 2G 实测可用**（见下方调优）|
| 系统 | Ubuntu 22.04 / 24.04 LTS | 任意 Linux | 脚本兼容 24.04 |
| 带宽 | 按量计费 或 ≥5 Mbps | — | **每并发用户 ≈ 1 Mbps**（上行 PCM ~768kbps + 下行 TTS ~384kbps）|
| 安全组入站 | 放行 **22 / 80 / 443** | — | **不要**对公网开 8080 |

前置：
- **域名**：一条 A 记录指向服务器公网 IP。
- **备案**：服务器在中国大陆时，80/443 对外通常需 **ICP 备案**（否则被拦、Caddy 也签不了证书）。境外服务器免备案。
- **密钥**：阿里云 DashScope Key（ASR/TTS）、DeepSeek Key（可选）。没有可先用纯桩模式把链路和 HTTPS 跑通。

### 6.1 本机：打包并上传

> ⚠ **不要在 2G 服务器上 `mvn package`**（编译吃内存易卡/OOM）。在本机打好 jar，服务器只需 JRE。

```bash
# 项目根目录
./mvnw -pl vca-bootstrap -am package -DskipTests

ssh root@你的IP "mkdir -p /opt/vca"
scp vca-bootstrap/target/vca-bootstrap-0.0.1-SNAPSHOT.jar root@你的IP:/opt/vca/app.jar
scp deploy/vca.service deploy/setup-ubuntu.sh           root@你的IP:/root/
```

### 6.2 服务器：一键安装

```bash
# SSH 进服务器（root）
bash /root/setup-ubuntu.sh 你的域名.com
# 脚本会：建2G swap → 装JDK17 → 装Caddy → 建vca用户 → 生成/etc/vca.env
#         → 装并启动 vca 服务 → 写 Caddyfile(你的域名) → 启动 Caddy 自动签证书
```

### 6.3 服务器：填密钥并重启

```bash
nano /etc/vca.env
```
```ini
SPRING_PROFILES_ACTIVE=real
DASHSCOPE_API_KEY=sk-你的阿里云key
DEEPSEEK_ENABLED=true
DEEPSEEK_API_KEY=sk-你的deepseek-key
DEEPSEEK_PROXY=          # ‼ 必须留空：服务器没有本地 7890 代理，留空=直连
```
```bash
systemctl restart vca
```

### 6.4 验证 & 手机访问

```bash
journalctl -u vca -f        # 看到 "Started Application" 即成功
systemctl status caddy      # 证书签发 OK
curl -I https://你的域名.com  # 200
```
手机浏览器打开 `https://你的域名.com` → 「🔊 开启声音」→「💬 免提对话」。

### 6.5 针对 2 核 2G 的调优（已内置）

- `deploy/vca.service` 已设 `-Xms256m -Xmx512m -XX:MaxDirectMemorySize=128m`，给系统/Caddy/Netty 直接内存留余量；
- 脚本自动创建 **2G swap** 兜底；
- 若日志出现 `OutOfMemoryError` 或进程被 kill：把 `-Xmx512m` 降到 `-Xmx384m`，`systemctl daemon-reload && systemctl restart vca`。
- 连接本身很轻，2 核 2G 跑十几路并发的瓶颈是**带宽**而非内存/CPU。

### 6.6 更新版本（重新发布）

```bash
# 本机
./mvnw -pl vca-bootstrap -am package -DskipTests
scp vca-bootstrap/target/vca-bootstrap-0.0.1-SNAPSHOT.jar root@你的IP:/opt/vca/app.jar
# 服务器
systemctl restart vca
# 手机端强制刷新（Cmd/Ctrl+Shift+R）避免缓存旧页面（徽标应为 界面 v4）
```

### 6.7 常见卡点（按概率）

| 现象 | 原因 | 处理 |
|------|------|------|
| LLM 连不上 | `DEEPSEEK_PROXY` 没留空 | `/etc/vca.env` 置空后 `systemctl restart vca` |
| 手机打不开 / 证书失败 | 80/443 没放行、A 记录未生效、未备案 | 查安全组 + DNS + 备案 |
| 能开但麦克风用不了 | 用了 http 或直接 IP | 必须 `https://域名` |
| 启动即 OOM | 堆太大 | 调小 `-Xmx` |
| 改了没生效 | 没重启/浏览器缓存 | 重传 jar + restart + 手机强刷 |

## 7. Docker（示例）

```dockerfile
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY vca-bootstrap/target/vca-bootstrap-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
```
```bash
docker build -t vca:latest .
docker run -d -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=real \
  -e DASHSCOPE_API_KEY=sk-xxx \
  -e DEEPSEEK_ENABLED=true -e DEEPSEEK_API_KEY=sk-yyy \
  vca:latest
```

## 8. 多副本与扩缩容要点

- **无共享状态**：每路会话只活在其连接所在副本的内存里，扩缩容无需共享存储。缩容/重启会断开该副本上的活动连接，前端已实现**断线 3s 自动重连**（重连即新会话，历史不跨连接保留）。
- **并发上限**：单副本受 `max-concurrency`（每厂商）与机器资源约束；用副本数线性扩。
- **API Key 池**：DeepSeek 支持配置多 Key（`ApiKeyPool` 轮询）分摊厂商侧限流。
- **优雅停机**：Spring Boot 支持优雅关闭（`server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase`），缩容前先摘流量再停。

## 9. 可观测

- **日志**：`logging.level.com.vca=DEBUG`（开发）/ `INFO`（生产）。状态机迁移、回合开始/结束、熔断跳过、故障转移都有日志。
- **健康检查**：可引入 `spring-boot-starter-actuator` 暴露 `/actuator/health`，给 LB/容器探针使用（当前默认未引入，按需添加）。
- **关键指标建议**：每路首字延迟（ASR final→首个音频块）、打断生效延迟、各厂商熔断次数/配额命中、并发连接数。

## 10. 局域网访问（同一 WiFi：手机 / 另一台电脑）

> 适合开发自测：手机和电脑连**同一个 WiFi**直接访问本机服务，无需公网/域名。

### 10.1 直接用 HTTP（文字聊天 + 放歌可用，**语音不可用**）

服务默认绑定所有网卡（`server.address` 未设 → `*:8080`），局域网开箱可达：

```bash
# 在 Mac 上查本机 WiFi IP
ipconfig getifaddr en0        # 形如 192.168.x.x

# 手机/另一台电脑浏览器打开（IP 换成上面查到的）
http://<本机IP>:8080
```

打不开就放行 macOS 防火墙（系统设置 → 网络 → 防火墙：关闭，或允许 `java` 接受传入连接）。在另一台设备上验证可达：`curl -I http://<本机IP>:8080`。

> ⚠️ **麦克风的硬限制**：浏览器只在 `https://` 或 `http://localhost` 下允许 `getUserMedia`。用 `http://<IP>` 远程访问属于**不安全来源**，免提/按住说话会被禁用；文字聊天与放歌不受影响。要远程用语音，见下。

### 10.2 自签 HTTPS（让远程也能用麦克风）

项目内置 `https` profile（`application-https.yml`），用打包在 classpath 的自签证书 `vca-keystore.p12` 跑 HTTPS（默认 8443）：

```bash
# 与 real 叠加启用 https profile
SPRING_PROFILES_ACTIVE=real,https \
  java -jar vca-bootstrap/target/vca-bootstrap-0.0.1-SNAPSHOT.jar
# 起来后日志可见: Netty started on port 8443 (https)
```

各设备访问 `https://<本机IP>:8443`，首次会提示证书不受信任 → 点「继续/仍要访问」即可（自签难免）。

- **桌面 Chrome/Firefox**：点击「继续前往」后麦克风即可用。
- **iPhone Safari**：自签证书较严格，若点击通过后仍拿不到麦克风，需把证书装为「受信任」：把 `vca-bootstrap/src/main/resources/vca-keystore.p12` 导出证书发到手机安装为描述文件，并在「设置 → 通用 → 关于本机 → 证书信任设置」里打开完全信任。嫌麻烦就用 §6 的公网部署（Caddy 自动签**受信任**证书，手机零配置）。

### 10.3 IP 变了 / 换了网络 → 重新生成证书

证书把当前 WiFi IP 写进了 SAN（Subject Alternative Name）。换网络导致 IP 变化后，HTTPS 仍能用但证书名不匹配（桌面可强行继续，iOS 更严）。重新生成：

```bash
IP=$(ipconfig getifaddr en0)
keytool -genkeypair -alias vca -keyalg RSA -keysize 2048 -validity 3650 \
  -storetype PKCS12 -keystore vca-bootstrap/src/main/resources/vca-keystore.p12 \
  -storepass changeit -dname "CN=VCA Dev, O=VCA, C=CN" \
  -ext "SAN=ip:$IP,dns:localhost,ip:127.0.0.1"
# 重新打包后再跑
```

可用环境变量覆盖：`HTTPS_PORT`、`SSL_KEYSTORE`、`SSL_KEYSTORE_PASSWORD`、`SSL_KEY_ALIAS`。
