# 當前工作（所有 AI 工具共用）

> **覆寫**這份檔案，只寫現在的狀態，上限 60 行（`docs-site` build 會檢查）。已完成的寫 `progress.md`，
> 決策寫 `decisions.md`。改寫時保留仍有效的限制、未解問題與下一步。舊版查 git 歷史或 `archive/context-2026-10-24.md`。

最後更新：2026-10-24（Claude Code）

## 線上狀態

- 部署紀錄以 `docs-site/docs/deploy-2026-09-06-v2.md` 為準；VM 連線見 `docs-site/docs/direct-ssh-deployment.md`。
- bestpartner：c3565f1，JAR `77010962…`，PID 307850（2026-10-24 08:44），已開啟 PR 衝突同步（2 輪＋自動推回）。
- spring-starters、dynamic-api：8269d27，JAR `5fc7db7b…`（2026-10-23 23:45／23:51），未開啟 PR 衝突同步。
- 三個實例都已啟用角色諮詢／共享看板、延後驗收；Codex 分支 `codex/direct-ssh-deployment` 已於 2026-10-23 併入 main，之後以 main 為準
  （`D:/agent_team-dynamicapi` 工作樹的 `.ai/` 只剩歷史紀錄）。

## 待處理

- **負責人**：在 GitHub 審核合併 bestpartner PR #6（#1055，已自動同步到 71bd7ad，與 dev 無衝突）。
- 以下一個小型任務對照 #1053～#1055 的耗時與停頓次數，確認效率修正（沿用前次結果、延後驗收、NO_PROXY、qaOnlyPaths）的實際效果。
- 其他實例要用 PR 衝突同步，需在配置開 `project.autonomy.pullRequestSyncRounds`／`pullRequestSyncAutoPush` 並部署。
- VM 上 `/home/agent/prsync-build.j5Flph` 可刪。
- 未確認：#1053、#1054 目前狀態（2026-10-22 記錄 #1054 待中止），動手前先唯讀查 DB。

## 已知未解

- 尚未通過乾淨 VM 重建驗收（見 `docs-site/docs/rebuild-verification.md`）。
- opencode runtime 在 VM 沙箱下的端對端執行未驗證。
- spring-starters 基準驗證有既有失敗 `MailServiceSmtpsScenariosTest`。
- dev VM 曾出現核心 RCU／watchdog 警告，根因未確認。
- 以下只有程式測試、沒有真實 Slack 端對端證據：「中斷這一輪」、「保存未提交的修改並承接」、「改用新任務繼續」、
  PR 內文 Markdown 條列、心跳訊息台北時間、帳號被拒的固定說明。
- `deploy/bootstrap/test-bootstrap.sh` 的 SQLite fixture 以雙引號寫字串值，在 VM 會中斷。
- docs-site `npm install` 回報弱點未處理。

## 本機環境備忘

- Windows 沒有 PATH 上的 mvn：`D:\tools\apache-maven-3.9.5\bin\mvn.cmd -B "-Dmaven.repo.local=D:\agent_team\.build-m2" test`；
  預期有 3～4 項 Windows 專屬失敗（symlink、`/bin/sh`、CRLF），完整驗證在 Linux 隔離目錄跑。
- 本機 `python` 是 Store 空殼，改檔用 Edit 或 node。
- Windows 打包來源用 `git -c core.autocrlf=false archive`，否則 `.sh` 變 CRLF、Linux check-source 失敗。
- `deploy/*.tgz` 是本機舊來源快照（gitignore）。
