# AI Team 從零建置唯一入口

配置式團隊（2026-09-06）：安裝包含 `teams/`；要切換專案或增刪角色，請依
[配置式團隊指南](configurable-team.md) 設定 `AI_TEAM_DEFINITION`，先靜態驗證再啟動。
留空仍使用以下 legacy 五角色配置；新模式不需要沿用固定角色清單。

更新：2026-09-10。**目前尚未通過乾淨 VM 重建驗收**；進度、缺口與證據見 [rebuild-verification.md](rebuild-verification.md)。
不是一鍵安裝：安裝媒體、官方工具鏈 checksum、系統套件與本人登入仍需先備妥。
不得把舊 VM clone、暖快取建置或 mock 測試冒充從零驗證。

2026-09-07 之後併入本文的變更：額度自動續跑、Slack 階段進度回報、真人測試計畫核准閘門、
能力配置遷移與一次性受控恢復、Codex 雙層沙箱掛載點準備、本機測試網路能力、
Codex CLI 參數契約修正。這些會直接改變新機驗收會遇到的畫面與指令，
特別是 [§6.1 真人核准閘門](#61-真人核准閘門必讀否則全管線驗收會停住)。

## 1. 獨立 VM 與系統前置條件

既有 VM `AI` 與 SSH 2222 保持不動。演練使用新 VM、新磁碟、新 OS 安裝；名稱與 ISO 路徑須先確認。
建議演練配置 4 vCPU／8 GB／40 GB，啟動前確認主機有餘裕。NAT SSH 綁 127.0.0.1:2223，避免衝突。
Linux x86_64／glibc／systemd 是目前建置配置；優先驗證 Debian／Ubuntu 與 Rocky／AlmaLinux，
不限定 Ubuntu。各版本的實測狀態見 [linux-support.md](linux-support.md)。
需由管理員備妥 OpenSSH server、git、curl、jq、SQLite CLI、tar/xz、CA 憑證、
GNU 基礎工具、程序／網路工具、flock／unshare、systemd 與可用的 user bus。
`tar` 不只是解壓來源用：它已列入團隊配置的 `requiredTools`
（目前為 `java, mvn, node, npm, git, tar`），啟動時的沙箱工具鏈自檢會逐一檢查，
缺任何一項服務會直接啟動失敗，不會安靜略過。
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

Codex 有三個實測過的前置條件會讓 QA 整段跑不起來，換 CLI 版本時要重新確認
（細節與證據見 [codex-nested-sandbox.md](codex-nested-sandbox.md)、
[local-test-capabilities.md](local-test-capabilities.md)、
[codex-cli-argument-contract.md](codex-cli-argument-contract.md)）：

- **內層 bwrap 掛載點**：工作區缺 `.agents`／`.codex` 時，內層沙箱會在執行任何指令前
  以 `Can't mkdir …/.agents: Read-only file system` 失敗。程式已於派工前自動建立空目錄，
  不需手動處理；但若看到這個錯誤，代表跑的是舊版，不要改用放寬沙箱的方式繞過。
- **`codex sandbox` 與 `codex exec` 參數不對稱**：`-P/--permission-profile` 是
  `codex sandbox` 的必填參數，`codex exec` 完全不接受（結束碼 2）；exec 端要用
  `-c default_permissions="<profile>"`。程式已分成兩套參數並有零模型呼叫的契約測試，
  升級 CLI 後請跑 `AI_TEAM_CODEX_SANDBOX_SMOKE=true` 的那組測試再上線。
- **本機測試網路只支援 Codex**：`localTestNetwork` 節點必須是 codex runtime、
  LIMITED 範圍、VERIFICATION 閘門且宣告 node 工具，否則配置載入就會被拒絕。
  把 QA 角色改成 claude-code 或 opencode 時必須一併關閉此能力，代價是需要本機 socket 的
  情境測試（例如 GreenMail SMTP）會再度受阻。

### 3.1 opencode（選用的第三個 runtime，安裝前先讀完本節）

**沒有任何角色指定 `opencode` 時，整段跳過。** 不裝不影響啟動，也不影響既有部署。

```bash
npm install --global --prefix "$HOME/.local/node" opencode-ai@1.18.19
opencode --version
opencode providers        # 別名 auth；本人操作，不要輸出 auth.json
```

憑證與 session 寫在 `~/.local/share/opencode/`，設定與外掛在 `~/.config/opencode/`，
兩處都必須可寫，否則認證或 resume 會其中一項**靜默失效**（程式已在 `statePaths()` 宣告，
不需手動開權限）。

三件與 claude／codex 不同、會直接讓你踩到的事（實測依據見
[opencode-runtime-report.md](opencode-runtime-report.md)）：

- **計費模式不同**：claude／codex 走訂閱制、事件流沒有金額；opencode 接的是
  API key 供應商（例如 OpenRouter），每個 step 都有實際美元金額。它是唯一會回報
  `costEstimate` 的 runtime，也代表誤設一個貴模型的代價是直接的，不是「用掉一點額度」。
- **模型名格式是 `provider/model`**（例如 `openrouter/openai/gpt-oss-20b`），與另外兩家
  完全不通用。把角色改成 opencode 時 `AI_TEAM_MODEL_<角色>` 必須一併換掉，
  留著 `opus`／`sonnet` 會以 error 事件加結束碼 1 收場。
- **參數解析不會對不認識的旗標報錯**，而是印出整份說明並以結束碼 1 結束。程式已把
  prompt 一律放在 `--` 之後並另做用法錯誤判定；但升級 CLI 後旗標若有變動，
  失敗樣態會是「一份說明文字」而不是明確錯誤，請以 `opencode run --help` 重新核對契約。

**目前的驗證狀態**：旗標與事件流已在 opencode 1.18.19 實測，但**沙箱包裝下的端對端執行
與 Linux 上的狀態目錄位置尚未實機驗證**。在自己跑過之前，不要把某個正式角色切成 opencode。

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

另有一個**選填**的 `~/ai-team/team.env`（同樣 600）：`start.sh` 與 `preflight.sh` 會在
讀完 `env.sh` 之後再讀它，兩邊都設同一個變數時以 `team.env` 為準，`backup.sh` 也會備份它。
它的用途是把「切換團隊配置」（`AI_TEAM_DEFINITION`）與含憑證的 `env.sh` 分開；
不需要 profile 的部署可以完全不建立這個檔案，直接在 `env.sh` 設定即可。

`AI_TEAM_REPORT_DETAIL` 控制每個節點結束時貼進討論串的報告詳細程度，預設 `brief`：
只顯示判定／摘要／阻礙／建議後續，其餘欄位以「另有 N 個欄位未顯示」帶過。
**完整報告沒有被丟掉**，仍保存在 `stage_result`，隨時可用
`補充 #<ID> 檢視完整報告 [節點]` 取出（只讀資料，不呼叫模型、不推進流程）。
受阻原因不受此設定影響，一律完整顯示。設成 `full` 可回到舊行為。

`AI_TEAM_PROGRESS_INTERVAL_SECONDS` 控制 Slack 階段進度卡的更新間隔，
預設 300 秒、最小 30 秒。那張卡只說明角色／節點與耗時，
刻意不宣稱工具活動或完成百分比，不能當成驗收通過的證據。

數值型設定（`AI_TEAM_MAX_CONCURRENCY`、`*_TIMEOUT_SECONDS`、上面這一項）
**不能設成空字串**——空字串仍算「有設定」，會在啟動轉型時失敗、服務起不來。
不想改就整行註解掉採用預設值，要改就填實際數字。字串型設定（如 `AI_TEAM_BASE_BRANCH`）
留空才是合法的「未設定」。

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
`OPENCODE_BIN` 同理，只有在有角色設為 `opencode` 時才會被要求，範本裡是註解掉的；
`AI_TEAM_RUNTIME_<角色>` 可填 `claude-code`（預設）、`codex` 或 `opencode`，
留空退回 claude-code。preflight 只在 `OPENCODE_BIN` 有值時檢查它可執行。
Reviewer 預設 codex、AI_TEAM_CODE_REVIEW=on。不要關掉審查來取得綠燈。
前期演練 GitHub token 留空；推送另以測試 repo 與明確人工核准驗證。

### 6.1 真人核准閘門（必讀，否則全管線驗收會停住）

`teams/spring-starters.json` 與 `teams/node-web.json` 目前都是 `"requirePlanApproval": true`。
這代表 QA 驗收**不會自動派工**：系統會先把完整測試計畫貼進討論串，等需求／驗收負責人
（`acceptanceOwner`，也就是開任務的人）核准之後才執行。照舊流程等它自己跑會一直等不到。

在任務原討論串 @主機器人（不是只 tag 角色），指令都是「補充 #編號 …」的形式：

| 情境 | 指令 |
|---|---|
| 想先看計畫 | `補充 #<ID> 檢視測試計畫` |
| 想看某節點的完整報告 | `補充 #<ID> 檢視完整報告 [節點]`（省略節點＝目前節點） |
| 核准計畫並派工 | `補充 #<ID> 核准測試計畫 <計畫節點> <plan_revision>` |
| 一般續跑／回答 PM 提問 | `補充 #<ID> <你的答案>` |

`plan_revision` 由系統在呈現計畫時給出，照抄即可；版本對不上會被拒絕，這是刻意的。
核准也可以用計畫卡上的按鈕，效果與文字指令相同。

改了 `teams/*.json` 之後，**既有任務不會自動套用**——它跑的是自己的配置快照。
要讓進行中的任務改用新配置，得由同一位負責人在原討論串走一次遷移：

| 步驟 | 指令 |
|---|---|
| 1. 看差異（不套用） | `補充 #<ID> 檢視配置更新` |
| 2. 套用（token 由上一步給） | `補充 #<ID> 核准配置更新 <token>` |
| 3. 授權一次驗收（若次數已用盡） | `補充 #<ID> 核准恢復驗收 <驗收節點> <配置版本>` |
| 4. 重新核准計畫 | `補充 #<ID> 核准測試計畫 <計畫節點> <plan_revision>` |

三件事先講清楚，免得驗收時誤判成故障：

- 遷移**只接受執行能力設定**（`requiredTools`／`cachePaths`／建置與測試說明／`operations`／
  各角色 `tools`／節點 `writablePaths`、`localTestNetwork`）。改 runtime、模型、路由、角色、
  基準分支或核准政策一律拒絕，訊息是「請另建任務」——這是設計，不是 bug。
- 套用遷移會清掉舊的計畫核准與一次性恢復授權，所以第 4 步不能省。
- 「核准恢復驗收」對**同一個配置版本只能用一次**，而且不重設歷史次數。
  節點重跑上限是 3、流程總轉換上限 24，達上限時系統會停下並附上一次的原始阻礙，
  再送續跑也不會解除——要先修好真正的阻礙。

PR 發布是**另一道獨立核准**，任何計畫核准都不代表核准發布。

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

額度用盡時任務會轉成 `WAITING_QUOTA` 並保存節點、session 與工作區，
由系統依供應商回報的重置時間或退避排程自動續跑；此時**不需要**人工介入。
Slack 會分開呈現兩個時間，不要混為一談：

- **供應商指定解禁**：照原文貼出。Claude 的措辭是 `resets 6:30pm (UTC)`（標了時區）；
  Codex 的是 `try again at Sep 15th, 2026 1:09 PM`（**沒有時區**）。
  沒標時區的一律原文照登，系統不換算也不推測。
- **下次自動嘗試**：系統自己排的時間，換算成台北時間。這只是嘗試，不是解禁保證。

排程規則：標了 UTC 就採用該時刻加兩分鐘；沒標時區但指名日期時，
系統以「時區最壞解讀」（UTC−12）之後再試一次，中間不反覆重試；
兩者都沒有才退回 30 分鐘起、最多 6 小時的退避。

`AI_TEAM_RESUME_APPROVED_TASK=<任務編號>` 是啟動時的**一次性**變數，
用於讓管理者明確指定「恢復某個已有有效核准的任務」，它不會代建核准收據、
也不會掃描所有已核准任務。只在該次啟動生效，**不要**寫進 `env.sh`。

preflight 本機通過不代表登入或 Slack 成功。核對本次啟動 log、自檢與屬於該 JVM 的 ESTAB:443。
服務管理拒絕已知的執行中／排隊任務，但不是完整的競態免疫 drain 協定；維護時先停止提交新需求。

## 8. 分層驗收（不能略過失敗）

| 層級 | 必須保存的證據 |
|---|---|
| 基礎 | 新 OS／空快取證據、安裝媒體／工具鏈 checksum、版本與下載 log |
| 應用 | check-source、完整 Maven 測試與 jar SHA256 |
| Runtime | Claude／Codex 結構化輸出、resume、外部沙箱實測；CLI 版本一致。<br>用到 opencode 時另加：沙箱包裝下的實跑、狀態目錄可寫與提權釘選生效（尚未有人做過） |
| Slack | 詢問任務正常結束；白名單／去重；五角色正確身分 |
| 完整管線 | PM→可選 Architecture→QA 規劃→**真人核准測試計畫**→Backend→QA 驗收→Reviewer→人工閘門 |
| 核准閘門 | 計畫核准收據綁定 baseline／plan_revision／配置版本／核准人；未核准不得派工 QA |
| 負面案例 | 漏測／無法核實／工具失敗必須向上回報且不能發布；用量暫停續跑 |
| 發布 | 只在隔離測試 repo、人工核准後測試推分支與開 PR |
| 還原 | 停機備份→新目錄還原→DB integrity_check；登入／session 另驗證 |

原 #1010 的 Reviewer bwrap/.agents 問題已於 2026-09-09 修正（派工前自動建立掛載點，
含兩項實機雙層沙箱測試）；若在新機重現，代表跑的是舊版或掛載條件不同，
必須記錄為阻擋並查明原因，不能宣稱全管線成功，也不可改用放寬沙箱的方式繞過。
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
