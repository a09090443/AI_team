---
sidebar_position: 5
title: 設定參考
description: env.sh 環境變數與團隊配置 JSON 的欄位、可選值與限制。
---

# 設定參考

設定分兩處：**`~/ai-team/env.sh`**（權限 600，含憑證，不進版控）與 **團隊配置 JSON**
（`orchestrator/teams/*.json`，不含憑證，只引用環境變數名稱）。
範本是 `deploy/env.sh.template`；欄位驗證在 `config/AiTeamProperties.java` 與 `flow/TeamDefinition.java`。

## 環境變數

選填的 `~/ai-team/team.env`（同樣 600）會在 `env.sh` 之後載入，兩邊都有時以 `team.env` 為準；
用途是把「切換團隊配置」和含憑證的 `env.sh` 分開。

| 變數 | 用途 |
|---|---|
| `SLACK_APP_TOKEN`、`SLACK_BOT_TOKEN` | 主 App 的 `xapp-`（Socket Mode）與 `xoxb-` token |
| `SLACK_BOT_TOKEN_<角色>` | 角色 App 的 Bot Token；留空則以主 token 改名代發，無法被 @ |
| `SLACK_APP_TOKEN_<角色>` | 角色 App 自己的 App-Level Token，啟用直接對角色提問；不可共用主 App 的 |
| `AI_TEAM_ALLOWED_USERS` | 允許觸發的 Slack user ID，逗號分隔（不是顯示名稱） |
| `AI_TEAM_CHANNEL_ID` | 運作頻道 ID；preflight 視為必填 |
| `AI_TEAM_DEFINITION` | 團隊配置 JSON 的絕對路徑；**留空＝legacy 五角色模式**。修改後重啟，只影響新任務 |
| `AI_TEAM_INSTANCE` | 服務實例名稱（建議放 `team.env`）。單一服務留空；同一台 VM 多個專案服務時**必填且不可重複**，systemd unit 改名為 `ai-team-<實例>-…`。格式見 [多個服務實例](/docs/configurable-team#同一台-vm-多個服務實例) |
| `AI_TEAM_HOME`、`AI_TEAM_REPO`、`AI_TEAM_WORKSPACES`、`AI_TEAM_LOGS` | 服務根目錄、目標 repo 鏡像、任務工作區、階段 log |
| `CLAUDE_BIN`、`CODEX_BIN`、`OPENCODE_BIN` | 各 CLI 絕對路徑；只有用到該 runtime 才需要 |
| `AI_TEAM_GITHUB_TOKEN` | 推分支與開 PR；留空時管線能跑完但核准後推不出去 |
| `AI_TEAM_BASE_BRANCH`、`AI_TEAM_BRANCH_PREFIX` | 基準分支（留空自動偏好 `develop`）與任務分支前綴 |
| `AI_TEAM_GIT_USER_NAME`、`AI_TEAM_GIT_USER_EMAIL` | agent commit 署名，寫在工作區 local config |
| `AI_TEAM_MAX_CONCURRENCY` | 全域並行上限；訂閱帳號建議 1 |
| `AI_TEAM_TASK_TIMEOUT_SECONDS`、`AI_TEAM_GIT_TIMEOUT_SECONDS` | 全域逾時覆寫（0＝用各階段設定）與 git 逾時 |
| `AI_TEAM_PROGRESS_INTERVAL_SECONDS` | 進度卡更新秒數，預設 300、最小 30；**不能設成空字串** |
| `AI_TEAM_REPORT_DETAIL` | `brief`（預設）或 `full`；完整報告可用 `補充 #<id> 檢視完整報告 [節點]` 叫出 |
| `AI_TEAM_EXPLAINER_MODEL`、`AI_TEAM_EXPLAINER_ENABLED` | 系統錯誤白話說明所用模型（預設 haiku）與開關；布林值不能設成空字串 |
| `AI_TEAM_MODEL_<角色>`、`AI_TEAM_RUNTIME_<角色>`、`AI_TEAM_CODE_REVIEW` | **僅 legacy 模式**；配置式模式改在 JSON 的 `roles` 設定 |

`<角色>` 為 `PM`、`ARCHITECTURE`、`BACKEND`、`QA`、`CODE_REVIEW`。
使用 Claude 訂閱帳號時不可設定 `ANTHROPIC_API_KEY`，也不能用 `--bare`。

## 團隊配置 JSON

範例：`orchestrator/teams/spring-starters.json`（v2，完整流程）、`node-web.json`（Node 專案、不同角色組合）。
所有 prompt／schema 路徑必須在配置檔所在目錄內，跳脫或 symlink 指向外部會被拒絕。

```json
{
  "version": 2,
  "id": "spring-starters",
  "project": { "...": "專案層設定" },
  "roles": { "<角色 ID>": { "...": "角色設定" } },
  "entry": "intake",
  "maxTransitions": 24,
  "nodes": { "<節點 ID>": { "...": "節點設定" } }
}
```

### `project`

| 欄位 | 意義 |
|---|---|
| `name`、`repositoryEnv`、`baseBranch` | 專案名稱、存放 repo 路徑的環境變數名、基準分支 |
| `buildInstructions`、`testInstructions` | 注入角色的建置／測試規範；`testInstructions` 的每一步都要有對應 `operations` |
| `operations` | 操作契約：`{ "<id>": { nodes, directory, argv, writes } }`。`argv` 不支援管線，多步驟請拆開 |
| `verification` | 系統獨立驗證：管理者固定的 `commands` 與 `timeoutSeconds`，在 QA 前於全新 clone 執行 |
| `requiredTools` | 啟動時沙箱工具鏈自檢逐一檢查，缺任一項服務不啟動 |
| `protectedPaths`、`cachePaths` | 任何角色不可寫的路徑；允許的快取目錄（只限 `.m2/repository`、`.npm`、`.gradle/caches`、`.cache/<名稱>`） |
| `requirePlanApproval`、`autoApprovePlanWhenValid` | 測試計畫是否需真人核准；通過機械檢查時是否自動核准（預設 false） |

### `roles.<角色 ID>`

| 欄位 | 意義 |
|---|---|
| `name` | Slack 顯示名稱，例如 `QA Agent` |
| `runtime` | `claude-code`、`codex` 或 `opencode`（opencode 在 VM 沙箱下尚未驗證） |
| `model` | 該 runtime 認得的模型名；三家格式不通用（opencode 為 `provider/model`） |
| `reasoningEffort` | 選填，目前僅 Codex 支援 `low`／`medium`／`high`／`xhigh` |
| `prompt` | 角色一般職責的提示檔 |
| `botTokenEnv`、`appTokenEnv` | Slack token 的**環境變數名稱**（值不進 JSON、不進快照） |
| `tools` | 工具白名單，例如 `Read`、`Bash(mvn *)`。要角色核實產物就必須給對應唯讀工具，否則被拒後報告作廢 |

### `nodes.<節點 ID>`

| 欄位 | 意義 |
|---|---|
| `kind` | `AGENT` 或 `APPROVAL` |
| `role`、`schema`、`instructions` | 執行角色、輸出 schema、階段指示（優先於角色 prompt） |
| `scope` | 寫入權限，見下表 |
| `gate` | 工作契約，見下表 |
| `workspace` | 是否建立任務工作區；已有工作區的 READ_ONLY 節點仍唯讀查看該副本 |
| `writablePaths` | `LIMITED` 可寫路徑，支援 `/`、`*`、`**` |
| `localTestNetwork` | 只允許隔離 namespace 內本機 TCP；限 Codex、`LIMITED`、`VERIFICATION` 節點 |
| `plan` | 驗收節點引用的計畫節點 |
| `next`、`blocked`、`routes` | 成功、受阻時的下一站，以及 agent 可回傳的 route 鍵；保留目標 `human`、`end` |
| `requires` | 必須先有有效收據的前置節點；`APPROVAL` 節點至少要涵蓋實作、計畫、驗收、審查 |
| `maxVisits`、`timeoutSeconds`、`maxTurns` | 相同輸入連續重跑上限（最多 3）、逾時、回合上限 |

| `scope` | 權限 |
|---|---|
| `READ_ONLY` | 只允許 runtime 自身狀態寫入 |
| `WORKSPACE` | 獨立 clone 可寫；必須配 `IMPLEMENTATION` 契約 |
| `LIMITED` | 只有 `writablePaths` 匹配的既有目錄與該 clone 的 Git 狀態；沙箱外逐筆核對 commit 與未提交路徑 |

| `gate` | 契約 |
|---|---|
| `REPORT` | 共用完成／失敗報告 |
| `REQUIREMENTS` | 需求基準：需求 ID、原子驗收項目與維度、範圍、待確認問題 |
| `IMPLEMENTATION` | 成功狀態、建置品質、無範圍衝突 |
| `TEST_PLAN` | 情境覆蓋所有原子驗收 ID，每條有反例與可執行的 `operation_ids` |
| `VERIFICATION` | 逐情境實際結果與證據，引用計畫節點 |
| `REVIEW` | 獨立核實、檢查紀錄、無 blocker 或 HIGH finding |

### 驗證與生效

```bash
java -jar orchestrator/target/ai-team-orchestrator-0.1.0.jar --validate-team <配置檔>
bash deploy/bootstrap/check-source.sh
```

`--validate-team` 只驗證結構、引用、資產與 repo 目錄，不驗證 CLI 登入、Slack scope 或沙箱。
配置在服務啟動時讀取；改完要重啟，且**只影響新任務**。舊任務沿用建立時的快照。

完整說明見 [configurable-team](/docs/configurable-team) 與 [cross-project-execution](/docs/cross-project-execution)。
