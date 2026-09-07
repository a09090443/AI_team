#!/usr/bin/env bash
set -euo pipefail
build=/home/vboxuser/ai-team/qa-plan-build.NImyW8
root=/home/vboxuser/ai-team
live=$root/orchestrator
files=(
 src/main/java/com/zipe/aiteam/AiTeamApplication.java
 src/main/java/com/zipe/aiteam/agent/StageSpec.java
 src/main/java/com/zipe/aiteam/config/AiTeamProperties.java
 src/main/java/com/zipe/aiteam/task/Task.java
 src/main/java/com/zipe/aiteam/task/TaskOrchestrator.java
 src/main/java/com/zipe/aiteam/task/FailureReport.java
 src/main/java/com/zipe/aiteam/task/QaGate.java
 src/main/java/com/zipe/aiteam/slack/SlackBlocks.java
 src/test/java/com/zipe/aiteam/task/FailureReportTest.java
 src/test/java/com/zipe/aiteam/task/QuotaContinuationTest.java
 src/test/java/com/zipe/aiteam/task/QaGateTest.java
 src/test/java/com/zipe/aiteam/task/QaPipelineTest.java
 prompts/qa.md
 prompts/qa_plan.md
 prompts/backend.md
 schemas/qa-schema.json
 schemas/qa-plan-schema.json
 target/ai-team-orchestrator-0.1.0.jar
)
grep -q 'BUILD SUCCESS' "$build/build.log"
grep -q 'Tests run: 68, Failures: 0, Errors: 0, Skipped: 0' "$build/build.log"
for file in "${files[@]}"; do test -f "$build/$file"; done
active_count() {
 sqlite3 "$root/ai-team.db" "select count(*) from task where state like '%RUNNING' or state in ('QUEUED','RESUME_QUEUED');"
}
if [ "$(active_count)" != 0 ]; then echo '有執行中任務，停止部署'; exit 1; fi
backup=$(mktemp -d /home/vboxuser/ai-team/qa-plan-backup.XXXXXX)
for file in "${files[@]}"; do
 if [ -f "$live/$file" ]; then
  mkdir -p "$(dirname "$backup/orchestrator/$file")"
  cp -p "$live/$file" "$backup/orchestrator/$file"
 fi
done
cp -a "$root/prompts" "$root/schemas" "$backup/"
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
pkill -f '[a]i-team-orchestrator-0.1.0.jar' || true
for attempt in {1..20}; do
 if ! pgrep -f '[a]i-team-orchestrator-0.1.0.jar' >/dev/null; then break; fi
 sleep 1
done
if pgrep -f '[a]i-team-orchestrator-0.1.0.jar' >/dev/null; then echo '服務未停止，未替換 jar'; exit 1; fi
if [ "$(active_count)" != 0 ]; then
 setsid nohup bash "$root/start.sh" > "$root/orchestrator.log" 2>&1 </dev/null &
 echo '停止期間出現任務，已恢復舊版，未部署'; exit 1
fi
sqlite3 "$root/ai-team.db" ".backup $backup/ai-team.db"
for file in "${files[@]}"; do
 mkdir -p "$(dirname "$live/$file")"
 cp -p "$build/$file" "$live/$file"
done
for file in prompts/qa.md prompts/qa_plan.md prompts/backend.md schemas/qa-schema.json schemas/qa-plan-schema.json; do
 cp -p "$build/$file" "$root/$file"
done
setsid nohup bash "$root/start.sh" > "$root/orchestrator.log" 2>&1 </dev/null &
sleep 12
pgrep -f '[a]i-team-orchestrator-0.1.0.jar'
tail -22 "$root/orchestrator.log"
ss -tn state established
sha256sum "$live/target/ai-team-orchestrator-0.1.0.jar" "$build/target/ai-team-orchestrator-0.1.0.jar"
echo "備份：$backup"
