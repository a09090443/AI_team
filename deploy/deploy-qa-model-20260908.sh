#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
build=$root/qa-model-build.uP2dUG
jar=ai-team-orchestrator-0.1.0.jar
db=$root/ai-team.db
exec 8>"$root/deployment.lock"
flock -n 8
grep -q 'BUILD SUCCESS' "$build/build.log"
export JAVA_HOME=/home/vboxuser/.local/java
export PATH=$JAVA_HOME/bin:/home/vboxuser/.local/maven/bin:$PATH
source "$root/env.sh" >/dev/null
java -jar "$build/orchestrator/target/$jar" --validate-team "$build/orchestrator/teams/spring-starters.json"
bash "$root/service.sh" stop
backup=$(mktemp -d "$root/qa-model-backup.XXXXXX")
cp -p "$root/orchestrator/target/$jar" "$backup/$jar"
cp -a "$root/orchestrator/src" "$backup/src"
cp -p "$root/teams/spring-starters.json" "$backup/team.json"
cp -p "$root/orchestrator/teams/spring-starters.json" "$backup/source-team.json"
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
sqlite3 "$db" ".backup '$backup/ai-team.db'"
[[ $(sqlite3 -readonly "$backup/ai-team.db" 'pragma integrity_check;') == ok ]]
sqlite3 -readonly -json "$db" 'select * from flow_run order by task_id;' | sha256sum > "$backup/flows.before"
rollback() {
  echo "部署失敗；備份：$backup；嘗試回復程式與配置（不回復資料庫）"
  bash "$root/service.sh" stop || return 1
  cp -p "$backup/$jar" "$root/orchestrator/target/$jar"
  cp -a "$backup/src/." "$root/orchestrator/src/"
  cp -p "$backup/team.json" "$root/teams/spring-starters.json"
  cp -p "$backup/source-team.json" "$root/orchestrator/teams/spring-starters.json"
  bash "$root/service.sh" start 8>&-
}
trap 'rollback' ERR
cp -p "$build/orchestrator/target/$jar" "$root/orchestrator/target/$jar.next"
mv "$root/orchestrator/target/$jar.next" "$root/orchestrator/target/$jar"
cp -a "$build/orchestrator/src/." "$root/orchestrator/src/"
cp -p "$build/orchestrator/teams/spring-starters.json" "$root/teams/spring-starters.json"
cp -p "$build/orchestrator/teams/spring-starters.json" "$root/orchestrator/teams/spring-starters.json"
bash "$root/service.sh" start 8>&-
printf 'BACKUP=%s\n' "$backup"
for attempt in {1..45}; do
  if grep -q 'INSPECTOR 直接對話 Socket Mode 已啟動' "$root/orchestrator.log" && grep -q '沙箱工具鏈自檢通過' "$root/orchestrator.log"; then
    sqlite3 -readonly -json "$db" 'select * from flow_run order by task_id;' | sha256sum > "$backup/flows.after"
    cmp "$backup/flows.before" "$backup/flows.after"
    jq '.roles.tester | {runtime,model,reasoningEffort}' "$root/teams/spring-starters.json"
    sqlite3 -readonly "$db" 'select id,state from task where id=1014; select task_id,next_at,attempts from quota_retry where task_id=1014;'
    sha256sum "$root/orchestrator/target/$jar"
    grep -E '配置角色 tester|新任務使用配置版本|沙箱.*通過' "$root/orchestrator.log"
    bash "$root/service.sh" status
    date -Iseconds
    trap - ERR
    exit 0
  fi
  sleep 1
done
false
