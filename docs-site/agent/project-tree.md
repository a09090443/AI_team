---
sidebar_position: 3
title: 專案目錄樹
description: repo 每個目錄與檔案的用途，以及「我要找某樣東西該去哪裡」的對照表。
---

# 專案目錄樹

找檔案時先查本頁，再用搜尋。本頁由 `npm run build` 前的檢查機械驗證：
樹中列出的路徑必須存在；`orchestrator/src/main/`、`orchestrator/teams/`、`orchestrator/prompts/`、
`orchestrator/schemas/`、`deploy/`、`docs-site/`（`docs/` 子目錄除外，由[文件目錄](/catalog)涵蓋）、`.agents/`、`.claude/`
底下每個受版控的檔案都必須出現在樹中，否則 build 失敗。新增、搬移或刪除檔案時要同步更新本頁。

## 我要找……

| 要找的東西 | 位置 |
|---|---|
| 專案規範、agent 必讀規則 | `AGENTS.md` |
| 共用 skills、MCP 設定 | `.agents/`（說明見 [AI 工具設定](ai-tools.md)） |
| 目前進行中的工作、進度、決策 | `.ai/context.md`、`.ai/progress.md`、`.ai/decisions.md` |
| Slack 指令怎麼被解析 | `orchestrator/src/main/java/com/zipe/aiteam/slack/SlackListener.java` |
| 按鈕版面與 action_id | `slack/SlackBlocks.java`、`slack/HumanGate.java`、`slack/HumanInput.java`、`slack/PlanReview.java`、`slack/SpinoffButtons.java` |
| 配置式流程怎麼走、停止與承接 | `flow/FlowEngine.java`、`flow/FlowLimits.java` |
| 團隊 JSON 的欄位定義與驗證 | `flow/TeamDefinition.java`、`flow/TeamCatalog.java` |
| 各節點如何組 prompt 並呼叫 runtime | `flow/ConfiguredExecutor.java` |
| 驗收契約（需求、計畫、驗收、審查）的檢查 | `flow/FlowGates.java`、`flow/Traceability.java` |
| legacy 固定管線、狀態機、佇列 | `task/TaskOrchestrator.java`、`task/Task.java` |
| CLI 參數怎麼組 | `runtime/ClaudeCodeRuntime.java`、`runtime/CodexRuntime.java`、`runtime/OpenCodeRuntime.java` |
| 沙箱怎麼包 systemd unit | `sandbox/ExecutionSandbox.java`、`sandbox/SandboxPolicy.java` |
| 資料表 DDL | `orchestrator/src/main/resources/schema.sql` |
| 環境變數如何讀取與驗證 | `config/AiTeamProperties.java`；範本 `deploy/env.sh.template` |
| 目前使用的團隊配置與角色 prompt | `orchestrator/teams/spring-starters.json`（starters 實例）、`orchestrator/teams/bestpartner.json`（bestpartner 實例）、`orchestrator/teams/prompts/` |
| 新機安裝與維運腳本 | `deploy/bootstrap/` |
| 文件原文 | `docs-site/docs/`（索引見[文件目錄](/catalog)） |

表中 Java 路徑省略前綴 `orchestrator/src/main/java/com/zipe/aiteam/`。

## 目錄樹

```text
agent_team/
├── AGENTS.md                         # 所有 AI 工具的唯一規則來源
├── CLAUDE.md                         # 只有 @AGENTS.md 一行（Claude Code 入口）
├── README.md                         # 專案簡介與快速指令
├── .gitignore
├── .agents/                          # AI 工具共用設定的單一來源（見 AI 工具設定頁）
│   ├── sync-agent-config.mjs         # 由單一來源產生各工具設定；--check 只檢查一致性
│   ├── mcp.json                      # 共用 MCP server 單一來源
│   └── skills/                       # 共用 skills 單一來源（Codex、Cursor、opencode 直接讀）
│       └── docs-sync/
│           └── SKILL.md              # 文件優先核對流程
│       ├── service-build/            # 可攜式服務建置 skill（來源／同步鏡像）
│       │   ├── SKILL.md              # 環境檢查、建置、啟動與有界修復流程
│       │   ├── references/
│       │   │   ├── bestpartner.md    # 產品部署契約與檢查限制
│       │   │   └── host-setup.md     # SSH／VirtualBox 主機設定與換機
│       │   ├── assets/
│       │   │   └── host.example.json # 無憑證的主機設定範本
│       │   └── scripts/
│       │       ├── client.py         # Agent 固定操作入口與任務授權
│       │       ├── broker.py         # 主機連線與固定操作執行
│       │       ├── guest.py          # 目標環境安裝、建置、啟動與檢查
│       │       ├── enroll.py         # 管理者固定來源雜湊
│       │       ├── login.ps1         # Windows 遮蔽輸入與私人登入檔
│       │       └── test_service_agent.py # 執行工具測試
├── .claude/
│   └── skills/                       # 產生檔：.agents/skills 的鏡像（Claude Code 只讀這裡）
│       └── docs-sync/
│           └── SKILL.md
│       ├── service-build/            # 可攜式服務建置 skill（來源／同步鏡像）
│       │   ├── SKILL.md              # 環境檢查、建置、啟動與有界修復流程
│       │   ├── references/
│       │   │   ├── bestpartner.md    # 產品部署契約與檢查限制
│       │   │   └── host-setup.md     # SSH／VirtualBox 主機設定與換機
│       │   ├── assets/
│       │   │   └── host.example.json # 無憑證的主機設定範本
│       │   └── scripts/
│       │       ├── client.py         # Agent 固定操作入口與任務授權
│       │       ├── broker.py         # 主機連線與固定操作執行
│       │       ├── guest.py          # 目標環境安裝、建置、啟動與檢查
│       │       ├── enroll.py         # 管理者固定來源雜湊
│       │       ├── login.ps1         # Windows 遮蔽輸入與私人登入檔
│       │       └── test_service_agent.py # 執行工具測試
├── .cursor/
│   └── mcp.json                      # 產生檔：Cursor MCP
├── .gemini/
│   └── settings.json                 # 產生檔：Gemini CLI 讀 AGENTS.md 與 MCP
├── .mcp.json                         # 產生檔：Claude Code MCP
├── opencode.json                     # 產生檔：opencode MCP
├── .ai/                              # 跨工具交接紀錄；給人與 agent 看，不是執行設定
│   ├── context.md                    # 當前工作、卡關、換手資訊
│   ├── progress.md                   # 工作進度（由新到舊）
│   └── decisions.md                  # 架構與選型決策（只追加）
├── .vscode/
│   ├── settings.json                 # 編輯器設定；chat.useAgentsMdFile 讓 Copilot 讀 AGENTS.md
│   └── mcp.json                      # 產生檔：VS Code Copilot MCP
├── orchestrator/                     # 主程式：Java 21／Spring Boot 3.5，Maven 專案
│   ├── pom.xml
│   ├── src/main/java/com/zipe/aiteam/
│   │   ├── AiTeamApplication.java    # 進入點；支援 --validate-team 靜態驗證團隊配置
│   │   ├── agent/                    # legacy 固定管線的階段定義與執行
│   │   │   ├── StageRunner.java      # 通用階段執行器：組 prompt／schema、呼叫 runtime
│   │   │   └── StageSpec.java        # 各 legacy 階段的工具、權限、逾時、回合上限
│   │   ├── config/
│   │   │   └── AiTeamProperties.java # 從環境變數讀取並驗證所有設定
│   │   ├── flow/                     # 配置式（v2）流程引擎與工作契約
│   │   │   ├── FlowEngine.java       # 流程引擎：節點轉移、受阻路由、停止、承接新任務
│   │   │   ├── FlowRun.java          # 每任務的流程快照（存於 flow_run 表）
│   │   │   ├── AgentDispatch.java    # Agent 選擇角色、派工契約驗證與歷史保存
│   │   │   ├── FlowLimits.java       # 防循環上限的唯一判斷（引擎與按鈕共用）
│   │   │   ├── FlowGates.java        # 依工作契約（gate）驗證節點報告
│   │   │   ├── FlowContext.java      # 滾動的交接紀錄 flow.context
│   │   │   ├── ConfiguredExecutor.java # 執行配置節點與角色對話：組 prompt、呼叫 runtime
│   │   │   ├── TeamDefinition.java   # 團隊配置 JSON 的型別與驗證
│   │   │   ├── TeamCatalog.java      # 啟動時載入團隊配置與 prompt／schema 資產
│   │   │   ├── TeamMigration.java    # 舊任務的受控配置遷移（僅執行能力設定）
│   │   │   ├── AutomaticHandoff.java # 有證據的受阻事件自動交接與延長授權
│   │   │   ├── ContractRecovery.java # 恢復被系統規則誤判的前置契約
│   │   │   ├── DocOnlyChanges.java   # 純文件變更判定（略過建置測試）與強制全跑名單
│   │   │   ├── ProtectedPathFallback.java # 受保護路徑改派實作者：判定、禁止清單與事後核對
│   │   │   ├── VerificationRecovery.java # 配置核准後授予一次驗收重跑
│   │   │   ├── Traceability.java     # 需求基準、原子驗收項目與覆蓋對照
│   │   │   ├── IssueTracker.java     # 跨專案問題清單、需求對應、逐項驗證與承接完整性
│   │   │   ├── IndependentVerifier.java # 系統獨立驗證：全新 clone 執行固定指令並產生收據
│   │   │   ├── FileComparison.java # 指定舊檔比對的計畫契約、路徑驗證與能力說明
│   │   │   ├── ServiceDelivery.java # 服務建置目標、可用性檢查與完成契約
│   │   │   ├── OperationContract.java # 專案操作契約（operations）
│   │   │   ├── OperationPreflight.java # 各階段沙箱內的操作預檢
│   │   │   ├── PathRules.java        # 專案相對路徑與 ** 比對
│   │   │   ├── PlanApproval.java     # 測試計畫真人核准收據
│   │   │   ├── HumanOptions.java     # 停止時「現在可以做什麼」的選項
│   │   │   ├── OwnerPublish.java     # 驗收負責人命令開 PR 的條件判斷與審查提醒等級放行
│   │   │   ├── NodeInput.java        # 節點輸入指紋（判斷是否為相同輸入重跑）
│   │   │   ├── ToolTrust.java        # 工具被拒卻宣稱完成時作廢結論
│   │   │   ├── SystemFailure.java    # 區分系統沒跑起來與工作本身有問題
│   │   │   ├── ErrorExplainer.java   # 請 agent 把系統錯誤翻成白話
│   │   │   └── PrReport.java         # PR 內文的驗收紀錄 Markdown
│   │   ├── publish/
│   │   │   └── PullRequestPublisher.java # 推分支與開 PR（唯一需真人核准才發生的對外動作）
│   │   ├── runtime/                  # 各 agent CLI 的 adapter
│   │   │   ├── AgentRuntime.java     # 廠商無關的執行介面與能力宣告
│   │   │   ├── RuntimeRegistry.java  # 依 id 取用 runtime
│   │   │   ├── ClaudeCodeRuntime.java
│   │   │   ├── CodexRuntime.java
│   │   │   ├── OpenCodeRuntime.java
│   │   │   ├── CodexLocalTestPolicy.java # localTestNetwork 的 Codex 權限設定
│   │   │   ├── CodexSandboxPreparation.java # Codex 雙層沙箱掛載點準備
│   │   │   ├── SessionCapture.java   # 從 JSONL 事件流取出 session id
│   │   │   ├── QuotaExceeded.java    # 辨識供應商額度錯誤
│   │   │   ├── RuntimeUsageError.java # CLI 參數錯誤（未呼叫模型）
│   │   │   └── Diagnostics.java      # 遮蔽後的診斷輸出與白話原因
│   │   ├── sandbox/                  # 包在 runtime 外層的 systemd 執行沙箱
│   │   │   ├── ArgumentListTooLong.java # 子行程參數超過作業系統上限時附上大小診斷，不重試
│   │   │   ├── ExecutionSandbox.java # 把指令包成受限 systemd user unit
│   │   │   ├── SandboxPolicy.java    # 單次執行的隔離條件（可寫路徑、網路、資源）
│   │   │   └── TaskUnits.java        # 停掉某任務正在跑的 agent 與系統驗證
│   │   ├── slack/                    # Slack 進入點、發言與版面
│   │   │   ├── SlackListener.java    # 事件入口：白名單、去重、指令解析、按鈕處理
│   │   │   ├── SlackNotifier.java    # 所有對 Slack 的發言（主 App 或角色身分）
│   │   │   ├── SlackBlocks.java      # 訊息版面與按鈕 action_id
│   │   │   ├── SlackReport.java      # 階段報告的 Slack 呈現
│   │   │   ├── HumanGate.java        # 停下等人時的白話說明與選項按鈕
│   │   │   ├── HumanInput.java       # 要填內容的選項：按鈕打開 Slack 輸入視窗
│   │   │   ├── PlanReview.java       # 測試計畫逐情境呈現與核准按鈕
│   │   │   ├── SpinoffButtons.java   # 純詢問發現的「開成新需求」按鈕
│   │   │   └── StageProgress.java    # 執行中階段的定期狀態卡
│   │   ├── store/
│   │   │   ├── TaskStore.java        # SQLite 存取：任務、階段產出、去重、快照
│   │   │   └── AuditLog.java         # 稽核紀錄（資料庫＋log）
│   │   ├── task/                     # 任務治理：狀態機、佇列、閘門、額度
│   │   │   ├── TaskOrchestrator.java # 管線串接、並行控制、狀態機、審批閘門
│   │   │   ├── Task.java             # 任務與 Task.State
│   │   │   ├── Checkpoint.java       # 中斷階段的可恢復現場
│   │   │   ├── StageOutcome.java     # 單一階段產出
│   │   │   ├── DirectRequest.java    # 直接對角色對話的唯讀子請求
│   │   │   ├── Remediation.java      # QA 受阻後的派工狀態
│   │   │   ├── FailureReport.java    # 全角色共用失敗回報契約
│   │   │   ├── QaGate.java           # 測試計畫與驗收的情境完整性檢查
│   │   │   ├── ReviewGate.java       # 發布前的審查硬性條件
│   │   │   ├── TriageGate.java       # PM 分流報告的檢查
│   │   │   ├── QuotaAutoResume.java  # 額度用盡後的自動續跑排程
│   │   │   ├── QuotaMessage.java     # 額度訊息呈現
│   │   │   ├── QuotaRetryTime.java   # 解析供應商解禁時間
│   │   │   └── TaipeiTime.java       # 給人看的時間換算為台北時間
│   │   └── workspace/
│   │       └── WorkspaceManager.java # 每任務獨立 clone 工作區
│   ├── src/main/resources/
│   │   ├── application.yml           # Spring 設定：SQLite 路徑、無 HTTP server
│   │   └── schema.sql                # 資料表 DDL 唯一來源（啟動時執行，無遷移機制）
│   ├── src/test/java/com/zipe/aiteam/ # 測試，套件結構同主程式
│   │   ├── SpringWiringTest.java
│   │   ├── agent/
│   │   ├── flow/
│   │   ├── runtime/
│   │   ├── sandbox/
│   │   ├── slack/
│   │   ├── store/
│   │   ├── task/
│   │   └── workspace/
│   ├── teams/                        # 配置式團隊定義（現行模式），修改後重啟、只影響新任務
│   │   ├── skills/                   # 由 .agents 同步的 Slack runtime skill 套件
│   │   │   └── service-build/
│   │   │       ├── SKILL.md          # 建置流程（不得手改產生檔）
│   │   │       ├── references/
│   │   │       │   ├── bestpartner.md
│   │   │       │   └── host-setup.md
│   │   │       ├── assets/
│   │   │       │   └── host.example.json
│   │   │       └── scripts/
│   │   │           ├── client.py
│   │   │           ├── broker.py
│   │   │           ├── guest.py
│   │   │           ├── enroll.py
│   │   │           ├── login.ps1         # Windows 遮蔽輸入與私人登入檔
│   │   │           └── test_service_agent.py
│   │   ├── spring-starters.json      # 現行正式配置：zipe-spring-boot-starters
│   │   ├── bestpartner.json          # bestpartner 實例（Kotlin/Quarkus＋Gradle、Vue）；不跑需資料庫的測試
│   │   ├── node-web.json             # Node 專案範例配置
│   │   ├── prompts/                  # 節點提示
│   │   │   ├── intake.md             # 需求整理（PM）
│   │   │   ├── design.md             # 設計（Architecture）
│   │   │   ├── plan.md               # 測試計畫（QA）
│   │   │   ├── implement.md          # 實作（Backend）
│   │   │   ├── verify.md             # 驗收（QA）
│   │   │   ├── review.md             # 審查（Code Reviewer）
│   │   │   ├── infrastructure.md     # Infrastructure Agent：環境設定與核准目標的服務建置
│   │   │   ├── service.md            # 服務建置、啟動後檢查與自修
│   │   │   ├── service-triage.md     # 服務受阻分流
│   │   │   └── triage.md             # 受阻分流（PM）
│   │   └── schemas/                  # 節點輸出 JSON schema
│   │       ├── service.json          # 服務目標與逐項可用性證據
│   │       ├── requirements.json
│   │       ├── analysis.json
│   │       ├── plan.json
│   │       ├── implementation.json
│   │       ├── verification.json
│   │       └── review.json
│   ├── prompts/                      # legacy 五角色提示（未設 AI_TEAM_DEFINITION 時使用）
│   │   ├── pm.md
│   │   ├── pm_triage.md
│   │   ├── architecture.md
│   │   ├── qa_plan.md
│   │   ├── backend.md
│   │   ├── qa.md
│   │   ├── code_review.md
│   │   └── direct.md                 # 直接對角色的唯讀對話
│   └── schemas/                      # legacy 階段輸出 schema
│       ├── task-schema.json
│       ├── adr-schema.json
│       ├── qa-plan-schema.json
│       ├── impl-schema.json
│       ├── qa-schema.json
│       ├── review-schema.json
│       ├── triage-schema.json
│       └── direct-schema.json
├── deploy/                           # 部署相關
│   ├── README.md                     # deploy/ 目錄說明與 Slack 主 App 設定
│   ├── env.sh.template               # 環境變數範本（複製為 ~/ai-team/env.sh，權限 600）
│   ├── slack-app-manifest.yaml       # 主 App manifest
│   ├── agent-manifests/              # 五個既有角色與 bestpartner Infrastructure Agent 的 Slack App manifest
│   │   ├── pm.yaml
│   │   ├── architecture.yaml
│   │   ├── backend.yaml
│   │   ├── qa.yaml
│   │   ├── code-review.yaml
│   │   └── infrastructure.yaml       # BP Infrastructure Agent：Preview 部署、健康檢查與清理身分
│   ├── bootstrap/                    # 從零建置工具（新機只用這裡），安裝後位於 ~/ai-team/
│   │   ├── provision.sh              # 以已核實 checksum 的官方封存檔安裝工具鏈
│   │   ├── toolchain.tsv.example     # 工具鏈 URL／checksum 範本
│   │   ├── install-app.sh            # 隔離建置、測試並安裝應用
│   │   ├── check-linux.sh            # Linux 能力檢查（tools／runtime）
│   │   ├── linux-platform.sh         # 發行版辨識共用函式
│   │   ├── check-source.sh           # 來源一致性檢查（角色、schema、manifest、Bash 語法）
│   │   ├── preflight.sh              # 啟動前本機檢查
│   │   ├── start.sh                  # 載入 env 後啟動 jar
│   │   ├── service.sh                # 服務 start／stop／status
│   │   ├── audit.sh                  # 唯讀 SQLite 稽核查詢
│   │   ├── backup.sh                 # 停機備份（排除憑證與 session）
│   │   ├── restore.sh                # 還原到新目錄
│   │   ├── test-bootstrap.sh         # bootstrap fixture 測試
│   │   └── test-linux-platform.sh    # 平台辨識 fixture 測試
│   ├── deploy-orchestrator.sh        # 通用受控部署（隔離目錄建好的 JAR、src/main、teams）
│   ├── deploy-jar-safe.sh            # 只換 JAR 的受控部署，失敗自動回復
│   ├── engineering-v2-team.env       # 既有 VM 的 team.env（指定 AI_TEAM_DEFINITION）
│   └── test-maven-settings.xml       # 測試用空白 Maven settings
└── docs-site/                        # 文件網站（Docusaurus），主要讀者為 AI agent
    ├── README.md                     # 網站結構與維護方式
    ├── .gitignore
    ├── package.json                  # 指令：start、build（prebuild 跑文件檢查）、check:catalog
    ├── package-lock.json
    ├── docusaurus.config.js          # 網站設定：docs、agent 兩個文件區與 llms 外掛
    ├── sidebars.js                   # docs 側欄（由 catalog.mjs 產生）
    ├── sidebars-agent.js             # agent 側欄
    ├── catalog.mjs                   # 文件目錄：每份文件的分類、狀態、用途、相關程式
    ├── agent/                        # AI Agent 入口頁（/agent/）
    │   ├── index.md                  # 快速入口與依任務找文件
    │   ├── system-map.md             # 系統全貌
    │   ├── project-tree.md           # 本頁
    │   ├── operations.md             # 操作與指令
    │   ├── configuration.md          # 設定參考
    │   ├── contributing.md           # 修改專案的規則
    │   └── ai-tools.md               # 各 AI 工具的規則、skills、MCP 設定與同步
    ├── docs/                         # 所有原始文件（/docs/），清單見文件目錄
    ├── plugins/
    │   └── llms-txt.mjs              # build 後產生 llms.txt、llms-full.txt 與 .md 原文
    ├── scripts/
    │   └── check-catalog.mjs         # 檢查文件目錄與目錄樹是否與實際檔案一致
    └── src/
        ├── css/
        │   └── custom.css            # 全站樣式
        └── pages/
            ├── index.js              # 首頁
            ├── index.css
            ├── catalog.js            # 文件目錄頁（/catalog）
            └── catalog.module.css
```

## 不在版控內的本機產物

| 路徑 | 內容 |
|---|---|
| `orchestrator/target/` | Maven 建置輸出；服務正在使用時不可在此 clean／build |
| `docs-site/node_modules/`、`docs-site/build/`、`docs-site/.docusaurus/` | 網站依賴與建置輸出 |
| `.build-m2/` | 本機離線 Maven repository（可用 `-Dmaven.repo.local=.build-m2`） |
| `.build-cache/` | 本機建置快取 |
| `.codex/` | Codex 本機設定；同步腳本只改 `config.toml` 的 `agents-sync` 標記區塊 |
| `.claude/settings.local.json` | Claude Code 個人設定 |
| `*.tar.gz`、`*.tgz`、`deploy/*.tgz` | 部署用來源封存檔 |
| `env.sh`、`*.db` | 憑證與資料庫；永遠不進版控 |
