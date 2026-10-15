---
title: AI 閱讀入口
---

# AI 閱讀入口

AI Team 是 Slack 介面的 Java 21／Spring Boot 協調器。它管理任務、團隊配置、人工閘門、驗證與發布，透過 CLI 執行 agent。
本頁是公開的專案閱讀地圖，適合先建立理解，再依任務讀主題文件與原始碼。

## 建議閱讀順序

1. [專案介紹](index.md)：目標、讀者路線與能力邊界。
2. [架構概要](architecture.md)：分層、程式碼位置與資料責任。
3. [工作流程](workflow.md)：生命週期、狀態、返工與發布邊界。
4. 依任務查下表，再讀[開發與文件維護](development.md)。

## 依任務找文件與程式

Java 路徑相對於 `orchestrator/src/main/java/com/zipe/aiteam/`。

| 任務 | 公開文件 | repo 權威來源 |
|---|---|---|
| 使用 Slack／解讀按鈕 | [使用指南](quickstart.md)、[操作](usage.md) | `slack/SlackListener.java`、`HumanGate.java`、`HumanInput.java`；`docs-site/agent/operations.md` |
| 安裝、升級、環境能力 | [安裝](installation.md) | `deploy/bootstrap/`；`docs-site/docs/INSTALL.md`、`linux-support.md` |
| 新角色、專案或流程 | [設定](configuration.md) | `orchestrator/teams/`；`flow/TeamDefinition.java`、`TeamCatalog.java`；`docs-site/docs/configurable-team.md` |
| 流程停住、返工、承接 | [排查](troubleshooting.md)、[流程](workflow.md) | `flow/FlowEngine.java`、`FlowLimits.java`、`TeamMigration.java` |
| 需求追溯與問題完整性 | [流程](workflow.md) | `flow/Traceability.java`、`IssueTracker.java`、`FlowGates.java`；`docs-site/docs/engineering-workflow.md` |
| runtime 與沙箱 | [架構](architecture.md) | `runtime/`、`sandbox/`；`docs-site/docs/multi-runtime.md` 與 Codex 契約文件 |
| schema 與快照 | [架構](architecture.md) | `store/TaskStore.java`、`flow/FlowRun.java`、`orchestrator/src/main/resources/schema.sql`；`docs-site/docs/data-model.md` |
| 修改與驗證 | [開發](development.md) | 根目錄 `AGENTS.md`、`orchestrator/pom.xml`、`docs-site/catalog.mjs` |

repo 細部文件路徑是來源索引，不代表公開站有對應路由，也不應將私人部署紀錄直接載入公開回覆。

## 證據與規則

- 公開手冊描述一般操作，實際任務以其保存的團隊快照與當前程式為準。
- repo 文件目錄標示 `current`（現行）、`record`（紀錄）、`decision`（決策）、`historical`（歷史）。歷史不能直接照做。
- 分別陳述來源測試、啟動檢查、真實 Slack／CLI 端對端與乾淨重建證據，不把前一層推定為後一層。
- 新規則不會因重啟自動套入舊任務；操作契約不自動擴權；PR 已建立不等於已合併或部署。
- 在 repo 執行工作先讀 AGENTS.md 與其指定的交接、文件入口，再核對現場。公開站不能取代本機最新交接資訊。
- `.ai` 交接檔是開發 session 的交接面，不是 orchestrator 對下游 agent 的執行提示。下游提示在 teams/prompts 與 legacy prompts。
- 根目錄要求的上下文摘要，不代表每次 Slack runtime 已自動壓縮對話；避免混淆開發工具規範與產品能力。

## 機器可讀輸出

公開站 build 會產生根目錄 `llms.txt`（閱讀索引）、`llms-full.txt`（公開手冊全文）與 `manual/<文件 ID>.md`。
網址須保留本站 `/AI_team/` 子路徑。這些檔案只由公開文件清單產生，不包含完整內部站、私人部署紀錄或交接檔。
完整內部站另有自己的 llms 輸出，請勿混用。

下載：[公開索引](pathname:///AI_team/llms.txt)、[公開全文](pathname:///AI_team/llms-full.txt)、[本頁 Markdown](pathname:///AI_team/manual/ai-guide.md)。
