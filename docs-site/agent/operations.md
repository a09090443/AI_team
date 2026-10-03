---
sidebar_position: 4
title: 操作與指令
description: Slack 上建立與操作任務的指令、按鈕語意，以及維運與開發指令。
---

# 操作與指令

## 誰可以操作

- 只有 `AI_TEAM_ALLOWED_USERS`（Slack user ID 白名單）內的真人可以觸發；bot 與團隊 agent 身分一律拒絕。
- 只在 `AI_TEAM_CHANNEL_ID` 指定的頻道生效；不支援 DM。
- 核准測試計畫、需求變更、PR 與「改用新任務繼續」只限任務提出者（驗收負責人）。
- 一則訊息只能 @一個團隊帳號，避免多個 App 重複派工。

## 建立任務

在允許頻道 @主機器人並附上需求：

```text
@zipe-ai-team 在 base-spring-boot-starter 新增 SHA-256 工具方法
```

- 每個討論串只有一個工作任務；同串再 @主機器人 不會建立平行任務（**即使該任務已結束**）。**新需求請開新討論串**。
  系統回覆依原任務狀態而定：已結束（`COMPLETED`／`PR_CREATED`／`ABORTED`／`FAILED`）只提示改 @角色追問或開新討論串；
  `AWAIT_HUMAN`／`WAITING_QUOTA` 才會附上該任務的 `補充 #<id> 答案`／`補充 #<id> 狀態`；其他狀態（執行中、排隊、等 PR 核准）不建議補充。
  同串追問可直接 @角色（唯讀角色對話，不受此限制）。
- 純詢問（intake 判定 `question`）會直接回答並結束（`COMPLETED`），不進開發流程。「已結束」只代表這次問答完成，
  不代表回答中提到的問題已處理。
- intake 提示詞（`orchestrator/teams/prompts/intake.md`）要求 PM 在純詢問時，把回答過程發現的錯誤、文件與程式不一致、
  待修事項逐項寫進 `out_of_scope`（一項一件、可直接當需求），不可只在回答內文帶過；同一個錯誤出現在多個檔案時合併為一項，
  需要先做決定的項目句首標「需決定：」並列出選項。這是提示詞要求，仍依賴模型遵守；
  直接 @角色 的唯讀對話沒有這個機制。
- 純詢問的回答若列了 `out_of_scope`（回答時發現、但唯讀詢問不能處理的事），任務結束後原討論串會多一則
  「回答時另外發現以下事項」，前 5 項各有「開成新需求 N」按鈕。按下後在頻道開新討論串建立任務：需求文字＝該項原文、
  需求負責人＝按的人、原問答摘要帶入交接紀錄；從 intake 照正常流程走，不沿用任何核准。同一項只會開一次，
  重複按會回覆已開立的任務編號；只認白名單使用者與原討論串。實作類任務的 `out_of_scope` 是刻意排除的範圍，不出按鈕。
- 需求不清楚或有產品取捨時，PM 會停下來問（`AWAIT_HUMAN`）。

## 在任務原討論串下指令

以下指令都要在**該任務原本的討論串**內 @主機器人 送出，在其他討論串送出會被拒絕。
指令可以包在反引號或 text 程式碼區塊裡，系統會解開格式。

| 指令 | 作用 |
|---|---|
| `補充 #<id> <答案>` | 回答 agent 的問題，續跑原快照節點 |
| `補充 #<id> 狀態` | 唯讀：重新貼出目前狀態與真的可用的選項，不啟動 agent |
| `補充 #<id> 核准需求變更 <怎麼改與原因>` | 需求負責人核准需求變更，回到 intake 重訂基準，下游全部重跑 |
| `處理 #<id>` | 讓等待人工的任務交由 PM 分流分析 |
| `中斷 #<id>` | 只停下目前這一輪（任務轉 `AWAIT_HUMAN`，可續跑）；等同狀態卡上的「中斷這一輪」按鈕 |

停止訊息上的按鈕，送出的就是對應的 `補充` 指令，走同一套白名單與驗證，只是免抄授權碼。
下列指令通常由按鈕產生，格式由系統提供，不需要手打：
`核准測試計畫`、`核准配置更新`、`核准恢復驗收 <驗收節點> <配置版本>`、`核准恢復前置契約 <前置節點> <commit>`。

## 按鈕語意

| 按鈕 | 出現時機 | 結果 |
|---|---|---|
| 核准測試計畫／要求修改 | 測試計畫最後一頁 | 核准後自動排入 QA；要求修改會等你的具體意見 |
| 核准：… | agent 以 `human_decision` 提出選擇題 | 依所選選項繼續 |
| 繼續執行 | 只在真的會前進時出現 | 沿用原 session 續跑 |
| 中斷這一輪 | 執行中狀態卡 | 停掉本任務 agent 與系統驗證程序，任務保留 |
| 中止任務 | 停止訊息／核准卡（紅色） | 任務 `ABORTED`，下次服務重啟時刪除工作區 |
| 改用新任務繼續 | 任務停下等人時 | 在新討論串開新任務，從原分支最新 commit 接著做、套用目前配置、次數重算；**不沿用任何核准** |
| 保存修改並改用新任務繼續 | 工作區有未提交修改時 | 先代為 commit 可歸責的修改（不含保護路徑與 `.git`），再開新任務 |
| 檢視配置更新 → 核准配置更新 → 重新執行這一步 | 管理者部署修正配置後，送 `補充 #<id> 狀態` | 受控把舊任務遷移到新配置 |
| 核准，推分支並開 PR | `AWAIT_PR_APPROVAL` | 發布前重新檢查 commit、工作樹與分支，才推分支開 PR |

額度用盡（`WAITING_QUOTA`）會依供應商給的解禁時間自動續跑，不需按鈕，見 [quota-continuation](/docs/quota-continuation)。

## 直接對角色說話

```text
@QA Agent 這次 SMTP failover 還需要哪些測試情境？   ← 唯讀對話，不執行、不改檔
@Backend Agent 執行 新增 SMTP failover            ← 「執行 」前綴才是派工
```

- 一般文字一律視為對話，包括「幫我改」；派工必須用 `執行 ` 前綴。
- 在沒有工作任務的新討論串派工，會交給原本的 PM 管線；在既有任務討論串派工，交給該任務的分流，不另建平行實作。
- 對話是獨立唯讀子請求：只准 Read／Grep／Glob，不能 commit、派工、核准或取代 QA／Review 證據。
- 前提是角色 App 已設定 Socket Mode 與 App-Level Token，見 [direct-agent-requests](/docs/direct-agent-requests)。

## 維運指令（在服務主機上）

`deploy/bootstrap/` 的腳本安裝後位於 `AI_TEAM_HOME`（通常是 `~/ai-team`），從那裡執行：

```bash
# 啟動前本機檢查（Linux 能力、env.sh 權限 600、必填變數）；不輸出憑證、不呼叫模型或 Slack
bash ~/ai-team/preflight.sh

# 服務啟停（有執行中或排隊任務時 stop 會拒絕；start 送出不代表健康檢查成功）
# status 只列本實例 PID 的 443 連線。同一台 VM 有多個實例時，把 ~/ai-team 換成各自的安裝目錄
bash ~/ai-team/service.sh status|start|stop

# 唯讀稽核查詢
bash ~/ai-team/audit.sh list
bash ~/ai-team/audit.sh cost

# 只做團隊配置靜態驗證：不連 Slack、不呼叫模型、不驗證 CLI 登入或沙箱
java -jar "$AI_TEAM_HOME/orchestrator/target/ai-team-orchestrator-0.1.0.jar" \
  --validate-team "$AI_TEAM_DEFINITION"

# 唯讀查詢任務狀態
sqlite3 -readonly ~/ai-team/ai-team.db \
  "select id, state, updated_at from task order by cast(id as integer) desc limit 10;"
```

`deploy/bootstrap/` 另有 `provision.sh`（官方工具鏈安裝）、`install-app.sh`（隔離建置與安裝）、
`backup.sh`／`restore.sh`（刻意排除憑證與 session）、`check-linux.sh`。
完整順序見 [INSTALL](/docs/INSTALL)。

## 開發指令（在 repo 內）

```bash
# 單元與整合測試
cd orchestrator && mvn test

# 來源一致性檢查：改角色、schema、團隊配置或設定後必跑
bash deploy/bootstrap/check-source.sh

# 文件網站
cd docs-site && npm ci && npm run start      # http://localhost:3000
cd docs-site && npm run build                # 會先檢查文件目錄是否與 docs-site/docs/ 一致
```

不要在服務正在使用的 `target/` 做 clean 或 build；用隔離的來源與建置目錄。
