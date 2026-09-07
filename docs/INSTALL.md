# AI Team 從零建置唯一入口

配置式團隊（2026-09-06）：安裝包含 `teams/`；要切換專案或增刪角色，請依
[配置式團隊指南](configurable-team.md) 設定 `AI_TEAM_DEFINITION`，先靜態驗證再啟動。
留空仍使用以下 legacy 五角色配置；新模式不需要沿用固定角色清單。

更新：2026-09-06。**目前尚未通過乾淨 VM 重建驗收**；進度、缺口與證據見 [rebuild-verification.md](rebuild-verification.md)。
不是一鍵安裝：安裝媒體、官方工具鏈 checksum、系統套件與本人登入仍需先備妥。
不得把舊 VM clone、暖快取建置或 mock 測試冒充從零驗證。

## 1. 獨立 VM 與系統前置條件

既有 VM `AI` 與 SSH 2222 保持不動。演練使用新 VM、新磁碟、新 OS 安裝；名稱與 ISO 路徑須先確認。
建議演練配置 4 vCPU／8 GB／40 GB，啟動前確認主機有餘裕。NAT SSH 綁 127.0.0.1:2223，避免衝突。
Linux x86_64／glibc／systemd 是目前建置配置；優先驗證 Debian／Ubuntu 與 Rocky／AlmaLinux，
不限定 Ubuntu。各版本的實測狀態見 [linux-support.md](linux-support.md)。
需由管理員備妥 OpenSSH server、git、curl、jq、SQLite CLI、tar/xz、CA 憑證、
GNU 基礎工具、程序／網路工具、flock／unshare、systemd 與可用的 user bus。
套件名稱與安全政策依發行版／版本處理，不假設安裝 ISO 已內建；腳本不自動 sudo／apt／dnf。
解壓 source 後先執行 `bash deploy/bootstrap/check-linux.sh runtime`，缺能力則明確停止。

安裝 SSH 公鑰；使用既有公鑰，不覆蓋控制端既有私鑰。核對新主機指紋後測試：

```powershell
ssh -o BatchMode=yes -p 2223 vboxuser@127.0.0.1 'id; uname -m'
```

原始碼從本機打包（不帶 env.sh、DB、target 或 CLI 登入資料）：

```powershell
tar -cf ai-team-source.tar AGENTS.md CLAUDE.md .ai deploy docs orchestrator/pom.xml orchestrator/src orchestrator/prompts orchestrator/schemas
scp -P 2223 ai-team-source.tar vboxuser@127.0.0.1:/home/vboxuser/
```

新 VM 內解壓到新建的 `~/ai-team-source`，保留 tar 的 SHA256 作為演練來源證據。

## 2. 工具鏈（使用者層級）

`deploy/bootstrap/provision.sh` 需要先核實的固定版本 HTTPS URL 與 SHA256；格式見
`deploy/bootstrap/toolchain.tsv.example`。**範例刻意不能直接執行，尚未封存正式工具鏈 lock。**
從供應者的官方 release/checksum 核實後填入三列 node／java／maven，避免 latest 漂移。
歷史相容基準：Node 22.21.1、Temurin JDK 21、Maven 3.9.16；不表示目前仍可從 CDN 下載。

```bash
bash ~/ai-team-source/deploy/bootstrap/provision.sh ~/toolchain.tsv
export JAVA_HOME="$HOME/.local/java"
export PATH="$HOME/.local/node/bin:$JAVA_HOME/bin:$HOME/.local/maven/bin:$HOME/.local/bin:$PATH"
```

腳本校驗 checksum，對已有但非本工具管理的目錄拒絕覆蓋；失敗保留暫存。
它不裝 OS 套件、不設定登入、不改 .bashrc、不啟動服務。啟動腳本會自行設定 PATH。

## 3. CLI 與登入

歷史測試版本是 Claude Code 2.1.252、Codex CLI 0.153.4。先確認 registry 有該版本；若無，停止並重新驗證可用版本的契約，不靜默換 latest。

```bash
export MAKEFLAGS=-j1 JOBS=1
unset npm_config_jobs
npm install --global --prefix "$HOME/.local/node" @anthropic-ai/claude-code@2.1.252 @openai/codex@0.153.4
claude --version
codex --version
```

這仍不是完整 npm 相依鎖檔，正式可重建驗收須保存安裝紀錄與依賴完整性資料。
本人完成 Claude 訂閱登入；不設 ANTHROPIC_API_KEY、不用 --bare。
Codex headless 可用 `codex login --device-auth`，需帳號／workspace 允許；不支援時可採官方 SSH callback 轉發。
以 `codex login status` 檢查登入；不要輸出 auth.json。見 [官方認證文件](https://learn.chatgpt.com/docs/auth)。

## 4. 沙箱前置條件

```bash
systemctl --user show-environment >/dev/null
unshare -Ur true
```

各發行版的核心、安全政策或登入設定都可能限制必要能力；由管理員依實際錯誤查核，**不自動放寬整台 VM 的安全政策**。
舊 VM 曾以 sysctl 放寬，原因與風險見 [sandbox-report.md](sandbox-report.md)。
指令成功仍不能證明隔離有效：必須通過 orchestrator 的實際越界寫入自檢與工具鏈自檢。
本工具尚未設定開機自動啟動；登出／重開機後要再次驗證 user manager 與服務狀態。

## 5. 安裝應用與目標 repo

```bash
bash ~/ai-team-source/deploy/bootstrap/check-source.sh
bash ~/ai-team-source/deploy/bootstrap/install-app.sh ~/ai-team-source ~/ai-team
git clone --no-single-branch https://github.com/a09090443/zipe-spring-boot-starters.git ~/ai-team/zipe-spring-boot-starters
git -C ~/ai-team/zipe-spring-boot-starters rev-parse --is-shallow-repository
git -C ~/ai-team/zipe-spring-boot-starters rev-parse origin/develop
```

安裝器在獨立暫存目錄建置並跑完整測試，失敗不安裝；成功不啟動、不覆蓋已有目的地。
repo 不可 shallow；基準分支在 env.sh 指定 develop。演練記錄基準 SHA。
新 VM 自行下載 Maven 相依並執行 repo verify，記錄冷快取耗時，不複製舊 ~/.m2。

## 6. Slack／設定

主 App 使用 `deploy/slack-app-manifest.yaml`，五角色使用 `deploy/agent-manifests/`；
建立／安裝／加入頻道見 [slack-agent-apps.md](slack-agent-apps.md)。QA 兩階段共用 QA 身分。
演練應使用獨立主 App 與測試頻道，避免與正式服務競爭 Socket Mode 事件。

編輯 `~/ai-team/env.sh`（600），填 app token、主 bot、五角色 token、白名單、測試頻道。
確認 AI_TEAM_HOME／REPO／LOGS／WORKSPACES、CLI 路徑與模型設定；修改後必須重啟才生效。

每個角色需要**兩個** token，缺一個功能就少一半：

| token | 格式 | 變數 | 沒有它會怎樣 |
|---|---|---|---|
| Bot Token | `xoxb-` | `SLACK_BOT_TOKEN_<角色>` | 角色發言退回主 App 名稱覆寫，交接 mention tag 不到人 |
| App-Level Token | `xapp-` | `SLACK_APP_TOKEN_<角色>` | 直接 @ 該角色不會有反應（其餘功能不受影響） |

App-Level Token 的 scope 必須是 `connections:write`，且該 App 要開啟 Socket Mode、
訂閱 `app_mention`、具備 `app_mentions:read`。設定步驟見
[直接對角色提出請求](direct-agent-requests.md)。

`CODEX_BIN` 指向 codex 執行檔；只有在有角色的 runtime 設為 codex 時才會被要求
（Reviewer 預設就是），設定值打錯會在啟動時直接失敗，不會安靜退回 claude-code。
Reviewer 預設 codex、AI_TEAM_CODE_REVIEW=on。不要關掉審查來取得綠燈。
前期演練 GitHub token 留空；推送另以測試 repo 與明確人工核准驗證。

## 7. 前置檢查、啟停、查詢

```bash
bash ~/ai-team/preflight.sh
bash ~/ai-team/service.sh start
bash ~/ai-team/service.sh status
bash ~/ai-team/audit.sh list
bash ~/ai-team/audit.sh cost
# 無執行中任務時才可停止
bash ~/ai-team/service.sh stop
```

preflight 本機通過不代表登入或 Slack 成功。核對本次啟動 log、自檢與屬於該 JVM 的 ESTAB:443。
服務管理拒絕已知的執行中／排隊任務，但不是完整的競態免疫 drain 協定；維護時先停止提交新需求。

## 8. 分層驗收（不能略過失敗）

| 層級 | 必須保存的證據 |
|---|---|
| 基礎 | 新 OS／空快取證據、安裝媒體／工具鏈 checksum、版本與下載 log |
| 應用 | check-source、完整 Maven 測試與 jar SHA256 |
| Runtime | Claude／Codex 結構化輸出、resume、外部沙箱實測；CLI 版本一致 |
| Slack | 詢問任務正常結束；白名單／去重；五角色正確身分 |
| 完整管線 | PM→可選 Architecture→QA 規劃→Backend→QA 驗收→Reviewer→人工閘門 |
| 負面案例 | 漏測／無法核實／工具失敗必須向上回報且不能發布；用量暫停續跑 |
| 發布 | 只在隔離測試 repo、人工核准後測試推分支與開 PR |
| 還原 | 停機備份→新目錄還原→DB integrity_check；登入／session 另驗證 |

已知 #1010 Reviewer bwrap/.agents 問題未修；如果重現，必須記錄為阻擋，不能宣稱全管線成功。
不用刻意耗光訂閱驗證 quota；先用模擬錯誤測試狀態機，標明未做真實耗盡測試。

## 9. 備份／還原與憑證

停機後 `bash ~/ai-team/backup.sh ~/ai-team-backup-日期`；還原到不存在的新目錄：
`bash ~/ai-team/restore.sh ~/ai-team-backup-日期 ~/ai-team-restored`。
腳本保存 jar、外部契約、工作區、logs 與一致性 SQLite，含 checksum；不自動啟動。
備份可能有私有程式碼／需求／log，權限 700 仍非加密，需另外受保護儲存。
env.sh、CLI 憑證／sessions、基準 repo、工具鏈不包含於此備份；完整災難復原須另外加密保管與驗證。
沒有 CLI session 不能保證原對話續跑；不可把「應用備份」寫成完整 VM 還原保證。

## 文件優先序

本入口與 rebuild-verification 為現況；功能契約見 QA／quota／failure／review 專章。
跨發行版能力與支援狀態以 linux-support 為準，ARM64 與非 systemd 配置目前不放行。
vm-setup-manual、build-and-run 與 INSTALL-20260904-history 是歷史背景，不能混搭舊指令。
