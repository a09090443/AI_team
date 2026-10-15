---
title: 架構概要
---

# 架構概要

系統以 Java 21／Spring Boot 3.5 實作，把模型執行與流程治理分開。Slack 是使用介面，Git repo 是成果來源，SQLite 保存任務狀態與稽核。

## 分層責任與程式碼地圖

Java 路徑相對於 `orchestrator/src/main/java/com/zipe/aiteam/`。

| 層次 | 職責 | 程式入口 |
|---|---|---|
| Slack | 事件 ack、去重、授權、按鈕、角色發言與進度 | `slack/SlackListener.java`、`SlackNotifier.java`、`HumanGate.java` |
| 任務治理 | 中央佇列、任務狀態與恢復 | `task/TaskOrchestrator.java`、`Task.java` |
| 配置式流程 | 讀取快照、執行節點、檢查契約、返工與人工閘門 | `flow/FlowEngine.java`、`ConfiguredExecutor.java`、`FlowGates.java` |
| 團隊配置 | 專案、角色、節點、操作與驗證政策 | `flow/TeamDefinition.java`、`TeamCatalog.java`；`orchestrator/teams/` |
| Runtime | 呼叫 CLI、解析輸出、處理 session 與額度 | `runtime/AgentRuntime.java` 與各 adapter |
| 執行沙箱 | systemd user unit、唯讀掛載、限定寫入、資源與逾時 | `sandbox/ExecutionSandbox.java`、`SandboxPolicy.java` |
| 工作區與發布 | 每任務獨立 clone、分支、commit、推送與 PR | `workspace/WorkspaceManager.java`、`publish/PullRequestPublisher.java` |
| 狀態與稽核 | SQLite 存取、階段結果、快照與 audit | `store/TaskStore.java`、`AuditLog.java`、`schema.sql` |

## 一次請求如何流動

```text
真人 @Slack App → 事件入口與授權 → 任務與中央佇列
  → 團隊快照的流程節點 → 執行器組裝提示與權限
  → 外層沙箱呼叫 runtime CLI → 結構化結果與流程檢查
  → 下一節點／返工／等待真人 → 版本綁定驗證 → 真人核准 PR
```

中央工作迴圈依序執行，對話、續跑與發布也共享佇列。單獨提高並行槽設定不會使 v2 任務平行。
角色 App 的 mention 是可見交接，bot 事件不會自行建立新任務。

## 狀態、快照與證據

`task.state` 表示排隊、執行、等待或終止；目前節點、前置結果、問題清單與版本資訊由 FlowRun 快照保存。
配置更新不會覆蓋既有任務快照。需求與原子驗收項由 Traceability 檢查，IssueTracker 保存已登錄問題與處理證據，IndependentVerifier 保存綁定 commit 的驗證收據。
資料庫與 JSON 型別沒有通用自動遷移機制，舊資料解析失敗可能直接拋錯；改 schema 時同步資料模型文件與復原方案。

## Runtime 與隔離邊界

支援 Claude Code、Codex adapter，opencode 為選用 adapter；opencode 在 VM 沙箱的端對端尚未驗證。
安全邊界置於 runtime 外層，不能只依各 CLI 的權限旗標。systemd 沙箱限制檔案與資源，但不代表所有網路都已隔離。
操作契約、工具白名單、本機測試網路與 CLI 自身限制須一起核對。

下一步：[工作流程](workflow.md)、[AI 閱讀入口](ai-guide.md)。
