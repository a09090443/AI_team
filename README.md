# AI Team

以 Slack 為介面的 AI 軟體團隊協調器。使用者在 Slack 提出需求，由 Java
Orchestrator 驅動多個 AI Agent（Claude Code／Codex CLI）分階段完成需求釐清、
設計、實作、審查與驗證，最後產出 Git 分支與 PR。

> **專案狀態：開發中，尚未通過乾淨 VM 重建驗收。**
> 程式測試在本機通過，不等於真實端對端流程已驗收。缺口逐項列在
> [docs-site/docs/rebuild-verification.md](docs-site/docs/rebuild-verification.md) 的「尚待完成的驗收」。
> 閱讀本專案文件時請注意這個區分：「Maven 測試通過」與「真實 Slack／CLI 跑完」是兩件事。

## 核心設計

Orchestrator 不重寫 Agent 框架，只做 Agent CLI 不管的事：

| 層 | 負責者 | 內容 |
|---|---|---|
| 人機介面 | Slack App | 事件接收、ack、去重、觸發者授權、thread 回覆 |
| 任務治理 | **Java Orchestrator** | 任務狀態機、佇列與並行控制、人工審批閘門、稽核紀錄 |
| 安全隔離 | **ExecutionSandbox** | systemd user unit：唯讀檔案系統、限定可寫路徑、資源上限、逾時 |
| Agent 執行 | Runtime 原生能力 | 角色系統提示、工具權限、回合上限、結構化輸出 |
| 產物 | Git | 每任務獨立 clone、分支、commit、PR |

安全隔離刻意獨立成一層包在 Runtime 外面 —— 若安全依賴各 runtime 自己的權限旗標，
系統安全性等於所有 runtime 中最弱的那一個。詳見
[docs-site/agent/system-map.md](docs-site/agent/system-map.md) 與
[docs-site/docs/multi-runtime.md](docs-site/docs/multi-runtime.md)。

每個角色以自己的 Slack 身分發言，階段交接在同一討論串中公開可見；
佇列狀態與錯誤由主 App 發出，刻意不假裝是某個角色說的。
見 [docs-site/docs/interaction-model.md](docs-site/docs/interaction-model.md)。

## 目錄結構

```
orchestrator/
├── pom.xml                     # Spring Boot 3.5.3 / Java 21
├── src/main/java/com/zipe/aiteam/
│   ├── task/                   # 狀態機、佇列、審批閘門
│   ├── flow/                   # 配置式流程引擎、需求追溯、獨立驗證
│   ├── runtime/                # AgentRuntime 介面與 Claude Code／Codex／opencode adapter
│   ├── sandbox/                # 執行隔離
│   ├── slack/                  # ack-first 進入點、角色身分發言
│   └── store/                  # SQLite 任務儲存與稽核紀錄
├── prompts/                    # legacy 五角色的系統提示
├── schemas/                    # legacy 階段產出的 JSON schema
└── teams/                      # 配置式團隊定義（v2）與其 prompts／schemas
deploy/
├── bootstrap/                  # 從零建置工具（新機用這裡）
├── agent-manifests/            # 各角色的 Slack App manifest
└── deploy-*.sh                 # 既有 VM 的受控部署（新機不用）
docs-site/                      # 文件網站：docs/ 原始文件、agent/ 入口頁、catalog.mjs 目錄
.ai/                            # 各 AI 工具的跨工具交接記錄（archive/ 為歸檔）
```

角色定義與 schema 刻意放在 jar 之外 —— 調整提示詞不需重新編譯，改完重啟即可。

## 建置與執行

從零建置的唯一入口是 [docs-site/docs/INSTALL.md](docs-site/docs/INSTALL.md)。**不是一鍵安裝**：
安裝媒體、官方工具鏈 checksum、系統套件與本人 CLI 登入都需先備妥。

執行環境為 Linux x86_64／glibc／systemd，各發行版實測狀態見
[docs-site/docs/linux-support.md](docs-site/docs/linux-support.md)；未實測的不標成已支援。

本機測試：

```bash
cd orchestrator && mvn test
```

改動角色、schema 或設定後，另須執行來源檢查：

```bash
bash deploy/bootstrap/check-source.sh
```

## 文件網站

Docusaurus 文件網站位於 [docs-site](docs-site/README.md)，所有文件都在 `docs-site/docs/`（repo 根目錄已無 `docs/`）。
公開手冊來源位於 `docs-site/public-docs/`，提供使用者快速開始、管理設定與 AI 閱讀入口；`npm run build:public` 產生僅含公開手冊的 AI 索引。完整站主要讀者是 AI agent：[AI Agent 入口](docs-site/agent/index.md) 提供專案全貌、操作指令與依任務找文件，
[文件目錄](docs-site/catalog.mjs) 標記每份文件是現行、紀錄、決策或歷史；build 時另產生 `llms.txt`。
新增或刪除 `docs-site/docs/` 文件時要同步更新 `docs-site/catalog.mjs`，否則網站 build 失敗。

```bash
cd docs-site
npm ci
npm run start
```

本機網址為 <http://localhost:3000>；production build 使用 `npm run build`。

## 配置式團隊

新角色與流程改 `orchestrator/teams/` 的 JSON，不再擴充 legacy role switch。
以 `AI_TEAM_DEFINITION` 指向團隊定義檔啟用；留空則維持 legacy 五角色行為。
啟動前可用打包 jar 的 `--validate-team` 做靜態檢查。
見 [docs-site/docs/configurable-team.md](docs-site/docs/configurable-team.md)。

v2 流程加入需求基準與追溯、前置契約與失效傳遞、系統獨立驗證與 PR_CREATED，
見 [docs-site/docs/engineering-workflow.md](docs-site/docs/engineering-workflow.md)。

## 文件索引

| 主題 | 文件 |
|---|---|
| 從零建置 | [INSTALL.md](docs-site/docs/INSTALL.md) |
| 建置演練證據與缺口 | [rebuild-verification.md](docs-site/docs/rebuild-verification.md) |
| Linux 能力與驗證矩陣 | [linux-support.md](docs-site/docs/linux-support.md) |
| 架構 | [system-map.md](docs-site/agent/system-map.md)、[multi-runtime.md](docs-site/docs/multi-runtime.md) |
| 互動模型 | [interaction-model.md](docs-site/docs/interaction-model.md) |
| 配置式團隊 | [configurable-team.md](docs-site/docs/configurable-team.md) |
| v2 工程流程 | [engineering-workflow.md](docs-site/docs/engineering-workflow.md) |
| QA 流程 | [qa-scenario-workflow.md](docs-site/docs/qa-scenario-workflow.md) |
| QA 受阻與 PM 派工 | [pm-triage.md](docs-site/docs/pm-triage.md) |
| 直接對角色提問 | [direct-agent-requests.md](docs-site/docs/direct-agent-requests.md) |
| 失敗與發布閘門 | [failure-reporting.md](docs-site/docs/failure-reporting.md)、[review-gate.md](docs-site/docs/review-gate.md) |
| Session 與用量 | [quota-continuation.md](docs-site/docs/quota-continuation.md) |
| 現行 VM 部署實測 | [deploy-2026-09-06-v2.md](docs-site/docs/deploy-2026-09-06-v2.md) |

## 開發規範

專案規範的單一事實來源是 [AGENTS.md](AGENTS.md)，所有 AI 工具（Codex、Claude Code、Cursor、opencode、
Copilot、Gemini CLI）都讀它；`CLAUDE.md` 只有 `@AGENTS.md` 一行。共用 skills 與 MCP 的單一來源在 `.agents/`，
以 `node .agents/sync-agent-config.mjs` 產生各工具設定，見 [AI 工具設定](docs-site/agent/ai-tools.md)。
各 AI 工具的對話 session 不互通，`.ai/context.md`（目前狀態，覆寫）、`.ai/progress.md`（近期進度）、
`.ai/decisions.md`（決策，只追加）是唯一的交接面：開場先讀，完成工作單位後寫回；舊進度依月份歸檔到 `.ai/archive/`。

憑證不進版控。`env.sh` 已列入 `.gitignore`，token 不放進團隊定義 JSON。
