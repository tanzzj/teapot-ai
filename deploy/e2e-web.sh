#!/bin/bash
# 经 nginx 站点（Host 头）端到端验证：静态页 / SPA 回退 / 登录 / 鉴权接口 / AG-UI SSE 可达性
HOST='Host: teapot.teamer.com.cn'
BASE=http://127.0.0.1
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "PASS $1"; }
bad() { FAIL=$((FAIL+1)); echo "FAIL $1 : $2"; }

# 1. 静态首页
code=$(curl -s -o /tmp/idx.html -w '%{http_code}' -H "$HOST" $BASE/)
grep -q 'id="root"' /tmp/idx.html && [ "$code" = "200" ] && ok "01-index" || bad "01-index" "code=$code"

# 2. SPA 回退
code=$(curl -s -o /dev/null -w '%{http_code}' -H "$HOST" $BASE/agents)
[ "$code" = "200" ] && ok "02-spa-fallback" || bad "02-spa-fallback" "code=$code"

# 3. 登录（经 nginx → 后端）
LOGIN=$(curl -s -X POST $BASE/api/auth/login -H "$HOST" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Teapot@2026"}')
TOKEN=$(echo "$LOGIN" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] && ok "03-login-via-nginx" || bad "03-login-via-nginx" "$LOGIN"

# 4. 带 token 访问受保护接口
PRES=$(curl -s -H "$HOST" -H "Authorization: Bearer $TOKEN" $BASE/api/model/presets)
echo "$PRES" | grep -q '"code":0' && ok "04-presets-authed" || bad "04-presets-authed" "$PRES"

# 5. Agent 列表（前端首页数据源）
AGL=$(curl -s -H "$HOST" -H "Authorization: Bearer $TOKEN" "$BASE/api/agent/list?page=1&size=10")
echo "$AGL" | grep -q '"code":0' && ok "05-agent-list" || bad "05-agent-list" "$AGL"

# 6. AG-UI SSE 端点可达性（无模型 Key 时预期 RUN_ERROR 事件或 SSE 流，而非 nginx 5xx）
AGUI=$(curl -s --max-time 20 -X POST $BASE/agui/run/general-assistant \
  -H "$HOST" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"threadId":"e2e-web-test","runId":"e2e-1","messages":[{"id":"m1","role":"user","content":"hi"}]}')
if echo "$AGUI" | grep -qE 'RUN_FINISHED|RUN_ERROR|TEXT_MESSAGE_CONTENT'; then
  ok "06-agui-sse-reachable"
  echo "$AGUI" | grep -q 'RUN_ERROR' && echo "  (note) RUN_ERROR: $(echo "$AGUI" | grep RUN_ERROR | head -c 300)"
else
  bad "06-agui-sse-reachable" "$(echo "$AGUI" | head -c 200)"
fi

echo "===== E2E WEB: PASS=$PASS FAIL=$FAIL ====="
[ "$FAIL" = "0" ] && echo "ALL E2E PASSED"
