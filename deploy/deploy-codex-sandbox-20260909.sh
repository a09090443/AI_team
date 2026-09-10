#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
build=$root/codex-sandbox-build.ZFDWlB
jar=ai-team-orchestrator-0.1.0.jar
db=$root/ai-team.db
exec 8>"$root/deployment.lock"
flock -n 8
grep -q 'BUILD SUCCESS' "$build/build.log"
test -s "$build/1014-preflight.log"
test "$(git -C "$root/workspaces/task-1014" rev-parse --short HEAD)" = 3bcf2b7
test -z "$(git -C "$root/workspaces/task-1014" status --porcelain)"
test "$(sqlite3 -readonly "$db" 'select state from task where id=1014;')" = AWAIT_HUMAN
sqlite3 -readonly "$db" 'select data from flow_run where task_id=1014;' > "$build/flow.current.json"
cmp "$build/flow.before.json" "$build/flow.current.json"
bash "$root/service.sh" stop
backup=$(mktemp -d "$root/codex-sandbox-backup.XXXXXX")
cp -p "$root/orchestrator/target/$jar" "$backup/$jar"
cp -a "$root/orchestrator/src" "$backup/src"
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
sqlite3 "$db" ".backup '$backup/ai-team.db'"
test "$(sqlite3 -readonly "$backup/ai-team.db" 'pragma integrity_check;')" = ok
cp -p "$build/flow.before.json" "$backup/flow.before.json"
cp -p "$build/flow.after.json" "$backup/flow.after.json"
started=false
rollback() {
  echo "部署檢查失敗；備份 $backup"
  if $started; then
    echo '已嘗試啟動受控 QA，禁止盲目回復 DB 或中斷工作；保留現場待檢查。'
    return
  fi
  cp -p "$backup/$jar" "$root/orchestrator/target/$jar"
  cp -a "$backup/src/." "$root/orchestrator/src/"
  sqlite3 "$db" "UPDATE flow_run SET data=cast(readfile('$backup/flow.before.json') AS TEXT) WHERE task_id=1014;"
  bash "$root/service.sh" start 8>&-
}
trap rollback ERR
sqlite3 -readonly -json "$db" 'select * from task where id!=1014 order by id; select * from flow_run where task_id!=1014 order by task_id;' | sha256sum > "$backup/other.before"
jq -cS '{snapshot,baselineRevision,plan:.receipts.plan,planApprovals}' "$build/flow.before.json" | sha256sum > "$backup/approval.before"
# 候選 flow 只改恢復位置與診斷脈絡，不更動設定／核准／次數。
cmp <(jq -cS 'del(.node,.activeNode,.context,.lastBlockReason)' "$build/flow.before.json") <(jq -cS 'del(.node,.activeNode,.context,.lastBlockReason)' "$build/flow.after.json")
cp -p "$build/orchestrator/target/$jar" "$root/orchestrator/target/$jar.next"
mv "$root/orchestrator/target/$jar.next" "$root/orchestrator/target/$jar"
cp -a "$build/orchestrator/src/." "$root/orchestrator/src/"
sqlite3 "$db" "BEGIN IMMEDIATE; UPDATE flow_run SET data=cast(readfile('$build/flow.after.json') AS TEXT) WHERE task_id=1014; INSERT INTO audit_log(task_id,at,actor,action,detail) VALUES('1014',strftime('%Y-%m-%dT%H:%M:%fZ','now'),'system','STATE_CHANGE','使用者授權修復 Codex bwrap 啟動失敗與受控恢復 #1014。相同 QA policy 沙箱預檢通過；triage→verification，既有核准、工作區、計畫、實作與重試次數／上限不變。备份 $backup。'); COMMIT;"
started=true
AI_TEAM_RESUME_APPROVED_TASK=1014 bash "$root/service.sh" start 8>&-
echo "BACKUP=$backup"
for attempt in {1..45}; do
  if grep -q 'INSPECTOR 直接對話 Socket Mode 已啟動' "$root/orchestrator.log" && grep -q '沙箱工具鏈自檢通過' "$root/orchestrator.log"; then
    sqlite3 -readonly -json "$db" 'select * from task where id!=1014 order by id; select * from flow_run where task_id!=1014 order by task_id;' | sha256sum > "$backup/other.after"
    sqlite3 -readonly "$db" 'select data from flow_run where task_id=1014;' | jq -cS '{snapshot,baselineRevision,plan:.receipts.plan,planApprovals}' | sha256sum > "$backup/approval.after"
    cmp "$backup/other.before" "$backup/other.after"
    cmp "$backup/approval.before" "$backup/approval.after"
    echo 'PASS: other tasks and original approval unchanged'
    sha256sum "$root/orchestrator/target/$jar"
    bash "$root/service.sh" status
    sqlite3 -readonly "$db" 'select id,state,updated_at from task where id=1014;'
    trap - ERR
    exit 0
  fi
  sleep 1
done
false
