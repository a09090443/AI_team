#!/usr/bin/env bash
# workspace-check.sh 的比對規則測試：以暫存 git 工作區與 SQLite 模擬部署前後。
set -euo pipefail
script="$(cd "$(dirname "$0")" && pwd)/workspace-check.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; exit 1; }

workspace() {
  mkdir -p "$tmp/home/workspaces/task-$1"
  git -C "$tmp/home/workspaces/task-$1" init -q
  echo "$1" > "$tmp/home/workspaces/task-$1/file"
  git -C "$tmp/home/workspaces/task-$1" -c user.name=t -c user.email=t@example.com add file
  git -C "$tmp/home/workspaces/task-$1" -c user.name=t -c user.email=t@example.com commit -qm init
}
fresh() {
  rm -rf "$tmp/home"; mkdir -p "$tmp/home/workspaces"
  sqlite3 "$tmp/home/ai-team.db" "create table task(id text, state text);
    insert into task values ('1','COMPLETED'),('2','AWAIT_HUMAN'),('3','PR_CREATED'),('4','ABORTED');"
  for id in 1 2 3 4; do workspace "$id"; done
  bash "$script" snapshot "$tmp/home" > "$tmp/before"
}
check() { bash "$script" snapshot "$tmp/home" > "$tmp/after"; bash "$script" compare "$tmp/before" "$tmp/after" "$tmp/home/ai-team.db" >/dev/null; }

fresh
check || fail '沒有變動時應通過'
pass '沒有變動時通過'

fresh
rm -rf "$tmp/home/workspaces/task-1" "$tmp/home/workspaces/task-4"
check || fail '啟動清理刪除 COMPLETED／ABORTED 工作區應通過'
pass '啟動清理刪除已結束任務的工作區不再判失敗'

fresh
rm -rf "$tmp/home/workspaces/task-2"
check && fail '等待中任務的工作區消失應判失敗'
pass '等待中任務的工作區消失判失敗'

fresh
rm -rf "$tmp/home/workspaces/task-3"
check && fail 'PR_CREATED 工作區消失應判失敗'
pass 'PR_CREATED 工作區消失判失敗'

fresh
echo changed >> "$tmp/home/workspaces/task-2/file"
check && fail '仍在的工作區被改動應判失敗'
pass '仍在的工作區被改動判失敗'

fresh
workspace 5
check && fail '部署期間出現新工作區應判失敗'
pass '部署期間出現新工作區判失敗'
