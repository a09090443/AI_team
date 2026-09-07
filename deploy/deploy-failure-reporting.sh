#!/usr/bin/env bash
set -euo pipefail
build=/home/vboxuser/ai-team/failure-report-build.nI8bpq
live=/home/vboxuser/ai-team/orchestrator
root=/home/vboxuser/ai-team
files=(
  src/main/java/com/zipe/aiteam/task/TaskOrchestrator.java
  src/main/java/com/zipe/aiteam/task/FailureReport.java
  src/main/java/com/zipe/aiteam/runtime/Diagnostics.java
  src/main/java/com/zipe/aiteam/runtime/ClaudeCodeRuntime.java
  src/main/java/com/zipe/aiteam/runtime/CodexRuntime.java
  src/main/java/com/zipe/aiteam/agent/StageRunner.java
  src/main/java/com/zipe/aiteam/slack/SlackNotifier.java
  src/test/java/com/zipe/aiteam/task/ReviewGateTest.java
  src/test/java/com/zipe/aiteam/task/FailureReportTest.java
  src/test/java/com/zipe/aiteam/runtime/DiagnosticsTest.java
  src/test/java/com/zipe/aiteam/runtime/RuntimeDiagnosticsTest.java
  src/test/java/com/zipe/aiteam/runtime/CodexRuntimeTest.java
  target/ai-team-orchestrator-0.1.0.jar
)
grep -q 'BUILD SUCCESS' "$build/build.log"
for file in "${files[@]}"; do test -f "$build/$file"; done
active=$(sqlite3 "$root/ai-team.db" "select count(*) from task where state like '%RUNNING' or state='QUEUED';")
if [ "$active" != 0 ]; then
    echo '有執行中或排隊任務，停止部署。'
    exit 1
fi
backup=$(mktemp -d /home/vboxuser/ai-team/failure-report-backup.XXXXXX)
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
if pgrep -f '[a]i-team-orchestrator-0.1.0.jar' >/dev/null; then
    echo '舊服務尚未停止，未替換 jar。'
    exit 1
fi
for file in "${files[@]}"; do cp -p "$build/$file" "$live/$file"; done
setsid nohup bash "$root/start.sh" > "$root/orchestrator.log" 2>&1 </dev/null &
sleep 12
pgrep -f '[a]i-team-orchestrator-0.1.0.jar'
tail -16 "$root/orchestrator.log"
ss -tn state established
test -d "$root/workspaces/task-1010"
echo "備份：$backup"
sha256sum "$live/target/ai-team-orchestrator-0.1.0.jar" "$build/target/ai-team-orchestrator-0.1.0.jar"
