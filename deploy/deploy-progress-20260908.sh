#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
build=$root/progress-build.UqhoBm
live=$root/orchestrator
jar=ai-team-orchestrator-0.1.0.jar
exec 8>"$root/deployment.lock"
flock -n 8
grep -q 'BUILD SUCCESS' "$build/test-final.log"
[[ $(sha256sum "$build/orchestrator/target/$jar" | cut -d' ' -f1) == a73017c0d066f0c35fe3283c821cc0b71135b7ac22b24c8aed796d751d7d90ad ]]
bash "$root/service.sh" stop
if pgrep -f '[a]i-team-orchestrator-0.1.0.jar' >/dev/null; then echo '服務未停止'; exit 1; fi
backup=$(mktemp -d "$root/progress-backup.XXXXXX")
cp -p "$live/target/$jar" "$backup/$jar"
cp -a "$live/src" "$backup/src"
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
sqlite3 "$root/ai-team.db" ".backup '$backup/ai-team.db'"
[[ $(sqlite3 -readonly "$backup/ai-team.db" 'pragma integrity_check;') == ok ]]
sqlite3 -readonly -json "$root/ai-team.db" 'select * from task order by id;' | sha256sum > "$backup/tasks.before"
cp -p "$build/orchestrator/target/$jar" "$live/target/$jar.next"
mv "$live/target/$jar.next" "$live/target/$jar"
cp -a "$build/orchestrator/src/." "$live/src/"
printf 'BACKUP=%s\n' "$backup"
bash "$root/service.sh" start
for attempt in {1..40}; do
  if grep -q 'INSPECTOR 直接對話 Socket Mode 已啟動' "$root/orchestrator.log" && grep -q '沙箱工具鏈自檢通過' "$root/orchestrator.log"; then
    sqlite3 -readonly -json "$root/ai-team.db" 'select * from task order by id;' | sha256sum > "$backup/tasks.after"
    cmp "$backup/tasks.before" "$backup/tasks.after"
    sha256sum "$live/target/$jar"
    bash "$root/service.sh" status
    date -Iseconds
    exit 0
  fi
  sleep 1
done
echo "健康檢查未完成，回復版本位於 $backup；請檢查啟動 log"
exit 1
