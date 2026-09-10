#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
build=$root/usage-fix-build.Sa7L5Y
jar=ai-team-orchestrator-0.1.0.jar
exec 8>"$root/deployment.lock"
flock -n 8
# 建置證據：一般測試全過，且三組 opt-in 實機測試各自另行跑過。
grep -q 'BUILD SUCCESS' "$build/orchestrator/build-tests.log"
grep -q 'Tests run: 231, Failures: 0, Errors: 0, Skipped: 10' "$build/orchestrator/build-tests.log"
grep -q 'Tests run: 4, Failures: 0, Errors: 0, Skipped: 0' "$build/orchestrator/smoke.log"
test "$(sha256sum "$build/orchestrator/target/$jar" | cut -d' ' -f1)" \
   = 9d54bb4b18c4efacb7ac0e7dcce63191d97ff1963a9c3bf33a5037cceb52a3d7
# 不在任務執行中部署；#1014 必須仍停在等待人工。
test -z "$(sqlite3 -readonly "$root/ai-team.db" "select id from task where state in ('STAGE_RUNNING','RESUME_QUEUED');")"
test "$(sqlite3 -readonly "$root/ai-team.db" 'select state from task where id=1014;')" = AWAIT_HUMAN
bash "$root/service.sh" stop
backup=$(mktemp -d "$root/usage-fix-backup.XXXXXX")
cp -p "$root/orchestrator/target/$jar" "$backup/$jar"
cp -a "$root/orchestrator/src" "$backup/src"
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
sqlite3 "$root/ai-team.db" ".backup '$backup/ai-team.db'"
test "$(sqlite3 -readonly "$backup/ai-team.db" 'pragma integrity_check;')" = ok
rollback() {
  echo "部署檢查未通過；備份 $backup。"
  bash "$root/service.sh" stop || return 1
  cp -p "$backup/$jar" "$root/orchestrator/target/$jar"
  cp -a "$backup/src/." "$root/orchestrator/src/"
  bash "$root/service.sh" start 8>&-
}
trap rollback ERR
snapshot() { sqlite3 -readonly -json "$root/ai-team.db" 'select * from task order by id; select * from flow_run order by task_id; select * from task_checkpoint order by task_id; select * from stage_result order by task_id,role;' | sha256sum; }
snapshot > "$backup/tasks.before"
cp -p "$build/orchestrator/target/$jar" "$root/orchestrator/target/$jar.next"
mv "$root/orchestrator/target/$jar.next" "$root/orchestrator/target/$jar"
cp -a "$build/orchestrator/src/." "$root/orchestrator/src/"
# 只換程式：不動 teams 配置、不傳恢復環境變數、不代為核准或續跑任何任務。
bash "$root/service.sh" start 8>&-
echo "BACKUP=$backup"
for attempt in {1..45}; do
  if grep -q 'INSPECTOR 直接對話 Socket Mode 已啟動' "$root/orchestrator.log" && grep -q '沙箱工具鏈自檢通過' "$root/orchestrator.log"; then
    snapshot > "$backup/tasks.after"
    cmp "$backup/tasks.before" "$backup/tasks.after"
    echo 'PASS: 既有任務、流程、核准、checkpoint 與階段結果均未改變'
    sha256sum "$root/orchestrator/target/$jar"
    bash "$root/service.sh" status
    trap - ERR
    exit 0
  fi
  sleep 1
done
false
