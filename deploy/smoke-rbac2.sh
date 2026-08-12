#!/usr/bin/env bash
# M1 RBAC 补充冒烟：用户 CRUD、viewer 越权拒绝、登录失败锁定
BASE=http://127.0.0.1:9126
LOGIN=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"username":"admin","password":"Teapot@2026"}')
TOKEN=$(echo "$LOGIN" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
AUTH="Authorization: Bearer $TOKEN"

echo "== 7. create viewer user (expect code=0) =="
curl -s -X POST "$BASE/api/user/create" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"userId":"viewer01","username":"viewer01","password":"Viewer@2026","realName":"只读测试","roles":"viewer"}'; echo

echo "== 8. viewer login (expect code=0) =="
VLOGIN=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"username":"viewer01","password":"Viewer@2026"}')
echo "$VLOGIN" | head -c 200; echo
VTOKEN=$(echo "$VLOGIN" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

echo "== 9. viewer /api/user/list (expect code=403) =="
curl -s "$BASE/api/user/list" -H "Authorization: Bearer $VTOKEN"; echo

echo "== 10. viewer /api/user/profile (expect code=0) =="
curl -s "$BASE/api/user/profile" -H "Authorization: Bearer $VTOKEN" | head -c 300; echo

echo "== 11. update viewer01 realName (expect code=0) =="
curl -s -X PUT "$BASE/api/user/viewer01" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"realName":"只读测试-改"}' | head -c 300; echo

echo "== 12. lock: 5 wrong logins then locked (expect 401 x5 then lock msg) =="
for i in 1 2 3 4 5; do
  curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"username":"viewer01","password":"bad"}' -o /dev/null
done
curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"username":"viewer01","password":"Viewer@2026"}'; echo

echo "== 13. disable viewer01 then login (expect code=0 then 401) =="
curl -s -X DELETE "$BASE/api/user/viewer01" -H "$AUTH"; echo
curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"username":"viewer01","password":"Viewer@2026"}'; echo
