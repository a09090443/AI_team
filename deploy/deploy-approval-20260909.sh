#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
build=$root/approval-build.xcQbUc
jar=ai-team-orchestrator-0.1.0.jar
db=$root/ai-team.db
exec 8>"$root/deployment.lock"
flock -n 8
grep -q 'BUILD SUCCESS' "$build/build.log"
test "$(git -C "$root/workspaces/task-1014" rev-parse --short HEAD)" = 3bcf2b7
test -z "$(git -C "$root/workspaces/task-1014" status --porcelain)"
test "$(sqlite3 -readonly "$db" 'select state from task where id=1014;')" = AWAIT_HUMAN
sqlite3 -readonly "$db" 'select data from flow_run where task_id=1014;' > "$build/flow.current.json"
cmp "$build/flow.before.json" "$build/flow.current.json"
# service.sh 拒絕在有執行中或排隊任務時停止；不強制終止。
bash "$root/service.sh" stop
backup=$(mktemp -d "$root/approval-backup.XXXXXX")
cp -p "$root/orchestrator/target/$jar" "$backup/$jar"
cp -a "$root/orchestrator/src" "$backup/src"
cp -p "$root/teams/spring-starters.json" "$backup/team.json"
cp -p "$root/orchestrator/teams/spring-starters.json" "$backup/source-team.json"
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
sqlite3 "$db" ".backup '$backup/ai-team.db'"
test "$(sqlite3 -readonly "$backup/ai-team.db" 'pragma integrity_check;')" = ok
cp -p "$build/flow.before.json" "$backup/flow.before.json"
cp -p "$build/flow.after.json" "$backup/flow.after.json"
rollback() {
  echo "部署未通過；保留備份 $backup，嘗試回復。若服務已有任務執行則停止自動回復。"
  bash "$root/service.sh" stop || return 1
  cp -p "$backup/$jar" "$root/orchestrator/target/$jar"
  cp -a "$backup/src/." "$root/orchestrator/src/"
  cp -p "$backup/team.json" "$root/teams/spring-starters.json"
  cp -p "$backup/source-team.json" "$root/orchestrator/teams/spring-starters.json"
  sqlite3 "$db" "UPDATE flow_run SET data=cast(readfile('$backup/flow.before.json') AS TEXT) WHERE task_id=1014; INSERT INTO audit_log(task_id,at,actor,action,detail) VALUES('1014',strftime('%Y-%m-%dT%H:%M:%fZ','now'),'system','STATE_CHANGE','核准閘門部署未通過，已回復原快照；未核准或執行 QA。');"
  bash "$root/service.sh" start 8>&-
}
trap rollback ERR
sqlite3 -readonly -json "$db" 'select * from task order by id; select * from flow_run where task_id!=1014 order by task_id; select * from task_checkpoint order by task_id; select * from stage_result order by task_id,role;' | sha256sum > "$backup/unrelated.before"
cp -p "$build/orchestrator/target/$jar" "$root/orchestrator/target/$jar.next"
mv "$root/orchestrator/target/$jar.next" "$root/orchestrator/target/$jar"
cp -a "$build/orchestrator/src/." "$root/orchestrator/src/"
cp -p "$build/orchestrator/teams/spring-starters.json" "$root/teams/spring-starters.json"
cp -p "$build/orchestrator/teams/spring-starters.json" "$root/orchestrator/teams/spring-starters.json"
revision=$(jq -r '.snapshot.revision' "$build/flow.after.json")
sqlite3 "$db" "BEGIN IMMEDIATE; UPDATE flow_run SET data=cast(readfile('$build/flow.after.json') AS TEXT) WHERE task_id=1014; INSERT INTO audit_log(task_id,at,actor,action,detail) VALUES('1014',strftime('%Y-%m-%dT%H:%M:%fZ','now'),'system','STATE_CHANGE','使用者在 Codex 授權修復、部署與受控恢復。QA 遷移 Codex gpt-5.6-sol medium；啟用人工計畫前置閘門；節點 triage→verification 等待核准，保留 visits、需求、計畫、實作與工作區，未核准、未排隊、未執行 QA。新快照 $revision，完整備份 $backup。'); COMMIT;"
bash "$root/service.sh" start 8>&-
echo "BACKUP=$backup"
for attempt in {1..45}; do
  if grep -q 'INSPECTOR 直接對話 Socket Mode 已啟動' "$root/orchestrator.log" && grep -q '沙箱工具鏈自檢通過' "$root/orchestrator.log"; then
    sqlite3 -readonly -json "$db" 'select * from task order by id; select * from flow_run where task_id!=1014 order by task_id; select * from task_checkpoint order by task_id; select * from stage_result order by task_id,role;' | sha256sum > "$backup/unrelated.after"
    cmp "$backup/unrelated.before" "$backup/unrelated.after"
    sha256sum "$root/orchestrator/target/$jar"
    grep -E '新任務使用配置版本|沙箱.*通過' "$root/orchestrator.log"
    bash "$root/service.sh" status
    trap - ERR
    exit 0
  fi
  sleep 1
done
false
