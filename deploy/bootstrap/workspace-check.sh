#!/usr/bin/env bash
# 部署前後的任務工作區比對（deploy-orchestrator.sh 使用）。
#   workspace-check.sh snapshot <實例 home>                 每個工作區一行：task-<id> <內容雜湊>
#   workspace-check.sh compare <before> <after> <ai-team.db> 比對兩份快照
# 規則：部署後仍在的工作區必須完全相同；消失的只接受已結束（COMPLETED／ABORTED）任務——新版啟動清理會刪它們；
# 其他狀態（等待中、PR_CREATED、FAILED）的工作區消失，或部署期間出現新工作區，都判失敗。
# 保留規則的細節（例如負責人結案保留）不在這裡重寫：被保留的工作區會落在「仍在」那類，照樣要求完全相同。
set -euo pipefail

snapshot() {
  local ws
  for ws in "$1"/workspaces/task-*/; do
    [ -d "$ws/.git" ] || continue
    printf '%s %s\n' "$(basename "$ws")" "$( {
      git -C "$ws" rev-parse HEAD
      git -C "$ws" status --porcelain=v1
      git -C "$ws" diff --binary | sha256sum
      git -C "$ws" diff --cached --binary | sha256sum
    } | sha256sum | cut -d' ' -f1)"
  done
}

compare() {
  local before=$1 after=$2 db=$3 name hash now id state failed=0
  while read -r name hash; do
    now=$(awk -v n="$name" '$1 == n { print $2 }' "$after")
    if [ -n "$now" ]; then
      [ "$now" = "$hash" ] || { echo "工作區在部署期間被改動：$name"; failed=1; }
      continue
    fi
    id=${name#task-}
    [[ $id =~ ^[0-9]+$ ]] || { echo "無法辨識的工作區：$name"; failed=1; continue; }
    state=$(sqlite3 -readonly "$db" "select state from task where id='$id';")
    case $state in
      COMPLETED|ABORTED) echo "啟動清理刪除已結束任務的工作區：$name（$state）" ;;
      *) echo "工作區在部署期間消失：$name（狀態 ${state:-不存在}）"; failed=1 ;;
    esac
  done < "$before"
  while read -r name hash; do
    awk -v n="$name" '$1 == n { found = 1 } END { exit !found }' "$before" \
      || { echo "部署期間出現新的工作區：$name"; failed=1; }
  done < "$after"
  return "$failed"
}

case ${1:-} in
  snapshot) snapshot "${2:?需要實例 home}" ;;
  compare) compare "${2:?需要部署前快照}" "${3:?需要部署後快照}" "${4:?需要 ai-team.db}" ;;
  *) echo "用法：workspace-check.sh snapshot <home> | compare <before> <after> <db>" >&2; exit 2 ;;
esac
