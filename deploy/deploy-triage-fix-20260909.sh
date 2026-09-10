#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
build=$root/triage-fix-build.LFnvTZ
jar=ai-team-orchestrator-0.1.0.jar
db=$root/ai-team.db
exec 8>"$root/deployment.lock"
flock -n 8
grep -q 'BUILD SUCCESS' "$build/build.log"
test -d /home/vboxuser/.npm
test "$(git -C "$root/workspaces/task-1014" rev-parse --short HEAD)" = 814c9b1
test -z "$(git -C "$root/workspaces/task-1014" status --porcelain)"
export JAVA_HOME=/home/vboxuser/.local/java
export PATH=$JAVA_HOME/bin:/home/vboxuser/.local/maven/bin:/home/vboxuser/.local/node/bin:$PATH
source "$root/env.sh" >/dev/null
java -jar "$build/orchestrator/target/$jar" --validate-team "$build/orchestrator/teams/spring-starters.json"
bash "$root/service.sh" stop
backup=$(mktemp -d "$root/triage-fix-backup.XXXXXX")
cp -p "$root/orchestrator/target/$jar" "$backup/$jar"
cp -a "$root/orchestrator/src" "$backup/src"
cp -p "$root/teams/spring-starters.json" "$backup/team.json"
cp -p "$root/orchestrator/teams/spring-starters.json" "$backup/source-team.json"
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
sqlite3 "$db" ".backup '$backup/ai-team.db'"
[[ $(sqlite3 -readonly "$backup/ai-team.db" 'pragma integrity_check;') == ok ]]
rollback() {
  echo "修正部署失敗；備份：$backup；回復程式、配置及 #1014 快照，其他任務不改動"
  bash "$root/service.sh" stop || return 1
  cp -p "$backup/$jar" "$root/orchestrator/target/$jar"
  cp -a "$backup/src/." "$root/orchestrator/src/"
  cp -p "$backup/team.json" "$root/teams/spring-starters.json"
  cp -p "$backup/source-team.json" "$root/orchestrator/teams/spring-starters.json"
  sqlite3 "$db" "ATTACH '$backup/ai-team.db' AS old; UPDATE flow_run SET data=(SELECT data FROM old.flow_run WHERE task_id=1014) WHERE task_id=1014;"
  bash "$root/service.sh" start 8>&-
}
trap 'rollback' ERR
[[ $(sqlite3 -readonly "$db" 'select state from task where id=1014;') == AWAIT_HUMAN ]]
sqlite3 -readonly "$db" 'select data from flow_run where task_id=1014;' > "$backup/flow.before.json"
jq -f "$root/repair-1014-doc-tools.jq" "$backup/flow.before.json" > "$backup/flow.next.json"
# 維修快照採排序 JSON 的內容雜湊，稽核保存來源 revision；不冒用舊配置版本。
revision=$(jq -cS '.snapshot | del(.revision)' "$backup/flow.next.json" | sha256sum | cut -d' ' -f1)
jq --arg rev "$revision" '.snapshot.revision=$rev' "$backup/flow.next.json" > "$backup/flow.after.json"
for version in before after; do
  jq -cS 'del(.context,.snapshot.revision,.snapshot.definition.roles.builder.tools,.snapshot.definition.project.cachePaths,.snapshot.definition.project.requiredTools,.snapshot.definition.project.buildInstructions)' "$backup/flow.$version.json" > "$backup/invariants.$version"
done
cmp "$backup/invariants.before" "$backup/invariants.after"
sqlite3 -readonly -json "$db" 'select * from task order by id; select * from flow_run where task_id!=1014 order by task_id; select * from task_checkpoint order by task_id;' | sha256sum > "$backup/unrelated.before"
cp -p "$build/orchestrator/target/$jar" "$root/orchestrator/target/$jar.next"
mv "$root/orchestrator/target/$jar.next" "$root/orchestrator/target/$jar"
cp -a "$build/orchestrator/src/." "$root/orchestrator/src/"
cp -p "$build/orchestrator/teams/spring-starters.json" "$root/teams/spring-starters.json"
cp -p "$build/orchestrator/teams/spring-starters.json" "$root/orchestrator/teams/spring-starters.json"
sqlite3 "$db" "BEGIN IMMEDIATE; UPDATE flow_run SET data=cast(readfile('$backup/flow.after.json') AS TEXT) WHERE task_id=1014; INSERT INTO audit_log(task_id,at,actor,action,detail) VALUES('1014',strftime('%Y-%m-%dT%H:%M:%fZ','now'),'system','STATE_CHANGE','使用者授權修正 PM 工作區及文件建置权限。僅遷移工具與建置設定；舊 revision=1e66c07b26c5e2264dcdf965ae83d7f0ba80910432d65befd9649492a8c6692c，新 revision=$revision。保留模型、需求、計畫、checkpoint、狀態；未核准或續跑。'); COMMIT;"
bash "$root/service.sh" start 8>&-
printf 'BACKUP=%s\nTASK_REVISION=%s\n' "$backup" "$revision"
for attempt in {1..45}; do
  if grep -q 'INSPECTOR 直接對話 Socket Mode 已啟動' "$root/orchestrator.log" && grep -q '沙箱工具鏈自檢通過' "$root/orchestrator.log"; then
    sqlite3 -readonly -json "$db" 'select * from task order by id; select * from flow_run where task_id!=1014 order by task_id; select * from task_checkpoint order by task_id;' | sha256sum > "$backup/unrelated.after"
    cmp "$backup/unrelated.before" "$backup/unrelated.after"
    sha256sum "$root/orchestrator/target/$jar"
    grep -E '新任務使用配置版本|沙箱.*通過' "$root/orchestrator.log"
    bash "$root/service.sh" status
    date -Iseconds
    trap - ERR
    exit 0
  fi
  sleep 1
done
false
