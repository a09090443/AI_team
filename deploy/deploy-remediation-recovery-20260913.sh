#!/usr/bin/env bash
# 部署「人工修復 commit 後一次性重驗」；不替任何任務核准或續跑。
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
build=${1:?用法：deploy-remediation-recovery-20260913.sh <build 目錄>}
jar=ai-team-orchestrator-0.1.0.jar
expected=d65a4982ea41820a0bfd1b5e353c247d6b8905dd8b572a6a21fb39a966b67f38
exec 8>"$root/deployment.lock"
flock -n 8

grep -q 'Tests run: 267, Failures: 0, Errors: 0, Skipped: 10' "$build/build-final.log"
grep -q 'BUILD SUCCESS' "$build/build-final.log"
test "$(sha256sum "$build/target/$jar" | cut -d' ' -f1)" = "$expected"

test -z "$(sqlite3 -readonly "$root/ai-team.db" \
  "select id from task where state like '%RUNNING' or state in ('QUEUED','RESUME_QUEUED','TRIAGE_QUEUED');")"
test "$(sqlite3 -readonly "$root/ai-team.db" "select state from task where id=1014;")" = AWAIT_HUMAN
test -d "$root/workspaces/task-1014"
test -z "$(git -C "$root/workspaces/task-1014" status --porcelain)"
test "$(git -C "$root/workspaces/task-1014" rev-parse HEAD)" = 0b6a8b8a1b33829830990cfdeeec310b97679855

bash "$root/service.sh" stop
backup=$(mktemp -d "$root/remediation-recovery-backup.XXXXXX")
cp -p "$root/orchestrator/target/$jar" "$backup/$jar"
cp -a "$root/orchestrator/src" "$backup/src"
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

rollback() {
  echo "部署檢查未通過；已啟動回復。備份：$backup"
  bash "$root/service.sh" stop || true
  cp -p "$backup/$jar" "$root/orchestrator/target/$jar"
  cp -a "$backup/src/." "$root/orchestrator/src/"
  bash "$root/service.sh" start 8>&-
}
trap rollback ERR

cp -p "$build/target/$jar" "$root/orchestrator/target/$jar.next"
mv "$root/orchestrator/target/$jar.next" "$root/orchestrator/target/$jar"
cp -a "$build/src/." "$root/orchestrator/src/"

bash "$root/service.sh" start 8>&-
echo "BACKUP=$backup"
for attempt in {1..45}; do
  # service.sh 每次啟動都會重建 log，所以此處直接查本次完整檔案。
  if grep -q 'INSPECTOR 直接對話 Socket Mode 已啟動' "$root/orchestrator.log" \
      && grep -q '沙箱工具鏈自檢通過' "$root/orchestrator.log"; then
    snapshot > "$backup/tasks.after"
    cmp "$backup/tasks.before" "$backup/tasks.after"
    test -d "$root/workspaces/task-1014"
    test -z "$(git -C "$root/workspaces/task-1014" status --porcelain)"
    test "$(sha256sum "$root/orchestrator/target/$jar" | cut -d' ' -f1)" = "$expected"
    echo 'PASS: 既有任務、流程、核准、checkpoint、報告與額度排程均未改變'
    echo 'PASS: #1014 工作區仍乾淨；未核准、未排隊、未自動續跑'
    sha256sum "$root/orchestrator/target/$jar"
    bash "$root/service.sh" status
    trap - ERR
    exit 0
  fi
  sleep 1
done
false
