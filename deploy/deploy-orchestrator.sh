#!/usr/bin/env bash
# 通用受控部署：把隔離目錄建好的 orchestrator（JAR、src/main、teams）換到線上服務。
#
# 不指名任何任務、角色或專案。先前每一版部署腳本都寫死檢查 task-1014，
# 換一台機器或沒有那個任務就直接失敗；而「任務停在哪個節點、剩幾次額度」這類
# 會合法變動的值也不該當成前置斷言。真正的保證只有兩個：
#   1. 部署時沒有任何任務在跑或排隊、沒有 agent 程序
#   2. 所有任務資料與所有任務工作區在部署前後完全一致，否則自動回復
# 不修改任何任務狀態、不續跑、不調整額度或核准。
set -euo pipefail
umask 077

root=${AI_TEAM_HOME:-/home/vboxuser/ai-team}
source_root=${1:?用法：deploy-orchestrator.sh <隔離來源根目錄>}
build="$source_root/orchestrator"
jar=ai-team-orchestrator-0.1.0.jar

exec 8>"$root/deployment.lock"
flock -n 8

# ---- 候選版本：必須來自隔離目錄的完整建置 ----
grep -q 'BUILD SUCCESS' "$build/full-build.log"
summary=$(grep -E 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$build/full-build.log" | tail -1)
echo "$summary" | grep -qE 'Failures: 0, Errors: 0'
expected=$(sha256sum "$build/target/$jar" | cut -d' ' -f1)
previous=$(sha256sum "$root/orchestrator/target/$jar" | cut -d' ' -f1)
test "$expected" != "$previous" || { echo "候選版本與線上相同，不需部署"; exit 0; }
echo "候選 $expected（$summary）；線上 $previous"

# ---- 部署時不能有任何東西在跑 ----
test -z "$(sqlite3 -readonly "$root/ai-team.db" \
  "select id from task where state like '%RUNNING' or state in ('QUEUED','RESUME_QUEUED','TRIAGE_QUEUED');")"
test -z "$(pgrep -f 'claude|codex|opencode' || true)"

# ---- 所有團隊配置都要通過靜態驗證 ----
repo_path=$(bash -c 'set -a; . "$1/env.sh" >/dev/null 2>&1; [ -f "$1/team.env" ] && . "$1/team.env" >/dev/null 2>&1; printf %s "${AI_TEAM_REPO:-}"' _ "$root")
test -d "$repo_path/.git"
for team in "$build"/teams/*.json; do
  AI_TEAM_REPO="$repo_path" AI_TEAM_NODE_REPO="$repo_path" java -jar "$build/target/$jar" --validate-team "$team"
done

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
  for ws in "$root"/workspaces/task-*/; do
    [ -d "$ws/.git" ] || continue
    echo "== $ws"
    git -C "$ws" rev-parse HEAD
    git -C "$ws" status --porcelain=v1
    git -C "$ws" diff --binary | sha256sum
    git -C "$ws" diff --cached --binary | sha256sum
  done | sha256sum
}

backup=$(mktemp -d "$root/orchestrator-backup.XXXXXX")
cp -p "$root/orchestrator/target/$jar" "$backup/$jar"
if [ -d "$root/orchestrator/src/main" ]; then cp -a "$root/orchestrator/src/main" "$backup/orchestrator-main"; fi
cp -a "$root/teams" "$backup/teams"
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
sqlite3 "$root/ai-team.db" ".backup '$backup/ai-team.db'"
test "$(sqlite3 -readonly "$backup/ai-team.db" 'pragma integrity_check;')" = ok
snapshot > "$backup/tasks.before"
workspace_snapshot > "$backup/workspaces.before"
sqlite3 -readonly "$root/ai-team.db" \
  "select id||' '||state from task where state not in ('COMPLETED','ABORTED','PR_CREATED') order by id;" > "$backup/open-tasks.before"

rollback() {
  echo "部署檢查未通過；已啟動回復。備份：$backup"
  bash "$root/service.sh" stop || true
  cp -p "$backup/$jar" "$root/orchestrator/target/$jar"
  if [ -d "$backup/orchestrator-main" ]; then cp -a "$backup/orchestrator-main/." "$root/orchestrator/src/main/"; fi
  cp -a "$backup/teams/." "$root/teams/"
  bash "$root/service.sh" start 8>&- || true
}
trap rollback ERR

bash "$root/service.sh" stop
cp -p "$build/target/$jar" "$root/orchestrator/target/$jar.next"
mv "$root/orchestrator/target/$jar.next" "$root/orchestrator/target/$jar"
# JAR-only 安裝沒有來源目錄；來源不是執行必要資產，不另外建立。
if [ -d "$root/orchestrator/src/main" ]; then cp -a "$build/src/main/." "$root/orchestrator/src/main/"; fi
# 角色工具白名單在 teams 配置裡，不在 JAR 裡，必須一起更新。
cp -a "$build/teams/." "$root/teams/"
bash "$root/service.sh" start 8>&-
echo "BACKUP=$backup"

# service.sh 啟動時會覆寫 orchestrator.log，所以這裡看到的只有本次啟動。
for attempt in {1..60}; do
  if grep -q 'Started AiTeamApplication' "$root/orchestrator.log" \
      && grep -q '沙箱工具鏈自檢通過' "$root/orchestrator.log"; then
    sleep 15   # 各角色的 Socket Mode 連線在啟動後陸續建立
    snapshot > "$backup/tasks.after"
    workspace_snapshot > "$backup/workspaces.after"
    cmp "$backup/tasks.before" "$backup/tasks.after"
    cmp "$backup/workspaces.before" "$backup/workspaces.after"
    test "$(sha256sum "$root/orchestrator/target/$jar" | cut -d' ' -f1)" = "$expected"
    ! grep -q ' ERROR ' "$root/orchestrator.log"
    echo 'PASS: 所有任務資料與所有任務工作區部署前後完全一致'
    echo "PASS: 未結束的任務（未續跑、未改核准）："
    sed 's/^/  /' "$backup/open-tasks.before"
    sha256sum "$root/orchestrator/target/$jar"
    bash "$root/service.sh" status
    trap - ERR
    exit 0
  fi
  sleep 1
done
false
