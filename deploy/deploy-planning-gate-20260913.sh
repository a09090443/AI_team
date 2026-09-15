#!/usr/bin/env bash
# 部署原子驗收與反例規畫閘門；不遷移、核准或續跑任何既有任務。
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
source_root=${1:?用法：deploy-planning-gate-20260913.sh <隔離來源根目錄>}
build="$source_root/orchestrator"
jar=ai-team-orchestrator-0.1.0.jar
expected=06771046ccef8831922d1c42d44b2cad30cad5dc2ab9fe26788ef49ea61c021d
exec 8>"$root/deployment.lock"
flock -n 8

grep -q 'Tests run: 272, Failures: 0, Errors: 0, Skipped: 10' "$build/build-clean.log"
grep -q 'BUILD SUCCESS' "$build/build-clean.log"
test "$(sha256sum "$build/target/$jar" | cut -d' ' -f1)" = "$expected"
test -z "$(sqlite3 -readonly "$root/ai-team.db" \
  "select id from task where state like '%RUNNING' or state in ('QUEUED','RESUME_QUEUED','TRIAGE_QUEUED');")"
test "$(sqlite3 -readonly "$root/ai-team.db" "select state from task where id=1014;")" = AWAIT_HUMAN
test -d "$root/workspaces/task-1014"

bash "$root/service.sh" stop
backup=$(mktemp -d "$root/planning-gate-backup.XXXXXX")
cp -p "$root/orchestrator/target/$jar" "$backup/$jar"
cp -a "$root/orchestrator/src" "$backup/orchestrator-src"
cp -a "$root/orchestrator/teams" "$backup/orchestrator-teams"
cp -a "$root/teams" "$backup/teams"
cp -p "$root/prompts/qa_plan.md" "$backup/qa_plan.md"
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
sqlite3 "$root/ai-team.db" ".backup '$backup/ai-team.db'"
test "$(sqlite3 -readonly "$backup/ai-team.db" 'pragma integrity_check;')" = ok

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
  git -C "$root/workspaces/task-1014" status --porcelain=v1
  git -C "$root/workspaces/task-1014" diff --binary
  git -C "$root/workspaces/task-1014" diff --cached --binary
}
snapshot > "$backup/tasks.before"
workspace_snapshot | sha256sum > "$backup/workspace.before"

rollback() {
  echo "部署檢查未通過；已啟動回復。備份：$backup"
  bash "$root/service.sh" stop || true
  cp -p "$backup/$jar" "$root/orchestrator/target/$jar"
  cp -a "$backup/orchestrator-src/." "$root/orchestrator/src/"
  cp -a "$backup/orchestrator-teams/." "$root/orchestrator/teams/"
  cp -a "$backup/teams/." "$root/teams/"
  cp -p "$backup/qa_plan.md" "$root/prompts/qa_plan.md"
  bash "$root/service.sh" start 8>&-
}
trap rollback ERR

cp -p "$build/target/$jar" "$root/orchestrator/target/$jar.next"
mv "$root/orchestrator/target/$jar.next" "$root/orchestrator/target/$jar"
cp -a "$build/src/." "$root/orchestrator/src/"
cp -a "$build/teams/." "$root/orchestrator/teams/"
cp -a "$build/teams/." "$root/teams/"
cp -p "$build/prompts/qa_plan.md" "$root/prompts/qa_plan.md"

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
    echo 'PASS: 既有任務、流程、核准、checkpoint、報告與額度排程均未改變'
    echo 'PASS: #1014 未提交修正原樣保留；未核准、未排隊、未自動續跑'
    sha256sum "$root/orchestrator/target/$jar"
    bash "$root/service.sh" status
    trap - ERR
    exit 0
  fi
  sleep 1
done
false
