#!/bin/bash
# 部署密码修复版：替换 jar（若 .new 存在）+ 重启 + 前端解压 + 改密链路验证
set -u
cd /main/apps/teapot-ai

# 1. 后端：替换并重启
if [ -f app.jar.new ]; then
  mv -f app.jar.new app.jar
  echo "jar replaced"
fi
systemctl restart teapot-ai
echo "restarted"

# 2. 前端：覆盖解压
if [ -f dist.zip.new ]; then
  unzip -oq dist.zip.new -d ui/ && mv -f dist.zip.new dist.zip && echo "frontend updated"
fi

# 3. 等待健康
H=0
for i in $(seq 1 20); do
  H=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:9126/actuator/health)
  if [ "$H" = 200 ]; then echo "HEALTH_OK"; break; fi
  sleep 3
done
if [ "$H" != 200 ]; then echo "HEALTH_FAIL code=$H"; exit 1; fi

# 4. 改密链路验证
BASE=http://127.0.0.1:9126
AH='Content-Type: application/json'
T=$(curl -s -X POST $BASE/api/auth/login -H "$AH" \
  -d '{"username":"admin","password":"Teapot@2026"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")

# 预清理 pwdtest（软删不复活，物理删除）
curl -s -X DELETE $BASE/api/user/pwdtest -H "Authorization: Bearer $T" > /dev/null 2>&1 || true
source /main/apps/teapot-ai/app.env
echo "DELETE FROM teapot_ai.t_user WHERE user_id='pwdtest';" | \
  docker exec -i teapot-mysql mysql -uteapot_ai -p"$TEAPOT_AI_DB_PASSWORD" --default-character-set=utf8mb4 2>/dev/null || true

R=$(curl -s -X POST $BASE/api/user/create -H "$AH" -H "Authorization: Bearer $T" \
  -d '{"userId":"pwdtest","username":"pwdtest","realName":"Pwd Test","roles":"viewer"}')
echo "create: $(echo "$R" | python3 -c "import sys,json;print('code',json.load(sys.stdin)['code'])")"

R=$(curl -s -X PUT $BASE/api/user/pwdtest -H "$AH" -H "Authorization: Bearer $T" \
  -d '{"newPassword":"NewPwd@2026"}')
echo "update-newPassword: $(echo "$R" | python3 -c "import sys,json;print('code',json.load(sys.stdin)['code'])")"

R=$(curl -s -X POST $BASE/api/auth/login -H "$AH" \
  -d '{"username":"pwdtest","password":"NewPwd@2026"}')
echo "login-newpwd: $(echo "$R" | python3 -c "import sys,json;print('code',json.load(sys.stdin)['code'])")"

R=$(curl -s -X PUT $BASE/api/user/pwdtest -H "$AH" -H "Authorization: Bearer $T" -d '{}')
echo "empty-patch: $(echo "$R" | python3 -c "import sys,json;print('code',json.load(sys.stdin)['code'])")"

# 清理
curl -s -X DELETE $BASE/api/user/pwdtest -H "Authorization: Bearer $T" > /dev/null
echo "DELETE FROM teapot_ai.t_user WHERE user_id='pwdtest';" | \
  docker exec -i teapot-mysql mysql -uteapot_ai -p"$TEAPOT_AI_DB_PASSWORD" --default-character-set=utf8mb4 2>/dev/null || true
echo "DONE"
