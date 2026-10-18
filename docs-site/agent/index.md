---
sidebar_position: 1
title: AI Agent 快速入口
description: 給 AI agent 的專案總覽、閱讀規則與「依任務找文件」路由表。
slug: /
---

# AI Agent 快速入口

本區是寫給 AI agent 的入口：用最少的閱讀量建立正確的專案心智模型，再依任務跳到對應文件。
人類讀者一樣適用。原始文件在 `docs-site/docs/`，本區只做導覽與摘要，不重複其內容。

## 這個專案是什麼

AI Team 是一個以 **Slack 為介面**的 AI 軟體團隊協調器（Java 21／Spring Boot 3.5）。
使用者在 Slack @主機器人 提出需求，Orchestrator 依**團隊配置 JSON** 的流程節點，
派多個 AI agent CLI（Claude Code、Codex，選用 opencode）分階段完成需求整理、設計、
測試計畫、實作、驗收、程式審查，最後在**真人核准**後推分支並開 GitHub PR。

Orchestrator 不重寫 agent 框架，只做 CLI 不管的事：任務狀態機、佇列與並行、人工閘門、
稽核紀錄，以及包在 runtime 外層的 **systemd 執行沙箱**。

## 讀任何文件前的四條規則

1. **區分證據等級**。「Maven 測試通過」「本機實作」「已部署到既有 VM」「真實 Slack／CLI 端對端通過」
   是四件不同的事。文件寫哪一級就只能主張哪一級。目前**尚未通過乾淨 VM 重建驗收**。
2. **看文件狀態**。每份文件在[文件目錄](/catalog)標有狀態：
   `現行` 可據以行動；`紀錄` 是有日期的證據；`決策` 是 ADR；`歷史` 已被取代，**不可照做**。
3. **現行文件只寫現況**；被取代的內容直接改寫，不留新舊並存。`紀錄`／`決策` 文件以追加帶日期段落保存歷史，
   同一文件內以日期最新的段落為準。已移除的舊報告與一次性腳本需要時查 git 歷史。
4. **程式與 schema 是最終依據**。文件與程式不一致時，以 `orchestrator/src/`、
   `orchestrator/src/main/resources/schema.sql`、`orchestrator/teams/` 為準，並回報文件需要修正。

## 下一步讀什麼

| 你要做的事 | 先讀 |
|---|---|
| 建立整體心智模型（分層、流程、狀態、程式碼位置） | [系統全貌](system-map.md) |
| 找某個檔案或某類東西放在哪裡 | [專案目錄樹](project-tree.md) |
| 設定 AI 工具的規則、skills、MCP | [AI 工具設定](ai-tools.md) |
| 在 Slack 操作任務、解讀按鈕、跑維運指令 | [操作與指令](operations.md) |
| 查環境變數、團隊 JSON 欄位與可選值 | [設定參考](configuration.md) |
| 修改本 repo 的程式或文件 | [修改專案的規則](contributing.md) |
| 找某個主題的原始文件 | [文件目錄](/catalog) |

## 依任務找文件

| 任務或問題 | 權威文件 | 相關程式 |
|---|---|---|
| 從零安裝、升級服務 | [INSTALL](/docs/INSTALL) | `deploy/bootstrap/` |
| 「能不能從零建置？」「哪個 Linux 可用？」 | [rebuild-verification](/docs/rebuild-verification)、[linux-support](/docs/linux-support) | `deploy/bootstrap/check-linux.sh` |
| 建立測試環境、部署既有服務 | [service-delivery](/docs/service-delivery) | `flow/ServiceDelivery.java`、`teams/prompts/service.md` |
| 新增／修改角色、節點、流程 | [configurable-team](/docs/configurable-team) | `orchestrator/teams/`、`flow/TeamDefinition.java` |
| 新增角色的 Slack App、token 與直接 @ 身分 | [slack-agent-apps](/docs/slack-agent-apps) | `deploy/agent-manifests/`、`slack/SlackNotifier.java` |
| 新專案宣告建置／測試指令 | [cross-project-execution](/docs/cross-project-execution) | `flow/OperationContract.java` |
| 接入專案／部署配置後確認驗證指令能過 | [baseline-verification](/docs/baseline-verification) | `flow/BaselineVerifier.java` |
| 需求 ID、驗收覆蓋、返工失效、PR 狀態 | [engineering-workflow](/docs/engineering-workflow) | `flow/Traceability.java`、`flow/IndependentVerifier.java` |
| 測試計畫與 QA 行為 | [qa-scenario-workflow](/docs/qa-scenario-workflow) | `orchestrator/teams/prompts/plan.md`、`verify.md` |
| 任務停住、要在 Slack 下指令 | [pm-triage](/docs/pm-triage)、[plan-approval-buttons](/docs/plan-approval-buttons) | `slack/SlackListener.java`、`slack/HumanGate.java` |
| 直接問某個角色、派工給角色 | [direct-agent-requests](/docs/direct-agent-requests) | `task/DirectRequest.java` |
| 角色回報 BLOCKED 或矛盾結果 | [failure-reporting](/docs/failure-reporting) | `task/FailureReport.java` |
| 為什麼沒進 PR 核准 | [review-gate](/docs/review-gate) | `task/ReviewGate.java` |
| 任務卡在 `WAITING_QUOTA` | [quota-continuation](/docs/quota-continuation) | `task/QuotaAutoResume.java` |
| 查資料庫、改 schema 或快照型別 | [data-model](/docs/data-model) | `store/TaskStore.java`、`schema.sql` |
| Codex 參數、雙層沙箱、本機測試網路 | [codex-cli-argument-contract](/docs/codex-cli-argument-contract)、[codex-nested-sandbox](/docs/codex-nested-sandbox)、[local-test-capabilities](/docs/local-test-capabilities) | `runtime/CodexRuntime.java` |
| 新增 runtime adapter | [multi-runtime](/docs/multi-runtime)、[opencode-runtime-report](/docs/opencode-runtime-report) | `runtime/AgentRuntime.java` |
| 為什麼流程這樣設計 | [ADR 類文件](/catalog#decisions)、[multi-runtime](/docs/multi-runtime) | — |

表中程式路徑省略了前綴 `orchestrator/src/main/java/com/zipe/aiteam/`。

## 機器可讀索引

以下為完整內部站 `npm run build` 的輸出；公開站另以 `public-manual.mjs` 與 `public-docs/` 產生一般手冊索引，不包含本區與內部全文。

完整站 build 後會在根目錄產生：

- `/llms.txt`：依分類列出所有文件的標題、狀態、用途與 Markdown 原文連結（[llmstxt.org](https://llmstxt.org/) 格式）。
- `/llms-full.txt`：本區入口頁加上所有「現行」文件的全文，適合一次載入當脈絡。
- 每份文件的 Markdown 原文：在網頁路徑後加 `.md`，例如 `/docs/INSTALL.md`、`/agent/system-map.md`。

在 repo 內工作時不需要網站：本區頁面在 `docs-site/agent/`，文件目錄資料在 `docs-site/catalog.mjs`。
