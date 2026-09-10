#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
build=$root/plan-buttons-build.2TyoAq
jar=ai-team-orchestrator-0.1.0.jar
exec 8>"$root/deployment.lock"
flock -n 8
grep -q 'BUILD SUCCESS' "$build/build-final.log"
# 已有工作執行就拒絕停止，不中斷 QA。
bash "$root/service.sh" stop
backup=$(mktemp -d "$root/plan-buttons-backup.XXXXXX")
cp -p "$root/orchestrator/target/$jar" "$backup/$jar"
cp -a "$root/orchestrator/src" "$backup/src"
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
sqlite3 "$root/ai-team.db" ".backup '$backup/ai-team.db'"
rollback() {
  bash "$root/service.sh" stop || return 1
  cp -p "$backup/$jar" "$root/orchestrator/target/$jar"
  cp -a "$backup/src/." "$root/orchestrator/src/"
  bash "$root/service.sh" start 8>&-
  echo "已回復舊程式；DB 未回復或覆寫，備份 $backup"
}
trap rollback ERR
sqlite3 -readonly -json "$root/ai-team.db" 'select * from task order by id; select * from flow_run order by task_id; select * from task_checkpoint order by task_id; select * from stage_result order by task_id,role;' | sha256sum > "$backup/state.before"
cp -p "$build/orchestrator/target/$jar" "$root/orchestrator/target/$jar.next"
mv "$root/orchestrator/target/$jar.next" "$root/orchestrator/target/$jar"
cp -a "$build/orchestrator/src/." "$root/orchestrator/src/"
bash "$root/service.sh" start 8>&-
echo "BACKUP=$backup"
for attempt in {1..45}; do
  if grep -q 'INSPECTOR 直接對話 Socket Mode 已啟動' "$root/orchestrator.log" && grep -q '沙箱工具鏈自檢通過' "$root/orchestrator.log"; then
    sqlite3 -readonly -json "$root/ai-team.db" 'select * from task order by id; select * from flow_run order by task_id; select * from task_checkpoint order by task_id; select * from stage_result order by task_id,role;' | sha256sum > "$backup/state.after"
    # 只比對；若新事件已到達，不覆寫 DB 或強制停止正在執行的工作。
    if cmp -s "$backup/state.before" "$backup/state.after"; then echo 'PASS: task/flow/checkpoint/stage unchanged'; else echo 'NOTICE: state changed during startup; inspect new events, never overwrite DB'; fi
    sha256sum "$root/orchestrator/target/$jar"
    bash "$root/service.sh" status
    trap - ERR
    exit 0
  fi
  sleep 1
done
false
