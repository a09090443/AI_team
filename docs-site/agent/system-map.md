---
sidebar_position: 2
title: 系統全貌
description: 分層責任、任務生命週期、流程節點、任務狀態與程式碼地圖。
---

# 系統全貌

## 分層責任

| 層 | 負責者 | 內容 |
|---|---|---|
| 人機介面 | Slack 主 App（`zipe-ai-team`）與各角色 App | 事件接收與 ack、去重、白名單授權、討論串回覆、按鈕 |
| 任務治理 | Java Orchestrator | 狀態機、中央佇列與並行槽、人工閘門、稽核、checkpoint |
| 安全隔離 | `ExecutionSandbox`（systemd user unit） | 唯讀掛載、限定可寫路徑、資源上限、逾時 |
| Agent 執行 | runtime CLI（Claude Code／Codex／opencode） | 角色提示、工具白名單、回合上限、結構化輸出 |
| 產物 | Git／GitHub | 每任務獨立 clone、分支、commit、PR |

中央佇列由 `TaskOrchestrator` 的單一工作迴圈依序執行；`QueuedTask` 保留任務身分，
排入時以實際 active／pending 工作回報前方任務，開始時通知原討論串。
FlowEngine 的新任務承接、續跑、發布及角色請求均保留這個身分；不能用資料庫任務編號排序代表續跑的 FIFO 順序。

安全隔離刻意放在 runtime 外層：若依賴各 CLI 自己的權限旗標，系統安全性就等於最弱的那個 runtime。
主 App 負責排程、按鈕與流程控制；角色 App 只用來以角色身分發言與接收 @提問，不能自己觸發或中止任務。
啟用 `candidateRoles` 的階段由上游 agent 依能力選擇負責角色，`AgentDispatch` 驗證並保存交接；
執行仍經中央排程與原階段的權限／驗收契約，詳見 [自主派工](/docs/configurable-team#agent-自主派工)。

## 兩種執行模式

新 v2 快照共用 `IssueTracker`：各階段登錄問題，`FlowRun.issueInventory` 完整保存、承接不遺漏；
需求映射與逐項驗收缺漏會被 `FlowGates` 擋下，沿既有責任路由修正。角色與專案名稱不參與判斷；
舊快照維持舊契約，詳見[跨專案問題完整性](/docs/engineering-workflow#跨專案問題完整性2026-10-11)。

指定舊檔保持不變時，`FileComparison` 處理測試計畫的路徑契約，`WorkspaceManager` 比較固定 Git 版本的檔案物件，
`IndependentVerifier` 保存比對收據並於發布前查核；差異沿既有驗證失敗路線交回實作。這項能力不需要擴充 agent 的 shell 權限。

| 模式 | 啟用方式 | 狀態 |
|---|---|---|
| 配置式（v2） | `AI_TEAM_DEFINITION` 指向團隊 JSON | **現行**。流程、角色、權限都在 JSON；由 `FlowEngine` 執行 |
| legacy 五角色 | `AI_TEAM_DEFINITION` 留空 | 相容舊任務用。不要再擴充 legacy role switch |

每個服務實例只載入一份團隊配置。每個任務建立時保存完整配置快照（`flow_run`），
之後修改配置**不影響**既有任務；要讓舊任務套用新規則，需走受控配置遷移或「改用新任務繼續」。

bestpartner 另提供 `intake → service → end`：由 Infrastructure 建置既有服務並檢查啟動結果，
失敗經 `service_triage` 派回修正。服務路線不進 QA 計畫、開發獨立驗證或 PR 發布；目標主機未配置則等待補齊。
詳見 [服務建置](/docs/service-delivery)。

## 任務生命週期（以 `spring-starters.json` 為例）

```text
Slack @主機器人 需求
  → intake        PM（coordinator）整理需求基準：需求 ID、原子驗收項目、範圍、待確認問題
  → design        Architecture（architect）設計
  → plan          QA（tester）撰寫測試計畫：每個情境引用 operation_ids 並提供反例
  → [真人核准測試計畫]（或 autoApprovePlanWhenValid 通過機械檢查時自動核准）
  → implementation Backend（builder）在獨立 clone 實作並 commit
  → [系統獨立驗證：管理者固定的建置／測試指令，產生綁定 HEAD 的收據]
  → verification  QA（tester）逐情境執行並附證據
  → review        Code Reviewer（inspector）獨立審查
  → approval      [真人核准 PR] → 推分支、開 PR → 狀態 PR_CREATED
```

bestpartner 的 design、plan、implementation、verification、review 節點受阻（`blocked`）時交給 **triage**（PM 分流）；
intake 與 triage 受阻則交真人。實際依任務保存的配置快照，舊快照的 design 可能直接交真人。分流只能回傳配置中既有的 route：
`implementation`、`verification`、`plan`、`design`、`requirements`（回到 intake 改需求）或 `human`。
agent 不能自行指定角色或跳到任意節點。

### 防循環與上限

- 節點 `maxVisits`（最多 3）計算的是「輸入完全相同的連續重跑」；commit、前置結果、受阻發現或真人補充改變就重新起算。
- 整輪 `maxTransitions`（範例為 24，上限 64）。
- 達上限後，有證據且依配置路由的受阻事件可自動交接：每任務最多 6 個事件、每事件最多經過 12 個節點。
- runtime 啟動的系統性失敗（執行檔不存在、DNS 中斷、沙箱啟動失敗等）自動重試最多 2 次，不計次。
- 用完時停止訊息會說明原因並提供真的可用的選項（延長授權、受控恢復、改用新任務繼續）。

### 人工閘門（永遠需要真人）

- 測試計畫核准（除非專案設定自動核准且通過機械檢查）
- 需求變更核准
- PR 核准。**計畫核准不等於 PR 核准**；核准收據綁定配置、需求基準與計畫版本
- `PR_CREATED` 只表示自動開發管線結束，**不代表已合併、發布或產品驗收**

只有任務提出者（`requester_user_id`，即驗收負責人）能核准計畫、需求變更與 PR。

## 任務狀態（`task.state`）

| 分類 | 值 |
|---|---|
| 佇列 | `QUEUED` |
| 等待 | `WAITING_QUOTA`、`RESUME_QUEUED`、`TRIAGE_QUEUED`、`AWAIT_PR_APPROVAL`、`AWAIT_HUMAN` |
| 執行中（v2） | `PM_TRIAGE_RUNNING`、`DIRECT_RUNNING`、`STAGE_RUNNING`、`PUBLISH_RUNNING` |
| 執行中（legacy） | `PM_RUNNING`、`ARCH_RUNNING`、`QA_PLAN_RUNNING`、`IMPL_RUNNING`、`QA_RUNNING`、`REVIEW_RUNNING` |
| 終止 | `COMPLETED`、`PR_CREATED`、`ABORTED`、`FAILED` |

配置式流程目前停在哪個節點，存在 `flow_run` 的 JSON 快照（`node`、`activeNode`、`visits`、`receipts`、
`blockedFindings`），不在 `task.state`。完整欄位見 [data-model](/docs/data-model)。

## 程式碼地圖

根目錄 `orchestrator/src/main/java/com/zipe/aiteam/`：

| 套件 | 責任 | 關鍵類別 |
|---|---|---|
| `task/` | 任務狀態機、佇列、legacy 管線與各種閘門 | `TaskOrchestrator`、`Task`、`ReviewGate`、`QaGate`、`TriageGate`、`QuotaAutoResume` |
| `flow/` | 配置式流程引擎與 v2 契約 | `FlowEngine`、`TeamDefinition`、`TeamCatalog`、`FlowLimits`、`AutomaticHandoff`、`Traceability`、`IndependentVerifier`、`VerificationPlan`、`OperationContract`、`OperationJobs`、`PlanApproval`、`ToolTrust` |
| `runtime/` | 各 CLI adapter、typed timeout 與診斷 | `AgentRuntime`、`RuntimeRegistry`、`ClaudeCodeRuntime`、`CodexRuntime`、`OpenCodeRuntime`、`RuntimeTimedOut`、`QuotaExceeded`、`ProviderAccessDenied` |
| `sandbox/` | systemd 執行沙箱與政策 | `ExecutionSandbox`、`SandboxPolicy`、`SandboxUnitCleanup`、`TaskUnits` |
| `slack/` | Slack 進入點、發言身分、按鈕、進度卡 | `SlackListener`、`SlackNotifier`、`HumanGate`、`HumanInput`、`PlanReview`、`SpinoffButtons`、`StageProgress` |
| `store/` | SQLite 存取與稽核 | `TaskStore`、`AuditLog` |
| `workspace/` | 每任務獨立 clone | `WorkspaceManager` |
| `publish/` | 推分支與開 PR | `PullRequestPublisher` |
| `agent/` | legacy 階段定義與執行 | `StageSpec`、`StageRunner` |
| `config/` | 啟動設定驗證 | `AiTeamProperties` |

其他位置：

| 路徑 | 內容 |
|---|---|
| `orchestrator/teams/*.json` | 團隊配置：`spring-starters.json`（Java starters）、`node-web.json`（Node 範例） |
| `orchestrator/teams/prompts/`、`schemas/` | 配置式節點的提示與輸出 schema |
| `orchestrator/prompts/`、`orchestrator/schemas/` | legacy 五角色的提示與 schema |
| `orchestrator/src/main/resources/schema.sql` | SQLite DDL 唯一來源 |
| `deploy/bootstrap/` | 從零建置工具（新機只用這裡） |
| `deploy/deploy-orchestrator.sh`、`deploy-jar-safe.sh` | 既有 VM 的受控部署（新機不用） |
| `deploy/agent-manifests/` | 角色 Slack App manifest |
| `.ai/` | Codex 與 Claude Code 的跨工具交接紀錄（不是執行設定） |

## 執行時資料在哪（正式機）

| 資料 | 位置 |
|---|---|
| 服務根目錄 | `~/ai-team`（`AI_TEAM_HOME`） |
| 資料庫 | `~/ai-team/ai-team.db`（SQLite；沒有遷移機制，沒有外鍵） |
| 任務工作區 | `AI_TEAM_WORKSPACES` 下的 `task-<id>` 獨立 clone |
| 階段 log | `AI_TEAM_LOGS/<id>/`，例如 `verification.codex.jsonl`、`*.stderr.log` |
| 固定執行器 journal（2026-10-20 來源新增） | AI_TEAM_HOME/operation-jobs/&lt;task&gt;/&lt;id&gt;.json，在模型工作區外 |
| 取消要求／執行器心跳（來源新增） | AI_TEAM_HOME/operation-cancellations/&lt;task&gt;/&lt;id&gt;.json、AI_TEAM_HOME/operation-executor-heartbeat |
| 服務 log | `~/ai-team/orchestrator.log` |


## 服務執行工具（2026-10-10）

服務建置另有 `orchestrator/teams/skills/service-build/`：工作區 client → Agent VM systemd 執行器 → SSH 公鑰直連目標。執行器讀即時任務授權，執行四個固定操作，目標持久化收據並交回工作區；不透過 Slack 傳遞憑證。詳見 [服務建置](/docs/service-delivery)。

### 文件任務驗證（2026-10-12 來源更新）

工作分類由共用提示指導，純文字採合併內容查核、文件站採隔離副本建置、SQL／設定採行為驗證；不是新的節點或跳關。
plan.protected_path_changes 由實作前置計畫提供，ProtectedPathFallback 取清單與允許目錄交集，FlowEngine 在代提交前逐檔核對。文字提及不再觸發改派。
現有 requires、計畫核准、QA／Review 與 PR 閘門保持；舊快照不自動載入新提示／schema／操作，詳見[配置流程](/docs/configurable-team)。


### 2026-10-19 部署脈絡（已部署 bestpartner）

服務目標的歷史背景由 TaskStore.serviceHistory → ServiceDeploymentContext → ConfiguredExecutor 注入所有正式節點；管理者說明讀自實例 service-context/&lt;target&gt;.json，不自動執行管理命令。


### 2026-10-19 SSH 直連規格（已啟用）

Agent VM 的 systemd 固定部署執行器已啟用，以 SSH 公鑰直連 dev VM；網路、公鑰、主機指紋、受限入口與 health 已實測，不需要 Windows broker。#1046 的舊 skill 快照須新任務承接；dev VM 曾 heartbeat 停止，經使用者核准重啟後已重新驗證健康與8088入口，當機根因待查。本輪未重部署產品或續跑 Slack。詳見 [直連部署](/docs/direct-ssh-deployment)。

2026-10-20：SSH目標取消逐版來源manifest比對，沿用任務SERVICE授權、SSH身分與固定操作；SHA256只作傳輸完整性和收據綁定。新版本不需人工更新清單。

## 持久長作業恢復分層（2026-10-20 已部署 bestpartner）

client → Agent VM 固定 executor → SSH root 固定入口 → 獨立 systemd job／持久 result。角色仍只呼叫四個固定業務操作；result/status/cancel 由執行器管理，不給模型任意 SSH。Agent journal 與遠端 root 收據分開保存，SQLite 連線明確關閉，heartbeat 獨立於工作區。

OperationJobs 驗證 task／node／HEAD 與結果綁定；ConfiguredExecutor 注入系統檢查點。client 預設等待 30 秒後交回 OPERATION_PENDING，模型立即 BLOCKED；FlowEngine 在一般受阻路由之前等待 journal，沒有模型程序但保留中央執行槽，續同 activeNode 不新計次。RuntimeTimedOut 的 MODEL 階段須 cleanup=CONFIRMED 才可走逾時對帳；精確模型 unit 的 cgroup 清理不波及獨立 executor／遠端 job。

重啟恢復由 TaskOrchestrator.recoverOnStartup → FlowEngine.recover 經中央佇列排原 task；只涵蓋原執行中／RESUME_QUEUED、node=activeNode 且有有效同 HEAD journal 的配置任務。舊 AWAIT_HUMAN 另需管理者先核實收據，再以一次 AI_TEAM_RESUME_OPERATION_TASK 指定。真人中斷產生精確 job 取消要求，模型 timeout／SSH 斷線本身不取消。

已驗證的同 HEAD prepare/start 可重用，health 綁當次 start release。未知結果不新建相同 start；#1048 已成功版本不得重建。流程細節與歷史證據見[直連部署](/docs/direct-ssh-deployment)。已部署 bestpartner；Linux cgroup與SSH背景health已實測。重啟、取消與所有故障組合的真實端對端尚未全面演練，見直連部署紀錄。


### 服務追問與來源清理（已部署bestpartner）

ConfiguredExecutor在結案來源已清理時建立dialogue-evidence，保持非產品來源標示；ServiceSupport從非機密service-context啟動固定support.py，從service-support取得新探測/修復證據。模型收到歷史收據與新診斷，FlowEngine驗證入口並必要時自動改寫回覆；原任務驗收不被對話覆寫。root SSH新增repair只操作已存在active應用，沒有新來源建置。原對話可透過AI_TEAM_RESUME_DIALOGUE_TASK正式續跑。
