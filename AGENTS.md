# AI Team 專案導覽

- 回應與 commit 使用台灣繁體中文；commit 格式遵循使用者提供的全域規範。
- **跨工具交接**：每個 session 開場先讀 `.ai/context.md`、`.ai/progress.md`；
  完成工作單位更新 `.ai/progress.md`，做出決策追加 `.ai/decisions.md`，
  中斷或換手更新 `.ai/context.md`。Codex 與 Claude Code 的對話 session 不互通，
  這三份檔案是唯一的交接面。`CLAUDE.md` 以 `@AGENTS.md` import 本檔，規範只寫這裡一份。
- 從零建置的唯一入口：[docs/INSTALL.md](docs/INSTALL.md)。
- 建置演練證據與未完成事項：[docs/rebuild-verification.md](docs/rebuild-verification.md)。
- Linux 能力與驗證矩陣：[docs/linux-support.md](docs/linux-support.md)；未實測不可標成已支援。
- Java 實作：`orchestrator/src/`；外部角色契約：`orchestrator/prompts/`、`orchestrator/schemas/`。
- QA 流程：[docs/qa-scenario-workflow.md](docs/qa-scenario-workflow.md)。
- QA 受阻與 PM 派工：[docs/pm-triage.md](docs/pm-triage.md)。
- 直接對角色提問／派工：[docs/direct-agent-requests.md](docs/direct-agent-requests.md)。
- 配置式團隊與舊任務相容：[docs/configurable-team.md](docs/configurable-team.md)；新角色／流程改 `orchestrator/teams/`，不要再擴充 legacy role switch。
- v2 需求追溯／返工／系統驗證／PR 狀態：[docs/engineering-workflow.md](docs/engineering-workflow.md)。
- 現行 VM 部署版本與實測：[docs/deploy-2026-09-06-v2.md](docs/deploy-2026-09-06-v2.md)；使用 team.env 啟用 v2，#1011 已依使用者確認清理。
- 失敗與發布閘門：[docs/failure-reporting.md](docs/failure-reporting.md)、[docs/review-gate.md](docs/review-gate.md)。
- Session 與用量：[docs/quota-continuation.md](docs/quota-continuation.md)。
- 建置工具：`deploy/bootstrap/`。`deploy/deploy-*.sh` 是特定舊 VM 的歷史升級腳本，不用於新機。
- 變更新角色／schema／設定後，執行 `bash deploy/bootstrap/check-source.sh` 與 Maven 測試。
- 不讀出憑證、不把 env.sh／CLI 登入檔放進一般發行包。OAuth 由使用者本人操作。
- 不在服務執行的 target/ 做 clean/build；用隔離來源與建置目錄。
- 不得把舊 VM 的 warm cache 測試寫成乾淨 VM 驗收成功。
- 更新狀態文件時保留歷史證據，明確區分「程式測試通過」與「真實端對端通過」。
