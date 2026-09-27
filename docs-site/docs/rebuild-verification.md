# 從零重建驗證紀錄

日期：2026-09-06。狀態：**進行中，不能宣稱乾淨 VM 重建成功**。

## 本次盤點

- 本機 VirtualBox 只登錄 `AI`，尚未建立演練 VM。
- 主機 RAM 約 32 GB、查詢時空閒約 12 GB；D 槽約 480 GB 空間，啟動前須重新查核。
- 已登錄 DVD 只有舊 VM unattended 輔助媒體與 Guest Additions，未取得 Ubuntu Server 安裝 ISO。
- 舊 VM 的 provision.sh／provision2.sh／provision3.sh 實際存在；已讀取，包含過時 Maven URL、
  npm jobs 錯誤與 rm -rf 覆蓋行為。不直接移植為新機自動化。
- 目前本機資料夾沒有 Git repository：文件存在不等於已提交或有遠端備份。本次不擅自初始化／推送。

## 六項可靠性診斷

| 原則 | 處理 |
|---|---|
| 來源真實性 | 找回舊腳本事實，保留歷史入口，現況另寫；未宣稱已入版控 |
| 精簡導覽 | 新增根目錄 AGENTS.md，指向唯一入口與功能契約 |
| 機械檢查 | check-source 驗證六階段檔案與五角色設定，bootstrap fixture 測試 |
| 可讀實作 | 保留 Java runtime 與安全閘門，不因文件盤點改產品行為 |
| 發布把關 | 演練使用獨立 App／頻道／repo，PR 必須人工核准 |
| 漂移管理 | 新增角色必須更新範本並跑 check-source；尚未接遠端 CI |

## 新增工具的邊界

provision：三個已核實 checksum 的官方封存檔安裝，不包含 OS 套件或 CLI 登入。
install-app：獨立暫存建置與測試，拒絕覆蓋新安裝目的地。
preflight／service／audit：本機檢查、限定 jar 路徑的啟停、唯讀 SQLite 查詢。
backup／restore：停機應用備份與新目錄還原，刻意排除 env.sh／CLI 憑證與 sessions。
check-source／test-bootstrap：結構漂移與 fixture 安全性測試，不是乾淨 VM 測試。

## 尚待完成的驗收

1. 確認 Linux 發行版／版本、ISO 路徑、來源與 checksum，建立獨立 VM；不限定 Ubuntu。
2. INSTALL 已記錄供應者核實的 Linux x86_64 工具鏈 URL／checksum；尚待在新 VM
   另存 `toolchain.tsv` 並完成冷下載驗證。
3. 新 OS 基礎套件／user namespace／user manager 前置條件實測。
4. 從官方來源下載、空 Maven/npm 快取建置；記錄完整版本與相依資料。
5. 使用者完成 Claude／Codex 登入，建立獨立測試 Slack App／頻道。
6. 真實完整管線、失敗硬閘門、Reviewer bwrap/.agents 與隔離測試 repo PR 核准驗證。
7. 新 VM 還原與 session 接續演練；規劃加密憑證備份，不能混入一般 source tar。

原有 68 項應用測試在前次 QA 部署通過，不等於以上項目完成。

### 2026-09-10 補註（保留上方 2026-09-06 原紀錄）

- 第 6 項的「Reviewer bwrap/.agents」已於 2026-09-09 修正並部署：派工前自動建立
  `.agents`／`.codex` 掛載點，含兩項實機雙層沙箱測試。其餘六項仍未完成。
- 應用測試已由 68 項成長為 237 項（10 項 opt-in 實機測試預設略過）。
  2026-09-13 新增 resume 與簡要回覆回歸後為 263 項，仍有 10 項 opt-in。
  數字請以當次建置 log 為準。
- 上述都是既有 VM 上的結果，**仍不是乾淨 VM 重建驗收**；本文狀態不變。

### 2026-09-13 文件一致性修正

- INSTALL 的來源打包清單原本漏掉 `orchestrator/teams/`，但 check-source 與 install-app
  都要求該目錄；已補入，並補上控制端／新 VM 的 SHA256 比對、解壓與來源檢查指令。
- INSTALL 已寫入 Node 22.21.1、Temurin 21.0.12.1+1、Maven 3.9.16 的固定 URL 與
  checksum 來源，避免讀者只能從不可執行的範例自行猜值。這仍須由新 VM 冷下載驗收。
- env 範本原稱 `AI_TEAM_CHANNEL_ID` 可留空，但 preflight 實際必填；文件與範本現統一為
  安裝時必須填專用測試頻道，未改 runtime 行為。
- INSTALL 已補上 Codex resume 選項位置與 `:read-only` 事故的防復發檢查，並更新
  opencode 的 Linux 狀態目錄為已驗證、沙箱端對端仍未驗證。
- `test-bootstrap.sh` 在現有 VM 的 SQLite 會因雙引號字串中斷；這是待修的腳本問題，
  本輪只修文件，不把它寫成已通過。

## 本次執行結果

- 本機 Git Bash：bootstrap 全部 Bash 語法、五角色 env／manifest 與六階段 prompt／schema 存在檢查通過。
- 本機 PowerShell：六份 JSON schema 均可解析。
- 本機負面測試：install-app 與 restore 拒絕覆蓋既有目錄。
- 本機缺少 sqlite3／flock，尚未執行完整 Linux 備份／還原 fixture 測試。
- 將不含憑證的 source 測試包傳到既有 VM，被自動權限審核拒絕：包含可能私有的原始碼、文件與提示詞，
  尚需使用者明確同意此資料傳至 `vboxuser@127.0.0.1:2222`。未繞過拒絕、未開始遠端測試。
- 本次未修改／重啟正式 VM 的服務，未新增 VM、未推送 PR。

## 跨發行版調整

新增 linux-platform／check-linux，把平台前置能力接到 provision、install-app 與 preflight；
安裝與應用備份一併包含檢查腳本。Debian／Ubuntu 與 Rocky／AlmaLinux 是優先驗證對象，
不是已通過的支援清單；見 [linux-support.md](linux-support.md)。
不執行套件管理器、不改 SELinux／AppArmor。遠端傳輸的審核阻礙仍未解除，未進行 VM 測試。

本機 Git Bash 已通過 16 項平台 fixture 斷言、bootstrap Bash 語法與來源結構檢查；
以實際 Windows 環境執行 check-linux tools 時正確拒絕。這些結果不代表任何新 Linux VM 已通過。

### 2026-09-15 自動交接計數修正驗證

- 既有 VM 的全新隔離目錄 `handoff-budget-build.q3hFuo` 完整 Maven package：289 項，
  0 失敗、10 項 opt-in 略過，BUILD SUCCESS。
- AutomaticHandoff／FlowEngine 針對測試 37 項全數通過；涵蓋舊制逐跳 key 合併、未知格式
  fail closed、額度延長綁定與防重放。
- `check-source.sh` 通過。這是程式與既有 VM 隔離建置驗證，仍不等於乾淨 VM 重建驗收。
- 正式部署 JAR SHA256 `310e578c…71dd`，PID 3420；沙箱／工具鏈自檢及五角色 Socket Mode
  啟動通過，部署前後主要 DB 表與 #1014 工作區雜湊一致，備份為
  `handoff-budget-backup.BmGdTK`。未自動續跑任務。
