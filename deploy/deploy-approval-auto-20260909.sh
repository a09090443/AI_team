#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
build=$root/approval-auto-build.7WpRR5
jar=ai-team-orchestrator-0.1.0.jar
exec 8>"$root/deployment.lock"
flock -n 8
grep -q 'BUILD SUCCESS' "$build/build.log"
test "$(sqlite3 -readonly "$root/ai-team.db" 'select state from task where id=1014;')" = AWAIT_HUMAN
sqlite3 -readonly "$root/ai-team.db" 'select data from flow_run where task_id=1014;' | jq -e '
  .node=="verification" and .activeNode==null and .pendingPlanRevision==null and .changeTarget==null
  and .planApprovals.plan.configuration==.snapshot.revision
  and .planApprovals.plan.baseline==.baselineRevision
  and .planApprovals.plan.user==.acceptanceOwner' >/dev/null
bash "$root/service.sh" stop
backup=$(mktemp -d "$root/approval-auto-backup.XXXXXX")
cp -p "$root/orchestrator/target/$jar" "$backup/$jar"
cp -a "$root/orchestrator/src" "$backup/src"
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
sqlite3 "$root/ai-team.db" ".backup '$backup/ai-team.db'"
test "$(sqlite3 -readonly "$backup/ai-team.db" 'pragma integrity_check;')" = ok
rollback() {
  echo "部署未通過；若 QA 已在執行，不強制停止或回復 DB，請查核 $backup"
  bash "$root/service.sh" stop || return 1
  cp -p "$backup/$jar" "$root/orchestrator/target/$jar"
  cp -a "$backup/src/." "$root/orchestrator/src/"
  bash "$root/service.sh" start 8>&-
}
trap rollback ERR
sqlite3 -readonly -json "$root/ai-team.db" 'select * from task where id!=1014 order by id; select * from flow_run where task_id!=1014 order by task_id;' | sha256sum > "$backup/other.before"
sqlite3 -readonly "$root/ai-team.db" 'select data from flow_run where task_id=1014;' | jq -cS '{snapshot,baselineRevision,plan:.receipts.plan,planApprovals}' | sha256sum > "$backup/approval.before"
cp -p "$build/orchestrator/target/$jar" "$root/orchestrator/target/$jar.next"
mv "$root/orchestrator/target/$jar.next" "$root/orchestrator/target/$jar"
cp -a "$build/orchestrator/src/." "$root/orchestrator/src/"
# 使用者明確授權只恢復 #1014；只傳給這次啟動，不寫入 env.sh 或永久設定。
AI_TEAM_RESUME_APPROVED_TASK=1014 bash "$root/service.sh" start 8>&-
echo "BACKUP=$backup"
for attempt in {1..45}; do
  if grep -q 'INSPECTOR 直接對話 Socket Mode 已啟動' "$root/orchestrator.log" && grep -q '沙箱工具鏈自檢通過' "$root/orchestrator.log"; then
    sqlite3 -readonly -json "$root/ai-team.db" 'select * from task where id!=1014 order by id; select * from flow_run where task_id!=1014 order by task_id;' | sha256sum > "$backup/other.after"
    sqlite3 -readonly "$root/ai-team.db" 'select data from flow_run where task_id=1014;' | jq -cS '{snapshot,baselineRevision,plan:.receipts.plan,planApprovals}' | sha256sum > "$backup/approval.after"
    cmp "$backup/other.before" "$backup/other.after"
    cmp "$backup/approval.before" "$backup/approval.after"
    echo 'PASS: other tasks and existing approval unchanged'
    sha256sum "$root/orchestrator/target/$jar"
    bash "$root/service.sh" status
    sqlite3 -readonly "$root/ai-team.db" 'select id,state,updated_at from task where id=1014;'
    trap - ERR
    exit 0
  fi
  sleep 1
done
false
