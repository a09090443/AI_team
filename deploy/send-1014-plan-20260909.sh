#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
build=$root/approval-build.xcQbUc
source "$root/env.sh" >/dev/null
channel=$(sqlite3 -readonly "$root/ai-team.db" 'select slack_channel from task where id=1014;')
thread=$(sqlite3 -readonly "$root/ai-team.db" 'select slack_thread_ts from task where id=1014;')
test "$(sqlite3 -readonly "$root/ai-team.db" 'select state from task where id=1014;')" = AWAIT_HUMAN
sqlite3 -readonly "$root/ai-team.db" 'select data from flow_run where task_id=1014;' | jq -e '.node=="verification" and .snapshot.definition.project.requirePlanApproval==true and (.planApprovals|length)==0' >/dev/null
# 每頁成功才繼續，中斷不自動重送。
test ! -e "$build/plan-send-started"
touch "$build/plan-send-started"
post() {
  response=$(curl --silent --show-error --max-time 25 -H "Authorization: Bearer $SLACK_BOT_TOKEN_PM" -H 'Content-Type: application/json; charset=utf-8' --data-binary @- https://slack.com/api/chat.postMessage)
  if ! jq -e '.ok==true' <<< "$response" >/dev/null; then
    jq '{ok,error}' <<< "$response"; return 1
  fi
  jq -c '{ok,channel,ts}' <<< "$response" >> "$build/plan-delivery.jsonl"
}
jq -n --arg c "$channel" --arg t "$thread" '{channel:$c,thread_ts:$t,text:"#1014 已受控恢復，現在等待測試計畫核准，沒有 Agent 正在執行。原實作、文件與測試 commit 均保留；QA 已切換 Codex gpt-5.6-sol（medium）。以下逐情境列出完整既有計畫；請審閱完再決定核准或退回。核准後仍需重新驗收，不能直接視為 PASS。",unfurl_links:false,unfurl_media:false}' | post
for page in "$build"/plan-pages/*.txt; do
  jq -n --arg c "$channel" --arg t "$thread" --rawfile text "$page" '{channel:$c,thread_ts:$t,text:$text,unfurl_links:false,unfurl_media:false}' | post
  sleep 1
done
sqlite3 "$root/ai-team.db" "INSERT INTO audit_log(task_id,at,actor,action,detail) VALUES('1014',strftime('%Y-%m-%dT%H:%M:%fZ','now'),'system','STATE_CHANGE','受控恢復後已於原 Slack 討論串送出完整計畫 27 條情境與核准指令，Slack API 回應成功；未核准、未派工。發送收據 approval-build.xcQbUc/plan-delivery.jsonl。');"
echo "DELIVERED=$(wc -l < "$build/plan-delivery.jsonl")"
curl --silent --show-error --max-time 20 -H "Authorization: Bearer $SLACK_BOT_TOKEN_PM" --get --data-urlencode "channel=$channel" --data-urlencode "message_ts=$thread" https://slack.com/api/chat.getPermalink | jq '{ok,permalink,error}'
