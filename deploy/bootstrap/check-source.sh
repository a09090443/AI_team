#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"
for file in deploy/bootstrap/*.sh deploy/sandbox-vm/*.sh; do bash -n "$file"; done
python3 -m unittest discover -s orchestrator/teams/skills/service-build/scripts -p 'test_*.py'
bash deploy/bootstrap/test-linux-platform.sh
bash deploy/bootstrap/test-stage-teams.sh
bash deploy/bootstrap/test-workspace-check.sh
# 內附的通用範例配置；各專案實例自己的配置放在實例 team-overlay，不在 repo。
for team in node-web maven-library gradle-fullstack; do test -s "orchestrator/teams/$team.json" || { echo "缺少範例配置 $team"; exit 1; }; done
# 下列固定列表僅檢查 legacy 相容資產；動態 roles/nodes 由 TeamCatalog 載入驗證。
for role in pm pm_triage architecture backend qa qa_plan code_review direct; do
 test -s "orchestrator/prompts/$role.md" || { echo "缺少 $role prompt"; exit 1; }
done
for schema in task triage adr impl qa qa-plan review direct; do
 test -s "orchestrator/schemas/$schema-schema.json" || { echo "缺少 $schema schema"; exit 1; }
done
for role in PM ARCHITECTURE BACKEND QA CODE_REVIEW; do
 for prefix in AI_TEAM_MODEL AI_TEAM_RUNTIME; do
  grep -q "^export ${prefix}_${role}=" deploy/env.sh.template || { echo "設定範本缺少 ${prefix}_${role}"; exit 1; }
 done
done
for role in PM ARCHITECTURE BACKEND QA CODE_REVIEW INFRASTRUCTURE; do
 for prefix in SLACK_BOT_TOKEN SLACK_APP_TOKEN; do
  grep -q "^export ${prefix}_${role}=" deploy/env.sh.template || { echo "設定範本缺少 ${prefix}_${role}"; exit 1; }
 done
done
for role in pm architecture backend qa code-review infrastructure; do test -s "deploy/agent-manifests/$role.yaml"; done
echo 'PASS：Bash 語法、管線與角色對話 prompt/schema、teams 合併規則、範例配置、五個 legacy 角色與 Infrastructure 的 Slack 設定／manifest 齊全。'
