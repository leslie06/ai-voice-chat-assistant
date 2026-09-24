#!/usr/bin/env bash
# 每天备份一次: VCA 的数据库 + 电话侧的配置(网关分机密码只存在服务器上, 丢了就得去每家店重配网关)。
# 由 cron 调用(见 install.sh)。失败会推群。
#
# 备份放在本机 BACKUP_DIR, 保留 BACKUP_KEEP_DAYS 天。本机备份防的是误删、改坏、升级把表弄坏;
# 防不了整块盘坏 —— 那个靠阿里云控制台给云盘开"自动快照"(按天, 保留 7 天), 两者互补。
set -uo pipefail
# shellcheck source=lib.sh
. "$(dirname "$(readlink -f "$0")")/lib.sh"

: "${BACKUP_DIR:=/var/backups/vca}"
: "${BACKUP_KEEP_DAYS:=7}"

fail() {
  push "【告警】每日备份失败 —— $1"
  exit 1
}

umask 077
mkdir -p "$BACKUP_DIR" || fail "建不了目录 $BACKUP_DIR"
stamp=$(date +%F)

command -v mysqldump >/dev/null || fail "服务器上没有 mysqldump(apt install mysql-client)"
load_db_params
out="$BACKUP_DIR/vca-db-$stamp.sql.gz"
# --single-transaction: InnoDB 一致性快照, 不锁表, 备份期间电话照常落库
if ! MYSQL_PWD="$DB_PASS" mysqldump -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" \
      --single-transaction --no-tablespaces --routines --default-character-set=utf8mb4 "$DB_NAME" \
      2> "$BACKUP_DIR/.mysqldump.err" | gzip > "$out.tmp"; then
  rm -f "$out.tmp"
  fail "mysqldump 出错: $(head -c 200 "$BACKUP_DIR/.mysqldump.err")"
fi
# 管道里 mysqldump 失败时 gzip 照样成功, 上面的判断兜不住; 再看一眼导出是不是完整的
if ! gzip -dc "$out.tmp" | tail -1 | grep -q "Dump completed"; then
  rm -f "$out.tmp"
  fail "数据库导出不完整: $(head -c 200 "$BACKUP_DIR/.mysqldump.err")"
fi
mv "$out.tmp" "$out"

# 电话侧配置: 各店网关分机(gateways/)、FreeSWITCH 密钥(.env)、VCA 配置。都是小文件
conf="$BACKUP_DIR/vca-conf-$stamp.tgz"
tar czf "$conf" --ignore-failed-read \
    -C / "${VCA_ENV_FILE#/}" "${FS_DIR#/}/.env" "${FS_DIR#/}/gateways" "${OPS_CONF#/}" 2>/dev/null \
  || fail "打包配置失败"

find "$BACKUP_DIR" -maxdepth 1 -name 'vca-*' -mtime +"$BACKUP_KEEP_DAYS" -delete
echo "$(date '+%F %T') 备份完成: $(du -h "$out" | cut -f1) 数据库, $(du -h "$conf" | cut -f1) 配置"
