#!/usr/bin/env bash
set -euo pipefail
build=/home/vboxuser/ai-team/quota-build.BMv0r3
live=/home/vboxuser/ai-team/orchestrator
root=/home/vboxuser/ai-team
files=(
 src/main/java/com/zipe/aiteam/task/TaskOrchestrator.java
 src/main/java/com/zipe/aiteam/task/Task.java
 src/main/java/com/zipe/aiteam/task/Checkpoint.java
 src/main/java/com/zipe/aiteam/store/TaskStore.java
 src/main/java/com/zipe/aiteam/runtime/AgentRuntime.java
 src/main/java/com/zipe/aiteam/runtime/ClaudeCodeRuntime.java
 src/main/java/com/zipe/aiteam/runtime/CodexRuntime.java
 src/main/java/com/zipe/aiteam/runtime/QuotaExceeded.java
 src/main/java/com/zipe/aiteam/runtime/SessionCapture.java
 src/main/java/com/zipe/aiteam/agent/StageRunner.java
 src/main/java/com/zipe/aiteam/slack/SlackBlocks.java
 src/main/java/com/zipe/aiteam/slack/SlackListener.java
 src/main/java/com/zipe/aiteam/workspace/WorkspaceManager.java
 src/main/resources/schema.sql
 src/test/java/com/zipe/aiteam/task/FailureReportTest.java
 src/test/java/com/zipe/aiteam/task/QuotaContinuationTest.java
 src/test/java/com/zipe/aiteam/runtime/QuotaExceededTest.java
 src/test/java/com/zipe/aiteam/runtime/CodexRuntimeTest.java
 src/test/java/com/zipe/aiteam/workspace/WorkspaceManagerTest.java
 target/ai-team-orchestrator-0.1.0.jar
)
grep -q 'BUILD SUCCESS' "$build/build.log"
for file in "${files[@]}"; do test -f "$build/$file"; done
active=$(sqlite3 "$root/ai-team.db" "select count(*) from task where state like '%RUNNING' or state in ('QUEUED','RESUME_QUEUED');")
if [ "$active" != 0 ]; then echo '有執行中／排隊任務，停止部署。'; exit 1; fi
backup=$(mktemp -d /home/vboxuser/ai-team/quota-backup.XXXXXX)
for file in "${files[@]}"; do
 if [ -f "$live/$file" ]; then
   mkdir -p "$(dirname "$backup/$file")"
   cp -p "$live/$file" "$backup/$file"
 fi
done
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
pkill -f '[a]i-team-orchestrator-0.1.0.jar' || true
for attempt in {1..20}; do
 if ! pgrep -f '[a]i-team-orchestrator-0.1.0.jar' >/dev/null; then break; fi
 sleep 1
done
if pgrep -f '[a]i-team-orchestrator-0.1.0.jar' >/dev/null; then echo '服務未停止，未替換 jar。'; exit 1; fi
sqlite3 "$root/ai-team.db" ".backup $backup/ai-team.db"
for file in "${files[@]}"; do cp -p "$build/$file" "$live/$file"; done
setsid nohup bash "$root/start.sh" > "$root/orchestrator.log" 2>&1 </dev/null &
sleep 12
pgrep -f '[a]i-team-orchestrator-0.1.0.jar'
tail -16 "$root/orchestrator.log"
sqlite3 "$root/ai-team.db" '.schema task_checkpoint'
ss -tn state established
test -d "$root/workspaces/task-1010"
echo "備份：$backup"
sha256sum "$live/target/ai-team-orchestrator-0.1.0.jar" "$build/target/ai-team-orchestrator-0.1.0.jar"
