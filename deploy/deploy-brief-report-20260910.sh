#!/usr/bin/env bash
# 部署階段回報摘要化（AI_TEAM_REPORT_DETAIL，預設 brief）與「檢視完整報告」指令。
# 只換程式，不動任何配置與任務；不設定新環境變數，靠程式預設值生效。
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
build=${1:?用法：deploy-brief-report-20260910.sh <build 目錄>}
jar=ai-team-orchestrator-0.1.0.jar
exec 8>"$root/deployment.lock"
flock -n 8

grep -q 'BUILD SUCCESS' "$build/orchestrator/build-tests.log"
grep -q 'Tests run: 261, Failures: 0, Errors: 0, Skipped: 10' "$build/orchestrator/build-tests.log"
grep -q 'Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: .* -- in com.zipe.aiteam.slack.SlackReportTest' \
     "$build/orchestrator/build-tests.log"
test "$(sha256sum "$build/orchestrator/target/$jar" | cut -d' ' -f1)" \
   = a4257e9b0462ea444dd01d9c90ba9515631e54bfa8eb9ff51c7f0f21ade14c63

# 不在任務執行中部署。
test -z "$(sqlite3 -readonly "$root/ai-team.db" "select id from task where state in ('STAGE_RUNNING','RESUME_QUEUED');")"
# 重啟會執行 purgeWorkspaces()：ABORTED 任務的工作區會被刪。#1014 必須仍在等待狀態，
# 否則它那三個未推送的 commit 會在這次重啟中消失。
test "$(sqlite3 -readonly "$root/ai-team.db" "select state from task where id=1014;")" = WAITING_QUOTA

bash "$root/service.sh" stop
backup=$(mktemp -d "$root/brief-backup.XXXXXX")
cp -p "$root/orchestrator/target/$jar" "$backup/$jar"
cp -a "$root/orchestrator/src" "$backup/src"
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
sqlite3 "$root/ai-team.db" ".backup '$backup/ai-team.db'"
test "$(sqlite3 -readonly "$backup/ai-team.db" 'pragma integrity_check;')" = ok

rollback() {
  echo "部署檢查未通過；備份 $backup。"
  bash "$root/service.sh" stop || return 1
  cp -p "$backup/$jar" "$root/orchestrator/target/$jar"
  rm -rf "$root/orchestrator/src"
  cp -a "$backup/src" "$root/orchestrator/src"
  bash "$root/service.sh" start 8>&-
}
trap rollback ERR

snapshot() { sqlite3 -readonly -json "$root/ai-team.db" 'select * from task order by id; select * from flow_run order by task_id; select * from task_checkpoint order by task_id; select * from stage_result order by task_id,role;' | sha256sum; }
snapshot > "$backup/tasks.before"

cp -p "$build/orchestrator/target/$jar" "$root/orchestrator/target/$jar.next"
mv "$root/orchestrator/target/$jar.next" "$root/orchestrator/target/$jar"
cp -a "$build/orchestrator/src/." "$root/orchestrator/src/"

bash "$root/service.sh" start 8>&-
echo "BACKUP=$backup"
for attempt in {1..45}; do
  if grep -q 'INSPECTOR 直接對話 Socket Mode 已啟動' "$root/orchestrator.log" && grep -q '沙箱工具鏈自檢通過' "$root/orchestrator.log"; then
    snapshot > "$backup/tasks.after"
    cmp "$backup/tasks.before" "$backup/tasks.after"
    echo 'PASS: 既有任務、流程、核准、checkpoint 與階段結果均未改變'
    test -d "$root/workspaces/task-1014" && echo 'PASS: #1014 工作區仍在'
    sha256sum "$root/orchestrator/target/$jar"
    bash "$root/service.sh" status
    trap - ERR
    exit 0
  fi
  sleep 1
done
false
