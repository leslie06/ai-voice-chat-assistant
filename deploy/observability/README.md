# VCA 监控栈 (Prometheus + Grafana)

抓取 VCA 的 `/actuator/prometheus`, 用 Grafana 看三段流水线延迟。

## 启动

```bash
# 1) 先确保 VCA 在跑(宿主机): ./run.sh  → http://localhost:8080
# 2) 起监控栈:
docker compose -f deploy/observability/docker-compose.yml up -d
```

- **Grafana**: http://localhost:3000 (账号 `admin` / `admin`)
  → 已自动配好数据源和面板「**VCA 实时语音**」, 进去直接看。
- **Prometheus**: http://localhost:9099  (宿主 9090/9091 常被占, 故用 9099; 容器内仍是 9090)
  → Status → Targets 应看到 `vca` 为 `UP`。

对着应用说几句话, 刷新面板就能看到真实延迟数据。

## 面板内容

| 面板 | 指标 | 说明 |
|------|------|------|
| TTS 首包延迟 | `vca_turn_tts_first_audio_seconds` | **用户感知的"开口延迟", 最关键** |
| LLM 首 token 延迟 | `vca_turn_llm_first_token_seconds` | 大模型思考延迟 |
| 整轮耗时 | `vca_turn_total_seconds` | 一轮从开始到结束 |
| 回合速率(按结果) | `vca_turn_count_total{outcome}` | complete / interrupted / error 速率 |

## 抓取目标说明

`prometheus.yml` 默认抓 `host.docker.internal:8080` —— 适用于「VCA 跑宿主机 + 监控栈跑 docker」。

如果 VCA 也用 `deploy/docker-compose.yml` 跑在 docker 里, 把 `prometheus.yml` 的 target 改成 `vca:8080`,
并让两个 compose 共用同一 docker 网络(或把 prometheus/grafana 直接并入主 compose)。

## 停止

```bash
docker compose -f deploy/observability/docker-compose.yml down       # 保留数据
docker compose -f deploy/observability/docker-compose.yml down -v    # 连数据卷一起删
```

## ⚠️ 安全

`/actuator/prometheus` 当前无鉴权。公网部署时务必用反向代理(Caddy/Nginx)把 `/actuator` 挡在外面,
不要把 8080 直接对公网开放。
