#!/usr/bin/env bash
# 每天清理过了保留期的通话录音。由 cron 调用(见 install.sh)。
#
# 录音里是客户的声音、常常还有手机号和病情, 属于个人信息: 留多久要有个上限, 到期就删(个人信息保护法
# 的"最小必要"与"保存期限")。保留期按和商家约定的来, 默认 90 天 —— 够处理投诉和回访, 也不至于越攒越多
# (双声道 8k 录音每分钟约 2MB, 一家店一天几十通, 半年就是几个 G)。
#
# 删掉之后商家后台的"听录音"会显示已过保留期; 通话小结和线索是文字, 不受影响。
set -uo pipefail
# shellcheck source=lib.sh
. "$(dirname "$(readlink -f "$0")")/lib.sh"

: "${RECORDING_KEEP_DAYS:=90}"

dir="$FS_DIR/recordings"
if [ ! -d "$dir" ]; then
  echo "$(date '+%F %T') 录音目录 $dir 不存在, 跳过"
  exit 0
fi
if ! [[ "$RECORDING_KEEP_DAYS" =~ ^[0-9]+$ ]] || [ "$RECORDING_KEEP_DAYS" -lt 1 ]; then
  push "【告警】录音清理没执行 —— RECORDING_KEEP_DAYS=$RECORDING_KEEP_DAYS 不是正整数"
  exit 1
fi

before=$(du -sh "$dir" 2>/dev/null | cut -f1)
# 只删 FreeSWITCH 按通话 id 命名的 wav, 目录里别的东西不碰
n=$(find "$dir" -maxdepth 1 -type f -name '*-*-*-*-*.wav' -mtime +"$RECORDING_KEEP_DAYS" -print -delete | wc -l)
echo "$(date '+%F %T') 录音清理: 删除 ${n// /} 个超过 ${RECORDING_KEEP_DAYS} 天的录音, 目录 ${before} → $(du -sh "$dir" 2>/dev/null | cut -f1)"
