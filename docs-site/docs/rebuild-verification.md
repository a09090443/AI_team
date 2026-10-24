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

- **2026-10-02 新 VM「Agent」演練（進行中）**：VirtualBox 新 VM、全新安裝 Ubuntu 26.04 LTS（kernel 7.0.0-38、x86_64、
  8 vCPU／8 GB／120 GB），使用者 `agent`，NAT SSH `127.0.0.1:2223`，主機 ED25519 指紋
  `SHA256:Na8oFwKGmS+0EYofwPoxaOTFpIGBONvnTnDV0IW76sU`（已與主控台開機畫面核對）。用途為乾淨重建驗收演練，既有 VM `AI` 不動。
  - 安裝 ISO 沒有 openssh-server 與 sqlite3；由使用者本人輸入 sudo 密碼，以 apt 補齊
    openssh-server、git、curl、jq、sqlite3、tar、xz-utils、ca-certificates、util-linux、iproute2、procps。
  - `unshare -Ur` 被 AppArmor（`kernel.apparmor_restrict_unprivileged_userns=1`）擋下，check-linux BLOCKED；
    使用者決定比照舊 VM 放寬，寫入 `/etc/sysctl.d/60-ai-team-userns.conf` 為 0 後 check-linux runtime 通過。
  - 來源包發現 INSTALL 舊打包指令的兩個問題（Windows CRLF 使 `set -o pipefail` 失敗、工作區 tar 會帶入 gitignore 的
    `deploy/*.tgz`），改以 `git -c core.autocrlf=false archive` 打包 commit `f077fe3`；兩端 SHA256
    `62ae8c23c43caffb86ce26c101c462a2b4075bd3173a7745d56e58071c7277a0` 一致，check-source PASS。
  - 空快取（無 `~/.m2`、`~/.npm`、`~/.local`）執行 provision：三個封存檔 checksum OK，Node 22.21.1、Temurin 21.0.12.1、
    Maven 3.9.16，31 秒。npm 安裝 Claude Code 2.1.282、Codex CLI 0.156.1（registry 均有該版本）。
  - install-app 冷快取建置：458 項、0 失敗、10 項 opt-in 略過，Maven 1 分 12 秒；JAR SHA256
    `ea74a4a3a8b3a62315ad89f8fb6954bb38eed77bcfe04e09c0bfa55982b29e4e`。
  - 目標 repo 完整 clone（非 shallow），`origin/develop` = `9972e456fcfbb8cdbf4596dba4e9180f9df382ae`。
  - 目標 repo 冷快取 `mvn -B verify`：第一次在下載 `batik-gvt:1.19` 時遇 Maven Central `Connection reset`（暫時性網路錯誤，
    紀錄 `repo-verify-attempt1.log`）；以 `mvn -B -U verify` 重跑 BUILD SUCCESS，8 個模組、303 項測試 0 失敗，12 分 59 秒。
  - 依使用者要求在 `~/.bashrc` 最前面加入工具鏈 PATH 區塊（原檔備份 `~/.bashrc.bak-ai-team`），非互動式 SSH 可直接找到
    claude／codex／java／mvn／node。
  - 證據保存在 VM 的 `~/rebuild-evidence/`（provision、cli-install、install-app、repo-verify 紀錄）。
  - Slack：依使用者決定**沿用既有的 6 個 App**（未另建測試 App），新建公開測試頻道並邀請 6 個 App；
    白名單填使用者 member ID。12 個 token 由使用者本人執行指令，從舊 VM env.sh 經 stdin 匯入新 VM
    （`~/import-slack-tokens.sh`，只回報已填／空白，不輸出值）。
  - 使用者本人完成 Claude（claude.ai 訂閱）與 Codex（ChatGPT）登入；preflight PASS（團隊配置靜態驗證通過）。
  - 因共用 Slack App，先停舊 VM `AI` 的服務並 ACPI 關機（停前確認無執行中／排隊任務；舊 VM 原為 saved state，
    headless 開機時正式服務隨之恢復並曾重新連上 Slack）。
  - 新 VM `service.sh start`：0 ERROR，啟動 7.9 秒；沙箱越界寫入自檢、工具鏈自檢（java、mvn、node、npm、git、tar、mkdir）通過；
    五角色獨立身分、主 App 與五角色 Socket Mode 皆啟動，ESTAB:443 屬於本服務 PID。
  - 尚未完成：Slack 事件實測（詢問任務、角色直接提問）、完整管線、負面案例、隔離 repo PR、備份還原。
    因沿用正式 App，Slack 層不符合「獨立測試 App」要求，驗收紀錄須註明。

- **2026-09-15**：既有 VM 全新隔離目錄完整 Maven package 289 項、0 失敗、10 項 opt-in 略過；
  AutomaticHandoff／FlowEngine 針對測試 37 項通過；check-source 通過。正式部署後自檢與五角色 Socket Mode 啟動通過，
  部署前後主要 DB 表與既有任務工作區雜湊一致。
- **2026-09-13 文件一致性修正**：INSTALL 補入原本漏掉的 `orchestrator/teams/` 打包、兩端 SHA256 比對、
  固定工具鏈 URL／checksum；env 範本與 preflight 統一 `AI_TEAM_CHANNEL_ID` 為必填；補上 Codex resume 選項位置的防復發檢查。
  應用測試 263 項（10 項 opt-in）。
- **2026-09-10**：應用測試 237 項（10 項 opt-in 實機測試預設略過）。
- **2026-09-06 盤點**：本機 VirtualBox 只登錄既有 VM `AI`，尚無演練 VM 與 Ubuntu Server 安裝 ISO。
  本機 Git Bash 通過 bootstrap Bash 語法、五角色 env／manifest 與階段 prompt／schema 存在檢查、16 項平台 fixture；
  install-app 與 restore 拒絕覆蓋既有目錄；check-linux tools 在 Windows 正確拒絕。
  本機缺 sqlite3／flock，未跑完整 Linux 備份／還原 fixture。傳 source 測試包到 VM 被權限審核拒絕，未繞過、未做 VM 測試。

測試數字請以當次建置 log 為準，不要沿用本文數字。
