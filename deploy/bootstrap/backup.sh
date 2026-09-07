#!/usr/bin/env bash
# 一致性應用備份，不包含 env.sh、CLI 憑證／session。
set -euo pipefail
umask 077
root=$(cd "$(dirname "$0")" && pwd)
export PATH="$HOME/.local/bin:$PATH"
dest=$(realpath -m "${1:?usage: backup.sh NEW-backup-directory}")
[[ ! -e $dest && ! -L $dest && $dest != "$root"/* ]] || { echo '備份須為安裝目錄外的新目錄'; exit 1; }
exec 9>"$root/service.lock"
flock -n 9 || exit 1
if pgrep -f '[a]i-team-orchestrator-0.1.0.jar' >/dev/null; then echo '請先停止 orchestrator，備份不會自行停止服務'; exit 1; fi
mkdir -m 700 "$dest"
for file in orchestrator prompts schemas teams team.env flow-assets workspaces workspace-archives logs start.sh service.sh preflight.sh audit.sh backup.sh restore.sh check-linux.sh linux-platform.sh; do
 [[ ! -e $root/$file ]] || cp -a "$root/$file" "$dest/"
done
# 只備份系統收集的驗證 log；獨立 clone 與 HOME 快取可由保存的 commit 重建，避免帶入 node_modules symlink。
if [[ -d $root/verification-runs ]]; then
 (cd "$root" && find verification-runs -mindepth 3 -maxdepth 3 -type f -name '*.log' -exec cp --parents -t "$dest" -- {} +)
fi
if [[ -f $root/ai-team.db ]]; then
 [[ $dest != *"'"* ]] || { echo '備份路徑不可含單引號'; exit 1; }
 sqlite3 "$root/ai-team.db" ".backup '$dest/ai-team.db'"
 [[ $(sqlite3 -readonly "$dest/ai-team.db" 'pragma integrity_check;') == ok ]]
fi
(cd "$dest" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 -r sha256sum > SHA256SUMS)
echo "應用備份：$dest。未包含 env.sh、目標基準 repo、工具鏈或 CLI 登入／session；不是完整 VM 備份。"
