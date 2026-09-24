#!/bin/sh
# 给一家店开通语音网关(HT813 这类 ATA)的两个分机, 不用重启 FreeSWITCH。
#
# ★ 日常请用运营后台(https://<域名>/admin/ → 商家 → 网关)在页面上开通; 这个脚本留作运营后台不可用时的备用。
#   两边生成的文件格式相同, 页面开的是 vca-gw-*.xml, 脚本开的是 gw-*.xml, 互不覆盖; 分机号也会互相避开。
#
#   ./add-gateway.sh 5002 阳光口腔       开通: 生成 LINE/PHONE 两个分机和密码, 绑定接入号 5002
#   ./add-gateway.sh --list              列出已开通的网关, 以及此刻有没有注册上来
#   ./add-gateway.sh --remove 5002       撤销这家店的网关分机
#
# 在 FreeSWITCH 所在机器、本脚本所在目录执行(服务器上是 /opt/vca/freeswitch)。
#
# 一台网关 = 一家店 = 一个文件 gateways/gw-<LINE 分机>.xml:
#   LINE 口(FXO, 接电话线)  分机 8NN1, 绑定 vca_access_number=<接入号>: 从它进来的电话一律按这家店接待,
#                           不看网关上"转 VoIP"填的号码 —— 那个框填错了也串不到别家
#   PHONE 口(FXS, 接话机)   分机 8NN2: 这家店的转人工分机, 在网页门店表单"转人工分机"里填它;
#                           也是 AI 接不了(没起、重启、崩了)时来电转去的座机(LINE 分机上的 vca_fallback_dial)
# 写完执行 reloadxml, 目录即刻生效; 在途通话不受影响。
set -eu

cd "$(dirname "$0")"
DIR=gateways
CONTAINER=${FS_CONTAINER:-vca-freeswitch}
mkdir -p "$DIR"

esl_password() {
  grep '^ESL_PASSWORD=' .env 2>/dev/null | cut -d= -f2-
}

fs_cli_x() {
  docker exec "$CONTAINER" fs_cli -p "$(esl_password)" -x "$1"
}

reload() {
  if fs_cli_x reloadxml >/dev/null 2>&1; then
    echo "✓ 已 reloadxml, 立即生效"
  else
    echo "! reloadxml 失败(FreeSWITCH 没在跑?)。文件已写好, FreeSWITCH 下次启动时生效" >&2
  fi
}

# 从文件里取某个 <variable name="X" value="..."/> 的值
var_of() {
  sed -n "s/.*name=\"$2\" value=\"\([^\"]*\)\".*/\1/p" "$1" | head -1
}

case "${1:-}" in
  --list)
    found=0
    for f in "$DIR"/gw-*.xml; do
      [ -e "$f" ] || continue
      found=1
      line=$(basename "$f" .xml | sed 's/^gw-//')
      phone=$((line + 1))
      number=$(var_of "$f" vca_access_number)
      label=$(sed -n 's/.*<!-- 门店: \(.*\) -->.*/\1/p' "$f" | head -1)
      echo "接入号 $number  $label  LINE=$line  PHONE=$phone"
    done
    [ "$found" = 1 ] || echo "(还没有开通任何网关)"
    echo
    echo "此刻注册上来的分机:"
    fs_cli_x "sofia status profile internal reg" 2>/dev/null | grep -E "^(User|Contact|Status):" || echo "(无, 或 FreeSWITCH 没在跑)"
    exit 0
    ;;
  --remove)
    number=${2:?用法: ./add-gateway.sh --remove <接入号>}
    for f in "$DIR"/gw-*.xml; do
      [ -e "$f" ] || continue
      if [ "$(var_of "$f" vca_access_number)" = "$number" ]; then
        rm -f "$f"
        echo "✓ 已删除 $f"
        reload
        exit 0
      fi
    done
    echo "✗ 没有绑定接入号 $number 的网关" >&2
    exit 1
    ;;
  ""|-h|--help)
    sed -n '2,15p' "$0"
    exit 0
    ;;
esac

number=$1
label=${2:-}
if ! echo "$number" | grep -Eq '^[0-9]{3,20}$'; then
  echo "✗ 接入号只能是 3~20 位数字(与网页门店表单里的接入号一致)" >&2
  exit 1
fi
# 名称会写进 XML 注释: 去掉可能破坏 XML 的字符
label=$(printf '%s' "$label" | tr -d '<>&"-' | cut -c1-40)

for f in "$DIR"/gw-*.xml; do
  [ -e "$f" ] || continue
  if [ "$(var_of "$f" vca_access_number)" = "$number" ]; then
    echo "✗ 接入号 $number 已经开通过网关: $f(先 --remove 再重开)" >&2
    exit 1
  fi
done

# 分机号: 8NN1 / 8NN2, NN 从 01 起找第一个空位。8001/8002 留给 .env 里老的 ATA_LINE_USER/ATA_PHONE_USER
used=" $(grep -E '^ATA_(LINE|PHONE)_USER=' .env 2>/dev/null | cut -d= -f2 | tr '\n' ' ') "
nn=1
while :; do
  if [ "$nn" -gt 99 ]; then
    echo "✗ 8011~8991 都用完了" >&2
    exit 1
  fi
  line=$(printf '8%02d1' "$nn")
  phone=$(printf '8%02d2' "$nn")
  taken=0
  case "$used" in *" $line "*|*" $phone "*) taken=1 ;; esac
  if [ ! -e "$DIR/gw-$line.xml" ] && [ "$taken" = 0 ]; then
    break
  fi
  nn=$((nn + 1))
done

line_pw=$(openssl rand -hex 12)
phone_pw=$(openssl rand -hex 12)
file="$DIR/gw-$line.xml"
umask 077
cat > "$file" <<XML
<include>
  <!-- 门店: ${label:-未命名} -->
  <!-- LINE 口(FXO, 接电话线): 从这里进来的电话一律按接入号 $number 认领门店 -->
  <user id="$line">
    <params>
      <param name="password" value="$line_pw"/>
    </params>
    <variables>
      <variable name="user_context" value="ai-agent"/>
      <variable name="effective_caller_id_number" value="$line"/>
      <variable name="sip-force-contact" value="NDLB-connectile-dysfunction"/>
      <variable name="vca_access_number" value="$number"/>
      <!-- AI 接不了时(没起、正在重启、崩了)来电转到这家店的前台座机, 而不是挂断 -->
      <variable name="vca_fallback_dial" value="user/$phone@vca.local"/>
    </variables>
  </user>
  <!-- PHONE 口(FXS, 接话机): 这家店的转人工分机 -->
  <user id="$phone">
    <params>
      <param name="password" value="$phone_pw"/>
    </params>
    <variables>
      <variable name="user_context" value="ai-agent"/>
      <variable name="effective_caller_id_number" value="$phone"/>
      <variable name="sip-force-contact" value="NDLB-connectile-dysfunction"/>
    </variables>
  </user>
</include>
XML
echo "✓ 已写入 $file"
reload

ext_ip=$(grep '^EXTERNAL_IP=' .env 2>/dev/null | cut -d= -f2-)
cat <<TXT

==== 接入号 $number ${label} ====
HT813 网页上按端口填(其余项见 docs/12-freeswitch.md §7.4):
  FXO PORT(LINE 口)  SIP Server = ${ext_ip:-<服务器公网 IP>}:5060   User ID / Authenticate ID = $line   Password = $line_pw
  FXS PORT(PHONE 口) SIP Server = ${ext_ip:-<服务器公网 IP>}:5060   User ID / Authenticate ID = $phone   Password = $phone_pw
  BASIC SETTINGS → Unconditional Call Forward to VOIP: User ID = $number, Sip Server = ${ext_ip:-<服务器公网 IP>}, 端口 5060
网页"电话客服"里新建门店: 接入号 $number, 转人工分机 $phone
密码只在这里和 $file 里出现, 装完网关后不需要再抄。
TXT
