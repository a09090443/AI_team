#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"
for file in deploy/bootstrap/*.sh; do bash -n "$file"; done
bash deploy/bootstrap/test-linux-platform.sh
test -s orchestrator/teams/spring-starters.json
test -s orchestrator/teams/node-web.json
# 下列固定列表僅檢查 legacy 相容資產；動態 roles/nodes 由 TeamCatalog 載入驗證。
for role in pm pm_triage architecture backend qa qa_plan code_review direct; do
 test -s "orchestrator/prompts/$role.md" || { echo "缺少 $role prompt"; exit 1; }
done
for schema in task triage adr impl qa qa-plan review direct; do
 test -s "orchestrator/schemas/$schema-schema.json" || { echo "缺少 $schema schema"; exit 1; }
done
for role in PM ARCHITECTURE BACKEND QA CODE_REVIEW; do
 for prefix in SLACK_BOT_TOKEN SLACK_APP_TOKEN AI_TEAM_MODEL AI_TEAM_RUNTIME; do
  grep -q "^export ${prefix}_${role}=" deploy/env.sh.template || { echo "設定範本缺少 ${prefix}_${role}"; exit 1; }
 done
done
for role in pm architecture backend qa code-review; do test -s "deploy/agent-manifests/$role.yaml"; done
echo 'PASS：Bash 語法、管線與角色對話 prompt/schema、五角色設定／manifest 齊全。'
