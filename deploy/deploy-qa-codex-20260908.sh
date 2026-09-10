#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
candidate=$root/progress-build.UqhoBm/orchestrator/teams/spring-starters.json
exec 8>"$root/deployment.lock"
flock -n 8
grep -q 'BUILD SUCCESS' "$root/progress-build.UqhoBm/qa-codex-test.log"
export JAVA_HOME=/home/vboxuser/.local/java
export PATH=/home/vboxuser/.local/java/bin:/home/vboxuser/.local/maven/bin:$PATH
source "$root/env.sh" >/dev/null
java -jar "$root/orchestrator/target/ai-team-orchestrator-0.1.0.jar" --validate-team "$candidate"
bash "$root/service.sh" stop
if pgrep -f '[a]i-team-orchestrator-0.1.0.jar' >/dev/null; then exit 1; fi
backup=$(mktemp -d "$root/qa-codex-backup.XXXXXX")
cp -p "$root/teams/spring-starters.json" "$backup/spring-starters.json"
cp -p "$root/orchestrator/teams/spring-starters.json" "$backup/orchestrator-spring-starters.json"
sqlite3 "$root/ai-team.db" ".backup '$backup/ai-team.db'"
sqlite3 -readonly -json "$root/ai-team.db" 'select * from flow_run order by task_id;' | sha256sum > "$backup/flows.before"
cp -p "$candidate" "$root/teams/spring-starters.json"
cp -p "$candidate" "$root/orchestrator/teams/spring-starters.json"
bash "$root/service.sh" start 8>&-
printf 'BACKUP=%s\n' "$backup"
for attempt in {1..40}; do
  if grep -q '配置角色 tester：runtime=codex' "$root/orchestrator.log" && grep -q 'INSPECTOR 直接對話 Socket Mode 已啟動' "$root/orchestrator.log"; then
    sqlite3 -readonly -json "$root/ai-team.db" 'select * from flow_run order by task_id;' | sha256sum > "$backup/flows.after"
    cmp "$backup/flows.before" "$backup/flows.after"
    grep -E '配置角色 tester|新任務使用配置版本|沙箱.*通過' "$root/orchestrator.log"
    bash "$root/service.sh" status
    exit 0
  fi
  sleep 1
done
echo "啟動驗證失敗；舊配置備份：$backup"
exit 1
