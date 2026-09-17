#!/bin/sh
# 把 /conf 里的模板渲染进 /etc/freeswitch, 再前台启动。
# 用 sed 而不是 FreeSWITCH 自己的 $${var}: 密码这类值不该出现在任何能被 "global_getvar" 读出来的地方。
set -eu

: "${EXTERNAL_IP:=127.0.0.1}"
: "${SIP_PASSWORD:?必须设置 SIP_PASSWORD}"
: "${ESL_PASSWORD:?必须设置 ESL_PASSWORD}"
: "${VCA_HOST:=host.docker.internal}"
: "${VCA_PORT:=8084}"

mkdir -p /etc/freeswitch /var/lib/freeswitch/db /var/log/freeswitch /recordings
for f in /conf/*.xml; do
  sed -e "s|@EXTERNAL_IP@|${EXTERNAL_IP}|g" \
      -e "s|@SIP_PASSWORD@|${SIP_PASSWORD}|g" \
      -e "s|@ESL_PASSWORD@|${ESL_PASSWORD}|g" \
      -e "s|@VCA_HOST@|${VCA_HOST}|g" \
      -e "s|@VCA_PORT@|${VCA_PORT}|g" \
      "$f" > "/etc/freeswitch/$(basename "$f")"
done

echo "freeswitch: EXTERNAL_IP=${EXTERNAL_IP} socket→${VCA_HOST}:${VCA_PORT}"
# -nf 不 fork(容器主进程); -nonat 不做 UPnP/NAT-PMP 探测; -c 控制台模式, 日志才会打到 stdout(docker logs 可见)。
# 控制台要读 stdin, 所以 docker-compose 里配了 stdin_open + tty, 否则读到 EOF 会直接退出。
exec freeswitch -nf -nonat -c \
  -conf /etc/freeswitch -log /var/log/freeswitch -db /var/lib/freeswitch/db \
  -recordings /recordings
