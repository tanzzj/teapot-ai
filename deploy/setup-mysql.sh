#!/bin/bash
# ============================================================
# Teapot AI — MySQL 8.4 LTS 容器初始化脚本（SPEC v1.8 §11.3）
# 幂等：重复执行安全；密码仅生成一次，存 /main/apps/teapot-ai/*.env（chmod 600）
# ============================================================
set -e

APP_DIR=/main/apps/teapot-ai
DATA_DIR=/main/mysql84/data
mkdir -p "$APP_DIR" "$DATA_DIR"

# ---------- 1. root 密码（首次生成） ----------
if [ ! -f "$APP_DIR/mysql.env" ]; then
  printf 'MYSQL_ROOT_PASSWORD=%s\n' "$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)" > "$APP_DIR/mysql.env"
  chmod 600 "$APP_DIR/mysql.env"
  echo "[1/5] root 密码已生成 -> $APP_DIR/mysql.env"
else
  echo "[1/5] root 密码已存在，跳过生成"
fi

# ---------- 2. 启动容器 ----------
if docker ps -a --format '{{.Names}}' | grep -q '^teapot-mysql$'; then
  docker start teapot-mysql >/dev/null 2>&1 || true
  echo "[2/5] 容器已存在，确保运行中"
else
  docker run -d --name teapot-mysql --restart unless-stopped \
    -p 3306:3306 \
    -v "$DATA_DIR":/var/lib/mysql \
    --env-file "$APP_DIR/mysql.env" \
    -e TZ=Asia/Shanghai \
    mysql:8.4 \
    --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci \
    --innodb-buffer-pool-size=512M
  echo "[2/5] 容器已创建"
fi

# ---------- 3. 等待就绪 ----------
for i in $(seq 1 60); do
  if docker exec teapot-mysql mysqladmin ping -h127.0.0.1 --silent >/dev/null 2>&1; then
    echo "[3/5] MySQL 已就绪（等待 ${i}x2s）"
    break
  fi
  sleep 2
  if [ "$i" = "60" ]; then echo "[3/5] 等待超时，请检查 docker logs teapot-mysql"; exit 1; fi
done

# ---------- 4. 建库建号（幂等） ----------
source "$APP_DIR/mysql.env"
if [ ! -f "$APP_DIR/app.env" ]; then
  printf 'TEAPOT_AI_DB_PASSWORD=%s\n' "$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-20)" > "$APP_DIR/app.env"
  chmod 600 "$APP_DIR/app.env"
fi
source "$APP_DIR/app.env"

docker exec -i teapot-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" 2>/dev/null <<SQL
CREATE DATABASE IF NOT EXISTS teapot_ai  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS agentscope DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'teapot_ai'@'localhost' IDENTIFIED BY '$TEAPOT_AI_DB_PASSWORD';
CREATE USER IF NOT EXISTS 'teapot_ai'@'127.0.0.1' IDENTIFIED BY '$TEAPOT_AI_DB_PASSWORD';
-- 宿主机经 127.0.0.1:3306 映射进容器时，MySQL 侧来源为 docker 网桥 IP
CREATE USER IF NOT EXISTS 'teapot_ai'@'172.17.0.1' IDENTIFIED BY '$TEAPOT_AI_DB_PASSWORD';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES ON teapot_ai.*  TO 'teapot_ai'@'localhost';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES ON teapot_ai.*  TO 'teapot_ai'@'127.0.0.1';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES ON teapot_ai.*  TO 'teapot_ai'@'172.17.0.1';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES ON agentscope.* TO 'teapot_ai'@'localhost';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES ON agentscope.* TO 'teapot_ai'@'127.0.0.1';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES ON agentscope.* TO 'teapot_ai'@'172.17.0.1';
FLUSH PRIVILEGES;
SQL
echo "[4/5] 建库建号完成（teapot_ai / agentscope）"

# ---------- 5. 连接验证 ----------
docker exec teapot-mysql mysql -uteapot_ai -p"$TEAPOT_AI_DB_PASSWORD" -h127.0.0.1 -e "SHOW DATABASES;" 2>/dev/null
docker exec teapot-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SELECT VERSION();" 2>/dev/null
echo "[5/5] 验证通过"
