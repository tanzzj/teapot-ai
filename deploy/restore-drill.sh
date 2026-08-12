#!/bin/bash
# 备份恢复演练：将最新 dump 改库名恢复到临时库，校验表数量后清理（SPEC §15 M5 验收）
set -e
source /main/apps/teapot-ai/mysql.env
DUMP=$(ls -t /main/backup/teapot-ai/*.sql | head -1)
echo "dump: $DUMP"

sed -e 's/`teapot_ai`/`teapot_ai_restore`/g' -e 's/`agentscope`/`agentscope_restore`/g' "$DUMP" > /tmp/restore-drill.sql

docker exec -i teapot-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 < /tmp/restore-drill.sql
echo "--- restored tables ---"
docker exec teapot-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e \
  "SELECT 'teapot_ai_restore' db, COUNT(*) tables_count FROM information_schema.tables WHERE table_schema='teapot_ai_restore' UNION ALL SELECT 'agentscope_restore', COUNT(*) FROM information_schema.tables WHERE table_schema='agentscope_restore';" 2>/dev/null
docker exec teapot-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e \
  "SELECT username,roles,status FROM teapot_ai_restore.t_user LIMIT 3;" 2>/dev/null

echo "--- cleanup temp dbs ---"
docker exec teapot-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE teapot_ai_restore; DROP DATABASE agentscope_restore;" 2>/dev/null
rm -f /tmp/restore-drill.sql
echo "RESTORE DRILL OK"
