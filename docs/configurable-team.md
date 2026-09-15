# 配置式專案、角色與工作流程

跨專案操作契約、每階段沙箱預檢、真人計畫核准及受控配置遷移，
見 [跨專案執行契約](cross-project-execution.md)。此版尚未部署，勿與下方歷史部署混淆。

## 狀態與範圍

**正式部署更新：**2026-09-06 18:08 已啟用 v2，詳見 [部署與重測準備](deploy-2026-09-06-v2.md)。
本台 VM 以非機密 `team.env` 設定 AI_TEAM_DEFINITION（由 start/preflight 在 env.sh 之後讀取）；其他憑證仍在原 env.sh。

後續更新：兩份範例已升為 **version 2**，加入需求基準、前置契約與失效傳遞、系統獨立驗證及 PR_CREATED。
詳見 [工程流程與驗證邊界](engineering-workflow.md)。下列 12:10／123 項是 v1 歷史證據，不代表 v2 已部署。

2026-09-06 本機實作，尚未部署到 VM，尚未以真實 Slack／CLI 跑完本版端對端流程。
原正式服務與 #1011 不受本輪修改影響。範例配置的實際產品建置仍需在目標 Linux 環境驗證。

本機驗證（2026-09-06 12:10，台北時間）：所選 17 個測試類別共 **123 項通過**，
包含 13 項真實暫存 Git repo 測試；`mvn package` 成功。
兩份範例皆已用打包 jar 的 `--validate-team` 通過靜態檢查，未啟動 Slack／Spring 服務或呼叫模型。
另有來源檢查及 16 項 Linux 平台 fixture 斷言通過；不等於 Linux 實機驗收。
未在本次 Windows 執行依賴 `/bin/sh` 的 RuntimeDiagnosticsTest 與 QuotaExceededTest 完整類別。
測試輸出位於 `orchestrator/target/surefire-reports/`，不得把這些本機結果冒充正式部署成功。

新模式將角色名稱、模型、runtime、Slack token 引用、工具清單與流程節點放入 JSON。
JSON 為可直接解析的配置格式，不代表未來綁定某個 CLI；runtime 仍由既有 adapter registry 決定。
目前每個服務實例選擇一個專案配置，不是單一程序同時管理多個專案。

## 啟用與配置

新模式明確 opt-in，`AI_TEAM_DEFINITION` 留空則維持 legacy 行為。
設定 VM `env.sh`（範例；不要把 token 放進 JSON）：

```bash
export AI_TEAM_DEFINITION="$AI_TEAM_HOME/teams/spring-starters.json"
```

新安裝會複製 `orchestrator/teams/`；既有服務升級須一併部署此資料夾與新版 jar，
先備份資料庫及舊版檔案，再確認無執行中／排隊任務後重啟。不可在執行中的 target/ 建置。
服務启动時完整讀取配置與資產；修改配置後須重啟，新任務才採用新版本。

可在啟動前只做靜態驗證，不連 Slack、不呼叫模型：

```bash
java -jar "$AI_TEAM_HOME/orchestrator/target/ai-team-orchestrator-0.1.0.jar" \
  --validate-team "$AI_TEAM_DEFINITION"
```

此命令驗證配置結構、引用、資產與 Git repository 目錄，**不驗證 CLI 登入、runtime 可用性、Slack token scope 或沙箱隔離**。
正常服務啟動還會驗證 runtime registry、自檢沙箱與配置列出的必要工具。

| 配置區塊 | 責任 |
|---|---|
| `project` | 專案名稱、repo 環境變數、基準分支、建置／測試規範、保護路徑、快取與必要工具 |
| `roles` | 任意穩定角色 ID、顯示名稱、runtime、模型、角色 prompt、工具清單與 Slack token 環境變數名稱 |
| `nodes` | 節點 ID、執行角色、schema、階段指示、權限、驗證政策與轉移 |
| `entry`、`maxTransitions` | 起點與整輪執行上限 |

角色 ID 與節點 ID 分開。一個角色可以執行多個節點，例如規劃測試與驗收；
checkpoint 保存的是節點 ID，不再靠 PM／QA 等角色名稱判斷續跑位置。
角色一般職責放 `role.prompt`；不同階段的任務放 `node.instructions`（選填），後者優先。
所有 prompt/schema/instructions 必須在配置檔所在目錄內；向上跳脫或 symlink 指向外部會被拒絕。

## 不改 Java 的增刪例子

- `orchestrator/teams/spring-starters.json`：設計、測試計畫、實作、驗收、獨立審查、人工 PR 核准。
- `orchestrator/teams/node-web.json`：改用 `AI_TEAM_NODE_REPO` 與 `main`，必要工具換為 Node/npm；
  移除 Architecture 與設計節點，加入 Frontend，Reviewer 替換為 `security_auditor`。

第二份配置由同一個 FlowEngine 執行，不需要增加 Java enum、role switch 或流程 if。
新增角色仍需建立／設定相應 Slack App，將 Bot/App Token 填入配置引用的環境變數，
使用 `chat:write`、`app_mentions:read`、`app_mention` 與 Socket Mode。角色不必有 Slack 帳號才能執行：
缺 token 時系統會明示代發；但無法直接 @沒有獨立帳號的角色。

刪除角色時要同步移除或改派引用它的節點與轉移。刪錯引用、未配置的下一步、孤立節點、
缺少必要驗收、實作與獨立審查共用角色都會被拒絕，不會在執行時默默跳過。

## 權限與發布仍由程式強制

配置只能選擇已實作的政策，不能用任意 shell 表達式建立新閘門：

- `READ_ONLY`：只允許 runtime 自身狀態寫入，不開放產品目錄。
- `WORKSPACE`：僅獨立 clone 可寫，必須配 `IMPLEMENTATION` 契約。
- `LIMITED`：只給配置匹配到的已存在目錄及該 clone 的 Git 狀態寫入；不把整個專案交給測試角色。
  在沙箱外逐筆核對新增 commit、未提交與未追蹤路徑，攔截 Git index 繞道及「修改後再還原」；
  單階段超過 100 個新增 commit 會停止要求查核。沒有可寫目錄時拒絕，不自動放寬。

路徑使用 `/`、`*` 與 `**`，例如 `tests/**` 或 `**/src/test/**`。
測試產物目錄應在實作階段依專案建置正常建立；限寫階段不能自行建立未獲授權的父目錄。
快取權限只容許 `.m2/repository`、`.npm`、`.gradle/caches` 或 `.cache/<單層名稱>`；不允許以快取名義寫入 `.ssh`。

驗證政策是「工作契約」，不是「角色名稱」：

- `REPORT`：共用完成／失敗報告契約。
- `REQUIREMENTS`（v2）：唯一需求基準，含穩定需求 ID、原子驗收項目與維度、範圍及待確認問題。
- `IMPLEMENTATION`：成功狀態、建置品質結果、無範圍衝突。
- `TEST_PLAN`：情境須覆蓋所有原子驗收 ID，並提供能區分弱實作的反例。
- `VERIFICATION`：逐情境實際結果與證據，無未核實事項；引用 `plan` 節點。
- `REVIEW`：獨立核實完成、有檢查紀錄、無阻擋事項或 HIGH finding。

`APPROVAL` 節點必須指定 `requires`，至少涵蓋上述四種實作／測試／審查契約。
v2 額外要求需求基準、所有間接前置契約，以及管理者固定的獨立驗證指令收據。
真正發布前再次檢查：驗收／審查對應同一個最終 commit、工作樹乾淨、
branch／remote／基準分支未變，並且由目前白名單使用者按下核准。
新的產品寫入會撤銷先前驗收與審查收據。修改角色配置不能讓舊收據變成有效核准。
GitHub 是目前唯一發布 adapter；更換 Git hosting 或加入全新驗證政策仍需要實作 adapter／程式。
網路隔離仍未補上，不能把本次配置化誤認為已解決 agent 的所有安全邊界。

## 成功、受阻、人工與重試

AGENT 節點指定 `next`、`blocked`，可選填 `routes`。
agent 只回傳 `route` 的鍵，不能自行指定角色或跳至任意節點；未知 route 明確停止。
`human` 與 `end` 是保留目標，分別代表等待人工與結束（不發布）。
範例將驗收受阻交給分流節點，能回到實作、驗收或詢問人類，仍受重試上限約束。
節點 `maxVisits` 最多 3；整輪 `maxTransitions` 最多 64。用量續跑不重建工作區，也不另算一次新節點。
達一般上限後，若前一份受阻報告已透過配置的 `blocked` 明確指向責任節點，
系統可處理每任務最多 6 個有證據的獨立受阻事件；同一事件可沿配置流程經過最多 12 個節點，
不會因「分流→修復→重驗→審查」每一站都重複消耗事件額度。它不清除歷史計數、不擴權，
也不視為人工核准。原因不明、目標為 `human`、需改需求／核准，或任一上限用完仍會停止。
舊快照若含第一版逐節點 key，執行前會按證據合併為獨立事件；無法辨識的格式不會降低計數。
事件額度用完時，原驗收負責人可使用停止訊息提供的一次性授權碼增加兩個事件，最多額外六個；
授權綁定配置、目前受阻證據與 HEAD，相同現場不能重放，也不重設一般流程或節點歷史次數。
配置遷移會讓舊配置下增加的額度失效；已使用 token 與事件歷史仍保留。
LIMITED 驗收節點若只在其核准的測試路徑留下未提交變更，系統會核對實際 diff，並依該節點的
`blocked` 分流配置中 `implementation` 路由交給具 `WORKSPACE` 權限的實作節點提交；產品檔、
保護路徑、混合或無法歸責的變更一律停止給真人，不會放寬 `.git` 權限。
詳見 [adr-automatic-blocker-routing.md](adr-automatic-blocker-routing.md)。

原討論串 `@主機器人／角色 補充 #編號 答案` 會續跑原快照節點。
同一工作討論串不會因一般 @主機器人訊息再建第二個平行工作任務；新需求請開新討論串。
用量暫停走既有繼續按鈕；新模式不使用舊版「重新開始」按鈕，以免誤封存工作區或套錯流程。
配置角色的一般對話使用獨立子請求，不會覆蓋原驗收；新加入、但不在原快照裡的角色不能接手舊任務對話。

## 快照與相容策略

`flow_run` 是 additive 資料表，保存每個新模式任務的完整配置、prompt/schema 內容、版本雜湊、
目前節點、執行次數、證據收據、工作區位置與發布目標。Token **值**不進快照，只保存環境變數引用。
CLI session 仍存在原 runtime 狀態目錄，checkpoint 保存其 ID；環境變數所指的實際憑證仍由維運管理。

修改／移除目前配置的角色不影響舊任務快照；舊快照需要的 runtime、工具、token 引用與工作區仍須保留。
如果它们被移除，任務會明確受阻，不會自動換引擎、使用新 repo 或重建現場。
重啟時執行中、排隊或發布中的新模式任務都停下並要求人工確認，不自動重播可能已完成的外部動作。

沒有 `flow_run` 的歷史任務沒有足夠資料可自動建立可信快照。新模式會拒絕續跑／發布這些任務，**不猜測遷移**。
要處理它們，須以原專案環境停用 `AI_TEAM_DEFINITION`，走原 TaskOrchestrator 的 legacy 相容路徑，或另行做明確遷移。
因此原始 StageSpec 常數與部分 role switch 暫時仍在，但不是新模式增刪角色的入口。
待歷史未完成任務結束並驗收後，才能另做 legacy 清除；本轮没有删除任何舊工作或紀錄。

## 尚待實機驗證

- Linux 完整測試與實際 systemd 的配置限寫政策。
- 新 Slack 角色的 App 設定、事件路由與真實身分回覆。
- 任務在真實額度中斷與重啟後的續跑。
- 以新模式走到 PR 人工閘門，再由使用者另行核准發布。
- Node 範例是配置與流程測試範例，不代表已在任意 Node repo 完成產品驗收。
# QA 明確模型設定（2026-09-08）

`spring-starters` 的 tester 現在使用 `runtime: "codex"`、
`model: "gpt-5.6-sol"`、`reasoningEffort: "medium"`，涵蓋 plan、verification 與 QA 唯讀對話。
`reasoningEffort` 是角色選填欄位，目前僅 Codex 支援 low/medium/high/xhigh；
新執行與 resume 都會傳入 `-c model_reasoning_effort="medium"`。
缺少此欄位的舊快照維持 CLI 原有行為，不回寫歷史任務或全域 config.toml。
這不是自動提升推理強度功能；需要 high 時由管理者修改配置，對新任務生效。
設定依 OpenAI Docs 的 [Codex 配置參考](https://learn.chatgpt.com/docs/config-file/config-reference) 核對。
