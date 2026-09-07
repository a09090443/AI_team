# 工作進度（Codex 與 Claude Code 共用）

> 由新到舊追加。每則一行到數行：日期、由誰（Codex／Claude Code）、做了什麼、驗證方式。

## 2026-09-07 — Claude Code

建立跨工具共享記憶架構：新增 `CLAUDE.md`（import `@AGENTS.md`）與
`.ai/context.md`、`.ai/decisions.md`、`.ai/progress.md`；於 `AGENTS.md` 加入
「開場讀、收尾寫 `.ai/`」的規範。

驗證：僅為文件新增，未改動 Java 實作與 orchestrator 契約，故未跑 Maven 測試。
