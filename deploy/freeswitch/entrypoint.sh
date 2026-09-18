#!/bin/sh
# 把 /conf 里的模板渲染进 /etc/freeswitch, 再前台启动。
# 用 sed 而不是 FreeSWITCH 自己的 $${var}: 密码这类值不该出现在任何能被 "global_getvar" 读出来的地方。
set -eu

: "${EXTERNAL_IP:=127.0.0.1}"
: "${SIP_PASSWORD:?必须设置 SIP_PASSWORD}"
: "${ESL_PASSWORD:?必须设置 ESL_PASSWORD}"
: "${VCA_HOST:=host.docker.internal}"
: "${VCA_PORT:=8084}"

# ---- SIP 中继 / 语音网关(可选) ----
# TRUNK_HOST 为空 = 没有中继, 只有软电话能打进来(本地联调就是这样)。
: "${TRUNK_HOST:=}"
: "${TRUNK_NAME:=trunk}"
: "${TRUNK_USER:=}"
: "${TRUNK_PASSWORD:=}"
: "${TRUNK_REALM:=}"
: "${TRUNK_FROM_USER:=}"
: "${TRUNK_ACL:=}"

gateway_body=""
if [ -n "$TRUNK_HOST" ]; then
  if [ -n "$TRUNK_USER" ]; then
    # 注册式: 有账号密码(FXO 网关、给了账号的中继)
    gateway_body=$(cat <<GW
  <gateway name="${TRUNK_NAME}">
    <param name="proxy" value="${TRUNK_HOST}"/>
    <param name="realm" value="${TRUNK_REALM:-$TRUNK_HOST}"/>
    <param name="username" value="${TRUNK_USER}"/>
    <param name="password" value="${TRUNK_PASSWORD}"/>
    <param name="from-user" value="${TRUNK_FROM_USER:-$TRUNK_USER}"/>
    <param name="register" value="true"/>
    <param name="expire-seconds" value="120"/>
    <param name="retry-seconds" value="30"/>
    <param name="caller-id-in-from" value="true"/>
    <param name="ping" value="60"/>
  </gateway>
GW
)
  else
    # IP 白名单式: 对方认我们的公网 IP, 不注册。必须同时配好 TRUNK_ACL, 否则电话进不来。
    gateway_body=$(cat <<GW
  <gateway name="${TRUNK_NAME}">
    <param name="proxy" value="${TRUNK_HOST}"/>
    <param name="register" value="false"/>
    <param name="caller-id-in-from" value="true"/>
    <param name="ping" value="60"/>
  </gateway>
GW
)
  fi
fi

# 中继来源白名单: 逗号分隔的 CIDR → 一行一个 allow 节点。
# 注意用 for 而不是 "管道 + while": 管道右侧在子 shell 里跑, 循环里攒的变量出不来,
# 表现是只有最后/第一个网段生效, 其余静默丢失(已踩过)。
acl_nodes=""
if [ -n "$TRUNK_ACL" ]; then
  old_ifs=$IFS
  IFS=','
  for cidr in $TRUNK_ACL; do
    [ -n "$cidr" ] || continue
    acl_nodes="${acl_nodes}          <node type=\"allow\" cidr=\"${cidr}\"/>
"
  done
  IFS=$old_ifs
fi

# ---- 语音网关(ATA)的两个口。不配则没有这两个分机, 与之前完全一致 ----
: "${ATA_LINE_USER:=}"        # LINE 口(FXO, 接电话线): 来电从这里进 AI
: "${ATA_LINE_PASSWORD:=}"
: "${ATA_PHONE_USER:=}"       # PHONE 口(FXS, 接有绳话机): 转人工时它响
: "${ATA_PHONE_PASSWORD:=}"

ata_users=""
ata_user_xml() {
  # $1=分机号 $2=密码 $3=注释
  printf '    <!-- %s -->\n    <user id="%s">\n      <params>\n        <param name="password" value="%s"/>\n      </params>\n      <variables>\n        <variable name="user_context" value="ai-agent"/>\n        <variable name="effective_caller_id_number" value="%s"/>\n        <variable name="sip-force-contact" value="NDLB-connectile-dysfunction"/>\n      </variables>\n    </user>\n' "$3" "$1" "$2" "$1"
}
if [ -n "$ATA_LINE_USER" ]; then
  ata_users="${ata_users}$(ata_user_xml "$ATA_LINE_USER" "$ATA_LINE_PASSWORD" "语音网关 LINE 口(FXO): 电话线来电从这里进")"
fi
if [ -n "$ATA_PHONE_USER" ]; then
  ata_users="${ata_users}$(ata_user_xml "$ATA_PHONE_USER" "$ATA_PHONE_PASSWORD" "语音网关 PHONE 口(FXS): 转人工时这台话机响")"
fi

mkdir -p /etc/freeswitch /var/lib/freeswitch/db /var/log/freeswitch /recordings
for f in /conf/*.xml; do
  sed -e "s|@EXTERNAL_IP@|${EXTERNAL_IP}|g" \
      -e "s|@SIP_PASSWORD@|${SIP_PASSWORD}|g" \
      -e "s|@ESL_PASSWORD@|${ESL_PASSWORD}|g" \
      -e "s|@VCA_HOST@|${VCA_HOST}|g" \
      -e "s|@VCA_PORT@|${VCA_PORT}|g" \
      "$f" > "/etc/freeswitch/$(basename "$f")"
done

# 这两处内容是多行的, sed 不好处理, 用 python 直接替换
python3 - "$gateway_body" "$acl_nodes" "$ata_users" <<'PY'
import sys, pathlib
gateway, acl, ata = sys.argv[1], sys.argv[2], sys.argv[3]
for path, mark, value in (('/etc/freeswitch/gateway.xml', '@GATEWAY_BODY@', gateway),
                          ('/etc/freeswitch/freeswitch.xml', '@TRUNK_ACL_NODES@', acl),
                          ('/etc/freeswitch/directory.xml', '@ATA_USERS@', ata)):
    p = pathlib.Path(path)
    p.write_text(p.read_text(encoding='utf-8').replace(mark, value), encoding='utf-8')
PY

if [ -n "$ATA_LINE_USER" ]; then
  echo "freeswitch: 语音网关 LINE 口=${ATA_LINE_USER}(来电进 AI), PHONE 口=${ATA_PHONE_USER:-未配}(转人工)"
fi
if [ -n "$TRUNK_HOST" ]; then
  echo "freeswitch: 中继 ${TRUNK_NAME} → ${TRUNK_HOST} ($([ -n "$TRUNK_USER" ] && echo 注册式 || echo IP白名单式)), 放行来源: ${TRUNK_ACL:-无(电话进不来!)}"
fi

echo "freeswitch: EXTERNAL_IP=${EXTERNAL_IP} socket→${VCA_HOST}:${VCA_PORT}"
# -nf 不 fork(容器主进程); -nonat 不做 UPnP/NAT-PMP 探测; -c 控制台模式, 日志才会打到 stdout(docker logs 可见)。
# 控制台要读 stdin, 所以 docker-compose 里配了 stdin_open + tty, 否则读到 EOF 会直接退出。
exec freeswitch -nf -nonat -c \
  -conf /etc/freeswitch -log /var/log/freeswitch -db /var/lib/freeswitch/db \
  -recordings /recordings
