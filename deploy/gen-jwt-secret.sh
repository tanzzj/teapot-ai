#!/usr/bin/env bash
# 为 /main/apps/teapot-ai/app.env 追加 RBAC_JWT_SECRET（幂等：已存在则跳过）
set -e
ENV_FILE=/main/apps/teapot-ai/app.env
if grep -q '^RBAC_JWT_SECRET=' "$ENV_FILE" 2>/dev/null; then
  echo "RBAC_JWT_SECRET already exists, skip"
else
  SECRET=$(openssl rand -hex 32)
  echo "RBAC_JWT_SECRET=$SECRET" >> "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  echo "RBAC_JWT_SECRET appended to $ENV_FILE"
fi
