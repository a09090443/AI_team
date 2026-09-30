# 當前工作（Codex 與 Claude Code 共用）

> 換手前務必更新這份檔案。內容只寫「現在正在做什麼、卡在哪、下一步」，
> 長期結論寫 `decisions.md`，已完成的寫 `progress.md`。舊內容查 git 歷史，不要在這裡累積流水帳。

最後更新：2026-09-30（Claude Code）

## 正在進行

- **受保護路徑事先告知（commit 0ac9f20，已部署，未 push）**：細節見 progress.md 2026-09-30。真實端對端未實測。
- VM 上 Claude Code OAuth 曾於 2026-09-30 13:06 UTC 過期（換新失敗，expiresAt 被清為 0）；
  使用者本人 13:14 UTC 重新登入，`claude auth status` loggedIn=true、實際 `claude -p` 呼叫成功。

## 線上服務（既有 VM，`ssh -p 2222 vboxuser@127.0.0.1`）

- 最後一次部署：2026-09-30 21:09 台北時間（受保護路徑事先告知，commit 0ac9f20），PID 467207，
  JAR SHA256 `604fc19fac7373088754c923cd494fc6c03dec115d1094c7d35dc5f05c7fe90b`，備份 `orchestrator-backup.Xqkhwu`
  （前一版 `340121cb…`）。隔離建置 `protected-path-build.AwyMOC`。
- 配置式模式（`team.env` → `teams/spring-starters.json`），builder=opus、tester=codex gpt-5.6-sol medium。
- CLI：Claude Code 2.1.282、Codex 0.156.1。部署用 `deploy/deploy-jar-safe.sh`（只換 JAR）或 `deploy/deploy-orchestrator.sh`；
  兩者都要求沒有執行中／排隊的任務。

## 任務現況

- #1014 → #1016 → #1017 → #1019 為同一需求（zipe-spring-boot-starters 的 SMTP 容錯與 smtps）的承接鏈；前三者已結束或不再接受操作。
- #1019：2026-09-26 13:01 停下（審查第 9 次退回，舊快照仍用舊審查規則）。當時交給使用者的下一步是在原討論串送
  `補充 #1019 狀態` 後按「改用新任務繼續」，讓新任務套用新的審查收斂規則。之後是否已操作未記錄，**先唯讀查 DB 再行動**。

- #1021（升級 Spring Boot）：2026-09-29 23:35 起 AWAIT_HUMAN。實作 3 個 commit 在 `feature/ai-team-task-1021`，
  只剩目標專案 `.claude/rules/project-overview.md` 第 28 行（3.5.x→4.0.8）是受保護路徑、Agent 改不了。
  triage 的「已開放權限，重派修改」選項無效；需真人手動改並提交後按「已手動改好，開始驗收」，或改需求排除。
  09-30 13:06 UTC 負責人按了「已開放權限，重派修改」，但分流因 OAuth 過期直接失敗、回到 AWAIT_HUMAN（audit 1007–1011），
  實作沒有被重派。
  09-30 依使用者要求由 Claude Code 在任務工作區手動修改第 28 行為 4.0.8（註明以根 pom 為準）並提交 fe1795b
  （作者 Gary；AC-DOC-05-2 要求寫出確切 4.0.N，所以保留版號）。已在 fe1795b 的獨立 clone 照驗收操作離線預演：`mvn -B -o verify` BUILD SUCCESS（~/.m2 已有 4.0.8），
  文件 `npm ci --offline`、build、llms 兩份比對結束碼皆 0；暫存目錄已刪。這是預演，不是正式驗收。
  下一步：使用者在 Slack 按「已手動改好，開始驗收」。
  另待確認驗收端離線 Maven 快取是否有 4.0.8。

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
