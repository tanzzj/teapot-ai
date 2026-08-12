#!/bin/bash
# 模型入口功能部署：V3 SQL → 替换 jar → 重启 → 前端 → 验证
set -u
cd /main/apps/teapot-ai
source /main/apps/teapot-ai/app.env
MYSQL="docker exec -i teapot-mysql mysql -uteapot_ai -p$TEAPOT_AI_DB_PASSWORD --default-character-set=utf8mb4"

# 1. 建表 + 种子（幂等）
$MYSQL < /main/apps/teapot-ai/V3__model_entry.sql && echo "SQL_OK" || { echo "SQL_FAIL"; exit 1; }

# 2. 后端
if [ -f app.jar.new ]; then mv -f app.jar.new app.jar; echo "jar replaced"; fi
systemctl restart teapot-ai

# 3. 前端（unzip 对反斜杠 zip 警告 exit 1 但解压成功，不用 && 链）
if [ -f dist.zip.new ]; then
  rm -rf ui/assets
  unzip -oq dist.zip.new -d ui/ || true
  mv -f dist.zip.new dist.zip
  echo "frontend updated"
fi

# 4. 健康
H=0
for i in $(seq 1 20); do
  H=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:9126/actuator/health)
  if [ "$H" = 200 ]; then echo "HEALTH_OK"; break; fi
  sleep 3
done
[ "$H" != 200 ] && { echo "HEALTH_FAIL code=$H"; exit 1; }

# 5. 验证
BASE=http://127.0.0.1:9126
AH='Content-Type: application/json'
code() { grep -o '"code":[0-9-]*' | head -1; }
msg() { grep -o '"message":"[^"]*"' | head -1; }

T=$(curl -s -X POST $BASE/api/auth/login -H "$AH" \
  -d '{"username":"admin","password":"Teapot@2026"}' \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

echo -n "presets(期望3个dashscope): "; curl -s $BASE/api/model/presets -H "Authorization: Bearer $T" \
  | grep -o 'dashscope:[a-z-]*' | tr '\n' ' '; echo

echo -n "create openai:fake-model: "; R=$(curl -s -X POST $BASE/api/model/create -H "$AH" -H "Authorization: Bearer $T" \
  -d '{"provider":"openai","modelName":"fake-model","displayName":"Fake","baseUrl":"https://api.fake.com/v1"}')
echo "$R" | code; echo "$R" | msg

FAKE_ID=$(echo "SELECT id FROM teapot_ai.t_model_entry WHERE provider='openai' AND model_name='fake-model';" | $MYSQL -N)
echo "fake id=$FAKE_ID"

echo -n "update displayName: "; curl -s -X PUT $BASE/api/model/$FAKE_ID -H "$AH" -H "Authorization: Bearer $T" \
  -d '{"displayName":"Fake v2"}' | code

echo -n "list 含 fake-model: "; curl -s $BASE/api/model/list -H "Authorization: Bearer $T" | grep -c 'fake-model'

# 非 admin 写拦截
curl -s -X DELETE $BASE/api/user/modelviewer -H "Authorization: Bearer $T" > /dev/null 2>&1 || true
echo "DELETE FROM teapot_ai.t_user WHERE user_id='modelviewer';" | $MYSQL 2>/dev/null || true
curl -s -X POST $BASE/api/user/create -H "$AH" -H "Authorization: Bearer $T" \
  -d '{"userId":"modelviewer","username":"modelviewer","password":"Viewer@2026","realName":"model-v","roles":"viewer"}' > /dev/null
VT=$(curl -s -X POST $BASE/api/auth/login -H "$AH" \
  -d '{"username":"modelviewer","password":"Viewer@2026"}' | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
echo -n "viewer create(期望-1): "; R=$(curl -s -X POST $BASE/api/model/create -H "$AH" -H "Authorization: Bearer $VT" \
  -d '{"provider":"openai","modelName":"x"}'); echo "$R" | code; echo "$R" | msg
echo -n "viewer presets(期望0可读): "; curl -s $BASE/api/model/presets -H "Authorization: Bearer $VT" | code

echo -n "delete fake-model: "; curl -s -X DELETE $BASE/api/model/$FAKE_ID -H "Authorization: Bearer $T" | code

# 清理 viewer
curl -s -X DELETE $BASE/api/user/modelviewer -H "Authorization: Bearer $T" > /dev/null
echo "DELETE FROM teapot_ai.t_user WHERE user_id='modelviewer';" | $MYSQL 2>/dev/null || true
echo "DEPLOY VERIFY DONE"
