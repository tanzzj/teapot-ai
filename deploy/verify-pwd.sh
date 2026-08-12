#!/bin/bash
# 改密链路验证（无 python3 依赖，grep 提取 code）
BASE=http://127.0.0.1:9126
AH='Content-Type: application/json'
code() { grep -o '"code":[0-9-]*' | head -1; }
msg() { grep -o '"message":"[^"]*"' | head -1; }

# 前端 zip 补落库（unzip 对反斜杠 zip 警告导致上轮 && 链断开，解压本身已成功）
cd /main/apps/teapot-ai
if [ -f dist.zip.new ]; then
  mv -f dist.zip.new dist.zip
  echo "frontend zip finalized"
fi
ls -l ui/index.html | awk '{print "ui/index.html:", $6, $7, $8}'

T=$(curl -s -X POST $BASE/api/auth/login -H "$AH" \
  -d '{"username":"admin","password":"Teapot@2026"}' \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
echo "admin token: ${T:0:16}..."

curl -s -X DELETE $BASE/api/user/pwdtest -H "Authorization: Bearer $T" > /dev/null 2>&1 || true
source /main/apps/teapot-ai/app.env
echo "DELETE FROM teapot_ai.t_user WHERE user_id='pwdtest';" | \
  docker exec -i teapot-mysql mysql -uteapot_ai -p"$TEAPOT_AI_DB_PASSWORD" --default-character-set=utf8mb4 2>/dev/null || true

echo -n "create: "; R=$(curl -s -X POST $BASE/api/user/create -H "$AH" -H "Authorization: Bearer $T" \
  -d '{"userId":"pwdtest","username":"pwdtest","password":"OldPwd@2026","realName":"Pwd Test","roles":"viewer"}'); echo "$R" | code; echo "$R" | msg

echo -n "update-newPassword: "; R=$(curl -s -X PUT $BASE/api/user/pwdtest -H "$AH" -H "Authorization: Bearer $T" \
  -d '{"newPassword":"NewPwd@2026"}'); echo "$R" | code; echo "$R" | msg

echo -n "login-newpwd: "; curl -s -X POST $BASE/api/auth/login -H "$AH" \
  -d '{"username":"pwdtest","password":"NewPwd@2026"}' | code

echo -n "login-oldpwd(expect -1): "; curl -s -X POST $BASE/api/auth/login -H "$AH" \
  -d '{"username":"pwdtest","password":"Teapot@2026"}' | code

echo -n "empty-patch(expect 0): "; R=$(curl -s -X PUT $BASE/api/user/pwdtest -H "$AH" -H "Authorization: Bearer $T" \
  -d '{}'); echo "$R" | code; echo "$R" | msg

curl -s -X DELETE $BASE/api/user/pwdtest -H "Authorization: Bearer $T" > /dev/null
echo "DELETE FROM teapot_ai.t_user WHERE user_id='pwdtest';" | \
  docker exec -i teapot-mysql mysql -uteapot_ai -p"$TEAPOT_AI_DB_PASSWORD" --default-character-set=utf8mb4 2>/dev/null || true
echo "VERIFY DONE"
