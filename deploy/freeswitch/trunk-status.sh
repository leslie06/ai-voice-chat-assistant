#!/usr/bin/env bash
# 中继体检: 接真实线路时一条命令看清"通没通、卡在哪"。
#
#   ./trunk-status.sh            # 看状态
#   ./trunk-status.sh --trace    # 打开 SIP 报文跟踪(排完记得 --no-trace 关掉, 否则日志刷屏)
#   ./trunk-status.sh --no-trace
set -euo pipefail

cd "$(dirname "$0")"

if [ ! -f .env ]; then
  echo "✗ 未找到 deploy/freeswitch/.env" >&2
  exit 1
fi
P=$(grep '^ESL_PASSWORD' .env | cut -d= -f2)
fs() { docker exec vca-freeswitch fs_cli -p "$P" -x "$1" 2>/dev/null; }

if [ "$(docker inspect -f '{{.State.Running}}' vca-freeswitch 2>/dev/null)" != "true" ]; then
  echo "✗ FreeSWITCH 没在运行: cd deploy/freeswitch && docker compose up -d" >&2
  exit 1
fi

case "${1:-}" in
  --trace)
    fs "sofia global siptrace on" >/dev/null
    echo "✓ 已打开 SIP 报文跟踪。看报文: docker logs -f vca-freeswitch"
    echo "  排查完务必关掉: ./trunk-status.sh --no-trace"
    exit 0
    ;;
  --no-trace)
    fs "sofia global siptrace off" >/dev/null
    echo "✓ 已关闭 SIP 报文跟踪"
    exit 0
    ;;
esac

echo "== SIP 通道 =="
echo "   internal=软电话(5060)  external=中继(5080)"
fs "sofia status" | sed -n '3,8p'

echo
echo "== 中继 =="
gw=$(fs "sofia status" | awk '$2=="gateway" {print $1}')
if [ -z "$gw" ]; then
  echo "   未配置中继(TRUNK_HOST 为空) —— 现在只有软电话能打进来"
else
  for g in $gw; do
    # 注册式看 REGED; IP 白名单式是 NOREG, 那是正常的
    state=$(fs "sofia status gateway $g" | awk '$1=="State" {print $2}')
    echo "   $g: $state"
    case "$state" in
      REGED) echo "      注册成功, 可以拨出" ;;
      NOREG) echo "      未注册 —— IP 白名单式对接本来就是这样; 若你配的是账号密码式, 说明没注册上" ;;
      FAIL*|*FAIL*) echo "      注册失败: 账号密码/realm 对不上, 或对方没放行本机 IP" ;;
      *) echo "      状态异常, 看日志: docker logs --tail 100 vca-freeswitch" ;;
    esac
  done
fi

echo
echo "== 放行的来电来源(白名单之外一律拒接) =="
docker exec vca-freeswitch sed -n '/list name="trunk"/,/<\/list>/p' /etc/freeswitch/freeswitch.xml \
  | grep -o 'cidr="[^"]*"' | sed 's/cidr=/   /' | tr -d '"' || true
docker exec vca-freeswitch sed -n '/list name="trunk"/,/<\/list>/p' /etc/freeswitch/freeswitch.xml \
  | grep -q 'cidr=' || echo "   (空) —— 中继送过来的电话会被拒, 必须配 TRUNK_ACL"

echo
echo "== 当前通话 =="
fs "show channels count"

echo
echo "== 最近的拒接/失败 =="
docker logs --tail 400 vca-freeswitch 2>&1 | sed 's/\x1b\[[0-9;]*m//g' \
  | grep -iE "rejected by acl|NO_ROUTE|no reg|USER_NOT_REGISTERED|Gateway.*down|auth" | tail -5 \
  || echo "   (无)"
