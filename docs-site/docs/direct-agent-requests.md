# 白名單使用者直接對角色提出請求

## 啟用條件

每個角色 App 要有自己的 App-Level Token（`SLACK_APP_TOKEN_<角色>`，scope `connections:write`）、
開啟 Socket Mode 並訂閱 `app_mention`；建立與填寫步驟見 [建立 Slack App](slack-agent-apps.md)。
空白代表該角色只能發言、不能被直接 @。這與「角色可以用 Bot Token 發言」是兩件不同的事。
2026-09-06 v2 部署時五個 App-Level Token 尚未設定；目前是否啟用以啟動 log 與下方真實驗收為準。

驗收必須在允許頻道實際 `@QA Agent 請說明你的測試職責`，看到 QA 回覆才算接通；只看到 auth.test 成功不算。
目前不支援 DM。bot 互相 tag 僅是可見交接，仍會被事件入口排除，不會自行重複派工。

## 使用方式

僅支援已允許頻道內的 @mention，不含 DM。沿用 `AI_TEAM_ALLOWED_USERS`（以逗號分隔的真實 Slack user ID）。
白名單內的人可以對五個角色提問；即使誤把 bot 加進白名單，仍拒絕 bot 訊息與團隊 agent 身分。

- `@QA Agent 這次 SMTP failover 還需要哪些測試情境？`：由 QA 唯讀回答，不執行測試或修改程式。
- `@Backend Agent 執行 新增 SMTP failover`：明確派工；無工作任務的新討論串交原本 PM 管線。
- 原工作任務討論串中的 `@Backend Agent 執行 補齊測試`：交原任務 PM triage，不另建平行實作。
  此入口目前仍要求原任務在 `AWAIT_HUMAN` 且有 QA 計畫與 QA 結果；不符合會明示拒絕。
- `@PM Agent 處理 #1011` 或 `@PM Agent 補充 #1011 答案`：沿用原 PM triage 指令，須原頻道與原討論串。

一般文字一律視為對話，包括「幫我改」；真正派工請用「執行 」前綴。
原有 `@zipe-ai-team` 行為不變。一次只能 @一個團隊帳號，避免多個 App 重複派工。
沒有明確任務歸屬、同串有多個工作任務時拒絕猜測。

## 脈絡、權限與紀錄

每次對話建立獨立編號的唯讀子請求；`direct_request` 保存角色、原工作任務 ID 與脈絡快照。
`stage_result`、`stage_attempt`、checkpoint、audit 與成本使用既有機制，但不覆蓋原工作任務紀錄。
新訊息使用新 CLI session，帶入同串原任務需求、階段報告與最近最多 8 筆已記錄對話；長報告會截斷。
不同角色不共用 CLI session，只有同一次請求的用量續跑才恢復其自己的 session。
不讀整個 Slack 歷史；未 @的訊息與未記錄的附件不會自動帶入。

有原任務工作區時唯讀查看該工作區，否則明示只看主 repo。
2026-09-25 起，系統會把原任務工作區現況（最新 commit、未提交的檔案清單）查好附進脈絡，
標明是系統查得；對話 agent 是全新 session，不記得上一輪留下什麼，不附上會把「有未提交的修改」答成「已經 commit」（#1017）。
回覆規則寫在對話的系統提示（`ConfiguredExecutor.DIALOGUE_REPLY_RULES`）：第一句直接回答、用白話不用內部代號、
以系統查得的工作區現況為準、最後寫「你可以怎麼做」並給可複製的指令（例如 `@QA Agent 執行 <要做的事>`）、不確定就說不確定。
共用中央工作佇列與並行槽，不會在 Backend 寫入中途另啟聊天讀檔。
只准 Read/Grep/Glob，且沙箱自檢必須通過。對話不能 commit、派工、核准 PR 或取代 QA／Review 證據。
同串已有未完成對話時拒絕重複排隊；用量中斷保持 `WAITING_QUOTA`，按主 App 的繼續按鈕。
重啟中斷的對話會明示失敗，請重新提問，不會誤入實作管線。

事件以角色與 event_id 去重，拒絕 bot、編輯事件與缺少 event_id 的事件。
Socket handler 僅放入 ingress 佇列並 ack，模型與 Slack 回覆不在 handler 中等待。
目前 ingress 仍是記憶體佇列：若 ack 後、持久化前服務崩潰，訊息可能遺失，需人工重送。

依據：[Slack app_mention](https://docs.slack.dev/reference/events/app_mention/)、
[Socket Mode](https://docs.slack.dev/apis/events-api/using-socket-mode/)。

## 真實驗收清單（尚未執行）

- 白名單使用者分別 @五個角色，各由正確角色回覆。
- 非白名單、其他頻道、agent 交接不產生工作。
- 同串詢問可引用原工作，原任務驗收與狀態不被聊天改寫。
- 「執行」走中央管線；重送與多角色 mention 不重複實作。
- 用量中斷可恢復對話，且沒有進入 Backend 或 PR 發布。
- 觀察每個角色 Socket Mode 的實際事件到達，不能只依 443 連線數宣稱成功。
