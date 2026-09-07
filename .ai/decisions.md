# 架構決策紀錄（Codex 與 Claude Code 共用）

> 只追加，不覆蓋。每則寫「決策／理由／影響」，並註明日期與提出者。

## 2026-09-07：以檔案共享取代 session 同步

**決策**：不嘗試同步 Codex 與 Claude Code 的對話 session，改以
`AGENTS.md` + `CLAUDE.md`（import `@AGENTS.md`）+ `.ai/` 三份檔案做跨工具長期記憶。

**理由**：兩者的 session 各自獨立且格式不同，Claude Code 的 `--resume`／`--continue`
只能接續自己的 session，無法載入 Codex 的 thread。共用 repo 檔案是目前唯一可靠的交接面。

**影響**：規範只寫在 `AGENTS.md` 一處，避免兩份規範漂移；代價是 agent 必須在
開場讀、收尾寫 `.ai/`，這項義務寫在 `AGENTS.md` 與 `CLAUDE.md` 裡。

**範圍界線**：`.ai/` 是開發者與 agent 的交接記錄，不是 orchestrator 執行期讀取的設定。
orchestrator 對下游 agent 的規範仍在 `orchestrator/prompts/`；`StageRunner` 讀的是
「目標 repo」的 `AGENTS.md`，與本 repo 的 `.ai/` 無關。
