#!/usr/bin/env bash
# 乱码排查：对比 DB 存储字节 vs HTTP 响应原始字节（base64 传输，避开终端转码）
cd /main/apps/teapot-ai
set -a; source ./app.env; set +a
CONTAINER=$(docker ps --format '{{.Names}}' | grep -i mysql | head -1)
echo "== container: $CONTAINER =="
echo "== DB: HEX(real_name) admin =="
docker exec "$CONTAINER" mysql -uteapot_ai -p"$TEAPOT_AI_DB_PASSWORD" --default-character-set=utf8mb4 teapot_ai -e "SELECT HEX(real_name), HEX(username) FROM t_user WHERE user_id='admin'" 2>&1 | grep -v Warning
echo "== DB: server charset =="
docker exec "$CONTAINER" mysql -uteapot_ai -p"$TEAPOT_AI_DB_PASSWORD" -e "SHOW VARIABLES LIKE 'character_set%'" 2>&1 | grep -v Warning
echo "== HTTP: profile resp base64 =="
TOKEN=$(curl -s -X POST http://127.0.0.1:9126/api/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"Teapot@2026"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
curl -s http://127.0.0.1:9126/api/user/profile -H "Authorization: Bearer $TOKEN" | base64 -w0; echo
