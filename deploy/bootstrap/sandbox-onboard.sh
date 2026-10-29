#!/usr/bin/env bash
# Agent VM 端：一個指令讓一個 orchestrator 實例接上共用沙箱 VM（沙箱已由 deploy/sandbox-vm/create-sandbox-vm.sh 建好）。
#
#   sandbox-onboard.sh <實例 home> <target> <沙箱 IP> <known_hosts 檔> [--paths a,b,...] [--restart]
#
# 依序處理先前導入時一定會撞到的事，可重複執行：
#   1. 建立配置 cachePaths 宣告、但這台機器還沒有的快取目錄（缺了任務會停在派工前檢查）。
#   2. 實例 overlay 補上沙箱服務路線並設定 serviceTarget（sandbox-overlay.py），以候選配置靜態驗證通過才寫回 overlay 與 teams/。
#   3. 量要送到沙箱的工作樹大小；超過 exec 上限只警告（agent 可用 source-paths 自行縮小）。
#   4. sandbox-target.sh register：直連設定、部署執行器與目標確認。
#   5. --restart：沒有執行中的任務才重啟實例，讓新配置生效；否則提示稍後重啟。
# 不讀出憑證、不需要 sudo。--paths 是管理者允許送出的路徑（預設整個工作樹）。
set -euo pipefail
umask 077
home=${1:?用法：sandbox-onboard.sh <實例 home> <target> <沙箱 IP> <known_hosts 檔> [--paths a,b] [--restart]}
target=${2:?缺少 target}; address=${3:?缺少沙箱 IP}; hosts=${4:?缺少 known_hosts 檔}; shift 4
paths=.; restart=false
while (($#)); do
  case $1 in
    --paths) paths=${2:?--paths 需要逗號分隔的路徑}; shift 2;;
    --restart) restart=true; shift;;
    *) echo "未知參數：$1"; exit 1;;
  esac
done
here=$(cd "$(dirname "$0")" && pwd)
home=$(realpath "$home")
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/java}
export PATH="$JAVA_HOME/bin:$HOME/.local/bin:$PATH"
instance_env() {
  bash -c 'set -a; . "$1/env.sh" >/dev/null 2>&1; [ -f "$1/team.env" ] && . "$1/team.env" >/dev/null 2>&1; printf %s "${!2:-}"' _ "$home" "$1"
}
definition=$(instance_env AI_TEAM_DEFINITION)
[[ -n $definition ]] || { echo "實例沒有設定 AI_TEAM_DEFINITION（不是配置式團隊）"; exit 1; }
[[ $definition == /* ]] || definition=$home/$definition
definition=$(realpath "$definition")
[[ $(dirname "$definition") == "$home/teams" ]] || { echo "啟用中的配置不在 $home/teams：$definition；請手動處理"; exit 1; }
name=$(basename "$definition")
repo=$(instance_env AI_TEAM_REPO)
overlay=$(instance_env AI_TEAM_TEAM_OVERLAY); overlay=${overlay:-$home/team-overlay}
template=${AI_TEAM_SANDBOX_TEMPLATE:-$home/teams/maven-library.json}
jar=$home/orchestrator/target/ai-team-orchestrator-0.1.0.jar

echo "== 1. 快取目錄"
python3 - "$definition" <<'PY' | while IFS= read -r cache; do
import json, sys
for path in json.load(open(sys.argv[1], encoding="utf-8"))["project"].get("cachePaths") or []:
    print(path)
PY
  dir=$HOME/$cache
  [[ ! -L $dir ]] || { echo "快取路徑是 symlink，請管理者確認：$dir"; exit 1; }
  if [[ -d $dir ]]; then echo "已存在：$dir"; else mkdir -p "$dir"; echo "已建立：$dir"; fi
done

echo "== 2. 團隊配置"
mkdir -p "$overlay"
[[ -f $overlay/$name ]] || { cp "$definition" "$overlay/$name"; echo "overlay 沒有 $name，以目前配置建立"; }
candidate=$(mktemp); stage=$(mktemp -d)
trap 'rm -rf "$candidate" "$stage"' EXIT
cp "$overlay/$name" "$candidate"
result=$(python3 "$here/sandbox-overlay.py" "$candidate" "$template" "$target")
echo "$result"
if grep -q '^WRITTEN:' <<<"$result"; then
  cp -a "$home/teams/." "$stage/"
  cp "$candidate" "$stage/$name"
  validation=$(AI_TEAM_REPO="$repo" AI_TEAM_NODE_REPO="$repo" java -jar "$jar" --validate-team "$stage/$name" 2>&1) \
    || { echo "$validation" | tail -5; echo "候選配置靜態驗證失敗，overlay 與 teams/ 都沒有改"; exit 1; }
  grep -E '靜態配置驗證通過|警告' <<<"$validation" || true
  cp "$overlay/$name" "$overlay/$name.bak-$(date +%Y%m%d%H%M%S)"
  cp "$candidate" "$overlay/$name"
  cp "$overlay/$name" "$home/teams/$name"
  echo "已寫回 overlay 與 teams/（原檔已備份）；重啟實例後新任務才會採用"
fi

echo "== 3. 工作樹大小"
python3 - "$repo" "$paths" <<'PY'
import subprocess, sys
from pathlib import Path
repo, paths = Path(sys.argv[1]), sys.argv[2].split(",")
limit = 128 * 1024 * 1024
names = subprocess.check_output(["git", "-C", str(repo), "ls-files", "-z", "-c", "-o", "--exclude-standard", "--", *paths]).decode().split("\0")
sizes = {}
for name in filter(None, names):
    if any(p in {".git", ".ssh", ".secrets"} or p.startswith(".env") for p in name.split("/")):
        continue
    file = repo / name
    if file.is_file() and not file.is_symlink():
        top = name.split("/")[0]
        sizes[top] = sizes.get(top, 0) + file.stat().st_size
total = sum(sizes.values())
print("送出範圍 %s：%.1f MiB（上限 128 MiB）" % (",".join(paths), total / 1048576))
if total > limit:
    print("WARN：超過上限，exec 會回 SOURCE_TOO_LARGE；agent 可在腳本旁寫 source-paths 自行縮小，或重跑本指令加 --paths。最大的頂層路徑：")
    for top, size in sorted(sizes.items(), key=lambda item: -item[1])[:8]:
        print("  %-40s %8.1f MiB" % (top, size / 1048576))
PY

echo "== 4. 登錄沙箱目標"
bash "$here/sandbox-target.sh" register "$home" "$target" "$address" "$hosts" "$paths"

echo "== 5. 重啟"
if ! $restart; then
  echo "未指定 --restart；請在沒有執行中的任務時以 $home/service.sh stop／start 重啟"
elif [[ -n $(sqlite3 -readonly "$home/ai-team.db" "select id from task where state like '%RUNNING' or state in ('QUEUED','RESUME_QUEUED','TRIAGE_QUEUED');") ]]; then
  echo "有執行中或排隊的任務，未重啟；請稍後重啟"
else
  bash "$home/service.sh" stop
  bash "$home/service.sh" start
  sleep 40
  bash "$home/service.sh" status | head -1
fi
echo "PASS：$(basename "$home") 已接上沙箱目標 $target（$address）"
