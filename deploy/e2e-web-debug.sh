#!/bin/bash
# 调试：AG-UI 实际返回内容 + 登录响应密码脱敏检查
HOST='Host: teapot.teamer.com.cn'
BASE=http://127.0.0.1

LOGIN=$(curl -s -X POST $BASE/api/auth/login -H "$HOST" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Teapot@2026"}')
TOKEN=$(echo "$LOGIN" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

echo "--- login user object (password leak check) ---"
echo "$LOGIN" | grep -o '"user":{[^}]*}' | head -c 400
echo
if echo "$LOGIN" | grep -q '"password"'; then
  echo "WARN: login response contains password field"
else
  echo "OK: no password field in login response"
fi

echo "--- profile leak check ---"
PROF=$(curl -s -H "$HOST" -H "Authorization: Bearer $TOKEN" $BASE/api/user/profile)
echo "$PROF" | head -c 300
echo
echo "$PROF" | grep -q '"password"' && echo "WARN: profile contains password field" || echo "OK: profile clean"

echo "--- user list leak check ---"
UL=$(curl -s -H "$HOST" -H "Authorization: Bearer $TOKEN" "$BASE/api/user/list?page=1&size=5")
echo "$UL" | grep -q '"password"' && echo "WARN: user list contains password field" || echo "OK: user list clean"

echo "--- agui raw response ---"
curl -s --max-time 25 -X POST $BASE/agui/run/general-assistant \
  -H "$HOST" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"threadId":"e2e-dbg2","runId":"e2e-3","messages":[{"id":"m1","role":"user","content":"hi"}]}' | head -c 600
echo
