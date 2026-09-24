#!/usr/bin/env bash
# 电话客服的值守脚本: 出事推群, 恢复再推一次, 同一件事不反复刷屏。由 cron 调用(见 install.sh)。
#
#   vca-watch.sh check    每 5 分钟: VCA 健康、FreeSWITCH、各家店的网关在不在线、磁盘
#   vca-watch.sh daily    每天早上: 昨天各店接了多少电话、多少高意向、留资几条
#   vca-watch.sh test     发一条测试消息, 确认推送地址配对了
#
# 为什么需要它: AI 不接电话是<b>静默故障</b> —— 网关掉线、服务挂了、欠费停服, 诊所那边只会觉得"最近没电话",
# 等发现的时候已经丢了几天的客户。
set -uo pipefail
# shellcheck source=lib.sh
. "$(dirname "$(readlink -f "$0")")/lib.sh"

: "${VCA_HEALTH_URL:=http://127.0.0.1:8080/actuator/health}"
: "${FAIL_TIMES:=2}"        # 连续几次检查失败才报警: 发布重启要二三十秒, 一次失败不算
: "${GW_FAIL_TIMES:=3}"     # 网关掉线连续几次才报: 诊所宽带抖一下很常见
: "${GW_CHECK_HOURS:=}"     # 只在这些钟点查网关, 如 8-21(诊所晚上断电关机就不必半夜报警); 空 = 全天
: "${DISK_LIMIT:=85}"       # 磁盘占用超过这个百分比报警

# 一项检查的结果。状态存在 STATE_DIR/<key>: "连续失败次数 是否已报警"
# 连续失败到阈值时报一次警; 之后恢复了再报一次恢复; 中间不重复
report() {
  local key=$1 ok=$2 title=$3 detail=$4 limit=${5:-$FAIL_TIMES}
  local file="$STATE_DIR/$key" fails=0 alerted=0
  [ -f "$file" ] && read -r fails alerted < "$file"
  if [ "$ok" = 1 ]; then
    if [ "$alerted" = 1 ]; then
      push "【恢复】$title 已恢复正常"
    fi
    echo "0 0" > "$file"
    return
  fi
  fails=$((fails + 1))
  if [ "$fails" -ge "$limit" ] && [ "$alerted" != 1 ]; then
    push "【告警】$title —— $detail"
    alerted=1
  fi
  echo "$fails $alerted" > "$file"
}

in_gateway_hours() {
  [ -z "$GW_CHECK_HOURS" ] && return 0
  local from=${GW_CHECK_HOURS%-*} to=${GW_CHECK_HOURS#*-} h
  h=$((10#$(date +%H)))
  [ "$h" -ge "$from" ] && [ "$h" -le "$to" ]
}

# 输出 "LINE分机|接入号|店名", 一行一台网关。不用制表符分隔: 它算空白, 连续两个会被 read 合并, 空的接入号就错位了
gateways() {
  local f ext number label
  # gw-*.xml 是 add-gateway.sh 开的, vca-gw-*.xml 是运营后台页面上开的, 格式相同
  for f in "$FS_DIR"/gateways/gw-*.xml "$FS_DIR"/gateways/vca-gw-*.xml; do
    [ -e "$f" ] || continue
    ext=$(basename "$f" .xml); ext=${ext##*gw-}
    number=$(sed -n 's/.*name="vca_access_number" value="\([^"]*\)".*/\1/p' "$f" | head -1)
    label=$(sed -n 's/.*<!-- 门店: \(.*\) -->.*/\1/p' "$f" | head -1)
    printf '%s|%s|%s\n' "$ext" "$number" "${label//|/}"
  done
  ext=$(env_val "$FS_DIR/.env" ATA_LINE_USER)
  if [ -n "$ext" ]; then
    printf '%s|%s|%s\n' "$ext" "$(env_val "$FS_DIR/.env" ATA_LINE_NUMBER)" "老网关"
  fi
}

check() {
  # 1. VCA 本身
  local health ok=0
  health=$(curl -sS -m 5 "$VCA_HEALTH_URL" 2>&1)
  printf '%s' "$health" | grep -q '"status":"UP"' && ok=1
  report vca "$ok" "AI 服务(VCA)" "健康检查不是 UP: ${health:0:120}。此时来电会转到各店前台座机(拨号计划兜底), 但不会有 AI 接待"

  # 2. FreeSWITCH: 容器在跑且 SIP 通道 RUNNING。它挂了所有店的电话都进不来
  local status fs_ok=0
  status=$(fs_cli_x "sofia status")
  printf '%s' "$status" | grep -E "internal[[:space:]]+profile" | grep -q RUNNING && fs_ok=1
  report freeswitch "$fs_ok" "电话交换(FreeSWITCH)" "容器 $FS_CONTAINER 不在运行或 SIP 通道没起来, 所有店的来电都进不来"

  # 3. 各家店的网关(FreeSWITCH 自己挂了就不逐台报了, 免得一次刷一屏)
  if [ "$fs_ok" = 1 ] && in_gateway_hours; then
    local reg ext number label
    reg=$(fs_cli_x "sofia status profile internal reg")
    while IFS='|' read -r ext number label; do
      [ -n "$ext" ] || continue
      ok=0
      printf '%s' "$reg" | grep -qE "^User:[[:space:]]+${ext}@" && ok=1
      report "gw-$ext" "$ok" "网关 $ext(接入号 ${number:-未绑定} ${label})" \
        "没有注册上来。多半是诊所断网/断电/网关重启; 这期间打给这家店的电话进不了 AI, 也转不了人工" "$GW_FAIL_TIMES"
    done < <(gateways)
  fi

  # 4. 磁盘: 满了数据库先挂, 而且表现得莫名其妙
  local used
  used=$(df -P / | awk 'NR==2 {gsub("%","",$5); print $5}')
  ok=1; [ "${used:-0}" -ge "$DISK_LIMIT" ] && ok=0
  report disk "$ok" "磁盘" "根分区已用 ${used}%(阈值 ${DISK_LIMIT}%), 先看 $FS_DIR/recordings 和日志"
}

daily() {
  local day calls lines sql
  local today
  day=$(date -d yesterday +%F 2>/dev/null || date -v-1d +%F)
  today=$(date +%F)
  # 每通进了拨号计划的电话都有一个录音文件, 按文件时间数最全(包括秒挂、AI 没接住转前台的)
  calls=$(find "$FS_DIR/recordings" -maxdepth 1 -name '*.wav' -newermt "$day" ! -newermt "$today" 2>/dev/null | wc -l)
  sql="SELECT s.called_number, COUNT(*), SUM(s.intent='A'), SUM(s.intent='B'),
         (SELECT COUNT(*) FROM phone_lead l WHERE l.called_number=s.called_number
            AND l.created_at >= '$day' AND l.created_at < '$day' + INTERVAL 1 DAY)
       FROM phone_call_summary s
       WHERE s.created_at >= '$day' AND s.created_at < '$day' + INTERVAL 1 DAY
       GROUP BY s.called_number ORDER BY s.called_number"
  local msg="【日报】$day 共 ${calls// /} 通来电"
  if lines=$(vca_sql "$sql" 2>&1); then
    if [ -n "$lines" ]; then
      msg+=$'\n'"(10 秒以上、有小结的通话)"
      while IFS=$'\t' read -r number n a b leads; do
        msg+=$'\n'"接入号 $number: $n 通, 意向 A $a / B $b, 留资 $leads 条"
      done <<< "$lines"
    else
      msg+=", 没有 10 秒以上的通话"
    fi
  else
    msg+=$'\n'"(查库失败: ${lines:0:100})"
  fi
  # 顺带报一下当前还有哪些告警没恢复: 日报是每天一定会看到的那条
  local open="" f
  for f in "$STATE_DIR"/*; do
    [ -f "$f" ] || continue
    read -r _ alerted < "$f"
    [ "$alerted" = 1 ] && open+=" $(basename "$f")"
  done
  if [ -n "$open" ]; then
    msg+=$'\n'"仍未恢复:$open"
  else
    msg+=$'\n'"服务状态: 正常"
  fi
  push "$msg"
}

case "${1:-check}" in
  check) check ;;
  daily) daily ;;
  test) push "【测试】值守脚本的推送配通了" ;;
  *) echo "用法: $0 check|daily|test" >&2; exit 2 ;;
esac
