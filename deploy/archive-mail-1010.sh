#!/usr/bin/env bash
# 僅封存已確認的 #1010 並唯讀查詢 Slack；不刪除訊息或本機資料。
set -euo pipefail
umask 077
export PATH="$HOME/.local/bin:$PATH"
root=/home/vboxuser/ai-team
db=$root/ai-team.db
task=$(sqlite3 -readonly -json "$db" "select * from task where id='1010';")
[[ $(jq -r '.[0].state' <<< "$task") == ABORTED ]]
[[ $(jq -r '.[0].slack_channel' <<< "$task") == C0BTV3M6E9L ]]
[[ $(jq -r '.[0].slack_thread_ts' <<< "$task") == 1788615347.983239 ]]
archive=$(mktemp -d "$root/mail-1010-archive.XXXXXX")
printf '%s\n' "$task" > "$archive/task.json"
sqlite3 -readonly -json "$db" "select * from stage_result where task_id='1010';" > "$archive/stages.json"
sqlite3 -readonly -json "$db" "select * from audit_log where task_id='1010' order by id;" > "$archive/audit.json"
sqlite3 -readonly -json "$db" "select * from task_checkpoint where task_id='1010';" > "$archive/checkpoint.json"
[[ ! -d $root/logs/1010 ]] || cp -a "$root/logs/1010" "$archive/logs"
[[ ! -d $root/workspaces/task-1010 ]] || cp -a "$root/workspaces/task-1010" "$archive/workspace"
mkdir "$archive/sessions"
while IFS= read -r session; do
 [[ $session =~ ^[a-zA-Z0-9-]+$ ]] || continue
 for base in "$HOME/.claude/projects" "$HOME/.codex/sessions"; do
  [[ -d $base ]] || continue
  while IFS= read -r -d '' file; do
   # 保留來源相對路徑，避免同檔名碰撞；只複製對應 session 的 jsonl。
   target=$archive/sessions/${file#"$HOME/"}
   mkdir -p "$(dirname "$target")"
   cp -p "$file" "$target"
  done < <(find "$base" -type f -name "*$session*.jsonl" -print0)
 done
done < <(jq -r '.[].session_id // empty' "$archive/stages.json" "$archive/audit.json" | sort -u)
# token 僅留在 shell 記憶體並由 stdin 傳給 curl，不列在 command line／log。
set +x
source "$root/env.sh"
printf 'Authorization: Bearer %s\n' "$SLACK_BOT_TOKEN" |
 curl --silent --show-error --fail --max-time 30 --header @- --get \
  --data-urlencode 'channel=C0BTV3M6E9L' --data-urlencode 'ts=1788615347.983239' \
  --data-urlencode 'limit=15' https://slack.com/api/conversations.replies > "$archive/slack-replies-first-page.json"
(cd "$archive" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS && sha256sum -c SHA256SUMS >/dev/null)
printf 'archive=%s\n' "$archive"
jq '{ok,error,needed,provided,has_more,message_count:(.messages // [] | length)}' "$archive/slack-replies-first-page.json"
echo '原始資料全部保留；此腳本沒有呼叫刪除 API。'
