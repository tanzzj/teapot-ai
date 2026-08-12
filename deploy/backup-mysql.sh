#!/bin/bash
# ============================================================
# Teapot AI — MySQL 每日备份（SPEC §11.6）
# crontab -e 增加：0 3 * * * /main/apps/teapot-ai/backup-mysql.sh >> /main/apps/teapot-ai/logs/backup.log 2>&1
# 保留 7 份；磁盘水位超过 90% 时优先清理备份与日志
# ============================================================
set -e

source /main/apps/teapot-ai/mysql.env     # MYSQL_ROOT_PASSWORD（chmod 600）
DATE=$(date +%F)
DIR=/main/backup/teapot-ai
mkdir -p "$DIR"

docker exec teapot-mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" \
  --databases teapot_ai agentscope \
  --single-transaction --routines --triggers > "$DIR/$DATE.sql"

# 保留最近 7 份
find "$DIR" -name '*.sql' -mtime +7 -delete

echo "[$(date '+%F %T')] backup ok -> $DIR/$DATE.sql ($(du -h "$DIR/$DATE.sql" | cut -f1))"
