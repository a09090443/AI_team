#!/usr/bin/env bash
# 僅臨時 fixture，不碰正式 DB／工作區／憑證；不下載或呼叫模型。
set -euo pipefail
source_root=$(cd "$(dirname "$0")/../.." && pwd)
bash "$source_root/deploy/bootstrap/check-source.sh"
test_root=$(mktemp -d)
mkdir "$test_root/app" "$test_root/bin"
cp "$source_root/deploy/bootstrap/"{backup,restore,audit,service}.sh "$test_root/app/"
# 隔離 fixture 的停機檢查，不改動真實服務。
printf '#!/bin/sh\nexit 1\n' > "$test_root/bin/pgrep"
chmod 700 "$test_root/bin/pgrep"
export PATH="$test_root/bin:$HOME/.local/bin:$PATH"
mkdir -p "$test_root/app/prompts" "$test_root/app/workspaces/task-42/src/test"
printf 'test prompt\n' > "$test_root/app/prompts/qa.md"
printf 'uncommitted work\n' > "$test_root/app/workspaces/task-42/src/test/example"
printf 'do-not-back-up-secret\n' > "$test_root/app/env.sh"
sqlite3 "$test_root/app/ai-team.db" 'create table task(id text,state text,created_at text,updated_at text); insert into task values("42","WAITING_QUOTA","now","now");'
bash "$test_root/app/backup.sh" "$test_root/backup"
test ! -e "$test_root/backup/env.sh"
bash "$test_root/app/restore.sh" "$test_root/backup" "$test_root/restored"
cmp "$test_root/app/workspaces/task-42/src/test/example" "$test_root/restored/workspaces/task-42/src/test/example"
test "$(sqlite3 "$test_root/restored/ai-team.db" 'pragma integrity_check;')" = ok
if bash "$test_root/app/restore.sh" "$test_root/backup" "$test_root/restored"; then echo 'FAIL: 覆蓋既有還原目錄'; exit 1; fi
printf 'tampered\n' >> "$test_root/backup/prompts/qa.md"
if bash "$test_root/app/restore.sh" "$test_root/backup" "$test_root/tampered"; then echo 'FAIL: 未偵測損毀'; exit 1; fi
test ! -e "$test_root/tampered"
if AI_TEAM_TOOLCHAIN="$test_root/toolchain" bash "$source_root/deploy/bootstrap/provision.sh" "$source_root/deploy/bootstrap/toolchain.tsv.example"; then echo 'FAIL: 不完整 manifest 被接受'; exit 1; fi
test ! -e "$test_root/toolchain/node"
if bash "$test_root/app/audit.sh" "42';delete from task;--"; then echo 'FAIL: task id 未驗證'; exit 1; fi
if bash "$source_root/deploy/bootstrap/install-app.sh" "$source_root" "$test_root/app"; then echo 'FAIL: 安裝覆蓋舊目錄'; exit 1; fi
echo "PASS：備份／還原資料一致、憑證排除、覆蓋拒絕、checksum 損毀拒絕、manifest 缺漏拒絕、SQL ID 驗證。測試現場：$test_root"
