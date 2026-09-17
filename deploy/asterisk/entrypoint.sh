#!/bin/sh
# 把 /conf 里的模板渲染进 /etc/asterisk。Asterisk 的配置文件不认环境变量, 所以这里用 sed 替换占位符。
set -eu

: "${EXTERNAL_IP:=127.0.0.1}"
: "${SIP_PASSWORD:?必须设置 SIP_PASSWORD}"
: "${VCA_HOST:=host.docker.internal}"
: "${VCA_PORT:=9092}"

for f in /conf/*.conf; do
  sed -e "s|@EXTERNAL_IP@|${EXTERNAL_IP}|g" \
      -e "s|@SIP_PASSWORD@|${SIP_PASSWORD}|g" \
      -e "s|@VCA_HOST@|${VCA_HOST}|g" \
      -e "s|@VCA_PORT@|${VCA_PORT}|g" \
      "$f" > "/etc/asterisk/$(basename "$f")"
done

mkdir -p /var/spool/asterisk/monitor
echo "asterisk: EXTERNAL_IP=${EXTERNAL_IP} AudioSocket→${VCA_HOST}:${VCA_PORT}"
# -f 前台运行(容器主进程); -vvv 把通话过程打到 docker logs
exec asterisk -f -vvv
