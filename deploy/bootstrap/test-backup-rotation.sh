#!/usr/bin/env bash
# 兩個部署腳本的 orchestrator-backup.* 輪替：本次備份（回復點）即使最舊也保留，另留最近 N-1 份；N=0 或非數字不輪替。
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
instance_env() { :; }
for script in deploy-orchestrator.sh deploy-jar-safe.sh; do
  root="$tmp/$script"; mkdir -p "$root"
  for i in 1 2 3 4 5 6; do mkdir "$root/orchestrator-backup.b$i"; touch -d "2026-11-0$i" "$root/orchestrator-backup.b$i"; done
  : > "$root/orchestrator-backup.file"
  eval "$(sed -n '/^prune_backups() {/,/^}/p' "$here/$script")"
  backup="$root/orchestrator-backup.b1"
  keep=0; AI_TEAM_BACKUP_KEEP=0; prune_backups >/dev/null
  keep=x; AI_TEAM_BACKUP_KEEP=x; prune_backups >/dev/null
  test "$(ls "$root" | wc -l)" -eq 7 || { echo "FAIL $script：N=0／非數字仍刪除"; exit 1; }
  keep=3; AI_TEAM_BACKUP_KEEP=3; prune_backups >/dev/null
  test "$(cd "$root" && ls -d orchestrator-backup.b* | tr '\n' ' ')" = "orchestrator-backup.b1 orchestrator-backup.b5 orchestrator-backup.b6 " \
    || { echo "FAIL $script：輪替結果 $(ls "$root")"; exit 1; }
  test -f "$root/orchestrator-backup.file" || { echo "FAIL $script：刪了非目錄"; exit 1; }
done
echo 'PASS：部署備份輪替保留本次回復點與最近 N-1 份'
