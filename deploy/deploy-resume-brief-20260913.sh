#!/usr/bin/env bash
# 修正 Codex resume 權限選項位置，並將角色與受阻回覆預設簡要化。
# 只更新程式與 direct prompt；不變更團隊配置、任務快照或自動續跑 #1014。
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
build=${1:?用法：deploy-resume-brief-20260913.sh <build 目錄>}
jar=ai-team-orchestrator-0.1.0.jar
expected=b86c86896cec38f150b91c5d46501943d6cd4e74c46c7958d377ad1cee607f50
exec 8>"$root/deployment.lock"
flock -n 8

grep -q 'Tests run: 263, Failures: 0, Errors: 0, Skipped: 10' "$build/orchestrator/build-final.log"
grep -q 'Tests run: 10, Failures: 0, Errors: 0, Skipped: 0' "$build/orchestrator/smoke.log"
grep -q 'BUILD SUCCESS' "$build/orchestrator/build-final.log"
grep -q 'BUILD SUCCESS' "$build/orchestrator/smoke.log"
test "$(sha256sum "$build/orchestrator/target/$jar" | cut -d' ' -f1)" = "$expected"

# 不在任務執行中部署，也不讓重啟清掉 #1014 工作區。
test -z "$(sqlite3 -readonly "$root/ai-team.db" \
  "select id from task where state like '%RUNNING' or state in ('QUEUED','RESUME_QUEUED','TRIAGE_QUEUED');")"
test "$(sqlite3 -readonly "$root/ai-team.db" "select state from task where id=1014;")" = AWAIT_HUMAN
test -d "$root/workspaces/task-1014"
test -z "$(git -C "$root/workspaces/task-1014" status --porcelain)"

bash "$root/service.sh" stop
backup=$(mktemp -d "$root/resume-brief-backup.XXXXXX")
cp -p "$root/orchestrator/target/$jar" "$backup/$jar"
cp -a "$root/orchestrator/src" "$backup/src"
cp -p "$root/prompts/direct.md" "$backup/direct.md"
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
sqlite3 "$root/ai-team.db" ".backup '$backup/ai-team.db'"
test "$(sqlite3 -readonly "$backup/ai-team.db" 'pragma integrity_check;')" = ok

rollback() {
  echo "部署檢查未通過；已啟動回復。備份：$backup"
  bash "$root/service.sh" stop || return 1
  cp -p "$backup/$jar" "$root/orchestrator/target/$jar"
  rm -rf "$root/orchestrator/src"
  cp -a "$backup/src" "$root/orchestrator/src"
  cp -p "$backup/direct.md" "$root/prompts/direct.md"
  bash "$root/service.sh" start 8>&-
}
trap rollback ERR

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

cp -p "$build/orchestrator/target/$jar" "$root/orchestrator/target/$jar.next"
mv "$root/orchestrator/target/$jar.next" "$root/orchestrator/target/$jar"
cp -a "$build/orchestrator/src/." "$root/orchestrator/src/"
cp -p "$build/orchestrator/prompts/direct.md" "$root/prompts/direct.md"

bash "$root/service.sh" start 8>&-
echo "BACKUP=$backup"
for attempt in {1..45}; do
  if grep -q 'INSPECTOR 直接對話 Socket Mode 已啟動' "$root/orchestrator.log" \
      && grep -q '沙箱工具鏈自檢通過' "$root/orchestrator.log"; then
    snapshot > "$backup/tasks.after"
    cmp "$backup/tasks.before" "$backup/tasks.after"
    test -d "$root/workspaces/task-1014"
    test -z "$(git -C "$root/workspaces/task-1014" status --porcelain)"
    echo 'PASS: 既有任務、流程、核准、checkpoint、報告與額度排程均未改變'
    echo 'PASS: #1014 工作區仍存在且乾淨；未自動續跑'
    sha256sum "$root/orchestrator/target/$jar"
    bash "$root/service.sh" status
    trap - ERR
    exit 0
  fi
  sleep 1
done
false
