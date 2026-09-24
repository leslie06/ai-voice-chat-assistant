# 值守与备份（服务器上）

电话客服出问题时往往是**静默**的：网关掉线、服务挂了、欠费停服，诊所只会觉得"最近没电话"。
这里的两个脚本由 cron 驱动，出事推到群里，并且每天备份一次数据。

| 脚本 | 时间 | 做什么 |
|---|---|---|
| `vca-watch.sh check` | 每 5 分钟 | VCA 健康、FreeSWITCH、各店网关是否在线、磁盘。连续失败到阈值报一次警，恢复再报一次，中间不刷屏 |
| `vca-watch.sh daily` | 每天 9:00 | 昨天共多少通来电；各接入号有小结的通话数、A/B 意向数、留资数；还有哪些告警没恢复 |
| `vca-backup.sh` | 每天 3:30 | 数据库（`mysqldump --single-transaction`，不锁表）+ 电话侧配置（网关分机、FreeSWITCH 密钥、`/etc/vca.env`），保留 7 天；失败推群 |

## 安装

```bash
# 开发机上
rsync -az deploy/ops/ root@<服务器>:/opt/vca/ops-src/
# 服务器上
cd /opt/vca/ops-src && ./install.sh
vi /etc/vca-watch.env                      # 填 ALERT_WEBHOOK: 企业微信/钉钉群机器人地址
/opt/vca/ops/vca-watch.sh test             # 群里应收到一条测试消息
/opt/vca/ops/vca-backup.sh && ls -lh /var/backups/vca
```

`install.sh` 可重复执行：拷脚本到 `/opt/vca/ops`、写 `/etc/cron.d/vca-ops` 和日志转储；
`/etc/vca-watch.env` 只在不存在时生成，不会覆盖已填好的配置。运行日志在 `/var/log/vca-ops.log`。

建议给告警单独建一个"运维"群，不要和商家的通话小结群混在一起。

## 几个取舍

- **VCA 连续 2 次（约 10 分钟）不健康才报**：发布重启要二三十秒，一次失败不算。VCA 挂着的这段时间，
  来电由拨号计划转到各店前台座机（见 `docs/12-freeswitch.md` §7.4），不会被挂断。
- **网关连续 3 次不在线才报**，且可以用 `GW_CHECK_HOURS=8-21` 只在营业时间查：诊所晚上断电关机很常见。
- **本机备份防误删、改坏，防不了整块盘坏**。后者请在阿里云控制台给系统盘开"自动快照"（按天、保留 7 天），两者互补。
- 不 `source /etc/vca.env`：它是 systemd 的 EnvironmentFile，值不加引号，数据库 URL 里的 `&` 会被 shell 当成后台执行符。脚本按键取值。
