#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
build=$root/quota-build.fi1Ckq
jar=ai-team-orchestrator-0.1.0.jar
db=$root/ai-team.db
exec 8>"$root/deployment.lock"
flock -n 8
grep -q 'BUILD SUCCESS' "$build/build-final.log"
state=$(sqlite3 -readonly "$db" "select state from task where id='1014';")
[[ $state == AWAIT_HUMAN || $state == WAITING_QUOTA ]]
[[ $(sqlite3 -readonly "$db" "select json_extract(data,'$.role') from task_checkpoint where task_id='1014';") == implementation ]]
[[ $(sqlite3 -readonly "$db" "select json_extract(cast(readfile('$root/logs/1014/implementation.stdout.json') as text),'$.is_error');") == 1 ]]
[[ $(sqlite3 -readonly "$db" "select instr(json_extract(cast(readfile('$root/logs/1014/implementation.stdout.json') as text),'$.result'),'hit your session limit')>0;") == 1 ]]
bash "$root/service.sh" stop
if pgrep -f '[a]i-team-orchestrator-0.1.0.jar' >/dev/null; then exit 1; fi
backup=$(mktemp -d "$root/quota-auto-backup.XXXXXX")
cp -p "$root/orchestrator/target/$jar" "$backup/$jar"
cp -a "$root/orchestrator/src" "$backup/src"
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
sqlite3 "$db" ".backup '$backup/ai-team.db'"
[[ $(sqlite3 -readonly "$backup/ai-team.db" 'pragma integrity_check;') == ok ]]
sqlite3 -readonly -json "$db" 'select * from flow_run order by task_id;' | sha256sum > "$backup/flows.before"
cp -p "$build/orchestrator/target/$jar" "$root/orchestrator/target/$jar.next"
mv "$root/orchestrator/target/$jar.next" "$root/orchestrator/target/$jar"
cp -a "$build/orchestrator/src/." "$root/orchestrator/src/"
# 僅修正本次明確授權且已核實的 #1014。保留 updated_at 為錯誤發生時間，供重置時刻換算。
sqlite3 "$db" <<SQL
BEGIN IMMEDIATE;
UPDATE task_checkpoint SET data=json_set(data,'$.reason',json_extract(cast(readfile('$root/logs/1014/implementation.stdout.json') as text),'$.result')) WHERE task_id='1014';
UPDATE task SET state='WAITING_QUOTA' WHERE id='1014' AND state='AWAIT_HUMAN';
INSERT INTO audit_log(task_id,at,actor,action,detail) VALUES('1014',strftime('%Y-%m-%dT%H:%M:%fZ','now'),'system','STATE_CHANGE','使用者授權依 implementation 原始 session limit 證據修正為 WAITING_QUOTA，啟用自動續跑；工作区、session 與流程快照保留。');
COMMIT;
SQL
sqlite3 -readonly -json "$db" 'select * from flow_run order by task_id;' | sha256sum > "$backup/flows.after"
cmp "$backup/flows.before" "$backup/flows.after"
bash "$root/service.sh" start 8>&-
printf 'BACKUP=%s\n' "$backup"
sha256sum "$root/orchestrator/target/$jar"
for attempt in {1..50}; do
  if grep -q 'INSPECTOR 直接對話 Socket Mode 已啟動' "$root/orchestrator.log" && [[ $(sqlite3 -readonly "$db" "select count(*) from sqlite_master where name='quota_retry';") == 1 ]]; then
    count=$(sqlite3 -readonly "$db" "select count(*) from quota_retry where task_id='1014';")
    if [[ $count == 1 ]]; then
      sqlite3 -readonly -header -column "$db" "select task_id,next_at,attempts from quota_retry; select id,state from task where id='1014';"
      grep -E '沙箱.*通過|配置角色 tester' "$root/orchestrator.log"
      bash "$root/service.sh" status
      exit 0
    fi
  fi
  sleep 1
done
echo "自動續跑初始化尚未通過，備份：$backup"
exit 1
