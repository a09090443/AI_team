#!/usr/bin/env bash
# 部署自動交接舊計數正規化與有界人工延長；不修改或自動續跑既有任務。
set -euo pipefail
umask 077

root=/home/vboxuser/ai-team
source_root=${1:?用法：deploy-handoff-budget-normalization-20260915.sh <隔離來源根目錄>}
build="$source_root/orchestrator"
jar=ai-team-orchestrator-0.1.0.jar
previous=b54bda100d4faa0c7fcc55e099b1748514817fbf167128f9c19fa99854b004f6
expected=310e578cb0ca938dab466d169659215af08dd04a30c3744574c0e730f35171dd
repo="$root/workspaces/task-1014"

exec 8>"$root/deployment.lock"
flock -n 8
grep -q 'Tests run: 289, Failures: 0, Errors: 0, Skipped: 10' "$build/full-build.log"
grep -q 'BUILD SUCCESS' "$build/full-build.log"
test "$(sha256sum "$build/target/$jar" | cut -d' ' -f1)" = "$expected"
test "$(sha256sum "$root/orchestrator/target/$jar" | cut -d' ' -f1)" = "$previous"
test -z "$(sqlite3 -readonly "$root/ai-team.db" \
  "select id from task where state like '%RUNNING' or state in ('QUEUED','RESUME_QUEUED','TRIAGE_QUEUED');")"
test "$(sqlite3 -readonly "$root/ai-team.db" "select state from task where id=1014;")" = AWAIT_HUMAN
test "$(sqlite3 -readonly "$root/ai-team.db" "select json_extract(data,'$.node') from flow_run where task_id=1014;")" = triage
test -z "$(sqlite3 -readonly "$root/ai-team.db" "select json_extract(data,'$.activeNode') from flow_run where task_id=1014;")"
test "$(sqlite3 -readonly "$root/ai-team.db" "select json_extract(data,'$.automaticHandoffs') from flow_run where task_id=1014;")" = 6
test "$(sqlite3 -readonly "$root/ai-team.db" "select json_array_length(json_extract(data,'$.automaticHandoffKeys')) from flow_run where task_id=1014;")" = 6
test -z "$(git -C "$repo" status --porcelain=v1)"

AI_TEAM_REPO="$repo" java -jar "$build/target/$jar" --validate-team "$build/teams/spring-starters.json"
AI_TEAM_NODE_REPO="$repo" java -jar "$build/target/$jar" --validate-team "$build/teams/node-web.json"

snapshot() {
  sqlite3 -readonly -json "$root/ai-team.db" '
    select * from task order by id;
    select * from flow_run order by task_id;
    select * from task_checkpoint order by task_id;
    select * from direct_request order by task_id;
    select * from stage_result order by task_id,role;
    select * from quota_retry order by task_id;' | sha256sum
}
workspace_snapshot() {
  git -C "$repo" rev-parse HEAD
  git -C "$repo" status --porcelain=v1
  git -C "$repo" diff --binary
  git -C "$repo" diff --cached --binary
}

backup=$(mktemp -d "$root/handoff-budget-backup.XXXXXX")
cp -p "$root/orchestrator/target/$jar" "$backup/$jar"
cp -a "$root/orchestrator/src/main" "$backup/orchestrator-main"
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
sqlite3 "$root/ai-team.db" ".backup '$backup/ai-team.db'"
test "$(sqlite3 -readonly "$backup/ai-team.db" 'pragma integrity_check;')" = ok
snapshot > "$backup/tasks.before"
workspace_snapshot | sha256sum > "$backup/workspace.before"

rollback() {
  echo "部署檢查未通過；已啟動回復。備份：$backup"
  bash "$root/service.sh" stop || true
  cp -p "$backup/$jar" "$root/orchestrator/target/$jar"
  cp -a "$backup/orchestrator-main/." "$root/orchestrator/src/main/"
}
trap rollback ERR

bash "$root/service.sh" stop
cp -p "$build/target/$jar" "$root/orchestrator/target/$jar.next"
mv "$root/orchestrator/target/$jar.next" "$root/orchestrator/target/$jar"
cp -a "$build/src/main/." "$root/orchestrator/src/main/"
bash "$root/service.sh" start 8>&-
echo "BACKUP=$backup"

for attempt in {1..45}; do
  if grep -q 'INSPECTOR 直接對話 Socket Mode 已啟動' "$root/orchestrator.log" \
      && grep -q '沙箱工具鏈自檢通過' "$root/orchestrator.log"; then
    snapshot > "$backup/tasks.after"
    workspace_snapshot | sha256sum > "$backup/workspace.after"
    cmp "$backup/tasks.before" "$backup/tasks.after"
    cmp "$backup/workspace.before" "$backup/workspace.after"
    test "$(sha256sum "$root/orchestrator/target/$jar" | cut -d' ' -f1)" = "$expected"
    test "$(sqlite3 -readonly "$root/ai-team.db" "select state from task where id=1014;")" = AWAIT_HUMAN
    ! grep -q ' ERROR ' "$root/orchestrator.log"
    echo 'PASS: 所有任務資料與 #1014 工作區部署前後完全一致'
    echo 'PASS: #1014 保持 AWAIT_HUMAN／triage；正規化將於下次明確續跑時稽核套用'
    sha256sum "$root/orchestrator/target/$jar"
    bash "$root/service.sh" status
    trap - ERR
    exit 0
  fi
  sleep 1
done
false
