# 工作進度（Codex 與 Claude Code 共用）

> 由新到舊追加。每則一行到數行：日期、由誰（Codex／Claude Code）、做了什麼、驗證方式。

## 2026-09-08 — Codex（Slack 定期回報實作）

後續部署：使用者明確要求後，2026-09-08 22:23 台北時間部署成功，PID 80214。
jar SHA-256 a73017c0d066f0c35fe3283c821cc0b71135b7ac22b24c8aed796d751d7d90ad。
備份 /home/vboxuser/ai-team/progress-backup.oFdYpg；task 表前後雜湊一致，#1014
仍 AWAIT_HUMAN。沙箱／工具鏈與角色 Socket Mode 啟動檢查通過，真實五分鐘
更新仍待任務續跑驗收。以下保留實作時的歷史紀錄。

新增 StageProgress：每階段一張系統代報狀態卡，標明角色／節點與耗時，預設
300 秒更新同一訊息，支援 AI_TEAM_PROGRESS_INTERVAL_SECONDS（最小 30 秒）。
涵蓋 v2、legacy、新 session 接手、唯讀對話及系統獨立驗證；結束或任務不再執行
即停止，通知例外不影響任務。刻意不宣稱工具活動、完成百分比或產品已通過驗收。

隔離目錄 /home/vboxuser/ai-team/progress-build.UqhoBm 執行 mvn -B package：
167 項中 164 項通過、3 項 opt-in smoke 略過，0 失敗／錯誤，BUILD SUCCESS。
新增 4 項心跳回歸測試；check-source 與 16 項平台 fixture 通過。
未部署、未修改 #1014 或發出 Slack 測試訊息；真實五分鐘更新仍待驗收。
文件 docs/slack-progress.md。#1014 額度辨識修正仍待另行實作。

## 2026-09-08 — Codex（#1014 額度回報診斷）

唯讀確認 #1014 在 2026-09-08 22:07 台北時間於 implementation 停止，狀態
AWAIT_HUMAN。implementation.stdout.json 明確為 is_error=true、api_error_status=429、
terminal_reason=api_error，result 為 You've hit your session limit · resets 6:30pm (UTC)。
QuotaExceeded.explicit 未涵蓋 session limit 措辭；ClaudeCodeRuntime.describeFailure
未包含 result，僅呈現 errors／permission_denials，故額度原因未帶入 Slack 回報。
implementation checkpoint 與 session ID 存在，工作區保留未提交程式及測試。
本輪僅診斷，未修改程式、部署或續跑任務；需要補辨識與原始錯誤呈現的回歸測試。

## 2026-09-08 — Codex（SMTP 任務重測清理）

依使用者要求清除 Slack SMTP 重測相關任務 #1010 與 #1012；保留 #1009
單純查詢紀錄與既有 Slack 訊息。Live DB 的 task、stage_result、stage_attempt、
task_checkpoint、task_remediation、audit_log、flow_run、direct_request 關聯均已清零；
任務流水號高水位保留 1013，避免舊 Slack 按鈕誤指新任務。

#1010 工作區、log 與兩任務共 8 個專屬 Claude／Codex session 已移至可復原備份
`/home/vboxuser/ai-team/cleanup-backups/smtp-1010-1012.nA9qiF`，內含清理前 SQLite
備份。其他任務資料雜湊與 DB integrity check 通過。服務以 PID 71666 重新啟動；
配置、沙箱與工具鏈自檢通過，主入口及五角色 Socket Mode 均已啟動。

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
