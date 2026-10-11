# AI Team 專案導覽

- 回應與 commit 使用台灣繁體中文；commit 格式遵循使用者提供的全域規範。
- **本檔是所有 AI 工具的唯一規則來源**（Codex、Claude Code、Cursor、opencode、Copilot、Gemini CLI 等）。
  `CLAUDE.md` 只含 `@AGENTS.md` 一行，不得在其他工具專屬檔案另寫規則；skills 與 MCP 的單一來源見下方「AI 工具設定」。
- **跨工具交接**：每個 session 開場先讀 `.ai/context.md`（當前工作）、`.ai/progress.md`（進度），
  再依「文件優先」找到相關文件，之後才動手；不要重問已寫在裡面的脈絡。
  完成工作單位更新 `.ai/progress.md`，做出架構或選型決策追加 `.ai/decisions.md`（不覆蓋舊條目），
  中斷、卡關或換手更新 `.ai/context.md`。各工具的對話 session 不互通，這三份檔案是唯一的交接面。
- `.ai/` 是給人與 agent 看的交接紀錄，不是 orchestrator 執行時讀的設定；orchestrator 給下游 agent 的規範在
  `orchestrator/teams/prompts/`（配置式）與 `orchestrator/prompts/`（legacy），不是本檔。
- 大範圍修改先提出計畫讓使用者確認再實作（有 plan mode 的工具使用 plan mode）。
- 快速理解專案：[docs-site/agent/index.md](docs-site/agent/index.md)（系統全貌、Slack 指令、設定參考、依任務找文件）；
  找檔案先查專案目錄樹 [docs-site/agent/project-tree.md](docs-site/agent/project-tree.md)；
  每份文件的狀態（現行／紀錄／決策／歷史）與用途登錄在 `docs-site/catalog.mjs`。
  新增、搬移、刪除文件或程式檔時要同步更新 catalog 與目錄樹，否則 `docs-site` 的 build 失敗。
- 從零建置的唯一入口：[docs-site/docs/INSTALL.md](docs-site/docs/INSTALL.md)。
- 建置演練證據與未完成事項：[docs-site/docs/rebuild-verification.md](docs-site/docs/rebuild-verification.md)。
- Linux 能力與驗證矩陣：[docs-site/docs/linux-support.md](docs-site/docs/linux-support.md)；未實測不可標成已支援。
- Java 實作：`orchestrator/src/`；外部角色契約：`orchestrator/prompts/`、`orchestrator/schemas/`。
- QA 流程：[docs-site/docs/qa-scenario-workflow.md](docs-site/docs/qa-scenario-workflow.md)。
- QA 受阻與 PM 派工：[docs-site/docs/pm-triage.md](docs-site/docs/pm-triage.md)。
- 直接對角色提問／派工：[docs-site/docs/direct-agent-requests.md](docs-site/docs/direct-agent-requests.md)。
- 配置式團隊與舊任務相容：[docs-site/docs/configurable-team.md](docs-site/docs/configurable-team.md)；新角色／流程改 `orchestrator/teams/`，不要再擴充 legacy role switch。
- **新增 Agent 的 Slack 交付要求**：每次新增角色時，完成回報必須一併提供如何把該角色加入 Slack，至少包含角色 manifest
  路徑、建立／安裝 App、Bot Token 與 App-Level Token 對應的環境變數、邀請進目標頻道、服務重啟及直接 `@`／流程身分驗證；
  詳見 [建立 Slack App](docs-site/docs/slack-agent-apps.md#新增配置式角色)。若刻意不建立獨立角色 App，也要明示會由主 App 代發且無法直接 `@`。
- 跨專案操作、沙箱預檢、真人計畫核准與配置遷移：[docs-site/docs/cross-project-execution.md](docs-site/docs/cross-project-execution.md)；操作宣告不是自動擴權，舊快照不自動更新。
- v2 需求追溯／返工／系統驗證／PR 狀態：[docs-site/docs/engineering-workflow.md](docs-site/docs/engineering-workflow.md)。
- 資料表與欄位說明（含四張 JSON 快照表的結構）：[docs-site/docs/data-model.md](docs-site/docs/data-model.md)；
  DDL 以 `orchestrator/src/main/resources/schema.sql` 為準，改 schema 或 `FlowRun`／`Checkpoint`
  等序列化型別時要同步更新本文，並注意沒有遷移機制、舊資料解析失敗會直接拋錯。
- 現行 VM 部署版本與實測：[docs-site/docs/deploy-2026-09-06-v2.md](docs-site/docs/deploy-2026-09-06-v2.md)；使用 team.env 啟用 v2，#1011 已依使用者確認清理。
- 失敗與發布閘門：[docs-site/docs/failure-reporting.md](docs-site/docs/failure-reporting.md)、[docs-site/docs/review-gate.md](docs-site/docs/review-gate.md)。
- Session 與用量：[docs-site/docs/quota-continuation.md](docs-site/docs/quota-continuation.md)。
- 真人計畫核准與 Slack 按鈕：[docs-site/docs/plan-approval-buttons.md](docs-site/docs/plan-approval-buttons.md)；核准收據綁版本，計畫核准不等於核准 PR。
- Codex 執行前置（三者都會讓 QA 整段跑不起來，換 CLI 版本要重驗）：
  雙層沙箱掛載點 [docs-site/docs/codex-nested-sandbox.md](docs-site/docs/codex-nested-sandbox.md)、
  本機測試網路能力 [docs-site/docs/local-test-capabilities.md](docs-site/docs/local-test-capabilities.md)、
  `codex sandbox` 與 `codex exec` 參數契約 [docs-site/docs/codex-cli-argument-contract.md](docs-site/docs/codex-cli-argument-contract.md)。
- opencode runtime（第三個 runtime，選用）：[docs-site/docs/opencode-runtime-report.md](docs-site/docs/opencode-runtime-report.md)；
  旗標與事件流已實測（1.18.19），但 VM 沙箱下的端對端執行尚未驗證，不可標成已支援。
  模型名格式 `provider/model`，與 claude／codex 都不通用；它是唯一會回報實際金額的 runtime。
- 建置工具：`deploy/bootstrap/`。`deploy/deploy-orchestrator.sh`、`deploy-jar-safe.sh` 只用於升級既有 VM，不用於新機。
- 變更角色、runtime、schema、teams 或其他設定後，執行 `bash deploy/bootstrap/check-source.sh` 與 Maven 測試。
- 不讀出憑證、不把 env.sh／CLI 登入檔放進一般發行包。OAuth 由使用者本人操作。
- 不在服務執行的 target/ 做 clean/build；用隔離來源與建置目錄。
- 不得把舊 VM 的 warm cache 測試寫成乾淨 VM 驗收成功。
- 更新狀態文件時保留歷史證據，明確區分「程式測試通過」與「真實端對端通過」。

## 文件優先（強制）

適用於所有 AI agent 的所有工作，包括回答問題、查錯、修改程式、部署與維運，不限功能修改。

### 一、先讀文件，再動手

1. 開始任何工作前，先從 [docs-site/agent/index.md](docs-site/agent/index.md) 的「依任務找文件」、
   [文件目錄](docs-site/catalog.mjs) 與 [專案目錄樹](docs-site/agent/project-tree.md) 找到相關文件並閱讀，
   以文件建立理解後再讀程式。不得跳過文件直接從程式推測行為後就行動或回答。
2. 引用文件時注意狀態：`現行` 可據以行動；`紀錄`、`決策` 是證據與理由；`歷史` 不可照做。
3. 文件是理解的起點，**程式與 schema 是行為的最終依據**；讀程式時要同時核對文件說法是否仍然成立。

### 二、文件缺漏或與程式不符時，一定要修正文件

- **文件查不到，但程式存在**：在同一次工作中補上文件說明——放進主題最接近的 `現行` 文件；
  沒有合適文件時新增 `docs-site/docs/<主題>.md` 並登錄 catalog；新檔案同步加入專案目錄樹。
  即使本次任務只是回答問題或查錯，也要補，不得只在回答裡說明。
- **功能與文件有落差**：以程式實際行為為準修正文件。若判斷是程式錯了（文件描述的才是原本意圖），
  不可只改文件把錯誤合理化：先向使用者回報落差並確認要修程式還是修文件；在修好之前，
  文件要如實寫出目前行為並標明是已知問題。
- 補寫或修正都要在回報與 `.ai/progress.md` 中列出「發現的缺漏／落差」與「已修正的文件」。

### 三、任何功能異動都要核對文件

任何功能異動（`orchestrator/src/`、`orchestrator/teams/`、`orchestrator/prompts/`、`orchestrator/schemas/`、
`schema.sql`、`deploy/`、`deploy/env.sh.template`、Slack manifest、`docs-site/` 的程式）在回報完成前，**都必須**確認文件是否仍然正確；
不符合就在同一次修改中修正文件，不得留到之後、不得只在回報裡註記「文件待更新」。

1. **找出受影響的文件**：
   - `docs-site/catalog.mjs` 中 `code` 列到被改檔案或其所在目錄的文件；
   - 在 `docs-site/docs/` 與 `docs-site/agent/` 搜尋被改的類別名、方法名、設定鍵、環境變數、Slack 指令與按鈕文字、狀態值；
   - 行為改變影響使用者操作時，一律檢查 `docs-site/agent/operations.md`；設定欄位或環境變數改變時檢查
     `docs-site/agent/configuration.md`；流程、狀態或程式碼位置改變時檢查 `docs-site/agent/system-map.md`。
2. **逐一核對內容**：預設值、上限、欄位名、指令格式、按鈕文字、狀態轉移、權限、錯誤訊息是否與新程式一致。
3. **修正方式**：
   - `現行` 文件：直接改成正確內容；同一文件中被新行為取代的舊段落要改寫或標明已被取代，不可讓新舊說法並存而無標示。
   - `紀錄`、`決策`、`歷史` 文件：不改寫歷史證據；需要時追加帶日期的更新段落，並在 `docs-site/catalog.mjs` 調整狀態
     （例如被取代時改為 `historical`）。
   - 新增或刪除 `docs-site/docs/` 文件時同步更新 `docs-site/catalog.mjs`；新增程式對應關係時補 `code` 欄位。
   - 新增、搬移或刪除檔案時同步更新 `docs-site/agent/project-tree.md`（含一句用途說明）。
4. **驗證**：`cd docs-site && npm run build` 必須通過（含文件目錄、專案目錄樹與斷鏈檢查）。
5. **回報**：完成回報與 `.ai/progress.md` 須列出「已核對的文件」與「已修正的文件」；確認無需修改時也要寫明核對過哪些。

## AI 工具設定（單一來源）

所有 AI 工具共用同一套規則、skills 與 MCP；只改單一來源，再以同步腳本產生各工具需要的檔案。
詳細說明與各工具支援狀態見 [docs-site/agent/ai-tools.md](docs-site/agent/ai-tools.md)。

| 項目 | 單一來源（只改這裡） | 產生／入口檔（不要手改） |
|---|---|---|
| 規則 | `AGENTS.md` | `CLAUDE.md`（`@AGENTS.md`）、`.gemini/settings.json` 的 `context.fileName`、`.vscode/settings.json` 的 `chat.useAgentsMdFile` |
| Skills | `.agents/skills/<名稱>/SKILL.md` | `.claude/skills/`（Claude Code 只讀這裡，由腳本鏡像） |
| MCP | `.agents/mcp.json` | `.mcp.json`、`.cursor/mcp.json`、`.vscode/mcp.json`、`.gemini/settings.json`、`opencode.json`、`.codex/config.toml` 的標記區塊 |

- 修改 skills 或 MCP 後執行 `node .agents/sync-agent-config.mjs`，並把產生檔一起提交。
  `cd docs-site && npm run build` 前會以 `--check` 檢查一致性，不一致則 build 失敗。
- 不要在 `.claude/skills/`、各工具的 MCP 設定檔或 `CLAUDE.md` 直接新增內容；會被判定為不一致。
- MCP 設定不得寫入憑證值；需要金鑰時由工具從環境變數讀取。
- `.codex/` 是本機檔（不進版控）：同步腳本只改其中的標記區塊，保留個人設定；Codex 只在受信任的專案載入它。
- 新增工具支援時，先查證官方文件並在 `ai-tools.md` 標明是否已實測，再擴充同步腳本；未實測不可標成已支援。
