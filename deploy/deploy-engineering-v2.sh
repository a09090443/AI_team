#!/usr/bin/env bash
# 本次已授權操作：刪除 #1011 活躍資料，部署 v2；不刪其他任務、主 repo 或 Slack 訊息。
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
stage=$root/engineering-v2-stage.jfyosm
db=$root/ai-team.db
live=$root/orchestrator
export JAVA_HOME=/home/vboxuser/.local/java
export PATH="$JAVA_HOME/bin:/home/vboxuser/.local/node/bin:/home/vboxuser/.local/maven/bin:/home/vboxuser/.local/bin:$PATH"
exec 9>"$root/deployment.lock"
flock -n 9 || { echo '另一個部署正在執行'; exit 1; }
grep -q 'BUILD SUCCESS' "$stage/build-final.log"
grep -q 'BUILD SUCCESS' "$stage/smoke.log"
source "$root/env.sh" >/dev/null
export AI_TEAM_DEFINITION="$root/teams/spring-starters.json"
java -jar "$stage/orchestrator/target/ai-team-orchestrator-0.1.0.jar" --validate-team "$stage/orchestrator/teams/spring-starters.json"
active() { sqlite3 -readonly "$db" "select count(*) from task where state like '%RUNNING' or state in ('QUEUED','RESUME_QUEUED','TRIAGE_QUEUED');"; }
[[ $(active) == 0 ]] || { echo '有執行中或排隊任務，拒絕部署'; exit 1; }
[[ $(sqlite3 -readonly "$db" "select state from task where id='1011';") == AWAIT_HUMAN ]]
[[ $(sqlite3 -readonly "$db" "select slack_thread_ts from task where id='1011';") == 1788660163.225849 ]]
sessions=(11697390-4886-42b6-9a9e-c1270fd26129 552b1b43-b12a-4eb2-8020-7c75aa5274e2 6a6b1f17-b1d1-4c51-89d8-8ec8965a5e4b d4724601-f266-45b2-a780-5044889ff6b6 d6d87d0e-24c9-4916-90e9-2050132bb128 fdc1f7e9-7e04-472f-b408-00d3c01449e6)
for sid in "${sessions[@]}"; do
 [[ $(sqlite3 -readonly "$db" "select count(*) from (select task_id,session_id from stage_result union all select task_id,session_id from stage_attempt union all select task_id,session_id from audit_log union all select task_id,json_extract(data,'$.sessionId') from task_checkpoint) where session_id='$sid' and task_id<>'1011';") == 0 ]]
done
targets=(
 "$root/workspaces/task-1011"
 "$root/logs/1011"
 /home/vboxuser/.claude/projects/-home-vboxuser-ai-team-workspaces-task-1011
 /home/vboxuser/.claude/projects/-home-vboxuser-ai-team-zipe-spring-boot-starters/11697390-4886-42b6-9a9e-c1270fd26129.jsonl
 /home/vboxuser/.claude/projects/-home-vboxuser-ai-team-zipe-spring-boot-starters/552b1b43-b12a-4eb2-8020-7c75aa5274e2.jsonl
 /home/vboxuser/.claude/projects/-home-vboxuser-ai-team-zipe-spring-boot-starters/d4724601-f266-45b2-a780-5044889ff6b6.jsonl
 /home/vboxuser/.claude/projects/-home-vboxuser-ai-team-zipe-spring-boot-starters/d6d87d0e-24c9-4916-90e9-2050132bb128.jsonl
 /home/vboxuser/.claude/session-env/552b1b43-b12a-4eb2-8020-7c75aa5274e2
 /home/vboxuser/.claude/session-env/6a6b1f17-b1d1-4c51-89d8-8ec8965a5e4b
 /home/vboxuser/.claude/session-env/d6d87d0e-24c9-4916-90e9-2050132bb128
 /home/vboxuser/.claude/session-env/fdc1f7e9-7e04-472f-b408-00d3c01449e6
)
for path in "${targets[@]}"; do
 [[ ! -L $path && $(realpath -m -- "$path") == "$path" ]] || { echo '刪除目標含 symlink 或解析位置不同，停止'; exit 1; }
done
others_hash() {
 for table in task stage_result stage_attempt task_checkpoint task_remediation audit_log; do
  column=task_id; [[ $table != task ]] || column=id
  sqlite3 -readonly -json "$db" "select * from $table where $column<>'1011' or $column is null order by rowid;"
 done | sha256sum | cut -d' ' -f1
}
main_before=$(git -C "$AI_TEAM_REPO" rev-parse HEAD)
others_before=$(others_hash)
# 保存其他工作區的 Git 版本；不做任何清理／移動。
for task in 1003 1004 1010; do git -C "$root/workspaces/task-$task" rev-parse HEAD; done > "$stage/other-heads.before"
pkill -f '[a]i-team-orchestrator-0.1.0.jar' || true
for attempt in {1..20}; do if ! pgrep -f '[a]i-team-orchestrator-0.1.0.jar' >/dev/null; then break; fi; sleep 1; done
! pgrep -f '[a]i-team-orchestrator-0.1.0.jar' >/dev/null || { echo '舊服務未停止，未清理'; exit 1; }
if [[ $(active) != 0 ]]; then
 setsid nohup bash "$root/start.sh" >> "$root/orchestrator.log" 2>&1 </dev/null 9>&- &
 echo '停止期間出現任務，已要求恢復舊服務，未清理或部署'; exit 1
fi
# 高水位須在刪除最大 ID 前保存；之後的新任務不會沿用舊 Slack 按鈕的 ID。
sqlite3 "$db" <<'SQL'
PRAGMA secure_delete=ON;
BEGIN IMMEDIATE;
CREATE TABLE IF NOT EXISTS task_sequence(id INTEGER PRIMARY KEY CHECK(id=1),value INTEGER NOT NULL);
INSERT INTO task_sequence(id,value) SELECT 1,MAX(1011,COALESCE(MAX(CAST(id AS INTEGER)),1000)) FROM task WHERE true
ON CONFLICT(id) DO UPDATE SET value=MAX(task_sequence.value,excluded.value);
DELETE FROM stage_result WHERE task_id='1011';
DELETE FROM stage_attempt WHERE task_id='1011';
DELETE FROM task_checkpoint WHERE task_id='1011';
DELETE FROM task_remediation WHERE task_id='1011';
DELETE FROM audit_log WHERE task_id='1011';
DELETE FROM task WHERE id='1011';
COMMIT;
PRAGMA wal_checkpoint(TRUNCATE);
SQL
[[ $(others_hash) == "$others_before" ]] || { echo '其他任務資料雜湊改變，停止'; exit 1; }
# 上方逐一驗證過的絕對、專屬目標；不可換成 glob 或工作區根目錄。
for path in "${targets[@]}"; do
 [[ ! -L $path && $(realpath -m -- "$path") == "$path" ]] || exit 1
 rm -rf -- "$path"
 printf 'REMOVED %s\n' "$path"
done
# 備份在清理之後建立，不另行保留 #1011 的專屬資料。既有歷史備份與共用診斷 log 不改寫。
backup=$(mktemp -d "$root/engineering-v2-backup.XXXXXX")
mkdir "$backup/orchestrator"
cp -a "$live/src" "$live/pom.xml" "$backup/orchestrator/"
cp -p "$live/target/ai-team-orchestrator-0.1.0.jar" "$backup/orchestrator/"
cp -a "$root/prompts" "$root/schemas" "$backup/"
cp -p "$root/env.sh" "$root/start.sh" "$root/orchestrator.log" "$backup/"
sqlite3 "$db" ".backup '$backup/ai-team.db'"
[[ $(sqlite3 -readonly "$backup/ai-team.db" 'pragma integrity_check;') == ok ]]
printf '%s\n' "$backup" > "$stage/backup-path.txt"
# 已有來源完整備份；移走舊來源避免 tar 疊檔殘留，不碰任何任務工作區。
[[ $(realpath "$live/src") == /home/vboxuser/ai-team/orchestrator/src ]]
mv "$live/src" "$backup/orchestrator/live-src"
cp -a "$stage/orchestrator/src" "$live/src"
cp -p "$stage/orchestrator/pom.xml" "$live/pom.xml"
cp -a "$stage/orchestrator/prompts/." "$root/prompts/"
cp -a "$stage/orchestrator/schemas/." "$root/schemas/"
cp -a "$stage/orchestrator/teams" "$root/teams"
cp -a "$stage/orchestrator/teams" "$live/teams"
for script in start.sh service.sh backup.sh restore.sh preflight.sh check-linux.sh linux-platform.sh; do
 cp -p "$stage/deploy/bootstrap/$script" "$root/$script"; chmod 700 "$root/$script"
done
# 不編輯含 token 的 env.sh；由可重現、非機密的 profile 覆蓋團隊配置。
cp -p "$stage/deploy/engineering-v2-team.env" "$root/team.env"
chmod 600 "$root/team.env"
cp -p "$stage/orchestrator/target/ai-team-orchestrator-0.1.0.jar" "$live/target/ai-team-orchestrator-0.1.0.jar.next"
mv "$live/target/ai-team-orchestrator-0.1.0.jar.next" "$live/target/ai-team-orchestrator-0.1.0.jar"
[[ $(git -C "$AI_TEAM_REPO" rev-parse HEAD) == "$main_before" ]]
for task in 1003 1004 1010; do git -C "$root/workspaces/task-$task" rev-parse HEAD; done > "$stage/other-heads.after"
cmp "$stage/other-heads.before" "$stage/other-heads.after"
[[ $(others_hash) == "$others_before" ]]
printf 'BACKUP=%s\n' "$backup"
printf 'SEQUENCE_HIGH_WATER='; sqlite3 -readonly "$db" 'select value from task_sequence where id=1;'
sha256sum "$live/target/ai-team-orchestrator-0.1.0.jar"
# 只送出啟動，另做真實 Slack／沙箱健康檢查才宣稱完成。
setsid nohup bash "$root/start.sh" > "$root/orchestrator.log" 2>&1 </dev/null 9>&- &
printf 'New service launch requested\n'
