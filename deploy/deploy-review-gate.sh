#!/usr/bin/env bash
set -euo pipefail
build=/home/vboxuser/ai-team/review-gate-build.8Mclsi
live=/home/vboxuser/ai-team/orchestrator
root=/home/vboxuser/ai-team
test -f "$build/target/ai-team-orchestrator-0.1.0.jar"
grep -q 'BUILD SUCCESS' "$build/build.log"
active=$(sqlite3 "$root/ai-team.db" "select count(*) from task where state like '%RUNNING' or state='QUEUED';")
if [ "$active" != 0 ]; then
    echo '有執行中或排隊任務，停止部署。'
    exit 1
fi
cp -p "$live/target/ai-team-orchestrator-0.1.0.jar" "$build/previous-service.jar"
cp -p "$root/orchestrator.log" "$build/previous-service.log"
pkill -f '[a]i-team-orchestrator-0.1.0.jar' || true
for attempt in {1..20}; do
    if ! pgrep -f '[a]i-team-orchestrator-0.1.0.jar' >/dev/null; then break; fi
    sleep 1
done
if pgrep -f '[a]i-team-orchestrator-0.1.0.jar' >/dev/null; then
    echo '舊服務尚未停止，未替換 jar。'
    exit 1
fi
for file in src/main/java/com/zipe/aiteam/task/TaskOrchestrator.java \
            src/main/java/com/zipe/aiteam/task/ReviewGate.java \
            src/main/java/com/zipe/aiteam/slack/SlackBlocks.java \
            src/test/java/com/zipe/aiteam/task/ReviewGateTest.java \
            prompts/code_review.md schemas/review-schema.json \
            target/ai-team-orchestrator-0.1.0.jar; do
    mkdir -p "$(dirname "$live/$file")"
    cp -p "$build/$file" "$live/$file"
done
cp -p "$build/prompts/code_review.md" "$root/prompts/code_review.md"
cp -p "$build/schemas/review-schema.json" "$root/schemas/review-schema.json"
setsid nohup bash "$root/start.sh" > "$root/orchestrator.log" 2>&1 </dev/null &
sleep 12
pgrep -f '[a]i-team-orchestrator-0.1.0.jar'
tail -16 "$root/orchestrator.log"
ss -tn state established
test -d "$root/workspaces/task-1010"
