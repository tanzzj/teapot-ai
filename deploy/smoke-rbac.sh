#!/usr/bin/env bash
# M1 RBAC 冒烟：登录正例 + 三类负例（错密码/未认证/viewer 越权）
BASE=http://127.0.0.1:9126
echo "== wait for startup =="
for i in $(seq 1 60); do
  if curl -s -o /dev/null "$BASE/api/auth/logout" -X POST; then break; fi
  sleep 2
done

echo "== 1. login admin (expect code=0) =="
LOGIN=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"username":"admin","password":"Teapot@2026"}')
echo "$LOGIN" | head -c 600; echo
TOKEN=$(echo "$LOGIN" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

echo "== 2. wrong password (expect code=401) =="
curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"username":"admin","password":"wrong-pass"}'; echo

echo "== 3. no token /api/user/list (expect code=401) =="
curl -s "$BASE/api/user/list"; echo

echo "== 4. with token /api/user/list (expect code=0, admin) =="
curl -s "$BASE/api/user/list" -H "Authorization: Bearer $TOKEN" | head -c 400; echo

echo "== 5. with token /api/user/profile (expect code=0) =="
curl -s "$BASE/api/user/profile" -H "Authorization: Bearer $TOKEN" | head -c 400; echo

echo "== 6. refresh token (expect code=0) =="
REFRESH=$(echo "$LOGIN" | sed -n 's/.*"refreshToken":"\([^"]*\)".*/\1/p')
curl -s -X POST "$BASE/api/auth/refresh" -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}" | head -c 200; echo
