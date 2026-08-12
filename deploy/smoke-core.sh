#!/bin/bash
# Teapot AI M2/M3 冒烟：Agent/Skill/会话/模型预设 CRUD + 权限
BASE=http://127.0.0.1:9126
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "PASS $1"; }
bad()  { FAIL=$((FAIL+1)); echo "FAIL $1 => $2"; }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "$3"; fi
}
code() { curl -s -o /tmp/resp.json -w '%{http_code}' "$@"; }
biz() { # name —— 断言 /tmp/resp.json 业务码 code=0
  if grep -q '"code":0' /tmp/resp.json; then ok "$1"; else bad "$1" "$(cat /tmp/resp.json)"; fi
}

# 1. health
check "01-health-200" "200" "$(code $BASE/actuator/health)"

# 2. admin 登录
TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Teapot@2026"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] && ok "02-admin-login" || bad "02-admin-login" "no-token"
AH="Authorization: Bearer $TOKEN"

# 3. 模型预设
code -H "$AH" $BASE/api/model/presets
biz "03-model-presets"
grep -q 'dashscope:qwen-plus' /tmp/resp.json && ok "03b-preset-content" || bad "03b-preset-content" "$(cat /tmp/resp.json)"

# 4. Agent 列表含种子
code -H "$AH" "$BASE/api/agent/list?page=1&size=20"
grep -q 'general-assistant' /tmp/resp.json && ok "04-agent-list" || bad "04-agent-list" "$(cat /tmp/resp.json)"

# 5. Agent 详情
code -H "$AH" $BASE/api/agent/detail/general-assistant
grep -q '"sysPrompt"' /tmp/resp.json && ok "05-agent-detail" || bad "05-agent-detail" "$(cat /tmp/resp.json)"

# 6. 创建 Agent（软删复活场景：先建→删→再建同 key）
code -X POST -H "$AH" -H 'Content-Type: application/json' $BASE/api/agent/create \
  -d '{"agentKey":"smoke-agent","name":"冒烟助手","description":"冒烟用","sysPrompt":"你是冒烟测试助手。","modelId":"dashscope:qwen-plus","skillNames":[]}'
biz "06-agent-create"
code -X DELETE -H "$AH" $BASE/api/agent/delete/smoke-agent
biz "07-agent-delete"
code -X POST -H "$AH" -H 'Content-Type: application/json' $BASE/api/agent/create \
  -d '{"agentKey":"smoke-agent","name":"冒烟助手2","description":"复活","sysPrompt":"你是复活后的助手。","modelId":"dashscope:qwen-plus","skillNames":[]}'
biz "08-agent-recreate"

# 7. AGENTS.md 落盘
[ -f /main/apps/teapot-ai/workspace/smoke-agent/AGENTS.md ] && ok "09-agents-md" || bad "09-agents-md" "missing"

# 8. Skill 保存 + 列表 + 详情回解析
code -X POST -H "$AH" -H 'Content-Type: application/json' $BASE/api/skill/save \
  -d '{"name":"smoke-skill","description":"冒烟技能：当用户提到冒烟时使用","instructions":"# 冒烟技能\n\n输出固定语：冒烟通过。","resources":[{"path":"references/note.md","content":"备注内容"}]}'
biz "10-skill-save"
code -H "$AH" $BASE/api/skill/list
grep -q 'smoke-skill' /tmp/resp.json && grep -q 'meeting-notes' /tmp/resp.json && ok "11-skill-list" || bad "11-skill-list" "$(cat /tmp/resp.json)"
code -H "$AH" $BASE/api/skill/detail/smoke-skill
grep -q '冒烟技能：当用户提到冒烟时使用' /tmp/resp.json && grep -q 'references/note.md' /tmp/resp.json \
  && ok "12-skill-detail" || bad "12-skill-detail" "$(cat /tmp/resp.json)"

# 9. Skill 预览（不落库）
code -X POST -H "$AH" -H 'Content-Type: application/json' $BASE/api/skill/preview \
  -d '{"name":"preview-skill","description":"预览用","instructions":"正文"}'
biz "13-skill-preview"
grep -q '\\nname: preview-skill' /tmp/resp.json && ok "13b-preview-frontmatter" || bad "13b-preview-frontmatter" "$(cat /tmp/resp.json)"

# 10. 绑定/解绑
code -X POST -H "$AH" -H 'Content-Type: application/json' $BASE/api/agent/bindSkill/smoke-agent -d '{"skillName":"smoke-skill"}'
biz "14-bind-skill"
code -H "$AH" $BASE/api/agent/detail/smoke-agent
grep -q 'smoke-skill' /tmp/resp.json && ok "15-bind-visible" || bad "15-bind-visible" "$(cat /tmp/resp.json)"
code -X POST -H "$AH" -H 'Content-Type: application/json' $BASE/api/agent/unbindSkill/smoke-agent -d '{"skillName":"smoke-skill"}'
biz "16-unbind-skill"

# 11. 会话：create/list/clear
code -X POST -H "$AH" -H 'Content-Type: application/json' $BASE/api/chat/session/create -d '{"agentKey":"smoke-agent","title":"冒烟会话"}'
SID=$(sed -n 's/.*"sessionId":"\([^"]*\)".*/\1/p' /tmp/resp.json)
[ -n "$SID" ] && ok "17-session-create" || bad "17-session-create" "$(cat /tmp/resp.json)"
code -H "$AH" "$BASE/api/chat/session/list?agentKey=smoke-agent"
grep -q '冒烟会话' /tmp/resp.json && ok "18-session-list" || bad "18-session-list" "$(cat /tmp/resp.json)"
code -X DELETE -H "$AH" $BASE/api/chat/session/clear/$SID
biz "19-session-clear"

# 12. Skill 删除级联解绑
code -X POST -H "$AH" -H 'Content-Type: application/json' $BASE/api/agent/bindSkill/smoke-agent -d '{"skillName":"smoke-skill"}'
biz "20-rebind"
code -X DELETE -H "$AH" $BASE/api/skill/delete/smoke-skill
biz "21-skill-delete"
code -H "$AH" $BASE/api/agent/detail/smoke-agent
grep -q '"skillNames":\[\]' /tmp/resp.json && ok "22-cascade-unbind" || bad "22-cascade-unbind" "$(cat /tmp/resp.json)"

# 13. viewer 越权创建 Agent 应 403（自建临时 viewer，先预清理保证幂等）
curl -s -X DELETE $BASE/api/user/smokeviewer -H "$AH" > /dev/null 2>&1 || true
# 停用为软删且 create 不复活，重跑冒烟需物理清理测试用户
if [ -f /main/apps/teapot-ai/app.env ]; then
  source /main/apps/teapot-ai/app.env
  echo "DELETE FROM teapot_ai.t_user WHERE user_id='smokeviewer';" | \
    docker exec -i teapot-mysql mysql -uteapot_ai -p"$TEAPOT_AI_DB_PASSWORD" --default-character-set=utf8mb4 2>/dev/null || true
fi
curl -s -X POST $BASE/api/user/create -H "$AH" -H 'Content-Type: application/json' \
  -d '{"userId":"smokeviewer","username":"smokeviewer","password":"Viewer@2026","realName":"冒烟只读","roles":"viewer"}' > /dev/null
VTOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"smokeviewer","password":"Viewer@2026"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
if [ -n "$VTOKEN" ]; then
  check "23-viewer-create-403" "403" "$(code -X POST -H "Authorization: Bearer $VTOKEN" -H 'Content-Type: application/json' $BASE/api/agent/create \
    -d '{"agentKey":"hack-agent","name":"x","sysPrompt":"x","modelId":"dashscope:qwen-plus"}')"
  check "24-viewer-detail-200" "200" "$(code -H "Authorization: Bearer $VTOKEN" $BASE/api/agent/detail/general-assistant)"
else
  bad "23-viewer-login" "smokeviewer 创建/登录失败，跳过越权项"
fi

# 14. 清理
curl -s -X DELETE $BASE/api/user/smokeviewer -H "$AH" > /dev/null
code -X DELETE -H "$AH" $BASE/api/agent/delete/smoke-agent
biz "25-cleanup-delete"

echo "=========================================="
echo "PASS=$PASS FAIL=$FAIL"
[ $FAIL -eq 0 ] && echo "ALL SMOKE PASSED" || echo "SMOKE HAS FAILURES"
