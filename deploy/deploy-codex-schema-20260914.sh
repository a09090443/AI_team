#!/usr/bin/env bash
# 修正 Codex output schema 相容性；不修改、核准或自動續跑既有任務。
set -euo pipefail
umask 077

root=/home/vboxuser/ai-team
source_root=${1:?用法：deploy-codex-schema-20260914.sh <隔離來源根目錄>}
build="$source_root/orchestrator"
jar=ai-team-orchestrator-0.1.0.jar
expected=a9637b143d0c0e438d70e98a66732577db0c86ca1a3c25299d3fb6e5a38bf334

exec 8>"$root/deployment.lock"
flock -n 8
grep -q 'Tests run: 275, Failures: 0, Errors: 0, Skipped: 10' "$build/schema-fix-build.log"
grep -q 'BUILD SUCCESS' "$build/schema-fix-build.log"
test "$(sha256sum "$build/target/$jar" | cut -d' ' -f1)" = "$expected"
test -z "$(sqlite3 -readonly "$root/ai-team.db" \
  "select id from task where state like '%RUNNING' or state in ('QUEUED','RESUME_QUEUED','TRIAGE_QUEUED');")"
test "$(sqlite3 -readonly "$root/ai-team.db" "select state from task where id=1014;")" = AWAIT_HUMAN
test "$(sqlite3 -readonly "$root/ai-team.db" \
  "select json_extract(data,'$.activeNode') from flow_run where task_id=1014;")" = plan
test -z "$(git -C "$root/workspaces/task-1014" status --porcelain=v1)"

bash "$root/service.sh" stop
backup=$(mktemp -d "$root/codex-schema-backup.XXXXXX")
cp -p "$root/orchestrator/target/$jar" "$backup/$jar"
cp -a "$root/orchestrator/src" "$backup/orchestrator-src"
cp -a "$root/orchestrator/teams" "$backup/orchestrator-teams"
cp -a "$root/teams" "$backup/teams"
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
snapshot > "$backup/tasks.before"
git -C "$root/workspaces/task-1014" status --porcelain=v1 | sha256sum > "$backup/workspace.before"

rollback() {
  echo "部署檢查未通過；已啟動回復。備份：$backup"
  bash "$root/service.sh" stop || true
  cp -p "$backup/$jar" "$root/orchestrator/target/$jar"
  cp -a "$backup/orchestrator-src/." "$root/orchestrator/src/"
  cp -a "$backup/orchestrator-teams/." "$root/orchestrator/teams/"
  cp -a "$backup/teams/." "$root/teams/"
  bash "$root/service.sh" start 8>&-
}
trap rollback ERR

cp -p "$build/target/$jar" "$root/orchestrator/target/$jar.next"
mv "$root/orchestrator/target/$jar.next" "$root/orchestrator/target/$jar"
cp -a "$build/src/." "$root/orchestrator/src/"
cp -a "$build/teams/." "$root/orchestrator/teams/"
cp -a "$build/teams/." "$root/teams/"

bash "$root/service.sh" start 8>&-
echo "BACKUP=$backup"
for attempt in {1..45}; do
  if grep -q 'INSPECTOR 直接對話 Socket Mode 已啟動' "$root/orchestrator.log" \
      && grep -q '沙箱工具鏈自檢通過' "$root/orchestrator.log"; then
    snapshot > "$backup/tasks.after"
    git -C "$root/workspaces/task-1014" status --porcelain=v1 | sha256sum > "$backup/workspace.after"
    cmp "$backup/tasks.before" "$backup/tasks.after"
    cmp "$backup/workspace.before" "$backup/workspace.after"
    test "$(sha256sum "$root/orchestrator/target/$jar" | cut -d' ' -f1)" = "$expected"
    echo 'PASS: 既有任務、流程、核准、checkpoint、報告與額度排程均未改變'
    echo 'PASS: #1014 保持 AWAIT_HUMAN／plan；未排隊、未自動續跑'
    sha256sum "$root/orchestrator/target/$jar"
    bash "$root/service.sh" status
    trap - ERR
    exit 0
  fi
  sleep 1
done
false
