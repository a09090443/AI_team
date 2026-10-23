# 資料模型

Orchestrator 的持久化結構。DDL 唯一來源是
[`orchestrator/src/main/resources/schema.sql`](../../orchestrator/src/main/resources/schema.sql)，
本文說明每張表的用途、每個欄位的意義與允許值，以及四個 JSON 欄位對應的 Java 型別。

- **資料庫**：SQLite，正式機路徑 `~/ai-team/ai-team.db`。
- **建立方式**：服務啟動時執行 `schema.sql`，全部 `IF NOT EXISTS`，可重複執行。
- **沒有遷移機制**：schema 改了不會自動升級既有資料庫；加欄位要自己補 `ALTER TABLE`，
  這也是流程狀態刻意存成 JSON 而非展開欄位的原因（見 [執行現場](#執行現場四張-json-快照表)）。
- **沒有外鍵**：所有 `task_id` 都是邏輯關聯，刪除 `task` 不會連動刪除其他表。
- **時間格式**：所有時間欄位都是 `TEXT`，`Instant.toString()` 的 ISO-8601 UTC
  （例如 `2026-09-10T14:42:16.123Z`），可直接字串排序。
- **金額單位**：`cost_usd` 為美元。只有 runtime 實際回報花費時才有值；
  claude 與 codex 多半回報 `null`，opencode 會回報實際金額（見 [opencode-runtime-report.md](opencode-runtime-report.md)）。

## 全表一覽

| 資料表 | 每列代表 | 生命週期 |
|---|---|---|
| [`task`](#task) | 一則 Slack 需求 | 永久保留 |
| [`task_sequence`](#task_sequence) | 全域唯一列，任務 ID 發號器 | 永久，只有一列 |
| [`processed_event`](#processed_event) | 一個已處理的 Slack event_id | 保留 1 小時後清除 |
| [`audit_log`](#audit_log) | 一次稽核事件 | 永久保留 |
| [`task_checkpoint`](#task_checkpoint) | 一個任務的中斷現場 | 階段恢復後清空 |
| [`direct_request`](#direct_request) | 一次對角色的直接提問 | 永久保留 |
| [`flow_run`](#flow_run) | 一個任務的 v2 流程快照 | 每次狀態變更整份覆寫 |
| [`task_remediation`](#task_remediation) | 一個任務的 PM 派工狀態 | 收斂後整列刪除 |
| [`stage_attempt`](#stage_attempt) | 一次階段執行（含被覆蓋的） | 永久保留，目前只寫不讀 |
| [`stage_result`](#stage_result) | 一個角色的最新產出 | 重做時覆蓋 |

關聯都以 `task.id` 為中心：

```
task (1) ──┬── (0..1) task_checkpoint     中斷現場
           ├── (0..1) flow_run            v2 流程快照
           ├── (0..1) task_remediation    PM 派工狀態
           ├── (0..1) direct_request      （子請求自己那一列）
           ├── (0..n) stage_result        每角色一列
           ├── (0..n) stage_attempt       每次執行一列
           └── (0..n) audit_log           每個事件一列
```

---

## task

**2026-10-11 相容性注意**：新 JAR 可讀取沒有 issueInventory／issueEvidence／issueContext／issueScopeChangeAuthorized 的舊 FlowRun，預設為空／false，
是否啟用問題追蹤仍依快照資產標記。新版將這些欄位寫入 JSON 後，舊 JAR 的嚴格反序列化無法讀取；
回滾應成套還原部署前 JAR、teams 與備份 DB，且不得覆蓋部署後新增的任務／核准紀錄，須先評估並保留現場。
沒有自動資料遷移，也不回溯把舊摘要當成已驗證的問題清單。

任務主表，一則 Slack 需求一列。對應 `com.zipe.aiteam.task.Task`。

| 欄位 | 型別 | 說明 |
|---|---|---|
| `id` | TEXT PK | 序號字串，如 `'1014'`。**是字串不是整數**，排序一律 `ORDER BY CAST(id AS INTEGER)` |
| `slack_channel` | TEXT NOT NULL | 需求所在頻道 |
| `slack_thread_ts` | TEXT NOT NULL | 討論串根訊息 ts，所有回報都貼回這裡 |
| `requester_user_id` | TEXT NOT NULL | 提出者。同時是 `flow_run.acceptanceOwner`，**唯一有權核准計畫／變更／PR 的人** |
| `request_text` | TEXT NOT NULL | 原始需求文字，不做正規化 |
| `state` | TEXT NOT NULL | `Task.State` 的列舉名稱，見下表 |
| `status_message_ts` | TEXT | 狀態訊息 ts，供原地編輯進度；尚未貼出時為 NULL |
| `pm_session_id` | TEXT | **遺留欄位**，v2 後已無任何程式碼讀寫 |
| `pm_result` | TEXT | **遺留欄位**，產出改存 `stage_result` |
| `redo_count` | INTEGER NOT NULL DEFAULT 0 | 重做次數，上限 `Task.MAX_REDO`（3），避免人與 agent 互相消耗額度 |
| `failure_reason` | TEXT | 只有 `FAILED` 時有值 |
| `created_at` | TEXT NOT NULL | 建立後不再變動 |
| `updated_at` | TEXT NOT NULL | 每次 `TaskStore.save()` 覆寫 |

索引：`idx_task_state ON task(state)`——重啟時掃未結束任務用。

注意 `TaskStore.save()` 的 upsert **只更新** `state`、`status_message_ts`、`redo_count`、
`failure_reason`、`updated_at`；其餘欄位建立後不再改寫。

### `state` 的允許值

取自 `Task.State`。三類的判定規則寫在列舉自己的 `isRunning()` / `isWaiting()` / `isTerminal()`。

| 分類 | 值 | 意義 |
|---|---|---|
| 佇列 | `QUEUED` | 已受理，等待排入執行 |
| 等待 | `WAITING_QUOTA` | 額度用盡，等待 `QuotaAutoResume` 或人工續跑 |
| 等待 | `RESUME_QUEUED` | 續跑已被領取，等待執行（原子轉換，連按只有一次生效） |
| 等待 | `TRIAGE_QUEUED` | PM 派工已被領取，等待執行 |
| 等待 | `AWAIT_PR_APPROVAL` | 全自動模式下唯一的人工閘門：推分支與開 PR 之前 |
| 等待 | `AWAIT_HUMAN` | PM 把握度低、判定為純詢問，或流程受阻，停下等人決定 |
| 執行中 | `PM_TRIAGE_RUNNING`、`DIRECT_RUNNING`、`STAGE_RUNNING`、`PUBLISH_RUNNING` | v2 配置式流程使用 |
| 執行中 | `PM_RUNNING`、`ARCH_RUNNING`、`QA_PLAN_RUNNING`、`IMPL_RUNNING`、`QA_RUNNING`、`REVIEW_RUNNING` | legacy 固定管線使用 |
| 終止 | `COMPLETED` | 完成 |
| 終止 | `PR_CREATED` | 自動開發管線結束。**不代表已合併、發布或產品驗收** |
| 終止 | `ABORTED` | 中止。注意：下次服務重啟的 `purgeWorkspaces()` 會刪掉這類任務的工作區；例外是 audit 有 `OWNER_CLOSE`（負責人結案）或舊管線審查未通過的任務 |
| 終止 | `FAILED` | 失敗，原因在 `failure_reason` |

`findUnfinished()` 以 `state NOT IN ('COMPLETED','PR_CREATED','ABORTED','FAILED')` 判定「未結束」。

## task_sequence

任務 ID 發號器，**永遠只有一列**（`CHECK (id = 1)`）。

| 欄位 | 型別 | 說明 |
|---|---|---|
| `id` | INTEGER PK | 恆為 1 |
| `value` | INTEGER NOT NULL | 最後發出的號碼，初始 1000 |

發號用 `UPDATE ... SET value=value+1 ... RETURNING value` 原子完成。
即使刪掉最高編號的任務也不重用 ID——舊的 Slack 按鈕會指到新任務。
啟動時 `initSequence()` 會把 `value` 拉高到 `MAX(CAST(id AS INTEGER))`，避免還原資料庫後撞號。

## processed_event

Slack `event_id` 去重表。

| 欄位 | 型別 | 說明 |
|---|---|---|
| `event_id` | TEXT PK | Slack 的 event_id |
| `seen_at` | TEXT NOT NULL | 首次看到的時間 |

索引：`idx_processed_event_seen_at ON processed_event(seen_at)`，供清除舊紀錄。
保留時間 `TaskStore.DEDUP_RETENTION_HOURS` = 1 小時（Slack 最長 5 分鐘後重送）。
**必須持久化**：服務重啟後若把 Slack 的重送當成新任務，同一個需求會被執行兩次。

## audit_log

稽核紀錄，是 Orchestrator 的三大職責之一（另兩項是任務狀態機與審批閘門），
用來回答「這個任務到底發生過什麼事」。同時寫入資料庫與應用程式 log。

| 欄位 | 型別 | 說明 |
|---|---|---|
| `id` | INTEGER PK AUTOINCREMENT | 也是同一任務內的事件順序 |
| `task_id` | TEXT | **可為 NULL**：任務尚未建立就發生的事件 |
| `at` | TEXT NOT NULL | 事件時間 |
| `actor` | TEXT NOT NULL | Slack user id、`system`（`AuditLog.ACTOR_SYSTEM`）、或角色名稱 |
| `action` | TEXT NOT NULL | 見下表 |
| `detail` | TEXT | 自由文字，**寫入前截斷至 2000 字元**，超出補 `…` |
| `cost_usd` | REAL | 僅 runtime 有回報花費時有值 |
| `session_id` | TEXT | 對應 runtime session，供事後追查 |

索引：`idx_audit_task ON audit_log(task_id, id)`。

`action` 的取值（常數定義在 `AuditLog`）：

| 值 | 意義 |
|---|---|
| `RECEIVED` | 收到需求 |
| `STATE_CHANGE` | 狀態轉換。`detail` 固定格式 `FROM → TO`，由 `stateChange()` 產生 |
| `RUNTIME_CALL` | 呼叫 agent runtime |
| `BUTTON` | Slack 按鈕操作 |
| `ERROR` | 錯誤（同時以 `log.error` 輸出） |
| `REJECTED` | 權限或閘門拒絕 |
| `RECOVERY` | 恢復授權相關 |
| `TOOL_DENIED` | Agent 想用但被權限設定擋下的工具呼叫。不記錄的話會被誤判成 agent 能力不足 |
| `OWNER_CLOSE` | 驗收負責人按了 agent 提出的結案選項（`action=close`）。任務狀態同樣是 `ABORTED`，啟動清理靠這筆紀錄保留工作區（2026-10-06 起；舊版 JAR 不認得，會照一般中止刪除） |

稽核寫入失敗不會讓任務失敗，但會留下 `log.error`。常用查詢：

```bash
sqlite3 ~/ai-team/ai-team.db "SELECT at,actor,action,detail FROM audit_log WHERE task_id='1014' ORDER BY id"
```

---

## 執行現場：四張 JSON 快照表

`task_checkpoint`、`direct_request`、`flow_run`、`task_remediation` 結構相同：

```sql
task_id TEXT PRIMARY KEY,
data    TEXT NOT NULL
```

`data` 是 Jackson 直接序列化對應 Java 型別的結果，由 `TaskStore` 讀寫。
**這些結構沒有版本欄位**：Java 型別改了欄位名稱，舊資料就解析失敗，
而 `TaskStore` 對解析失敗一律拋 `IllegalStateException`（例如「流程快照損毀，拒絕改用新配置」）
而不是靜默降級——寧可停下來，也不要拿半份狀態繼續跑。改這些 record 前先確認線上沒有進行中的任務。

### task_checkpoint

中斷階段的最小可恢復現場，對應 `com.zipe.aiteam.task.Checkpoint`。
工作樹與未提交檔案不在這裡，另外留在工作區。

| JSON 欄位 | 說明 |
|---|---|
| `taskId` | 對應 `task.id` |
| `role` | 中斷時所在的角色（v2 是節點名稱）。續跑時會比對，不符就拒絕 |
| `runtime` | `claude` / `codex` / `opencode`。與配置不符時拒絕跨引擎續跑 |
| `model` | 當時使用的模型；opencode 是 `provider/model` 格式，與另兩者不通用。同樣會比對 |
| `sessionId` | 供 `--resume` 接回同一個 session。中斷太早可能為 null，此時以新 session 接手同一階段 |
| `upstream` | **兩條路徑意義不同**：legacy 存上游階段摘要，v2 存累積的 `flow.context` |
| `qaHead` | **兩條路徑意義不同**：legacy 存 `task.qaBaseline()`，v2 存 `flow.beforeHead`（限寫階段起始 commit） |
| `reason` | 中斷原因 |

`upstream` 與 `qaHead` 這兩個名字是 legacy 留下來的，v2 借用同一個 record 塞了不同東西——
看欄位名會猜錯，以 `StageRunner`（legacy）與 `ConfiguredExecutor`（v2）的建構處為準。

### direct_request

對話另存的唯讀子請求，對應 `com.zipe.aiteam.task.DirectRequest`。
**子請求有自己的 `task.id`**，這裡的 `task_id` 是子請求本身的 ID，不是母任務的。
用途見 [direct-agent-requests.md](direct-agent-requests.md)。

| JSON 欄位 | 說明 |
|---|---|
| `taskId` | 子請求自己的 ID |
| `role` | 目標角色。序列化為字串（`SYSTEM` / `PM` / `ARCHITECTURE` / `BACKEND` / `QA` / `CODE_REVIEW`，或配置定義的角色名） |
| `parentId` | 母任務 ID |
| `context` | 提問內容 |

只 `INSERT` 不 upsert——一次提問一列，不覆蓋原任務的驗收與 checkpoint。

### flow_run

v2 配置式流程的整份執行快照，對應 `com.zipe.aiteam.flow.FlowRun`。
**每次狀態變更整份覆寫**。這是四張表裡欄位最多、也最需要小心的一張。
自主派工新增 assignments／assignmentHistory，以及 snapshot.definition 中的 candidateRoles／capabilities；
新版寫入後舊 JAR 不能直接解析，回滾需配合升級前的資料庫與配置備份。

角色諮詢與共享看板（2026-10-17）新增了以下欄位：

| JSON 欄位 | 說明 |
|---|---|
| `board` | 看板，依 `B-<n>` 編號存放 `TeamBoard.Entry`（id、kind、text、to、refs、author、node、status、resolution、supersededBy、origin） |
| `boardSeq` | 下一個看板編號 |
| `consultations` | 已完成的問答，型別是 `Consultation.Answer`，以 node、visit、round、index 定位 |
| `pendingConsult` | 還沒答完的一輪，型別是 `Consultation.Pending` |
| `consultUsed` | 整個任務已用掉的題數 |
| `snapshot.definition.collaboration`、`nodes.*.consultRoles` | 對應的配置欄位 |

`snapshot.definition.sharedInstructions`（2026-10-23）與上述欄位一樣在 null 時不寫入；但四份內附配置都有設定，
所以部署後新建的任務快照一律帶這個欄位，舊 JAR 讀不了，回滾需搭配部署前的 DB 備份。

這些欄位為空時都不會寫進 JSON，所以沒有用到協作的任務，舊 JAR 仍然讀得到。只有用過協作的任務會受「舊 JAR 不能解析」的限制。

最關鍵的性質：`snapshot` 內含「任務開始時」的 teams 配置，
磁碟上的配置改了不會影響進行中的任務。要換配置必須走 `TeamMigration`，
而遷移會清掉 `planApprovals`、`executionContexts`、`recoveryGrants`
並把舊快照整份推進 `migrationHistory`——舊的核准不會自動延用到新配置。

| JSON 欄位 | 型別 | 說明 |
|---|---|---|
| `snapshot` | `TeamDefinition.Snapshot` | 當時的配置：`revision`（雜湊）、`definition`、`repository`、`assets` |
| `node` | String | 目前所在節點 |
| `activeNode` | String | 已進入但尚未結束的節點。**非 null 代表現場還在**，續跑可直接恢復，不需重做配置遷移 |
| `transitions` | int | 一般流程累計轉換次數；受控責任交接可越過 `definition.maxTransitions()`，歷史值不重設 |
| `visits` | Map&lt;節點,次數&gt; | 各節點造訪次數，上限為節點自己的 `maxVisits()` |
| `systemRetries` | Map&lt;鍵,次數&gt; | 系統層自動重試次數。鍵為節點 id 時是環境失敗重試（上限 `SystemFailure.RETRY_LIMIT`，該節點真的跑起來即清除）；鍵為 `<節點>#issue-format` 時表示已對該節點自動修正過一次問題清單格式，回報通過問題清單檢查即清除（2026-10-15 起）；2026-10-20 長作業另使用 operation-checkpoint／operation-timeout／operation-timeout-total 鍵，見下節；2026-10-23 起 `owner-forward-cap`（負責人裁定或開 PR 後往 PR 核准走的換手上限）與 `owner-forward-path:<節點>`（該路徑上的節點），任何節點受阻即清除，見 [工程流程](engineering-workflow.md#負責人裁定待決問題2026-10-23)。沿用既有 Map，無 schema 變更 |
| `automaticHandoff` | String | 尚待使用的受阻證據鍵；含配置版本、來源／目標節點、HEAD 與報告摘要 |
| `automaticHandoffs` | int | 已處理的獨立受阻事件數；基本上限 6；可辨識的舊制逐節點 key 會按證據合併重算 |
| `automaticHandoffExtraBudget` | int | 真人針對目前現場增加的事件額度；每次 2、最多額外 6，不重設既有計數；配置遷移時歸零 |
| `automaticHandoffHops` | int | 同一受阻事件已經過的節點數；固定上限 12，避免同一事件在節點間循環 |
| `automaticHandoffKeys` | Set&lt;String&gt; | 已使用的事件證據鍵，防止同一受阻事件重複消耗事件額度 |
| `automaticHandoffExtensionKeys` | Set&lt;String&gt; | 已使用的人工延長授權碼；綁定配置、受阻證據與 HEAD，永久保留以防重放 |
| `workspaceReady` / `workspacePath` | boolean / String | 工作區是否已備妥、路徑 |
| `context` | String | 滾動交接紀錄，預設空字串。上限 20000 字元，單筆事件上限 6000 字元；**超量時淘汰最舊的段落而不是截斷新內容**，並在開頭標示已淘汰。舊版以留頭截尾的 `clip` 累加，context 一旦飽和就永久凍結，之後的受阻報告與使用者補充都會被靜默丟棄（#1014） |
| `lastBlockReason` | String | 上一次受阻原因，回報時附在訊息裡（截斷 4500 字元） |
| `acceptanceOwner` | String | 需求／驗收負責人，來自 `task.requester_user_id`。所有核准都比對這個值 |
| `ownerPublishBy` | String | 負責人以「開 PR」命令發布、但審查還沒跑時記下指令者；走到發布核准節點且檢查全過就直接發布。停下等人時清除。2026-10-08 起。null 也會寫出，所以新版存過的任務舊 JAR 都讀不了（回滾要注意） |
| `baselineRevision` | String | 需求基準摘要。需求節點產出後建立；需求變更會清空並連帶清掉 `receipts`／`heads` |
| `baselineHistory` | List | 歷次需求基準報告的深拷貝，不覆蓋 |
| `receipts` | Map&lt;節點,報告&gt; | 各節點**通過**時的結構化報告；受阻報告不寫進來。例外：驗收負責人以「開 PR」放行的審查報告會寫進來，帶 `owner_waiver`（放行者、時間、原因），被放行的 finding 改列 `LOW` 並帶 `waived_from` 原等級（2026-10-06 起，見 [review-gate](review-gate.md#負責人命令開-pr-與放行提醒等級2026-10-06)）；放行未核實事項的驗收報告也會寫進來，`uncertainties` 清空、原項目移到 `waived_uncertainties`，帶 `owner_waiver`（2026-10-08 起） |
| `blockedFindings` | Map&lt;節點,報告&gt; | 尚未解除的受阻發現，以來源節點為鍵。受阻時寫入，該節點自己通過時移除。**不受 `context` 長度上限影響**，必定注入下游節點提示；節點受阻證據與 issueInventory 問題清單分別保存。需求變更不清除，採 fail closed |
| `heads` | Map&lt;節點,commit&gt; | 各節點結束時的 HEAD |
| `issueInventory` | Map&lt;問題 ID,JSON&gt; | 新 v2 共用契約的完整問題清單，含 description／evidence／scope／reason／requirement_ids、系統 source_task／source_node；DECISION 額外保存 decision_supplements，防止未收到新回覆就自行決定。空更新不清除，承接完整深拷貝 |
| `issueEvidence` | Map&lt;問題 ID,JSON&gt; | 通過驗收／審查／服務階段的逐項 id／status／evidence／head／node；承接只作歷史證據，不能取代新任務驗收。2026-10-23 起另有 `owner-decision:<問題 ID>` 鍵：負責人裁定移出範圍的紀錄（`previous` 原項目、`user`、`at`） |
| `priorReceipts` | Map&lt;節點,JSON&gt; | 2026-10-23 新增。被清掉的前次收據（`report`、`head`、`baseline`、`configuration`、`actionable_issues`、`inputs` 前置結果摘要），流程重走時符合條件就原樣沿用（`ReceiptReuse`），沿用後移除；只是候選、不是有效收據。舊 JSON 沒有此欄位時為空；新版存過的任務舊 JAR 讀不了 |
| `issueRevisionAliases` | Map&lt;新版本,舊版本&gt; | 2026-10-23 新增。問題清單變動只是既有待決項目更新說明時記錄的版本別名，舊驗收沿用；最多 50 筆。舊 JSON 沒有此欄位時為空；新版存過的任務一律寫出此欄位，回滾到舊 JAR 會讀不了 |
| `issueContext` | String | 單項衍生任務的來源總數與局部範圍說明，承接保留 |
| `issueScopeChangeAuthorized` | boolean | 由原需求負責人明確核准需求變更時給予的一輪排除變更權；建立新基準後清除，不由模型授予、不沿用到承接任務 |
| `origin` / `branch` | String | 工作區的 origin 與分支 |
| `beforeHead` | String | 限寫階段的起始 commit。**缺這個就拒絕核實寫入範圍**（`SecurityException`） |
| `comparisonBase` | String | 工作區建立／承接當下的 Git commit，返工不更換；內建指定檔案比對的基準。舊任務為 null，不允許以目前 HEAD 補造 |
| `changeTarget` / `changeAuthorized` | String / boolean | 待核准的需求變更目標節點；核准後才允許回頭改需求 |
| `pendingPlanRevision` | String | 待核准的測試計畫節點 |
| `planApprovals` | Map&lt;節點,`PlanApproval`&gt; | 真人核准收據：`plan`、`planRevision`、`baseline`、`configuration`、`user`、`at`。**只由已授權真人指令建立**，不接受 agent 報告裡的 `approved` 欄位 |
| `approvalHead` | String | 核准當下的 HEAD |
| `executionContexts` | Map&lt;節點,`OperationPreflight.Evidence`&gt; | 沙箱預檢證據：`workspace`、`head`、`configuration`、`checkedAt`、`log`、`sha256` |
| `independentEvidence` | `IndependentVerifier.Receipt` | 獨立驗證收據：`head`、`baseline`、`configuration`、`startedAt`、`completedAt`、`commands[]`（每項含 `argv`、`exitCode`、`log`、`sha256`）；`docOnlyChanges`（2026-10-03 新增）非 null 表示純文件變更、依配置略過建置測試，此時 `commands` 為空並列出判定依據的變更檔案。**回滾到舊 JAR 會讀不了含此欄位的任務** |
| `recoveryGrants` | Map&lt;節點,配置版本&gt; | 一次性的 verification 恢復授權 |
| `recoveryRevisions` | Set&lt;配置版本&gt; | 已用掉恢復授權的配置版本，同一版本不給第二次 |
| `remediationRecoveryGrants` | Map&lt;節點,commit&gt; | 人工修復後尚待使用的一次性 verification 授權；同時以 `recoveryGrants` 綁定配置版本 |
| `remediationRecoveryKeys` | Set&lt;配置版本:節點:commit&gt; | 已授權／使用的人工修復重驗 key，防止同一 commit 重放；CLI 未呼叫模型時才移除並還原授權 |
| `attemptHeads` | Map&lt;節點,commit&gt; | 各 workspace 節點最近一次真正開始執行時的 HEAD；人工修復重驗必須使用不同的新 commit |
| `migrationHistory` | List&lt;Snapshot&gt; | 歷次配置遷移前的完整舊快照 |
| `dialogue` / `dialogueRole` / `parentId` | boolean / String / String | 對話模式旗標與目標角色、母任務 |
| `assignments` | Map&lt;節點,Assignment&gt; | 目前有效的自主派工或同一固定角色交接；Assignment 含 source、node、role、reason、instruction；續跑沿用，不修改配置原 role |
| `assignmentHistory` | List&lt;Assignment&gt; | 依序保存每次自主派工決定，最多 256 筆；缺欄位的舊快照初始化為空 |
| `successor` / `continuationOf` | String / String | 承接成的新任務／本任務承接自哪個任務 |
| `spinoffs` | Map&lt;項目序號,任務編號&gt; | 純詢問結束後以「開成新需求」按鈕從最後報告的 `out_of_scope` 開立的任務（序號從 1 起，最多 5）。用來防止同一項重複開立（2026-10-03 新增） |
| `spinoffOf` | String | 本任務由哪個任務的純詢問發現開立；不沿用該任務的核准、工作區或結論（2026-10-03 新增） |
| `roleOverrides` | Map&lt;節點,角色&gt; | 整節點改派（舊行為）實際執行的角色；同一任務重跑沿用。分段改派上線後新任務不再寫入（2026-10-04 新增，回滾風險同上） |
| `delegatedParts` | Map&lt;節點,DelegatedPart&gt; | 分段改派的改派段結果：`role`、`startHead`（本輪起始 commit）、`head`（系統代為提交後）、`files`、`summary`（摘要，最多 2000 字）。續跑同一輪時略過已完成的改派段；節點新的一輪開始時移除（2026-10-04 新增，回滾風險同上） |
| `prUrl` / `publishedHead` | String | 發布結果 |

2026-10-17 `IndependentVerifier.Receipt` 新增可為 null 的 `decisions`、`changedFiles`、`taskId`（見 [engineering-workflow](engineering-workflow.md)「依變更檔案只跑相關指令」）：
`decisions[]` 與配置指令一一對應，每項含 `index`、`argv`、`action`（`RUN`／`SKIP`／`REUSE`）、`reason`、`matched[]`（最多 20 個命中檔案）、
`fingerprint`、`log`、`sha256`（RUN／REUSE 才有）、`sourceHead`（REUSE 的來源 commit）；有 `decisions` 時 `commands[]` 只列本輪實際執行的結果。
`changedFiles` 最多 500 個；`taskId` 供發布前重算。舊收據三者為 null，沿用 `commands[]` 逐一核對。
`FlowRun` 新增 `verificationHistory`：List&lt;`VerificationPlan.HistoryEntry`&gt;，每項 `argv`、`fingerprint`、`configuration`、`head`、`log`、`sha256`，
最多 20 筆；缺欄位的舊資料初始化為空。**回滾到舊 JAR 會讀不了含這些欄位的任務**，須配合部署前 DB 備份。

2026-10-10 `IndependentVerifier.Receipt` 新增可為 null 的 `fileComparison`：
`base`（固定 Git 基準）、`head`、`planDigest`（所有已保存測試計畫的雜湊）、`files[]`（`path`、`before`、`after`，後兩者為 Git mode／blob ID）、
`log`（系統比較結果 JSON）及 `sha256`。未引用內建比對的舊計畫保持 null。
計畫情境新增 `unchanged_paths` 字串陣列，配合 `operation_ids` 的 `compare-unchanged-files` 使用；舊計畫缺欄位仍可讀取。
**新版保存 `comparisonBase`／`fileComparison` 後不可直接換回不認識欄位的舊 JAR**；回滾須配合升級前 DB 備份評估，不覆蓋升級後任務。

**回滾風險**：`TaskStore` 以預設 ObjectMapper 讀 `flow_run.data`，未知欄位會拋錯。新版寫入的欄位
（例如 `spinoffs`、`spinoffOf`）對舊資料相容（缺欄位取預設值），但**回滾到不認得這些欄位的舊 JAR 後，
讀這些任務會解析失敗**。回滾前先確認沒有任務寫入新欄位，或一併處理資料。

相關文件：[configurable-team.md](configurable-team.md)、[engineering-workflow.md](engineering-workflow.md)、
[plan-approval-buttons.md](plan-approval-buttons.md)、[cross-project-execution.md](cross-project-execution.md)。

### task_remediation

QA 受阻後的 PM 派工狀態，對應 `com.zipe.aiteam.task.Remediation`。
不取代原本的 PM 與 QA 計畫。流程見 [pm-triage.md](pm-triage.md)。

| JSON 欄位 | 說明 |
|---|---|
| `taskId` | 對應 `task.id` |
| `phase` | 目前派給誰。`active()` 認得的值：`pm_triage`、`backend`、`qa`、`code_review`；其他值視為已結束 |
| `repairs` | 已修復次數，上限 `Remediation.MAX_REPAIRS`（2） |
| `context` | 派工說明 |

收斂後由 `clearRemediation()` **整列刪除**，所以沒有紀錄不代表沒發生過派工——
要查歷史得看 `audit_log`。

---

## 階段產出：stage_attempt 與 stage_result

兩張表都由 `TaskStore.saveStage()` 在同一次呼叫裡寫入，差別是保留策略：

- `stage_attempt`：**每一次**執行都留一列，含被後續重做覆蓋掉的那幾次。
- `stage_result`：只留**最新**一次，`(task_id, role)` 唯一。

用獨立資料表而非在 `task` 加欄位，這樣新增角色不需要改 schema。

### stage_attempt

| 欄位 | 型別 | 說明 |
|---|---|---|
| `id` | INTEGER PK AUTOINCREMENT | 也是執行順序 |
| `task_id` | TEXT NOT NULL | 對應 `task.id` |
| `role` | TEXT NOT NULL | 角色名稱，取值由 teams 配置決定 |
| `session_id` | TEXT | 該次執行的 runtime session |
| `result` | TEXT | `structured_output` 的 JSON 原文 |
| `completed_at` | TEXT NOT NULL | 完成時間 |

**目前只寫不讀**：Java 端沒有任何查詢路徑，用途是事後用 sqlite3 追查
「這個角色到底跑過幾次、每次交出什麼」。沒有索引，也沒有清理機制。

### stage_result

`result` 保存節點建立當下 schema 的完整結構化報告。新建 v2 任務的需求報告含
`requirements[].acceptance_items[]`（`id`、`criterion`、`dimension`）；測試計畫情境含
`acceptance_ids[]` 與 `counterexample`。舊任務的配置快照仍依舊 schema 解析，資料庫不回填
這些欄位；若要採用新版規畫契約，必須建立新任務或走正式配置遷移，不能直接改歷史 JSON。

| 欄位 | 型別 | 說明 |
|---|---|---|
| `task_id` | TEXT NOT NULL | 與 `role` 合為主鍵 |
| `role` | TEXT NOT NULL | legacy 為 `pm` / `architecture` / `backend` / `qa`；v2 由配置決定 |
| `session_id` | TEXT | 供重做時 `--resume` 續接 |
| `result` | TEXT | `structured_output` 的 JSON 原文。還原成 `JsonNode` 放進 `Task.stages` |
| `cost_usd` | REAL | 該階段花費（USD）。`Task.totalCost()` 把非 null 的加總後顯示在 Slack |
| `elapsed_sec` | INTEGER | 該階段耗時（秒） |
| `completed_at` | TEXT NOT NULL | 完成時間 |

這裡是**完整報告的唯一去處**。Slack 的階段回報預設只貼判定／摘要／阻礙／建議後續，
完整內容留在這張表，要看得用「補充 #&lt;ID&gt; 檢視完整報告 [節點]」取回（只讀不改狀態），
或設 `AI_TEAM_REPORT_DETAIL=full` 回到舊行為。見 [INSTALL.md](INSTALL.md)。

---

## 直接查資料庫

```bash
sqlite3 ~/ai-team/ai-team.db ".schema"
sqlite3 ~/ai-team/ai-team.db "SELECT id,state,updated_at FROM task ORDER BY CAST(id AS INTEGER) DESC LIMIT 10"
sqlite3 ~/ai-team/ai-team.db "SELECT at,actor,action,detail FROM audit_log WHERE task_id='1014' ORDER BY id"
sqlite3 ~/ai-team/ai-team.db "SELECT role,cost_usd,elapsed_sec,completed_at FROM stage_result WHERE task_id='1014'"
sqlite3 ~/ai-team/ai-team.db "SELECT json_extract(data,'$.node'), json_extract(data,'$.activeNode') FROM flow_run WHERE task_id='1014'"
```

服務執行中請只做唯讀查詢。直接改 `task.state` 或 `flow_run.data` 會繞過狀態機與閘門，
記憶體中的任務也不會同步——要改狀態請用 Slack 指令。


## 2026-10-10：服務建置與指定腳本快照

TeamDefinition.Project 新增選填 serviceTarget：name（目標名稱）與 checks（檢查名稱 → operation_id）。Operation 新增選填 scriptSha256。
舊快照缺欄位解析為 null，既有開發流程維持不變。新節點 Gate.SERVICE 與 schema service.json 保存在 snapshot.definition／assets，
報告仍寫 stage_result 與 flow.receipts；沒有新增資料表或改寫舊需求。
服務報告含 target、service_status、product_code_changed、checks（name、operation_id、acceptance_ids、status、exit_code、evidence）、uncertainties，
共用 execution_status／failure_report／human_decision 仍由執行器補 schema。
新版本可讀舊資料；舊 binary 不認 SERVICE 與新欄位。新快照寫入後不能只回滾 JAR，必須使用相容程式或在維護期間還原相符備份，且不得覆蓋其後新任務進度。


## 2026-10-10 skill 快照欄位

`flow_run.data.snapshot.definition.nodes.*.skills` 為選填字串陣列；相對路徑與對應的 Markdown 全文分別存於 definition 與 snapshot.assets，納入 revision。旧資料省略時為 null，不需改 DDL。
新快照不能交給不認識 skills 欄位的舊 JAR。部署回滾仍須依既有流程處理 DB 相容性；不能擅自修改任務資料或刪除此欄位。

## 2026-10-12 計畫報告欄位

新 plan schema 的 `protected_path_changes` 是最多 100 個明確相對檔案的陣列，無改派則 []。
保存於 `receipts` 的 TEST_PLAN JSON，納入既有計畫雜湊／核准版本，沒有新增資料表或 FlowRun 欄位。
僅供實作前置計畫宣告需跨 runtime 改派的修改範圍，不包括唯讀與禁止修改檔案；實際變更需同時符合清單與配置白名單。
舊報告缺此欄位仍可解析，但不從文字自動推測新的分段改派；舊快照需更新 schema／計畫或承接新任務。


### 2026-10-19 部署脈絡（已部署 bestpartner）

stage_attempt 現在也供 TaskStore.serviceHistory 查詢同頻道、同目標 SERVICE 歷史報告（最近 12 筆，排除本任務）；以當時 flow_run 的節點 gate 核對，不把分流摘要當服務收據。未新增資料表或序列化欄位。

## 工作區外的長作業 journal（2026-10-20 已部署 bestpartner）

本次不新增 SQLite table、Task.State 或 FlowRun 欄位。OperationJobs 讀取實例 AI_TEAM_HOME/operation-jobs/&lt;task&gt;/&lt;id&gt;.json；標準 workspaces 佈局下 Python executor 由 workspace 的 home 定位同一路徑。journal 在模型工作區外，由固定執行器原子保存；工作區 docs/docker/sit/.agent-service/ 的請求／收據是工具交付通道，不取代可信 journal。

| journal 欄位 | 用途 |
|---|---|
| version／id／task／node | version=1；檔名須等於 32 位小寫十六進位 id.json；綁原任務與 activeNode |
| action／target | 固定操作及登錄目標 |
| source_head／source_sha256 | 當前 Git HEAD；start 封裝 SHA256，其他操作可為 null |
| state | QUEUED、RUNNING、SUCCEEDED、FAILED、UNCERTAIN；前兩者與 UNCERTAIN 均為待對帳 |
| updated／deadline | Unix epoch 秒，與 SQLite 的 ISO-8601 TEXT 時間不同；executor 建立作業時 deadline 為當下加 7200 秒 |
| result／verified／error_kind | 固定執行器的驗證結果、可信標記與結構化原因；SUCCEEDED 必須 verified=true，結果 id／action／target／digest 與 journal 一致，exit_code 為整數且為 0 |
| launched／next_poll_at／cancel_requested | Python 執行器使用的提交、輪詢及取消追蹤欄位；不是新的 FlowRun 欄位 |

OperationJobs 只讀取同 task／node／目前 HEAD 的有效記錄，拒絕 journal 基底／任務目錄 symlink、非一般檔案、非預期檔名、單檔超過 65536 bytes；每次最多讀取排序後 256 個檔案。無法讀取時保留現場並拋錯，不猜測部署結果。FAILED 終態也須有對應且非零的整數 exit_code；UNCERTAIN 不等於已失敗。

FlowRun.systemRetries 沿用以下持久鍵：

- operation-checkpoint:&lt;fingerprint&gt;：同一結果只續一次。
- operation-timeout:&lt;fingerprint&gt;：同一逾時觀察只恢復一次。
- operation-timeout-total:&lt;node&gt;：同節點模型逾時恢復總數，上限 SystemFailure.RETRY_LIMIT（2）。

fingerprint 由作業 id、state、sourceHead、errorKind 組合計算 SHA256，不含更新時間；不能靠 heartbeat／timestamp 變化取得新恢復額度。續原 activeNode 不新計次，原 visits／repeats／transitions 不歸零。

取消要求另存 AI_TEAM_HOME/operation-cancellations/&lt;task&gt;/&lt;id&gt;.json，含 version、id、task、node、requested_at、reason=user_interrupt；只要求取消原 pending job。獨立 heartbeat 為 AI_TEAM_HOME/operation-executor-heartbeat。目標 root 在 /var/lib/ai-team-service/ 保存 id.request、id.source、id.running、id.cancel 與 id.json 等作業證據；不要把 agent journal 與目標收據當同一份資料。

備份／換機須一併考量 journal、取消要求、原 task／flow_run、工作區 HEAD 與目標收據；只備份 SQLite 無法重建長作業證據，本文不宣稱現有備份腳本已自動涵蓋這些新目錄。此版本已部署 bestpartner，重啟／取消／晚到收據的所有真實故障組合尚未全面演練。


## 服務追問診斷資料（已部署bestpartner，非DB schema變更）

service-support/&lt;對話或service task&gt;/current.json是本次固定工具診斷：target、parent、observed_at、endpoints、healthy、repair_attempted、operations。endpoint含audience、url、probe_url、probe_origin、user_route_verified（Agent VM probe固定false）、http_status與healthy；operations保存受限SSH收據。health/diagnose/repair.json保存原id/action/target與result，repair另外持久attempts、最多兩次。

dialogue-evidence/&lt;對話task&gt;/deployment-evidence.txt保存已結案來源清理時的歷史脈絡，附明確「非產品repository」說明；獨立.git只供runtime對話資料夾運作，不是原程式或其commit。沒有改Task/FlowRun欄位或原始schema。備份需包含service-support及service-context才能保存診斷與入口對應。


### 持久診斷檢查點（已部署bestpartner）

新增非DB檔案service-support/<task>/job.json：version=1、task、parent、target、generation（transitions/visits/supplements，正式SERVICE另含來源HEAD及服務作業指紋）、started_at、deadline、updated_at、state（RUNNING/COMPLETED/FAILED）、phase。job.lock是Linux程序互斥；current.json新增task/generation，完成結果須匹配job身分與generation，進行中的舊結果不能當目前健康。repair-policy.json的fingerprint是diagnose證據SHA256，用於攔截相同證據下已失敗修復的原樣重試；原repair.json的ID/attempts仍是對帳與預算依據。沒有修改schema.sql或FlowRun序列化欄位。備份須包含以上檔案。

## 2026-10-22：project.autonomy 與自主恢復記帳

TeamDefinition.Project 新增選填 `autonomy`（modelTimeoutRecoveries、maxTimeoutSeconds、verificationContinuations、requirementChangeOptions、
channelCommandRouting），寫在 flow_run 的 snapshot.definition；舊快照缺欄位解析為 null（不啟用）。新 JAR 存回的快照一律帶 `"autonomy"` 鍵，
舊 JAR 讀到未知欄位會失敗，回滾須搭配部署前 DB 備份（與 serviceTarget 相同）。2026-10-23 `autonomy` 新增 `deferUnverifiable`（boolean，缺欄位為 false），新 JAR 存回的 autonomy 一律帶此鍵。
自主恢復的次數放在既有 `FlowRun.systemRetries`（Map<String,Integer>），鍵為 `autonomy-timeout:<節點>:<HEAD:未提交指紋>`、
`autonomy-timeout-total`、`autonomy-timeout-extended:<節點>`、`autonomy-verify:<commit>`；沒有新增 FlowRun 欄位或資料表。
