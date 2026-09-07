#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
build=$root/pm-triage-build.SzUZFI
live=$root/orchestrator
export PATH=/home/vboxuser/.local/bin:$PATH
grep -q 'BUILD SUCCESS' "$build/build.log"
test -s "$build/target/ai-team-orchestrator-0.1.0.jar"
active_count() {
 sqlite3 -readonly "$root/ai-team.db" "select count(*) from task where state like '%RUNNING' or state in ('QUEUED','RESUME_QUEUED','TRIAGE_QUEUED');"
}
[[ $(active_count) == 0 ]] || { echo '有執行中或排隊任務，未部署'; exit 1; }
backup=$(mktemp -d "$root/pm-triage-backup.XXXXXX")
cp -a "$live/src" "$live/pom.xml" "$backup/"
cp -p "$live/target/ai-team-orchestrator-0.1.0.jar" "$backup/"
cp -a "$root/prompts" "$root/schemas" "$backup/"
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
echo "備份：$backup"
pkill -f '[a]i-team-orchestrator-0.1.0.jar' || true
for attempt in {1..20}; do
 if ! pgrep -f '[a]i-team-orchestrator-0.1.0.jar' >/dev/null; then break; fi
 sleep 1
done
if pgrep -f '[a]i-team-orchestrator-0.1.0.jar' >/dev/null; then echo '服務未停止，未替換'; exit 1; fi
if [[ $(active_count) != 0 ]]; then
 setsid nohup bash "$root/start.sh" > "$root/orchestrator.log" 2>&1 </dev/null &
 echo '停止期间出現任務，已重啟舊版，未部署'; exit 1
fi
sqlite3 "$root/ai-team.db" ".backup $backup/ai-team.db"
cp -a "$build/src/." "$live/src/"
cp -p "$build/pom.xml" "$live/pom.xml"
cp -a "$build/prompts/." "$live/prompts/"
cp -a "$build/schemas/." "$live/schemas/"
cp -a "$build/prompts/." "$root/prompts/"
cp -a "$build/schemas/." "$root/schemas/"
cp -p "$build/target/ai-team-orchestrator-0.1.0.jar" "$live/target/ai-team-orchestrator-0.1.0.jar"
setsid nohup bash "$root/start.sh" > "$root/orchestrator.log" 2>&1 </dev/null &
echo '新版啟動已送出，須另查本次啟動與連線健康狀態。'
sha256sum "$live/target/ai-team-orchestrator-0.1.0.jar" "$build/target/ai-team-orchestrator-0.1.0.jar"
