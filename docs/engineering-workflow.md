# 需求追溯、獨立驗證與交付邊界

## 狀態

**後續部署更新：**2026-09-06 18:08 已部署至既有 VM，Linux 沙箱／獨立基準建置與啟動檢查通過。
版本雜湊、清理範圍、實測證據及尚待 email 端到端驗收見 [v2 部署紀錄](deploy-2026-09-06-v2.md)。下文保留本機階段的歷史紀錄。

2026-09-06 本機新增，僅適用配置式 `version: 2` 流程。尚未部署，不能認為既有 Slack 任務已套用。
兩份 teams 範例已改為 v2；既有 v1 快照及 legacy 任務不自動升級。
本機測試證據在本文件末尾；systemd 真實執行、CLI／Slack、目標專案建置仍待 Linux 驗收。

## 需求基準與責任

- 入口使用工作契約 `REQUIREMENTS`，不綁定角色名稱。
- 每項需求保存 `id`、`kind`（FUNCTIONAL／NON_FUNCTIONAL）、描述、可觀察的 `acceptance` 與 `source`。
- 保存排除範圍 `out_of_scope` 與待確認問題 `open_questions`；有未解問題不得進實作。
- 純詢問仍可結束而不建立工作基準；不會為了回答問題強迫執行開發流程。
- 任務提出者是預設真人需求／驗收負責人。這表示責任歸屬，**不表示他已逐條核准模型第一次整理的需求**。
  保留原先全自動策略：清楚的需求自動前進，歧義與產品取捨才停下。若需要每次都人工確認需求，需另加明確需求核准閘門。
- 基準 SHA-256 由系統計算，不接受模型自訂。PM 對使用者原意的語意理解仍須角色審查與真人確認，機械檢查不能保證理解正確。

QA 測試計畫必須引用 `baseline_revision`，每個情境的 `requirement_ids` 必須存在且不重複；
所有需求都至少被一個情境涵蓋。系統會列出漏掉的需求 ID，不只顯示「覆蓋不足」。
驗收結果再引用相同需求版本與系統計算的 `plan_revision`，逐一對應 scenario ID、指令、實際結果與證據。
即使情境 ID 沒變，預期結果改了也會使舊驗收失效。

這是結構追溯，不是語意正確性的證明。Reviewer 必須檢查測試內容真的驗證 acceptance，
不能只看 ID 對上。QA 可以讀程式做白箱測試，但不能只做 Code Review 或採信開發者說通過。

## 返工與需求變更

每個節點的 `requires` 宣告前置契約；啟動驗證會拒絕相依循環、略過必要前置契約或不完整的 PR 核准清單。
重做節點會撤銷所有相依報告／版本收據與獨立驗證，既有歷史 stage/audit 不刪除。

| PM 分流 | 行為 |
|---|---|
| implementation | 既定需求內修正實作，再驗收與審查 |
| verification | 補測試／證據，再獨立驗證與審查 |
| plan | 修正既定需求的測試計畫，再重跑下游實作／驗收／審查 |
| design | 修正設計，再測試計畫與下游；只有配置含此路線時可用 |
| requirements | 提議變更需求，停止等待原需求負責人明確核准 |
| human | 環境、權限、產品取捨或原因仍無法核實；明示目前沒有 agent 處理 |

需求變更的 Slack 回覆範例（僅新版部署後生效）：

```text
@zipe-ai-team 補充 #編號 核准需求變更 SMTP 逾時時禁止自動重送，改回報結果未知
```

只有原需求負責人且仍在白名單內能授權；「好」或一般補充不會核准變更，也不會核准 PR。
系統保留舊基準於 `flow_run.baselineHistory`，稽核記錄核准者、具體變更與原版本。
重新建立基準後清除有效下游收據，重新跑配置流程，不清掉原工作區或既有 Git 紀錄。
重試上限不因重新規劃而歸零；用完仍需人工檢查／另建任務。

## 系統獨立驗證

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
- 指令使用空白環境加少量明確變數、獨立 HOME，不把 Slack/GitHub token 或 CLI session 透過環境傳入。
- 不沿用共享依賴快取與個人 Maven/npm 設定；第一次建置可能很慢。私有依賴可能受阻，不可用複製整包憑證來解決。
- 記錄系統觀察的結束碼、argv、起訖時間、commit、配置與需求版本；log 存在可寫目錄外，單指令輸出超過 8 MB 即停止。
- 非零結束碼、超時、紀錄遺失／雜湊改變、版本不同，都阻止審查或 PR；失敗現場保留並明示等待人工。
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

尚未串接合併事件、發布版本、產品驗收結果、回滾或部署監控；沒有可靠來源就不產生 MERGED／RELEASED 等成功狀態。
導入這些功能前需先定義目標 repo 的發布方法與真人責任，不自行新增自動合併或部署權限。
舊版本 jar 不認得 PR_CREATED；產生新版任務後回退須用升級前備份，不讓舊 jar 直接讀新版資料。

## 驗證紀錄

2026-09-06 17:48:50（台北時間）：19 個選定測試類別共 **150 項通過、0 失敗、0 錯誤**，`mvn package` 成功。
包含 13 項真實暫存 Git repo 測試；IndependentVerifierTest 的 8 項測試模擬子行程，
驗證配置指令、全新 clone 策略、釘選與 log 收據，**不代表 systemd／實際建置已通過**。
另通過 `check-source.sh`（Bash 語法及 16 項平台 fixture）與打包 jar 的兩份 v2 `--validate-team`。

測試類別：TraceabilityTest、IndependentVerifierTest、ConfiguredExecutorTest、TeamCatalogTest、FlowEngineTest、
DirectStageTest、DirectRequestTest、RoleRoutingTest、TriageRoutingTest、RemediationTest、QaPipelineTest、
QuotaContinuationTest、QaGateTest、ReviewGateTest、FailureReportTest、StageRunnerTest、DiagnosticsTest、
CodexRuntimeTest、WorkspaceManagerTest。
未在 Windows 執行依賴 `/bin/sh` 的 RuntimeDiagnosticsTest／QuotaExceededTest 完整類別。
第一次在受限環境執行遇到 JUnit 暫存目錄 AccessDenied；取得測試目錄權限後重新執行。
中途修正一個 Mockito 重設 stub 時觸發的測試 NullPointerException，最終上述 150 項全數重跑成功。

產物：`orchestrator/target/ai-team-orchestrator-0.1.0.jar`

```text
SHA256 703D480A9B0CC2E623A5ED24FF186C6C982173B340BEA84B2C74F82387E01D20
```

升級前仍須驗收：Linux 的全新 clone／唯讀釘選／逾時與子行程終結、真實專案的固定驗證指令、
Slack 的需求變更核准及 PM 返工、額度續跑，以及 PR 人工核准。這輪未部署、未推送、未建立 PR，舊任務未改動。
