#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
build=$root/local-test-build.ym6i6i
jar=ai-team-orchestrator-0.1.0.jar
exec 8>"$root/deployment.lock"
flock -n 8
grep -q 'BUILD SUCCESS' "$build/build-final.log"
grep -q 'Tests run: 9, Failures: 0, Errors: 0, Skipped: 0' "$build/real-tools.log"
grep -q 'Generated static files in' "$build/real-tools.log"
cmp "$build/smoke-repo/llms.txt" "$build/smoke-repo/target/qa-docs/doc-site/build/llms.txt"
cmp "$build/smoke-repo/llms-full.txt" "$build/smoke-repo/target/qa-docs/doc-site/build/llms-full.txt"
test -z "$(git -C "$build/smoke-repo" status --porcelain)"
test "$(sqlite3 -readonly "$root/ai-team.db" 'select state from task where id=1014;')" = AWAIT_HUMAN
bash "$root/service.sh" stop
backup=$(mktemp -d "$root/local-test-backup.XXXXXX")
cp -p "$root/orchestrator/target/$jar" "$backup/$jar"
cp -a "$root/orchestrator/src" "$backup/src"
cp -p "$root/teams/spring-starters.json" "$backup/team.json"
cp -p "$root/orchestrator/teams/spring-starters.json" "$backup/source-team.json"
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
sqlite3 "$root/ai-team.db" ".backup '$backup/ai-team.db'"
test "$(sqlite3 -readonly "$backup/ai-team.db" 'pragma integrity_check;')" = ok
rollback() {
  echo "部署檢查未通過；備份 $backup。若已有執行中任務則不強制停止。"
  bash "$root/service.sh" stop || return 1
  cp -p "$backup/$jar" "$root/orchestrator/target/$jar"
  cp -a "$backup/src/." "$root/orchestrator/src/"
  cp -p "$backup/team.json" "$root/teams/spring-starters.json"
  cp -p "$backup/source-team.json" "$root/orchestrator/teams/spring-starters.json"
  bash "$root/service.sh" start 8>&-
}
trap rollback ERR
sqlite3 -readonly -json "$root/ai-team.db" 'select * from task order by id; select * from flow_run order by task_id; select * from task_checkpoint order by task_id; select * from stage_result order by task_id,role;' | sha256sum > "$backup/tasks.before"
cp -p "$build/orchestrator/target/$jar" "$root/orchestrator/target/$jar.next"
mv "$root/orchestrator/target/$jar.next" "$root/orchestrator/target/$jar"
cp -a "$build/orchestrator/src/." "$root/orchestrator/src/"
cp -p "$build/orchestrator/teams/spring-starters.json" "$root/teams/spring-starters.json"
cp -p "$build/orchestrator/teams/spring-starters.json" "$root/orchestrator/teams/spring-starters.json"
# 不傳恢復環境變數、不修改任何既有 flow，等待真人在 Slack 核准配置。
bash "$root/service.sh" start 8>&-
echo "BACKUP=$backup"
for attempt in {1..45}; do
  if grep -q 'INSPECTOR 直接對話 Socket Mode 已啟動' "$root/orchestrator.log" && grep -q '沙箱工具鏈自檢通過' "$root/orchestrator.log"; then
    sqlite3 -readonly -json "$root/ai-team.db" 'select * from task order by id; select * from flow_run order by task_id; select * from task_checkpoint order by task_id; select * from stage_result order by task_id,role;' | sha256sum > "$backup/tasks.after"
    cmp "$backup/tasks.before" "$backup/tasks.after"
    echo 'PASS: all existing tasks, flows, approvals, checkpoints and stage results unchanged'
    sha256sum "$root/orchestrator/target/$jar"
    bash "$root/service.sh" status
    grep '新任務使用配置版本' "$root/orchestrator.log"
    trap - ERR
    exit 0
  fi
  sleep 1
done
false
