---
sidebar_position: 6
title: 修改專案的規則
description: agent 在本 repo 修改程式、配置或文件時必須遵守的規則與常見地雷。
---

# 修改專案的規則

規範的單一來源是 repo 根目錄的 `AGENTS.md`（`CLAUDE.md` 以 `@AGENTS.md` 匯入）。本頁摘要最常用的部分。

## 開工與收尾

- 開工先讀 `.ai/context.md`（當前工作）與 `.ai/progress.md`（進度），不要重問已寫在裡面的脈絡。
- 完成一個工作單位 → 更新 `.ai/progress.md`；做出架構或選型決策 → 追加 `.ai/decisions.md`（不覆蓋舊條目）；
  中斷或換手 → 更新 `.ai/context.md`。Codex 與 Claude Code 的 session 不互通，這三份檔案是唯一交接面。
- 回應與 commit 使用台灣繁體中文，格式 `<類型>(<範圍>): <主旨>`，類型如 `新增`、`修復`、`重構`、`文件`、`設定`。

## 改哪裡

| 要改的東西 | 位置 | 改完必做 |
|---|---|---|
| 新角色、流程節點、權限 | `orchestrator/teams/*.json` 與其 `prompts/`、`schemas/` | `--validate-team`、`check-source.sh`、Maven 測試 |
| 新角色的 Slack 身分 | `deploy/agent-manifests/`、角色的 token 環境變數 | 完成回報附建立／安裝、token、邀請、重啟與 `@`／流程驗證方式；見 [建立 Slack App](/docs/slack-agent-apps#新增配置式角色) |
| 引擎行為 | `orchestrator/src/main/java/com/zipe/aiteam/flow/` | Maven 測試 |
| 資料表 | `orchestrator/src/main/resources/schema.sql` | 同步更新 [data-model](/docs/data-model)；**沒有遷移機制**，要自己補 `ALTER TABLE` |
| `FlowRun`、`Checkpoint` 等序列化型別 | 對應 Java 類別 | 同步更新 data-model；舊資料解析失敗會直接拋錯 |
| 新機建置流程 | `deploy/bootstrap/`、`docs-site/docs/INSTALL.md` | `test-bootstrap.sh`、`check-source.sh` |
| 文件 | `docs-site/docs/*.md` | 新增或刪除文件時同步更新 `docs-site/catalog.mjs`，否則文件網站 build 失敗 |
| 新增、搬移、刪除任何檔案 | — | 同步更新[專案目錄樹](project-tree.md)，否則文件網站 build 失敗 |
| agent 規則 | `AGENTS.md`（唯一來源；`CLAUDE.md` 只能有 `@AGENTS.md`） | 不要在其他工具專屬檔案另寫規則 |
| 共用 skills、MCP | `.agents/skills/`、`.agents/mcp.json` | `node .agents/sync-agent-config.mjs`，見 [AI 工具設定](ai-tools.md) |

## 文件優先（強制）

適用於所有工作，包括回答問題、查錯、修改、部署與維運。完整規則見 `AGENTS.md`。

1. **先讀文件**：從[快速入口](index.md)、[文件目錄](/catalog)、[專案目錄樹](project-tree.md)找到相關文件並閱讀，
   再讀程式。程式與 schema 是行為的最終依據，讀程式時同時核對文件是否仍然成立。
2. **文件查不到但程式存在**：同一次工作中補寫文件（放進最接近的現行文件，或新增文件並登錄 catalog），
   即使任務只是回答問題也要補。
3. **功能與文件有落差**：以程式實際行為修正文件；若判斷是程式錯了，先向使用者回報並確認，
   修好前文件如實寫出目前行為並標明已知問題，不可只改文件把錯誤合理化。
4. **任何功能異動**：找出受影響文件（catalog 的 `code` 對應、在 `docs-site/docs/` 與 `docs-site/agent/`
   搜尋被改的類別、設定鍵、環境變數、指令、按鈕文字、狀態值），核對預設值、上限、欄位名、格式、狀態轉移與權限；
   `現行` 文件直接改正，`紀錄`／`決策`／`歷史` 文件改為追加帶日期段落。
5. **驗證與回報**：`cd docs-site && npm run build` 必須通過；回報與 `.ai/progress.md` 列出核對過、補寫與修正的文件。

## 其他注意事項

- **不要再擴充 legacy role switch**；新角色與流程一律走 `orchestrator/teams/`。
- 新增 Agent 時，完成回報必須包含加入 Slack 的操作方式；若不建獨立角色 App，要明示由主 App 代發且不能直接 `@`。
- `deploy/deploy-orchestrator.sh`、`deploy/deploy-jar-safe.sh` 只用於升級既有 VM；新機一律走 `deploy/bootstrap/`。不要再新增一次性的 `deploy-<功能>-<日期>.sh`。

## 安全與證據

- 不讀出憑證、不把 `env.sh` 或 CLI 登入檔放進發行包；OAuth 由使用者本人操作。
- 不在服務執行中的 `target/` 做 clean 或 build；用隔離的來源與建置目錄。
- 不得把舊 VM 的暖快取測試寫成乾淨 VM 驗收成功。
- Linux 平台未實測不可標為已支援；opencode 在 VM 沙箱下的端對端尚未驗證。
- 更新狀態文件時保留歷史證據，明確區分「程式測試通過」與「真實端對端通過」。
- 操作宣告（`operations`）不是自動擴權；舊任務快照不會自動更新。

## 常見地雷

- 在 Windows 上，依賴 `/bin/sh` 的測試類別（如 `RuntimeDiagnosticsTest`、`QuotaExceededTest`）無法完整執行。
- `task.id` 是字串，排序要 `ORDER BY CAST(id AS INTEGER)`。
- Codex 收到不認識的模型名不會拒絕，而是退回 fallback 繼續跑；換 runtime 時一併換模型名。
- Codex 的 `sandbox` 與 `exec` 子指令參數不對稱，`resume` 的權限選項必須放在子指令之後，見
  [codex-cli-argument-contract](/docs/codex-cli-argument-contract)。換 CLI 版本要重驗雙層沙箱、本機測試網路與參數契約三件事。
- `ABORTED` 任務的工作區會在下次服務重啟時被刪除。
