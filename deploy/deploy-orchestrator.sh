#!/usr/bin/env bash
# 通用受控部署：把隔離目錄建好的 orchestrator（JAR、src/main、teams）換到線上服務。
# teams 是來源內附配置疊上實例 overlay（AI_TEAM_TEAM_OVERLAY，預設 $AI_TEAM_HOME/team-overlay）的結果，見 bootstrap/stage-teams.sh。
#
# 不指名任何任務、角色或專案。先前每一版部署腳本都寫死檢查 task-1014，
# 換一台機器或沒有那個任務就直接失敗；而「任務停在哪個節點、剩幾次額度」這類
# 會合法變動的值也不該當成前置斷言。真正的保證只有兩個：
#   1. 部署時本實例沒有任何任務在跑或排隊、沒有本實例的 agent／驗證 unit
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

instance_env() {
  bash -c 'set -a; . "$1/env.sh" >/dev/null 2>&1; [ -f "$1/team.env" ] && . "$1/team.env" >/dev/null 2>&1; printf %s "${!2:-}"' _ "$root" "$1"
}

# ---- 部署時本實例不能有任何東西在跑 ----
running=$(sqlite3 -readonly "$root/ai-team.db" \
  "select group_concat(id||':'||state, ' ') from task where state like '%RUNNING' or state in ('QUEUED','RESUME_QUEUED','TRIAGE_QUEUED');")
test -z "$running" || { echo "仍有執行中或排隊的任務（$running），停止"; exit 1; }
# 模型、基準驗證與預檢都在 AiTeamProperties.unitPrefix() 命名的 systemd unit 內執行。只看本實例的 unit：
# 同一台 VM 另一個實例重啟後跑基準驗證，不該擋住這裡（舊版用全機 pgrep，會把別的實例甚至呼叫者自己的指令列當成本實例的工作）。
instance=$(instance_env AI_TEAM_INSTANCE)
if [[ -n $instance ]]; then unit_patterns=("ai-team-$instance-*")
else unit_patterns=('ai-team-[0-9]*' 'ai-team-check-*' 'ai-team-selftest-*' 'ai-team-preflight-*' 'ai-team-codex-preflight-*'); fi
# 常駐的 <實例>-ssh-deploy.service 部署執行器同字首但不是工作，排除。
units=$(systemctl --user list-units "${unit_patterns[@]}" --no-legend --plain 2>/dev/null \
  | awk '$1 !~ /-ssh-deploy\.service$/ {print $1}' | tr '\n' ' ')
test -z "${units// /}" || { echo "仍有本實例的 unit 在跑（$units），停止"; exit 1; }
# 未經 systemd 沙箱時模型是本實例服務的子程序：只檢查本實例 JAR 程序底下的 agent CLI。
service_jar="$root/orchestrator/target/$jar"
agents=$(ps -eo pid=,ppid=,args= | awk -v jar="$service_jar" '
  { pid[NR]=$1; parent[$1]=$2; line[$1]=$0 }
  index($0, " " jar) { root_pid[$1]=1 }
  END {
    for (i = 1; i <= NR; i++) {
      p = pid[i]; q = parent[p]; hops = 0
      while (q != "" && q != 0 && !(q in root_pid) && hops++ < 64) q = parent[q]
      if ((q in root_pid) && line[p] ~ /(claude|codex|opencode)/) printf "%s ", p
    }
  }')
test -z "$agents" || { echo "仍有本實例服務底下的 agent 程序（PID $agents），停止"; exit 1; }

# ---- 來源 teams＋實例 overlay 合併後，所有團隊配置都要通過靜態驗證 ----
repo_path=$(instance_env AI_TEAM_REPO)
definition=$(instance_env AI_TEAM_DEFINITION)
overlay=$(instance_env AI_TEAM_TEAM_OVERLAY); overlay=${overlay:-$root/team-overlay}
test -d "$repo_path/.git"
staged=$(mktemp -d "$root/teams-stage.XXXXXX")
AI_TEAM_REPO="$repo_path" AI_TEAM_DEFINITION="$definition" \
  bash "$source_root/deploy/bootstrap/stage-teams.sh" "$build/target/$jar" "$build/teams" "$overlay" "$root/teams" "$staged" \
  || { rm -rf "$staged"; exit 1; }

snapshot() {
  # Stream rows in a read-only transaction. sqlite3 CLI -json can spend minutes escaping large flow snapshots.
  python3 - "$root/ai-team.db" <<'SNAPSHOT'
from contextlib import closing
from pathlib import Path
import hashlib, json, sqlite3, sys
checksum = hashlib.sha256()
tables = {"task": "id", "flow_run": "task_id", "task_checkpoint": "task_id",
          "direct_request": "task_id", "stage_result": "task_id,role", "quota_retry": "task_id"}
uri = Path(sys.argv[1]).resolve().as_uri() + "?mode=ro"
with closing(sqlite3.connect(uri, uri=True)) as db:
    db.execute("BEGIN")
    for table, order in tables.items():
        checksum.update(table.encode() + b"\0")
        for row in db.execute("select * from " + table + " order by " + order):
            checksum.update(json.dumps(row, ensure_ascii=False, separators=(",", ":")).encode() + b"\n")
print(checksum.hexdigest())
SNAPSHOT
}

# 逐工作區比對：新版啟動會依規則清掉已結束任務的工作區，這不算部署改動了任務（規則見 bootstrap/workspace-check.sh）。
workspace_snapshot() { bash "$source_root/deploy/bootstrap/workspace-check.sh" snapshot "$root"; }

backup=$(mktemp -d "$root/orchestrator-backup.XXXXXX")
cp -p "$root/orchestrator/target/$jar" "$backup/$jar"
if [ -d "$root/orchestrator/src/main" ]; then cp -a "$root/orchestrator/src/main" "$backup/orchestrator-main"; fi
cp -a "$root/teams" "$backup/teams"
cp -p "$root/orchestrator.log" "$backup/orchestrator.log"
sqlite3 "$root/ai-team.db" ".backup '$backup/ai-team.db'"
test "$(sqlite3 -readonly "$backup/ai-team.db" 'pragma integrity_check;')" = ok
mv "$staged" "$backup/teams.deployed"; staged="$backup/teams.deployed"
snapshot > "$backup/tasks.before"
workspace_snapshot > "$backup/workspaces.before"
sqlite3 -readonly "$root/ai-team.db" \
  "select id||' '||state from task where state not in ('COMPLETED','ABORTED','PR_CREATED') order by id;" > "$backup/open-tasks.before"

# 部署成功後輪替 orchestrator-backup.*：本次備份（回復點）一律保留，再保留最近的 N-1 份（依修改時間）。
# N 取 AI_TEAM_BACKUP_KEEP（env.sh／team.env 或呼叫端環境），預設 5；0 或非數字＝不輪替。失敗的部署不輪替，留作證據。
prune_backups() {
  local keep dir n=0
  keep=${AI_TEAM_BACKUP_KEEP:-$(instance_env AI_TEAM_BACKUP_KEEP)}; keep=${keep:-5}
  [[ $keep =~ ^[0-9]+$ ]] && (( keep > 0 )) || { echo "備份輪替：AI_TEAM_BACKUP_KEEP=$keep，不輪替"; return 0; }
  while IFS= read -r dir; do
    [[ -d $dir && $dir != "$backup" ]] || continue
    n=$((n + 1)); (( n < keep )) && continue
    rm -rf -- "$dir" && echo "備份輪替：刪除 $dir"
  done < <(ls -1dt "$root"/orchestrator-backup.* 2>/dev/null)
}

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
# 角色工具白名單在 teams 配置裡，不在 JAR 裡，必須一起更新；實例配置由 overlay 帶入，來源沒有的舊檔不刪。
cp -a "$staged/." "$root/teams/"
bash "$root/service.sh" start 8>&-
echo "BACKUP=$backup"

# service.sh 啟動時會覆寫 orchestrator.log，所以這裡看到的只有本次啟動。
for attempt in {1..60}; do
  if grep -q 'Started AiTeamApplication' "$root/orchestrator.log" \
      && grep -q '沙箱工具鏈自檢通過' "$root/orchestrator.log"; then
    sleep 15   # 各角色的 Socket Mode 連線在啟動後陸續建立
    # 啟動清理可能要刪 GB 級工作區；刪到一半就拍快照會被判成「工作區被改動」而誤回復。
    # 舊版 JAR 不印這行，等滿 300 秒照常比對（舊版只刪小工作區）。
    for wait in {1..300}; do grep -q '啟動清理完成' "$root/orchestrator.log" && break; sleep 1; done
    snapshot > "$backup/tasks.after"
    workspace_snapshot > "$backup/workspaces.after"
    cmp "$backup/tasks.before" "$backup/tasks.after"
    bash "$source_root/deploy/bootstrap/workspace-check.sh" compare \
      "$backup/workspaces.before" "$backup/workspaces.after" "$root/ai-team.db"
    test "$(sha256sum "$root/orchestrator/target/$jar" | cut -d' ' -f1)" = "$expected"
    ! grep -q ' ERROR ' "$root/orchestrator.log"
    echo 'PASS: 所有任務資料與仍在的任務工作區部署前後完全一致（已結束任務的工作區由啟動清理依規則刪除）'
    echo "PASS: 未結束的任務（未續跑、未改核准）："
    sed 's/^/  /' "$backup/open-tasks.before"
    sha256sum "$root/orchestrator/target/$jar"
    bash "$root/service.sh" status
    trap - ERR
    prune_backups || echo "備份輪替失敗，不影響本次部署"
    exit 0
  fi
  sleep 1
done
false
