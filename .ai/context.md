# 當前工作（Codex 與 Claude Code 共用）

> 換手前務必更新這份檔案。內容只寫「現在正在做什麼、卡在哪、下一步」，
> 長期結論寫 `decisions.md`，已完成的寫 `progress.md`。舊內容查 git 歷史，不要在這裡累積流水帳。

最後更新：2026-09-27（Claude Code）

## 正在進行

- **文件清理（未 commit）**：依使用者要求移除沒有用的文件內容——刪除歷史文件與一次性部署腳本、
  精簡現行文件、壓縮 `.ai/progress.md`。細節見 progress.md 2026-09-27 第一則。
  docs-site build 與 check-source 已通過，待使用者確認後 commit。

## 線上服務（既有 VM，`ssh -p 2222 vboxuser@127.0.0.1`）

- 最後一次部署：2026-09-26 22:42 台北時間（PR 驗收紀錄改 Markdown 條列），PID 450271，
  JAR SHA256 `340121cbaab206a9ccd417816e4b47567e066ce057d580682b6d4008b881e583`，備份 `orchestrator-backup.0MXfNx`。
  之後 repo 只有文件與 AI 工具設定的變更，沒有需要部署的程式行為變更。
- 配置式模式（`team.env` → `teams/spring-starters.json`），builder=opus、tester=codex gpt-5.6-sol medium。
- CLI：Claude Code 2.1.282、Codex 0.156.1。部署用 `deploy/deploy-jar-safe.sh`（只換 JAR）或 `deploy/deploy-orchestrator.sh`；
  兩者都要求沒有執行中／排隊的任務。

## 任務現況

- #1014 → #1016 → #1017 → #1019 為同一需求（zipe-spring-boot-starters 的 SMTP 容錯與 smtps）的承接鏈；前三者已結束或不再接受操作。
- #1019：2026-09-26 13:01 停下（審查第 9 次退回，舊快照仍用舊審查規則）。當時交給使用者的下一步是在原討論串送
  `補充 #1019 狀態` 後按「改用新任務繼續」，讓新任務套用新的審查收斂規則。之後是否已操作未記錄，**先唯讀查 DB 再行動**。

## 尚未實測的功能（Slack 真實操作）

「中斷這一輪」、「保存未提交的修改並承接」、「改用新任務繼續」、PR 內文 Markdown 條列、心跳訊息的台北時間。
程式測試已通過，但沒有真實 Slack 端對端證據，不可寫成已驗收。

## 已知未解

- 尚未通過乾淨 VM 重建驗收（見 `docs-site/docs/rebuild-verification.md` 的待辦清單）。
- opencode runtime 在 VM 沙箱下的端對端執行未驗證。
- `deploy/bootstrap/test-bootstrap.sh` 的 SQLite fixture 以雙引號寫字串值，在 VM 會中斷。
- docs-site `npm install` 回報 21 筆弱點（20 moderate、1 high），未處理。

## 本機環境備忘

- Windows 本機沒有 PATH 上的 mvn；可用 `D:\tools\apache-maven-3.9.5\bin\mvn.cmd -B "-Dmaven.repo.local=D:\agent_team\.build-m2" test`。
  預期有 4 項 Windows 專屬失敗（`/bin/sh` 不存在 2、symlink 權限 1、git CRLF 警告混入輸出 1），Linux 上要另外驗證。
- `deploy/*.tgz` 是本機舊來源快照（gitignore），未納入版控。
