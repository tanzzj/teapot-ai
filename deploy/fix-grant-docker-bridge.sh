#!/bin/bash
# 补充授权：宿主机经 127.0.0.1:3306 映射进容器时，MySQL 侧来源为 docker 网桥 172.17.0.1
set -e
source /main/apps/teapot-ai/mysql.env
source /main/apps/teapot-ai/app.env
docker exec -i teapot-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" 2>/dev/null <<SQL
CREATE USER IF NOT EXISTS 'teapot_ai'@'172.17.0.1' IDENTIFIED BY '$TEAPOT_AI_DB_PASSWORD';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES ON teapot_ai.*  TO 'teapot_ai'@'172.17.0.1';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP,REFERENCES ON agentscope.* TO 'teapot_ai'@'172.17.0.1';
FLUSH PRIVILEGES;
SQL
echo "grant 172.17.0.1 ok"
