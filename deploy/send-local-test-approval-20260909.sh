#!/usr/bin/env bash
set -euo pipefail
umask 077
root=/home/vboxuser/ai-team
build=$root/local-test-build.ym6i6i
source "$root/env.sh" >/dev/null
test "$(sqlite3 -readonly "$root/ai-team.db" 'select state from task where id=1014;')" = AWAIT_HUMAN
from=$(sqlite3 -readonly "$root/ai-team.db" 'select data from flow_run where task_id=1014;' | jq -r '.snapshot.revision')
test "$from" = "$(jq -r .from "$build/migration-preview.json")"
revision=$(jq -r .to "$build/migration-preview.json")
grep -q "新任務使用配置版本 $revision" "$root/orchestrator.log"
jq -n --slurpfile preview "$build/migration-preview.json" '{
  channel:"C0BTV3M6E9L",thread_ts:"1788874480.503669",
  text:("<@U0BTYUL820H> #1014 修正已部署，等待新版配置核准；目前沒有 agent 執行。\n\n"
  + "已驗證：隔離 clone 的 9 項 SMTP 測試通過；離線文件建置成功，llms.txt／llms-full.txt 比對一致；原始工作樹乾淨。這是環境修復實測，不取代正式 QA。\n\n"
  + "本次配置差異：\n"
  + "• QA 啟用 localTestNetwork：只允許隔離 namespace 內本機 TCP；外部直連與 Proxy 外連仍禁止，不開放主機服務。\n"
  + "• QA 新增明確的 target/ 與 base-spring-boot-starter/target/ 掛載根；原本就允許 **/target/**，現在可安全準備缺少目錄。src/main、pom.xml、原始 doc-site、根目錄 llms 檔維持唯讀。\n"
  + "• 文件由 git archive HEAD 複製到 target/qa-docs，再離線 npm ci --ignore-scripts --no-audit --no-fund 與 npm run build；不在原 doc-site 安裝或建置。快取不足則停止，不自動下載。\n"
  + "• 新增必要工具 tar；既有 Maven/npm 快取範圍不變。\n"
  + "• 補齊操作契約：實作階段 Java verify、文件安裝及建置；QA 階段離線 Java 驗證、文件快照／離線安裝／建置。宣告不自動增加實作角色權限。\n"
  + "• 更新建置／測試說明；builder 工具只有排序差異，功能集合不變。模型、角色、路由、產品保護與重試上限皆不變。\n\n"
  + "來源配置："+$preview[0].from+"\n目標配置："+$preview[0].to+"\n\n"
  + "第一步：審閱後，在本討論串標記 @PM Agent，送出：\n```補充 #1014 核准配置更新 "+$preview[0].token+"```\n"
  + "這一步只套用配置，不派工；原實作保留，舊計畫核准不套用新版本。\n\n"
  + "第二步：系統會提供「核准恢復驗收」指令。因 QA 已記錄 3 次，須由你明確授權新配置一次額外驗收（累計將為 4，不清零、不提高常態上限；流程總上限 24 仍有效）。同配置只能授權一次。\n"
  + "第三步：系統重新呈現測試計畫與核准按鈕；你核准後才自動執行 QA。PR 仍需另行核准。"),
  unfurl_links:false,unfurl_media:false
}' > "$build/slack-configuration-approval.json"
curl --silent --show-error --max-time 25 -H "Authorization: Bearer $SLACK_BOT_TOKEN" -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary @"$build/slack-configuration-approval.json" https://slack.com/api/chat.postMessage > "$build/slack-configuration-response.json"
jq -e '.ok==true' "$build/slack-configuration-response.json" >/dev/null
jq '{ok,error,ts,channel}' "$build/slack-configuration-response.json"
