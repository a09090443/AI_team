#!/usr/bin/env bash
# 部署 ToolTrust 範圍修正與前置契約恢復（ContractRecovery／繼續按鈕）。
# 不修改任何任務狀態、不自動續跑、不調整額度或核准。
set -euo pipefail
umask 077

root=/home/vboxuser/ai-team
source_root=${1:?用法：deploy-contract-recovery-20260916.sh <隔離來源根目錄>}
build="$source_root/orchestrator"
jar=ai-team-orchestrator-0.1.0.jar
previous=bf53f7a317bc4480f55be5584a0a5e407c9c295758ad149f301ae0a2a977c135
expected=a338780217ae98d96becfef279c32b0a51a00ce59431d555fe18f48ad22c2f9b
repo="$root/workspaces/task-1014"

exec 8>"$root/deployment.lock"
flock -n 8

# 候選版本必須來自隔離目錄的完整建置，且與線上目前版本相符。
grep -q 'Tests run: 341, Failures: 0, Errors: 0, Skipped: 10' "$build/full-build.log"
grep -q 'BUILD SUCCESS' "$build/full-build.log"
test "$(sha256sum "$build/target/$jar" | cut -d' ' -f1)" = "$expected"
test "$(sha256sum "$root/orchestrator/target/$jar" | cut -d' ' -f1)" = "$previous"

# 沒有執行中或排隊的任務才可停服務。
test -z "$(sqlite3 -readonly "$root/ai-team.db" \
  "select id from task where state like '%RUNNING' or state in ('QUEUED','RESUME_QUEUED','TRIAGE_QUEUED');")"
# 也不能有還在跑的 agent —— 狀態欄位是事後才寫的，程序才是現場。
test -z "$(pgrep -f 'claude|codex|opencode' || true)"
# 任務停在哪個節點、等人還是等額度，都是會合法變動的值，不當成部署前置斷言；
# 真正的保證是「沒有東西在跑」加上部署前後的狀態雜湊一致。
test -z "$(git -C "$repo" status --porcelain=v1)"

AI_TEAM_REPO="$repo" java -jar "$build/target/$jar" --validate-team "$build/teams/spring-starters.json"
AI_TEAM_NODE_REPO="$repo" java -jar "$build/target/$jar" --validate-team "$build/teams/node-web.json"

snapshot() {
  sqlite3 -readonly -json "$root/ai-team.db" '
    select * from task order by id;
    select * from flow_run order by task_id;
    select * from task_checkpoint order by task_id;
    select * from direct_request order by task_id;
    select * from stage_result order by task_id,role;
    select * from quota_retry order by task_id;' | sha256sum
}
workspace_snapshot() {
  git -C "$repo" rev-parse HEAD
  git -C "$repo" status --porcelain=v1
  git -C "$repo" diff --binary
  git -C "$repo" diff --cached --binary
}

backup=$(mktemp -d "$root/contract-recovery-backup.XXXXXX")
cp -p "$root/orchestrator/target/$jar" "$backup/$jar"
cp -a "$root/orchestrator/src/main" "$backup/orchestrator-main"
cp -a "$root/teams" "$backup/teams"
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
sqlite3 "$root/ai-team.db" ".backup '$backup/ai-team.db'"
test "$(sqlite3 -readonly "$backup/ai-team.db" 'pragma integrity_check;')" = ok
snapshot > "$backup/tasks.before"
workspace_snapshot | sha256sum > "$backup/workspace.before"
# 交接額度是會合法變動的值，不當成部署前置斷言；記錄下來供部署前後對照即可。
sqlite3 -readonly "$root/ai-team.db" \
  "select json_extract(data,'\$.automaticHandoffs')||'/'||json_array_length(json_extract(data,'\$.automaticHandoffKeys'))
   from flow_run where task_id=1014;" > "$backup/handoffs.before"
sqlite3 -readonly "$root/ai-team.db" "select state from task where id=1014;" > "$backup/state.before"

rollback() {
  echo "部署檢查未通過；已啟動回復。備份：$backup"
  bash "$root/service.sh" stop || true
  cp -p "$backup/$jar" "$root/orchestrator/target/$jar"
  cp -a "$backup/orchestrator-main/." "$root/orchestrator/src/main/"
  cp -a "$backup/teams/." "$root/teams/"
}
trap rollback ERR

bash "$root/service.sh" stop
cp -p "$build/target/$jar" "$root/orchestrator/target/$jar.next"
mv "$root/orchestrator/target/$jar.next" "$root/orchestrator/target/$jar"
cp -a "$build/src/main/." "$root/orchestrator/src/main/"
# 角色工具白名單在 teams 配置裡；只有一併更新，新任務才會拿到唯讀 git 權限。
cp -a "$build/teams/." "$root/teams/"
bash "$root/service.sh" start 8>&-
echo "BACKUP=$backup"

for attempt in {1..45}; do
  if grep -q 'INSPECTOR 直接對話 Socket Mode 已啟動' "$root/orchestrator.log" \
      && grep -q '沙箱工具鏈自檢通過' "$root/orchestrator.log"; then
    snapshot > "$backup/tasks.after"
    workspace_snapshot | sha256sum > "$backup/workspace.after"
    cmp "$backup/tasks.before" "$backup/tasks.after"
    cmp "$backup/workspace.before" "$backup/workspace.after"
    test "$(sha256sum "$root/orchestrator/target/$jar" | cut -d' ' -f1)" = "$expected"
    # 狀態不必是某個特定值，但必須和部署前完全一樣。
    test "$(sqlite3 -readonly "$root/ai-team.db" "select state from task where id=1014;")" \
       = "$(cat "$backup/state.before")"
    # service.sh 啟動時會覆寫 orchestrator.log，所以這裡看到的就只有本次啟動。
    ! grep -q ' ERROR ' "$root/orchestrator.log"
    echo 'PASS: 所有任務資料與 #1014 工作區部署前後完全一致'
    echo "PASS: #1014 維持 $(cat "$backup/state.before")；已用受阻事件／已記錄證據 $(cat "$backup/handoffs.before")，未續跑、未改核准"
    sha256sum "$root/orchestrator/target/$jar"
    bash "$root/service.sh" status
    trap - ERR
    exit 0
  fi
  sleep 1
done
false
