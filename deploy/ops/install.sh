#!/usr/bin/env bash
# 在服务器上安装值守与备份(可重复执行)。在本目录下以 root 运行:
#   sudo ./install.sh
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
DEST=/opt/vca/ops

install -d -m 755 "$DEST"
install -m 755 lib.sh vca-watch.sh vca-backup.sh "$DEST/"

if [ ! -f /etc/vca-watch.env ]; then
  install -m 600 vca-watch.env.example /etc/vca-watch.env
  echo "· 已生成 /etc/vca-watch.env, 先填 ALERT_WEBHOOK(告警群机器人地址)"
fi

command -v mysqldump >/dev/null || echo "! 没有 mysqldump, 备份会失败: apt install mysql-client"
command -v python3   >/dev/null || echo "! 没有 python3, 推送会失败"

cat > /etc/cron.d/vca-ops <<CRON
# VCA 电话客服值守与备份, 由 deploy/ops/install.sh 生成
SHELL=/bin/bash
*/5 * * * * root $DEST/vca-watch.sh check  >> /var/log/vca-ops.log 2>&1
0 9 * * *   root $DEST/vca-watch.sh daily  >> /var/log/vca-ops.log 2>&1
30 3 * * *  root $DEST/vca-backup.sh       >> /var/log/vca-ops.log 2>&1
CRON
chmod 644 /etc/cron.d/vca-ops

# 日志自己转储, 别越攒越大
cat > /etc/logrotate.d/vca-ops <<'ROT'
/var/log/vca-ops.log {
  weekly
  rotate 4
  compress
  missingok
  notifempty
}
ROT

echo "✓ 已安装到 $DEST, 定时任务 /etc/cron.d/vca-ops(每 5 分钟值守、每天 9 点日报、每天 3:30 备份)"
echo "  验证推送: $DEST/vca-watch.sh test"
echo "  立即备份: $DEST/vca-backup.sh && ls -lh /var/backups/vca"
