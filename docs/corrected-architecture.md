# 修正後的架構

搭配 `architecture-review.md` 閱讀。本文只講「改成什麼樣」，不重述「為什麼」。

---

## 核心調整：職責邊界重新劃分

原文把太多事情放進 Java Orchestrator。修正後的原則是：

> **Claude Code 已經有的能力不要重寫，Orchestrator 只做 Claude Code 不管的事。**

| 層 | 誰負責 | 具體內容 |
|---|---|---|
| 人機介面 | Slack App | 事件接收、ack、去重、觸發者授權、thread 回覆 |
| 任務治理 | **Java Orchestrator** | 任務狀態機、佇列與並行控制、人工審批閘門、稽核紀錄、session 對應表 |
| **安全隔離** | **Orchestrator 的 ExecutionSandbox** | 專用 user 或容器、唯讀掛載、網路白名單、逾時強制 |
| Agent 執行 | Runtime 原生能力 | 角色系統提示、工具權限、worktree 隔離、回合上限、subagent 分工 |
| 專案知識 | KG MCP Server | 依需求查詢，不預先塞 context |
| 產物 | Git / Gitea | 分支、commit、PR |

Orchestrator 從「Agent 框架」降級成「任務治理層」，程式碼量大幅減少。

> **多 runtime 前提下的重要調整**：安全隔離獨立成一層，包在 Runtime 外面。
> 若安全依賴各 runtime 的權限旗標，系統安全性等於所有 runtime 中最弱的那一個。
> Runtime 自己的權限機制保留為縱深防禦的第二層。詳見 `multi-runtime.md`。

---

## 資料流

```
Slack 使用者
    │  @zipe-ai-team 我要讓 db starter 支援 PostgreSQL
    ▼
┌─────────────────────────────────────────────┐
│ Slack Listener（Socket Mode）                │
│                                              │
│  1. event_id 去重      ← 擋掉 Slack 重送      │
│  2. 觸發者白名單檢查                          │
│  3. 入佇列                                    │
│  4. ack()              ← 必須在 3 秒內        │
└──────────────────┬──────────────────────────┘
                   │ （非同步移交）
                   ▼
┌─────────────────────────────────────────────┐
│ Task Orchestrator                            │
│                                              │
│  ・任務狀態機   ・並行控制（全域上限 1）        │
│  ・審批閘門     ・稽核紀錄                     │
│  ・thread_ts ↔ session_id 對應表              │
└──────────────────┬──────────────────────────┘
                   │ ProcessBuilder（參數陣列，不經 shell）
                   ▼
┌─────────────────────────────────────────────┐
│ ClaudeCodeRunner                             │
│                                              │
│  claude -p <prompt>                          │
│    --append-system-prompt-file <role>.md     │
│    --permission-mode dontAsk                 │
│    --allowedTools <依角色>                    │
│    --output-format json                      │
│    --json-schema <依角色>                     │
│    --max-turns <依角色>                       │
│    [--resume <session_id>]                   │
│                                              │
│  stdout → JSON 解析                           │
│  stderr → 每任務獨立 log（稽核用）              │
└──────────────────┬──────────────────────────┘
                   ▼
┌─────────────────────────────────────────────┐
│ Claude Code                                  │
│                                              │
│  .claude/agents/  pm / architecture /        │
│                   backend / qa               │
│  .mcp.json        knowledge-graph            │
└──────────────────┬──────────────────────────┘
                   ▼
        zipe-spring-boot-starters
                   │
        ┌──────────┴──────────┐
        ▼                     ▼
   Git worktree            Maven
   （Backend 隔離）
                   │
                   ▼
        ┌──────────────────────┐
        │ 人工審批閘門          │  ← 推分支 / 開 PR 前必經
        └──────────┬───────────┘
                   ▼
                 Gitea
```

---

## 兩層 orchestration 的分工

這是修正後最容易誤解的一點。系統裡有兩種「派工」，各管各的：

**外層（Java Orchestrator）** 管的是**跨越 process 邊界、需要持久化的狀態**：

```
PM 分析完成 → [狀態:ARCH_PENDING] → 呼叫 Architecture
Architecture 完成 → [狀態:IMPL_PENDING] → 呼叫 Backend
Backend 完成 → [狀態:QA_PENDING] → 呼叫 QA
QA FAIL → [狀態:IMPL_PENDING] → 重新呼叫 Backend（最多 N 次）
QA PASS → [狀態:AWAIT_HUMAN] → Slack 通知，停在這裡
```

每一步都是獨立的 `claude -p` 呼叫，狀態存在資料庫，重啟不遺失。

**內層（Claude Code 原生 subagent）** 管的是**單次呼叫內部的分工**。例如 Backend Agent 在實作時可以自己派出一個唯讀的 subagent 去查證某個模組的用法，這一層完全不需要 Java 介入。

不要把內層搬到外層。原文的錯誤就是把整個 agent 框架搬進 Java。

---

## 角色權限矩陣

| 角色 | `--allowedTools` | `permissionMode` | `maxTurns` | 隔離 | 輸出 |
|---|---|---|---|---|---|
| **PM** | `Read,Grep,Glob` | `dontAsk` | 15 | 無（唯讀） | JSON Schema |
| **Architecture** | `Read,Grep,Glob,Bash(git log *),Bash(git diff *)` | `dontAsk` | 25 | 無（唯讀） | JSON Schema（ADR 內容） |
| **Backend** | `Read,Write,Edit,Grep,Glob,Bash(mvn *),Bash(git *)` | `dontAsk` | 40 | **worktree** | JSON Schema（變更摘要） |
| **QA** | `Read,Grep,Glob,Bash(mvn *),Bash(git diff *)` | `dontAsk` | 25 | 唯讀掛載 Backend 的 worktree | JSON Schema（含 verdict） |

明確排除（任何角色都不給）：`sudo`、`docker`、`systemctl`、`rm`、`ssh`、`curl`、`Bash(git push *)`。

**推分支與開 PR 不由 Agent 執行**，由 Orchestrator 在人工核准後代為執行。這樣 Agent 永遠沒有離開沙箱的能力。

---

## 護欄總表（訂閱帳號版）

| 風險 | 護欄 | 實作位置 |
|---|---|---|
| Slack 重送造成重複執行 | `event_id` 去重表 | Slack Listener |
| 未授權者觸發 | Slack user ID 白名單 | Slack Listener |
| 額度暴衝 | 全域並行上限 1 + 佇列 | Orchestrator |
| 單次失控 | `--max-turns` + `waitFor` 逾時 + `destroyForcibly` | ClaudeCodeRunner |
| Agent 越權（主要） | 專用 user 或容器、唯讀掛載、網路白名單 | **ExecutionSandbox** |
| Agent 越權（縱深防禦） | `--permission-mode dontAsk` + 工具白名單 | ClaudeCodeRunner（僅 Claude Code 有） |
| 污染主 repo | `isolation: worktree`，或由 Orchestrator 自行開 worktree | Agent frontmatter / 降級層 |
| 不可逆操作 | 人工審批閘門 | Orchestrator |
| repo 內容具執行語意 | `.claude/`、`.mcp.json` 納入 CODEOWNERS + checksum 比對 | 流程 + Orchestrator |

> 切換到 API key 後可額外啟用：`--max-budget-usd`（單次金額上限）與 `--bare`（隔絕 repo 的 hooks 與 MCP 設定）。

---

## 目錄配置

```
~/ai-team/
├── orchestrator/                      # Spring Boot 專案
│   └── src/main/java/.../
│       ├── slack/SlackListener.java
│       ├── task/TaskOrchestrator.java
│       └── claude/ClaudeCodeRunner.java
│
├── prompts/                           # 附加系統提示（Orchestrator 用）
│   ├── pm.md
│   ├── architecture.md
│   ├── backend.md
│   └── qa.md
│
├── schemas/
│   └── task-schema.json
│
├── logs/                              # 每任務的 stderr 與稽核紀錄
│   └── task-1024/
│
└── zipe-spring-boot-starters/         # 目標專案
    ├── .claude/
    │   ├── agents/                    # 原生 subagent 定義
    │   └── settings.json              # permissions.allow 規則
    └── .mcp.json                      # knowledge-graph server
```

**為什麼角色定義有兩份？**

`prompts/*.md` 是 Orchestrator 透過 `--append-system-prompt-file` 傳入的**主 agent 角色**；`.claude/agents/*.md` 是主 agent 在單次呼叫內可以派出的**原生 subagent**。兩者用途不同，但內容可以共用同一份來源，建置時產生。

若要簡化，也可以只留 `.claude/agents/`，改用 `--agent <name>` 讓主執行緒直接採用該 subagent 的設定。這是第二階段可以考慮的收斂。
