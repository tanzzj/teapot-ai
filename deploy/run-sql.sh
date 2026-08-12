#!/bin/bash
# 在 teapot-mysql 容器内以 teapot_ai 账号执行 SQL 文件（密码取自 app.env，不回显）
# 必须显式 utf8mb4：客户端默认 latin1 会导致中文双重编码入库
set -e
source /main/apps/teapot-ai/app.env
sed -i 's/\r$//' "$1"
docker exec -i teapot-mysql mysql -uteapot_ai -p"$TEAPOT_AI_DB_PASSWORD" --default-character-set=utf8mb4 < "$1"
echo "OK: $1 executed"
