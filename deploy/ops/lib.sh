#!/usr/bin/env bash
# vca-watch.sh / vca-backup.sh 共用: 读配置、推群消息。被 source, 不单独执行。

# 运维脚本自己的配置(推送地址、检查阈值)。模板见同目录 vca-watch.env.example
OPS_CONF=${VCA_OPS_CONF:-/etc/vca-watch.env}
if [ -f "$OPS_CONF" ]; then
  # shellcheck disable=SC1090
  . "$OPS_CONF"
fi
: "${ALERT_WEBHOOK:=}"                       # 企业微信/钉钉群机器人地址; 空 = 只写日志不推送
: "${SITE_NAME:=$(uname -n)}"                # 消息里怎么称呼这台机器
: "${VCA_ENV_FILE:=/etc/vca.env}"            # VCA 的配置(取数据库账号)
: "${FS_DIR:=/opt/vca/freeswitch}"           # FreeSWITCH 编排目录(.env、gateways/、recordings/)
: "${FS_CONTAINER:=vca-freeswitch}"
: "${STATE_DIR:=/var/lib/vca-ops}"

mkdir -p "$STATE_DIR"

# 从 KEY=VALUE 文件取一个值。<b>不能 source</b> /etc/vca.env: 那是 systemd 的 EnvironmentFile,
# 值不加引号, 数据库 URL 里的 & 会被 shell 当成后台执行符
env_val() {
  local file=$1 key=$2 v
  v=$(grep -E "^${key}=" "$file" 2>/dev/null | tail -1 | cut -d= -f2-)
  v=${v%\"}; v=${v#\"}; v=${v%\'}; v=${v#\'}
  printf '%s' "$v"
}

# 推一条文本消息到群里。企业微信和钉钉都认 {"msgtype":"text","text":{"content":...}}
# (钉钉机器人若设了"自定义关键词", 关键词要出现在消息里, 可以把它写进 SITE_NAME)
push() {
  local text="[$SITE_NAME] $1"
  echo "$(date '+%F %T') $text"
  [ -n "$ALERT_WEBHOOK" ] || return 0
  local body
  body=$(TEXT="$text" python3 -c 'import json,os;print(json.dumps({"msgtype":"text","text":{"content":os.environ["TEXT"]}},ensure_ascii=False))')
  curl -sS -m 10 -H 'Content-Type: application/json' -d "$body" "$ALERT_WEBHOOK" >/dev/null \
    || echo "$(date '+%F %T') 推送失败: $ALERT_WEBHOOK" >&2
}

# 在 FreeSWITCH 里执行一条命令
fs_cli_x() {
  docker exec "$FS_CONTAINER" fs_cli -p "$(env_val "$FS_DIR/.env" ESL_PASSWORD)" -x "$1" 2>/dev/null
}

# 解析 VCA 的数据库连接, 设置 DB_HOST/DB_PORT/DB_USER/DB_NAME/DB_PASS。
# URL 形如 jdbc:mysql://host:port/db?参数; 没配时与 application.yml 的默认值一致
load_db_params() {
  local url
  url=$(env_val "$VCA_ENV_FILE" VCA_STORE_URL)
  url=${url:-jdbc:mysql://localhost:3306/vca}
  DB_HOST=$(printf '%s' "$url" | sed -E 's#^jdbc:mysql://([^:/?]+).*#\1#')
  DB_PORT=$(printf '%s' "$url" | sed -nE 's#^jdbc:mysql://[^:/?]+:([0-9]+).*#\1#p')
  DB_NAME=$(printf '%s' "$url" | sed -E 's#^jdbc:mysql://[^/]+/([^?]+).*#\1#')
  DB_USER=$(env_val "$VCA_ENV_FILE" VCA_STORE_USERNAME)
  DB_PASS=$(env_val "$VCA_ENV_FILE" VCA_STORE_PASSWORD)
  [ "$DB_HOST" = "localhost" ] && DB_HOST=127.0.0.1   # localhost 会走 socket 文件, 统一走 TCP
  DB_PORT=${DB_PORT:-3306}
  DB_USER=${DB_USER:-root}
}

# 连 VCA 的库执行一条 SQL, 无表头、制表符分隔。库连不上返回非 0
vca_sql() {
  load_db_params
  MYSQL_PWD="$DB_PASS" mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -N -B "$DB_NAME" -e "$1"
}
