# 工作進度（Codex 與 Claude Code 共用）

> 由新到舊追加。每則一行到數行：日期、由誰（Codex／Claude Code）、做了什麼、驗證方式。

## 2026-09-07 — Claude Code（版本控制上線）

專案首次納入 git 並推上 GitHub：`git init`（預設分支 `main`）、初始 commit
`a50642d` 共 174 個檔案，遠端為 https://github.com/a09090443/AI_team （private）。
`.gitignore` 追加 `*.tgz`，排除 `deploy/engineering-v2-source.tgz` 與
`deploy/handoff-source.tgz` 兩份原始碼快照（內容與 repo 重複）。

驗證：推送前掃描 `xoxb-`／`xapp-`／`ghp_`／`github_pat_`／`sk-ant-`／私鑰樣式，
命中全為文件佔位符（`xoxb-...`、`github_pat_example`），無真實憑證；`env.sh`
不存在於工作目錄且已列在 `.gitignore`。推送後以 GitHub API 確認遠端 `main`
指向 `a50642d`，與本機一致。僅版控作業，未改動 Java 實作與 orchestrator 契約，
故未跑 Maven 測試。

## 2026-09-07 — Claude Code

建立跨工具共享記憶架構：新增 `CLAUDE.md`（import `@AGENTS.md`）與
`.ai/context.md`、`.ai/decisions.md`、`.ai/progress.md`；於 `AGENTS.md` 加入
「開場讀、收尾寫 `.ai/`」的規範。

驗證：僅為文件新增，未改動 Java 實作與 orchestrator 契約，故未跑 Maven 測試。
