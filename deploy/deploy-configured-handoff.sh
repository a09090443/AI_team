#!/usr/bin/env bash
# 既有 v2 的交接通知修正；不修改 prompt、配置、憑證或任務狀態。
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
stage=$root/handoff-stage.FpcS54
live=$root/orchestrator
export PATH="$HOME/.local/bin:$PATH"
exec 9>"$root/deployment.lock"; flock -n 9
grep -q 'BUILD SUCCESS' "$stage/build.log"
[[ $(sha256sum "$live/target/ai-team-orchestrator-0.1.0.jar" | cut -d' ' -f1) == 7a706b133887b79fc660732e2a940d09adea5916518366dbc7d744c0c900d7f1 ]]
active() { sqlite3 -readonly "$root/ai-team.db" "select count(*) from task where state like '%RUNNING' or state in ('QUEUED','RESUME_QUEUED','TRIAGE_QUEUED');"; }
[[ $(active) == 0 ]] || { echo '任務執行中，拒絕部署'; exit 1; }
bash "$root/service.sh" stop
[[ $(active) == 0 ]] || { echo '停止期間出現任務，停止部署，需人工確認'; exit 1; }
backup=$(mktemp -d "$root/handoff-backup.XXXXXX")
cp -a "$live/src" "$backup/src"
cp -p "$live/target/ai-team-orchestrator-0.1.0.jar" "$root/orchestrator.log" "$backup/"
sqlite3 "$root/ai-team.db" ".backup '$backup/ai-team.db'"
[[ $(sqlite3 -readonly "$backup/ai-team.db" 'pragma integrity_check;') == ok ]]
# 更新已確認的兩個正式檔案與對應測試；不覆蓋其他來源。
cp -p "$stage/orchestrator/src/main/java/com/zipe/aiteam/flow/FlowEngine.java" "$live/src/main/java/com/zipe/aiteam/flow/FlowEngine.java"
cp -p "$stage/orchestrator/src/main/java/com/zipe/aiteam/slack/SlackNotifier.java" "$live/src/main/java/com/zipe/aiteam/slack/SlackNotifier.java"
cp -p "$stage/orchestrator/src/test/java/com/zipe/aiteam/flow/FlowEngineTest.java" "$live/src/test/java/com/zipe/aiteam/flow/FlowEngineTest.java"
cp -p "$stage/orchestrator/src/test/java/com/zipe/aiteam/slack/ConfiguredMentionTest.java" "$live/src/test/java/com/zipe/aiteam/slack/ConfiguredMentionTest.java"
cp -p "$stage/orchestrator/target/ai-team-orchestrator-0.1.0.jar" "$live/target/ai-team-orchestrator-0.1.0.jar.next"
mv "$live/target/ai-team-orchestrator-0.1.0.jar.next" "$live/target/ai-team-orchestrator-0.1.0.jar"
printf 'BACKUP=%s\n' "$backup"
sha256sum "$live/target/ai-team-orchestrator-0.1.0.jar"
bash "$root/service.sh" start
