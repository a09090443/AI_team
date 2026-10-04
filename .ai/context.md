# 當前工作（Codex 與 Claude Code 共用）

> 換手前務必更新這份檔案。內容只寫「現在正在做什麼、卡在哪、下一步」，
> 長期結論寫 `decisions.md`，已完成的寫 `progress.md`。舊內容查 git 歷史，不要在這裡累積流水帳。

最後更新：2026-09-30（Claude Code）

## 正在進行

- **新 VM「Agent」乾淨重建演練（2026-10-02 起）**：`ssh -p 2223 agent@127.0.0.1`，Ubuntu 26.04。
  **服務已在新 VM 運作**（2026-10-03，沿用正式 6 個 Slack App，頻道 `#ai-agents` C0C6ECHH44S），自檢全過；細節見 progress.md 2026-10-02。
  **舊 VM `AI` 服務已停並關機**（兩邊共用 Slack App，不可同時啟動）。舊 VM 上的任務（#1021、#1019 等 AWAIT_HUMAN，
  #1020、#1027 PR_CREATED）資料仍在舊 VM，新 VM DB 為空；要處理舊任務需停新服務、開舊 VM。
  Slack 事件已實測（#1001、#1002 完成）。2026-10-03 已部署多實例支援（`AI_TEAM_INSTANCE=starters`，現行 PID 見下方 bestpartner 段落）。
- **第二個實例 bestpartner（2026-10-03 啟動）**：`~/ai-team-bestpartner`，`AI_TEAM_INSTANCE=bestpartner`，
  配置 `teams/bestpartner.json`（commit 09b82bb），repo `bestpartner` master `ae4d98a`，頻道 `#bestpartner`（C0C6JKF1268），
  主 App `bp-ai-team`（A0C6END9UHG）；2026-10-03 升級 Slack Pro 後改用自己的 5 個 BP 角色 App（Bot＋App-Level Token），
  直接 @BP 角色已實測可用。starters 角色 App 已移出 #bestpartner。
  token 由使用者本人以 `~/import-slack-tokens.sh`、`~/set-slack-tokens.sh` 填入。
  2026-10-03 兩個實例都已部署 350b02e（JAR `ba3d6dcd…`，含「開成新需求」按鈕）：bestpartner PID 60161、starters PID 63293。
  之後部署 intake 提示詞 f251bf0 並重啟：bestpartner PID 66614（配置 5251bdbd…）、starters PID 69293（配置 238a05b5…）。
  2026-10-03 部署 intake 提示詞 6d46677 時 VM 失去回應（journal 停在 13:30:02 UTC，無 OOM 紀錄；先前有 rcu_preempt
  拿不到 CPU 的 stall 警告，疑主機端排程／休眠，未證實）。使用者重開 VM 後兩服務皆 STOPPED、無未結束任務、提示詞仍舊版；
  補完部署（舊檔備份 `intake.md.bak-before-6d46677`）：bestpartner PID 3042（配置 ea77edd3…）、starters PID 4855（配置 cfe287b5…），
  皆 0 ERROR、自檢與 5 角色連線正常。VM 無 swap（7.4 GB RAM）。
  之後部署 784ba9b（系統身分不覆寫名稱，JAR `7c0db7d4…`）：starters PID 10315、bestpartner PID 13678。
  再部署 1f5803e（Claude 解禁時間顯示台北時間，JAR `87c31cfd…`）：bestpartner PID 18693、starters PID 21854。
  再部署 5ff0155（內建 inspect-content＋三份提示詞）：bestpartner PID 26858（配置 045533c0…）、starters PID 30031（配置 7ed70287…）。
  再部署 68548aa（按鈕＋輸入視窗）、a0d84ef（沙箱路徑含空白加引號）、27dd80d（純文件略過建置測試，bestpartner 啟用）：
  目前 bestpartner PID 53254（配置 dafe84b2…）、starters PID 56461（JAR `5827e12e…`）。
  下一步：#1012（承接 #1011，計畫已以 inspect-content 通過、實作完成）在獨立驗證停下，使用者按「繼續執行」重跑；
  Slack 輸入視窗（modal）尚未真實實測。
  回滾注意：有任務寫入 `spinoffs`／`spinoffOf` 後，舊 JAR 讀不了這些任務（見 data-model）。
  下一步：Slack 實測「開成新需求」按鈕與同串訊息新說明；「中斷這一輪」真實操作；兩個實例的完整管線驗收。

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

## 2026-10-04 中斷點（Claude Code，用量上限）

- 受保護路徑改派（計畫已核准）：程式、bestpartner.json（`builder_codex`＋實作節點 `protectedPathFallback`）、測試（本機 512 項，
  僅既有 4 項 Windows 失敗）、VM 雙層沙箱實測（不呼叫模型）、multi-runtime／data-model／project-tree 文件已完成並 commit。
- **未做**：configurable-team／configuration 文件補欄位說明、decisions 追加、VM 隔離建置與全部測試、`--validate-team`、
  備份並更新 bestpartner.json、`deploy-jar-safe.sh` 部署兩個實例、#1014 按「改用新任務繼續」實測。
- 2026-10-04 部署 5026aa8 時 VM 又失去回應（SSH banner 逾時，與 10-03 13:30 UTC 同症狀）；原始碼上傳未完成，兩實例未變動。
  VM 恢復後：先唯讀確認服務與任務，再隔離建置／測試／--validate-team、備份並更新 bestpartner.json、deploy-jar-safe 兩實例。
