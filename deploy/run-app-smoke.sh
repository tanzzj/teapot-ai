#!/usr/bin/env bash
# 仅用于紧急/调试：临时以 nohup 启动 teapot-ai（prod profile）。
# 生产环境已由 systemd 接管（deploy/teapot-ai.service），优先用 systemctl restart teapot-ai。
set -e
cd /main/apps/teapot-ai
mkdir -p logs
# 用完整路径匹配，避免误杀其他同样以 app.jar 命名的进程（如 rising-sun）
pkill -f '/main/apps/teapot-ai/app.jar' 2>/dev/null || true
sleep 2
set -a
source ./app.env
set +a
nohup /opt/rising-sun/jdk21/bin/java -Xms256m -Xmx1024m -jar /main/apps/teapot-ai/app.jar --spring.profiles.active=prod > logs/startup.out 2>&1 &
echo "started pid=$!"
