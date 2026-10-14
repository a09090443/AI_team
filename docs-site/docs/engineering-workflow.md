# 需求追溯、獨立驗證與交付邊界

## 狀態

僅適用配置式 `version: 2` 流程，兩份 teams 範例皆為 v2；既有 v1 快照及 legacy 任務不自動升級。
2026-09-06 起已部署於既有 VM，見 [v2 部署紀錄](deploy-2026-09-06-v2.md)。

## 需求基準與責任

### 跨專案問題完整性（2026-10-11）

新版 `TeamCatalog` 對所有新 v2 團隊快照加入 `contracts/issue-tracking-v1` 共用契約，與角色名稱、語言、專案名稱無關。
既有快照沒有此標記則維持舊契約。2026-10-11 已部署 bestpartner 與 spring-starters 的共用引擎；
Linux 完整測試 619 項、0 失敗、0 錯誤、11 略過，兩實例啟動與沙箱檢查通過。尚未執行新契約的真實模型端對端驗收。
部署版本與備份見 [部署紀錄](deploy-2026-09-06-v2.md#2026-10-11-跨專案問題完整性升級)；不能把程式測試當成模型端對端成功。

- 每階段回傳 `issue_updates`（新發現／更新）、`issue_revision`（輸入清單版本）、`issue_checks`（逐項檢查）。
  需求階段另有 `work_intent=WORK|READ_ONLY` 與 `intent_evidence`。共用提示要求目標內查核問題自動派修；明確只讀或知識問答維持唯讀。
- 每項有穩定 `id`、`description`、`evidence`、`scope`、`reason`、`requirement_ids`。範圍為 `REPAIR`、`DECISION`、`EXCLUDED`、`OBSERVATION`。
  系統加上來源任務與節點；需求必須重列完整已知清單，REPAIR 必須對應需求（service 路線例外）。WORK 不可把問題只列為 OBSERVATION 或以 question 結束。
- 完整清單另存 `FlowRun.issueInventory`，不隨對話摘要截斷；空更新不刪既有問題。ID 重複、改寫既有描述、未授權降為排除、過期清單均被拒絕。
  需求草稿的結構／分類錯誤先交回原節點自修，沿用有界重試；後續問題依原配置 blocked 路由交責任角色，無路線或預算用盡才停下。
- verification／review／service 必須針對全部 REPAIR／DECISION 回 `issue_checks`，每项有 id、PASS／OPEN 與非空證據。
  DECISION 不能靠 PASS 自行放行，須收到新的真人回覆後由需求階段確認；明確核准需求變更才可將待處理問題排除。
  新增或更動清單會使舊的逐項驗證版本失效。缺對應、漏驗、待決／OPEN 都不能完成或發布，既有需求→情境→驗收檢查照常。
- 承接完整複製清單與 `issueEvidence` 歷史證據，不複製有效驗收／核准；新任務先核實已有成果，再補未完成項。
  明確從唯讀問答挑選單項建立的子任務仍是局部範圍，顯示來源總數與未涵蓋項數，不假稱整體完成，也不擅自擴張授權。
- 最多 100 個問題、清單 96000 字元；提示中的清單與歷史證據最多 128000 字元。超限明示停止，不截斷後宣稱全部處理。

機械檢查保證已登錄問題的結構、連續性與覆蓋，不保證模型找出所有問題、判斷範圍正確或證據文字真實；仍須 QA、Review、獨立操作收據與真實任務驗證。
未登錄只寫在自然語言摘要的發現，系統不能可靠地自行解析，不能宣稱「任何漏項都不可能發生」。

- 入口使用工作契約 `REQUIREMENTS`，不綁定角色名稱。
- 每項需求保存 `id`、`kind`（FUNCTIONAL／NON_FUNCTIONAL）、描述、可觀察的 `acceptance` 與 `source`。
- 新任務另將驗收拆成 `acceptance_items`；每項有全域唯一 ID、`criterion` 與 `dimension`。
  「每個／全部／各種」操作、角色、輸入、狀態及失敗模式必須逐項展開，不能以代表性路徑代替。
- 保存排除範圍 `out_of_scope` 與待確認問題 `open_questions`；有未解問題不得進實作。
- 純詢問仍可結束而不建立工作基準；不會為了回答問題強迫執行開發流程。
- 任務提出者是預設真人需求／驗收負責人。這表示責任歸屬，**不表示他已逐條核准模型第一次整理的需求**。
  保留原先全自動策略：清楚的需求自動前進，歧義與產品取捨才停下。若需要每次都人工確認需求，需另加明確需求核准閘門。
- 基準 SHA-256 由系統計算，不接受模型自訂。PM 對使用者原意的語意理解仍須角色審查與真人確認，機械檢查不能保證理解正確。

QA 測試計畫必須引用 `baseline_revision`，每個情境的 `requirement_ids` 必須存在且不重複；
所有需求都至少被一個情境涵蓋。新任務還必須以 `acceptance_ids` 覆蓋每個原子驗收項目，
並提供 `counterexample`，說明哪種錯誤實作可能通過弱測試，以及本情境如何使它失敗。
系統會列出漏掉的需求或原子驗收 ID，不只顯示「覆蓋不足」。
驗收結果再引用相同需求版本與系統計算的 `plan_revision`，逐一對應 scenario ID、指令、實際結果與證據。
即使情境 ID 沒變，預期結果改了也會使舊驗收失效。

這仍是結構追溯，不是語意正確性的證明。Reviewer 必須檢查測試內容真的驗證 acceptance，
不能只看 ID 對上。QA 可以讀程式做白箱測試，但不能只做 Code Review 或採信開發者說通過。
既有任務使用建立當下保存的 schema 與 prompt；不會在中途被新版欄位破壞，也不會自動補寫新版計畫。

## 返工與需求變更

每個節點的 `requires` 宣告前置契約；啟動驗證會拒絕相依循環、略過必要前置契約或不完整的 PR 核准清單。
重做需求、計畫、實作或驗收節點會撤銷所有相依報告／版本收據與獨立驗證，
既有歷史 stage/audit 不刪除。進入純唯讀 Review 不會撤銷 QA 前已產生的系統收據。

| PM 分流 | 行為 |
|---|---|
| implementation | 既定需求內修正實作，再驗收與審查 |
| verification | 補測試／證據，再獨立驗證與審查 |
| plan | 修正既定需求的測試計畫，再重跑下游實作／驗收／審查 |
| design | 修正設計，再測試計畫與下游；只有配置含此路線時可用 |
| requirements | 提議變更需求，停止等待原需求負責人明確核准 |
| human | 環境、權限、產品取捨或原因仍無法核實；明示目前沒有 agent 處理 |

需求變更的 Slack 回覆範例：

```text
@zipe-ai-team 補充 #編號 核准需求變更 SMTP 逾時時禁止自動重送，改回報結果未知
```

只有原需求負責人且仍在白名單內能授權；「好」或一般補充不會核准變更，也不會核准 PR。
系統保留舊基準於 `flow_run.baselineHistory`，稽核記錄核准者、具體變更與原版本。
重新建立基準後清除有效下游收據，重新跑配置流程，不清掉原工作區或既有 Git 紀錄。
重試上限不因重新規劃而歸零；用完仍需人工檢查／另建任務。

## 系統獨立驗證

計畫引用 `compare-unchanged-files` 時，系統先比較本任務實作前版本與最終 commit 的指定檔案，
通過才執行建置或依純文件規則略過建置。差異交回實作修正，查不到基準則停止；
比對收據綁定完整計畫與固定 Git 基準，發布前重驗紀錄。詳見 [QA 情境流程](qa-scenario-workflow.md)。

在 Code Review **之前**，系統從工作區的最終 commit 建立另一個全新 clone，不重用 QA 的產物／node_modules。
管理者在 `project.verification` 固定 argv 與整輪期限，例如：

```json
"verification": {
  "commands": [["mvn", "-B", "clean", "verify"]],
  "timeoutSeconds": 1800
}
```

Node 範例依序 `npm ci`、`npm test`、`npm run build`；需依實際 repo 選定非互動測試指令。
固定指令不能由 agent 的 `command` 欄位提供，不插入需求文字，不以 shell 字串執行。
這不代表建置工具不會執行 repo 裡的腳本；它們仍是需要沙箱約束的不可信程式。

- 必須通過既有沙箱自檢；使用受限 systemd user unit，2 核 CPU 配額、4 GB 記憶體與整輪最多 1800 秒。
- 只開放獨立驗證目錄，釘住新 clone 的所有版控檔與 `.git`；不開放原任務工作區寫入。repo 含 symlink 暫時拒絕，需管理者評估。
  檔名含空白時，交給 systemd 的路徑整段加雙引號（`ExecutionSandbox.pathProperty`；VM 實測確實唯讀，`\x20` 跳脫實測仍可寫，不採用）；
  含雙引號、反斜線或控制字元的路徑直接拒絕。2026-10-03 前未加引號，bestpartner 的「General rule.pdf」讓獨立驗證以
  `Invalid ReadOnlyPaths` 在啟動前失敗（#1012）。

**純文件變更略過建置測試（2026-10-03 起，逐專案啟用）**：`project.verification.docOnlyPaths` 有設定時，系統以
`git diff --name-only --no-renames <工作區基準>..HEAD`（`WorkspaceManager.changedPathsSinceBase`）自行列出變更，**不採信 agent 自陳**。
以下全部成立才略過（`DocOnlyChanges.qualifies`），否則照常全跑：
- 變更清單非空，且每個檔都符合 `docOnlyPaths`；2026-10-12 起來源另外只接受 .md、.mdx、.txt、.rst、.adoc，SQL／腳本／設定／網站程式等即使在 docs 目錄也全跑；
- 沒有任何檔落在程式強制的全跑名單（配置無法覆蓋）：路徑任一段是 `src`；`pom.xml`、`build.gradle(.kts)`、`settings.gradle(.kts)`、
  `gradle.properties`、`gradlew(.bat)`、`mvnw(.cmd)`、`package.json`、`package-lock.json`、`yarn.lock`、`pnpm-lock.yaml`、`Dockerfile*`；
  或位於 `gradle/`、`.mvn/`、`.github/` 之下。
- `--no-renames` 讓「程式檔改名搬進 docs/」同時列出刪除端，不會被當成純文件。

略過時不 clone、不跑指令，收據 `commands=[]`、`docOnlyChanges` 列出檔案，仍綁定 HEAD、需求與配置版本；`blockingReason` 會重驗清單仍符合配置。
Slack 會說明「只改了文件，依專案設定略過建置與測試」，QA／審查收到的收據說明這是配置設計而非查無證據，PR 說明列出檔案。
**QA 逐項驗收（`inspect-content`）、程式審查與 PR 真人核准照常。** `docOnlyPaths` 不可為 `*`、`**`、`**/*`、絕對路徑或含 `..`、`.git`。
來源三份範例配置均啟用文字路徑（spring-starters／node-web 為 2026-10-12 新增；2026-10-14 已部署 bestpartner 與 spring-starters）；設定屬新快照，既有任務不自動更新。
實作節點（`IMPLEMENTATION` 閘門）在專案有 `docOnlyPaths` 時，系統會在脈絡加一段「純文件變更規則」（`DocOnlyChanges.implementationNotice`）：
變更全部符合上述條件時不執行後端／前端編譯與測試操作，也不因缺建置證據回報 BLOCKED；有任一檔不符合就照建置規範完整執行
（2026-10-04 起；#1018 只改一行 Markdown，改派的 Codex 角色仍自行跑 Gradle 而受阻）。這是給角色的指示，略過與否仍以系統 git diff 判定為準。
- 指令使用空白環境加少量明確變數、獨立 HOME，不把 Slack/GitHub token 或 CLI session 透過環境傳入。
- 不沿用共享依賴快取與個人 Maven/npm 設定；第一次建置可能很慢。私有依賴可能受阻，不可用複製整包憑證來解決。
- 記錄系統觀察的結束碼、argv、起訖時間、commit、配置與需求版本；log 存在可寫目錄外，單指令輸出超過 8 MB 即停止。
- 配置的檢查指令以非零結束碼結束（例如測試失敗、編譯錯誤），代表程式沒過，屬於實作責任：
  系統把失敗摘要（失敗的測試與編譯錯誤，或 log 最後 40 行）存成受阻發現，自動交回實作節點修正，
  修好後重新獨立驗證；不需真人轉達。實作節點取受阻分流節點的 `implementation` 路由，
  否則取流程中唯一具工作區寫入權的實作節點；判定不出時照舊停給真人。仍受重跑、事件額度與整輪上限約束。
- clone／checkout 失敗、超時、輸出過大，以及 log 顯示環境缺件（相依套件抓不到、磁碟滿、找不到指令、
  沙箱啟動失敗等）、紀錄遺失／雜湊改變、版本不同，都阻止審查或 PR；失敗現場保留並明示等待人工。
- 發布前再驗收據、log 雜湊及同一最終 commit，不因模型回報 PASS 就跳過。

收據是「配置的指令真的在該版本成功執行」的證據，**不是每項情境均正確的證明，也不是 GitHub CI Check**。
例如 `npm test` 被 repo 寫成 no-op 仍可能 exit=0，必須由 Reviewer 查核；JUnit 報告解析、測試案例到機器產物的逐項對照與外部 CI adapter 尚未加入。
目前既有沙箱網路未隔離、唯讀檔案不等於隱藏檔案，不能宣稱完全隔絕同機憑證；仍建議專用非敏感測試環境。

收據與現場在 `$AI_TEAM_HOME/verification-runs/<task>/run-*`；應用備份包含系統 log 與資料庫收據，
不複製獨立 clone／HOME 快取（可能包含大量依賴與 node_modules symlink）。原任務工作區仍依既有備份策略保存。
驗證現場目前不自動清理，應另訂保留與容量政策。
收據含絕對路徑，搬到不同安裝位置後需要重新驗證，不手動竄改資料庫讓舊證據過關。

## PR 與產品交付

人工 PR 核准仍然必須存在。PR 建立成功後記為 `PR_CREATED`，保存 URL 與 publishedHead，
Slack 明示「尚未確認合併／發布／產品驗收」。它是本段自動管線的結束，不代表產品已交付。
重啟不重播發布；保留 PR_CREATED 工作區供後續查核。
推送或開 PR 本身失敗（token、權限、網路）時留在 `AWAIT_PR_APPROVAL` 並貼出錯誤原文，修好後再按核准即可；
發布前證據或 Git 目標不符時才停給人，停在 approval 節點時可按「重新進入核准 PR」，詳見 [失敗回報](failure-reporting.md)。

尚未串接合併事件、發布版本、產品驗收結果、回滾或部署監控；沒有可靠來源就不產生 MERGED／RELEASED 等成功狀態。
導入這些功能前需先定義目標 repo 的發布方法與真人責任，不自行新增自動合併或部署權限。
舊版本 jar 不認得 PR_CREATED；產生新版任務後回退須用升級前備份，不讓舊 jar 直接讀新版資料。

## 尚待真實端對端驗收

程式測試以子行程模擬獨立驗證，不代表 systemd 與實際建置都已在各環境通過。
2026-09-06 上線時列為待真實環境驗收的項目：Linux 的全新 clone／唯讀釘選／逾時與子行程終結、真實專案的固定驗證指令、
Slack 的需求變更核准及 PM 返工、額度續跑，以及 PR 人工核准。之後的實測狀態以 [從零重建驗證紀錄](rebuild-verification.md) 為準，未列為通過者不可宣稱已驗收。
