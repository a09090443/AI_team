# 從零重建驗證紀錄

狀態：**進行中，不能宣稱乾淨 VM 重建成功**。目前所有通過紀錄都來自既有 VM 或本機，不是乾淨 VM 重建驗收。

## 尚待完成的驗收

1. 確認 Linux 發行版／版本、ISO 路徑、來源與 checksum，建立獨立 VM；不限定 Ubuntu。
2. INSTALL 已記錄供應者核實的 Linux x86_64 工具鏈 URL／checksum；尚待在新 VM
   另存 `toolchain.tsv` 並完成冷下載驗證。
3. 新 OS 基礎套件／user namespace／user manager 前置條件實測。
4. 從官方來源下載、空 Maven/npm 快取建置；記錄完整版本與相依資料。
5. 使用者完成 Claude／Codex 登入，建立獨立測試 Slack App／頻道。
6. 真實完整管線、失敗硬閘門與隔離測試 repo PR 核准驗證。
   （原列的 Reviewer bwrap/.agents 問題已於 2026-09-09 修正，見 [Codex 雙層沙箱](codex-nested-sandbox.md)。）
7. 新 VM 還原與 session 接續演練；規劃加密憑證備份，不能混入一般 source tar。

已知待修：`deploy/bootstrap/test-bootstrap.sh` 的 SQLite fixture 以雙引號寫字串值，在既有 VM 的 sqlite3 會中斷。

## bootstrap 工具的邊界

| 工具 | 做什麼 | 不做什麼 |
|---|---|---|
| provision | 以三個已核實 checksum 的官方封存檔安裝工具鏈 | OS 套件、CLI 登入 |
| install-app | 獨立暫存目錄建置與測試 | 覆蓋已有安裝目的地 |
| preflight／service／audit | 本機檢查、限定 jar 路徑的啟停、唯讀 SQLite 查詢 | — |
| backup／restore | 停機應用備份與新目錄還原 | env.sh、CLI 憑證與 sessions |
| check-linux／linux-platform | 平台前置能力檢查（見 [Linux 支援](linux-support.md)） | 執行套件管理器、改 SELinux／AppArmor／sysctl |
| check-source／test-bootstrap | 結構漂移與 fixture 安全性測試 | 乾淨 VM 測試 |

舊 VM 的 provision.sh／provision2.sh／provision3.sh 含過時 Maven URL、npm jobs 錯誤與 `rm -rf` 覆蓋行為，不移植為新機自動化。

## 歷史證據（由新到舊）

- **2026-09-15**：既有 VM 全新隔離目錄完整 Maven package 289 項、0 失敗、10 項 opt-in 略過；
  AutomaticHandoff／FlowEngine 針對測試 37 項通過；check-source 通過。正式部署後自檢與五角色 Socket Mode 啟動通過，
  部署前後主要 DB 表與 #1014 工作區雜湊一致。
- **2026-09-13 文件一致性修正**：INSTALL 補入原本漏掉的 `orchestrator/teams/` 打包、兩端 SHA256 比對、
  固定工具鏈 URL／checksum；env 範本與 preflight 統一 `AI_TEAM_CHANNEL_ID` 為必填；補上 Codex resume 選項位置的防復發檢查。
  應用測試 263 項（10 項 opt-in）。
- **2026-09-10**：應用測試 237 項（10 項 opt-in 實機測試預設略過）。
- **2026-09-06 盤點**：本機 VirtualBox 只登錄既有 VM `AI`，尚無演練 VM 與 Ubuntu Server 安裝 ISO。
  本機 Git Bash 通過 bootstrap Bash 語法、五角色 env／manifest 與階段 prompt／schema 存在檢查、16 項平台 fixture；
  install-app 與 restore 拒絕覆蓋既有目錄；check-linux tools 在 Windows 正確拒絕。
  本機缺 sqlite3／flock，未跑完整 Linux 備份／還原 fixture。傳 source 測試包到 VM 被權限審核拒絕，未繞過、未做 VM 測試。

測試數字請以當次建置 log 為準，不要沿用本文數字。
