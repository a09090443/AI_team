# 配置式專案、角色與工作流程

跨專案操作契約、每階段沙箱預檢、真人計畫核准及受控配置遷移，見 [跨專案執行契約](cross-project-execution.md)；
需求基準、前置契約與失效傳遞、系統獨立驗證及 PR_CREATED 見 [工程流程與驗證邊界](engineering-workflow.md)。

## 狀態與範圍

配置式模式（version 2）以非機密的 `team.env` 設定 `AI_TEAM_DEFINITION` 啟用；憑證仍在 env.sh。

新模式將角色名稱、模型、runtime、Slack token 引用、工具清單與流程節點放入 JSON。
JSON 為可直接解析的配置格式，不代表未來綁定某個 CLI；runtime 仍由既有 adapter registry 決定。
目前每個服務實例選擇一個專案配置，不是單一程序同時管理多個專案；多個專案請見
[同一台 VM 多個服務實例](#同一台-vm-多個服務實例)。

## 啟用與配置

新模式明確 opt-in，`AI_TEAM_DEFINITION` 留空則維持 legacy 行為。
設定 VM `env.sh`（範例；不要把 token 放進 JSON）：

```bash
export AI_TEAM_DEFINITION="$AI_TEAM_HOME/teams/<project>.json"
```

新安裝會複製 `orchestrator/teams/`；既有服務升級須一併部署此資料夾與新版 jar，
先備份資料庫及舊版檔案，再確認無執行中／排隊任務後重啟。不可在執行中的 target/ 建置。
服務啟動時完整讀取配置與資產；修改配置後須重啟，新任務才採用新版本。
專案自己的配置不放在 repo，而放在實例 overlay，見下一節。

## 內附範例與實例 overlay

repo 的 `orchestrator/teams/` 只放共用 prompts、schemas、skills 與三份通用範例；各專案實例的配置不進公開 repo。

| 範例 | 用途 |
|---|---|
| `node-web.json` | Node 專案；功能開關全關，也是測試的對照組（因此驗證時會提醒沒有設定受保護路徑改派） |
| `maven-library.json` | Maven 多模組函式庫；開延後驗收、角色諮詢、計畫自動核准、受保護路徑改派（`.claude/rules`、`.claude/skills`、`.mvn`） |
| `gradle-fullstack.json` | Gradle 後端＋npm 前端＋文件站；啟用[功能開關一覽](#功能開關一覽)的全部項目（含服務建置路線，引用 `skills/service-build/references/project-template.md`） |

**實例 overlay**：專案配置（`<project>.json`、專屬 prompt、`skills/service-build/references/<project>.md` 等）放在
`$AI_TEAM_HOME/team-overlay/`（可用 `AI_TEAM_TEAM_OVERLAY` 改位置），目錄結構與 `teams/` 相同。
`AI_TEAM_DEFINITION` 仍指向 `$AI_TEAM_HOME/teams/<project>.json`：

- `deploy-orchestrator.sh` 以 `deploy/bootstrap/stage-teams.sh` 把來源 teams 與 overlay 合併到暫存目錄，
  用候選 JAR 逐一 `--validate-team`，通過後才停服務並覆蓋線上 `teams/`。
- **在停服務前中止**的情況：啟用中的配置不在來源也不在 overlay；任何合併後的配置驗證失敗；
  線上 `teams/` 裡的 overlay 檔與 overlay 內容不同（代表有人直接改了 `teams/`，覆蓋會遺失修改，須先把要保留的版本放回 overlay）。
- 來源沒有的舊檔不刪；部署備份的 `teams.deployed/` 是本次實際套用的合併結果。
- `install-app.sh <來源> <新目錄> [overlay]` 新安裝時一併複製 overlay；`backup.sh` 備份 `team-overlay/`。

**為什麼不把專案 json 放到 teams 以外**：`TeamCatalog` 只接受配置檔所在目錄內的 prompt／schema／skill（symlink 指出去也拒絕），
所以專案 json 必須和共用 prompts 位於同一個目錄；overlay 是在部署時疊進 `teams/`，而不是另開目錄引用。

**修改實例配置**：改 overlay 的檔案，再複製到 `teams/`（或等下次部署帶入）並重啟；只改 `teams/` 會讓下次部署擋停。
overlay 本身的版本管理（私有 repo 或本機紀錄）由各實例的維運者決定。

可在啟動前只做靜態驗證，不連 Slack、不呼叫模型：

```bash
java -jar "$AI_TEAM_HOME/orchestrator/target/ai-team-orchestrator-0.1.0.jar" \
  --validate-team "$AI_TEAM_DEFINITION"
```

此命令驗證配置結構、引用、資產與 Git repository 目錄，**不驗證 CLI 登入、runtime 可用性、Slack token scope 或沙箱隔離**。
正常服務啟動還會驗證 runtime registry、自檢沙箱與配置列出的必要工具。

| 配置區塊 | 責任 |
|---|---|
| `project` | 專案名稱、repo 環境變數、基準分支、建置／測試規範、保護路徑、快取與必要工具 |
| `roles` | 任意穩定角色 ID、顯示名稱、runtime、模型、角色 prompt、工具清單與 Slack token 環境變數名稱 |
| `nodes` | 節點 ID、執行角色、schema、階段指示、權限、驗證政策與轉移 |
| `entry`、`maxTransitions` | 起點與整輪執行上限 |

角色 ID 與節點 ID 分開。一個角色可以執行多個節點，例如規劃測試與驗收；
checkpoint 保存的是節點 ID，不再靠 PM／QA 等角色名稱判斷續跑位置。
角色一般職責放 `role.prompt`；不同階段的任務放 `node.instructions`（選填），後者優先。
所有 prompt/schema/instructions 必須在配置檔所在目錄內；向上跳脫或 symlink 指向外部會被拒絕。

## 同一台 VM 多個服務實例

每個專案各跑一個服務。一個服務只載入一個專案配置，
所以「多個專案」＝同一台 VM 上多個安裝目錄，每個實例各自擁有：

| 各自一份 | 說明 |
|---|---|
| 安裝目錄 | `install-app.sh` 安裝到新目錄（例如 `~/ai-team-web`）；env.sh、team.env、DB、workspaces、logs 都在裡面，`service.sh` 依目錄管理自己的程序 |
| 團隊配置與 repo | `team.env` 的 `AI_TEAM_DEFINITION`；`AI_TEAM_REPO` 指向該專案的完整 clone |
| `AI_TEAM_INSTANCE` | **必填且各實例不可重複**，建議放 `team.env` |
| 頻道 | `AI_TEAM_CHANNEL_ID`，每個服務只聽這一個頻道 |
| **一組 Slack App** | 主 App 與要用的角色 App。兩個服務若用同一組 App 的 Socket Mode token，Slack 會把事件分給任一條連線，需求可能被另一個專案的服務接走 |

可共用：工具鏈、Claude／Codex 登入（**額度也共用**，各實例的 `AI_TEAM_MAX_CONCURRENCY` 相加才是同時執行數）。
服務不開網路埠（`web-application-type: none`），實例之間沒有埠衝突。

**為什麼需要 `AI_TEAM_INSTANCE`**：每個 agent 與驗證都跑在 systemd user unit，各實例的任務編號都從 #1001 起。
「中斷這一輪」與「中止任務」以 glob 停止 unit（`TaskUnits`），沒有實例字首時，A 專案中斷 #1001
會一併停掉 B 專案 #1001 的 agent。設定後 unit 名稱改為：

| 用途 | 留空（單一實例，舊名稱） | `AI_TEAM_INSTANCE=projecta` |
|---|---|---|
| agent | `ai-team-<任務>-<角色>-…` | `ai-team-projecta-<任務>-<角色>-…` |
| 系統驗證 | `ai-team-check-<任務>-…` | `ai-team-projecta-check-<任務>-…` |
| 自檢／預檢 | `ai-team-selftest-…`、`ai-team-preflight-…`、`ai-team-codex-preflight-…` | 同左，前面加 `projecta-` |

格式：小寫英文字母開頭、只含小寫英數、最多 16 字；不可為 `check`、`preflight`、`selftest`、`codex`
（舊名稱的固定字首）。不合法時服務啟動失敗。實例名以字母開頭、任務編號是純數字，所以新舊格式的 glob 互不配對。
不用配置的 `id` 當實例名，是因為 `node-web.json` 這類配置可給多個 repo 共用，`id` 不保證唯一。

接入新實例後，第一次啟動會自動跑[基準驗證](baseline-verification.md)，確認配置的驗證指令在基準分支上能通過（2026-10-18 起）。

相關檢查：`preflight.sh` 偵測到其他安裝目錄的服務在跑、而本實例 `AI_TEAM_INSTANCE` 留空時直接失敗（不檢查兩個實例是否同名，需管理者自行確保）；
`service.sh status` 只列本實例 PID 的 443 連線；`deploy/deploy-jar-safe.sh` 的第三個參數是安裝目錄，
「有 unit 在跑就拒絕部署」只看本實例的 unit。
完整部署腳本 `deploy-orchestrator.sh` 也支援只安裝 JAR 的目錄：一律備份與更新 JAR／teams，
`src/main` 只有原安裝包含來源時才備份、更新與回復，不因缺少來源目錄中止。

**Slack App**：每個實例各自一組主 App＋角色 App，Bot Token 與 App-Level Token 都填自己的；
同一頻道只留該實例的 App，避免 @ 到別組同名角色而被對方以「忽略非指定頻道」丟棄。

**Slack 免費方案的替代做法**：免費 workspace 最多安裝 10 個 App，多個實例可能裝不下。
此時主 App 各自一個，角色沿用第一個實例的 App，但其他實例**只填角色的 Bot Token（`SLACK_BOT_TOKEN_<角色>`）、
App-Level Token 留空**：發言與交接 tag 使用同一個角色身分，不影響第一個實例；代價是其他實例的頻道不能直接 @角色
（事件會送到持有 App-Level Token 的第一個實例，它只記錄「忽略非指定頻道」後丟棄）。角色 App 要另外邀請進其他頻道。
**不可**在兩個實例填同一個 App-Level Token，否則 Slack 會把事件分給任一條連線。

**驗證狀態**：unit 命名、glob 互不配對與 preflight 檢查有程式測試；已實測同一台 VM 同時啟動兩個實例
（各自 0 ERROR、自檢 unit 帶各自字首、443 連線分屬各自 PID）。

## 功能開關一覽

所有功能都是通用的，由配置欄位開關，程式不針對任何專案。新專案只要新增一份配置，就能依需要啟用下列任一項：

| 功能 | 配置欄位 |
|---|---|
| 共用工作規則 | `sharedInstructions` |
| 角色諮詢與共享看板 | `collaboration`／`consultRoles` |
| 自主恢復（逾時接續、續驗、需求變更選項、頂層指令） | `project.autonomy` |
| 驗收環境驗不了的項目自動延後 | `project.autonomy.deferUnverifiable` |
| 已開 PR 衝突自動同步 | `project.autonomy.pullRequestSyncRounds`／`pullRequestSyncAutoPush` |
| 自主派工 | 節點 `candidateRoles` |
| 跨 runtime 改派 | 節點 `protectedPathFallback` |
| 服務建置路線 | `service` 節點、`serviceTarget` |
| 依變更範圍驗證 | `commandScopes` |
| 文件略過產品建置 | `docOnlyPaths` |
| 計畫自動核准 | `autoApprovePlanWhenValid` |

內附範例的啟用情形見[內附範例與實例 overlay](#內附範例與實例-overlay)。
各部署實例實際啟用哪些功能、為何這樣設定，屬於該實例的維運紀錄，由維運者自行保存在本機（例如 `.ai/records/`），不寫在本文件。

## 配置經驗（Gradle、前端與多 runtime 專案）

以下是接入實際專案時驗證過的通用做法，新增配置時可直接參考：

| 項目 | 做法與理由 |
|---|---|
| 工具 | `requiredTools` 與 operations 的執行檔必須是 PATH 上的工具名稱，不能是 `./gradlew` 路徑或 shell。Gradle 專案改用 PATH 上的 `gradle`（版本與 repo wrapper 相容即可），安裝見 [INSTALL §5.2](INSTALL.md#52-gradle-專案的額外前置) |
| Gradle 快取 | `cachePaths` 開 `.cache/gradle`（env 設 `GRADLE_USER_HOME=$HOME/.cache/gradle`）與 `.npm`；只開放 `~/.gradle/caches` 時 Gradle 以「Could not initialize native services」失敗 |
| 需要外部資源的測試 | VM 沒有資料庫／Docker 時，需要它們的測試不列入操作；相關情境由 QA 標「無法核實」，或以 `deferUnverifiable` 延後 |
| 操作的 argv 上限 | 單一操作 argv 上限 32；測試類別多時分成數個操作。負載下容易逾時的測試單獨成一個操作，並視情況排除在系統獨立驗證外（上限 5 條指令） |
| 只在 Windows 能跑的建置任務 | repo 的建置腳本寫死 Windows 工具時，操作要以參數排除該任務（例如 Gradle 的 `-x <task>`），並在建置指示禁止 agent 改建置檔繞過 |
| bash 腳本 | 不能當 operation 或系統驗證指令；寫在建置指示，由實作 agent 自行執行。**不能列為驗收項目**：測試計畫只能引用操作或內建 `inspect-content`，改由審查檢查 |
| 依變更範圍跑後端 | 建置／測試指示可規定只有變更含後端目錄或版本設定時才跑後端編譯與測試，只改部署檔、前端或文件時不跑；`implementation.timeoutSeconds` 上限 3600 |
| `.claude/` 文件修改 | Claude Code 實作角色寫不了 `.claude/`；實作節點設 `protectedPathFallback`，`.claude/rules/**`、`.claude/skills/**` 分段改派 Codex 角色，其餘（含 `.agents/`、程式、建置測試）由原角色接著完成，見 [多 runtime](multi-runtime.md)。settings／hooks 等仍須真人處理 |
| Codex 節點跑 Gradle | Codex 內層沙箱只開放工作區與 `/tmp` 可寫，且沒有網路 namespace 介面，Gradle 會出現「Failed to load native library」或「Could not determine a usable wildcard IP」。要跑 Gradle 的 Codex 節點（例如 QA）開 `localTestNetwork`；改派段只改 `.claude/` 檔案、不跑建置（實作節點不能開 `localTestNetwork`） |
| Codex 寫不了 `.agents/**` | Codex 內層沙箱把工作區的 `.agents` 掛成唯讀（[雙層沙箱](codex-nested-sandbox.md)），所以同時改 `.claude/` 與 `.agents/` 的需求要用分段改派，不能整節點改派 |
| `docOnlyPaths` | 可列 `*.md`、`docs/**`、`.claude/**`、`.agents/**` 與文件網站的純 Markdown 目錄；變更全部在其中且通過文字副檔名及強制全跑檢查時略過系統建置。文件網站的設定檔、`src/`、`package.json` 照樣全跑 |
| 系統獨立驗證 | 在全新 clone 與私有 HOME 執行，會重新下載相依（第一次較慢） |
| repo hooks | repo 的 `.claude/settings.json` 掛了 VM 沒有的工具（例如 pwsh）時，只產生非阻擋的錯誤訊息，工具呼叫照常完成 |

## 不改 Java 的增刪例子

- 典型的 Java 後端配置：設計、測試計畫、實作、驗收、獨立審查、人工 PR 核准。
- `orchestrator/teams/node-web.json`（內附範例）：改用 `AI_TEAM_NODE_REPO` 與 `main`，必要工具換為 Node/npm；
  移除 Architecture 與設計節點，加入 Frontend，Reviewer 替換為 `security_auditor`。

第二份配置由同一個 FlowEngine 執行，不需要增加 Java enum、role switch 或流程 if。
新增角色仍需建立／設定相應 Slack App，將 Bot/App Token 填入配置引用的環境變數，
使用 `chat:write`、`app_mentions:read`、`app_mention` 與 Socket Mode。角色不必有 Slack 帳號才能執行：
缺 token 時系統會明示代發；但無法直接 @沒有獨立帳號的角色。

刪除角色時要同步移除或改派引用它的節點與轉移。刪錯引用、未配置的下一步、孤立節點、
缺少必要驗收、實作與獨立審查共用角色都會被拒絕，不會在執行時默默跳過。

## 權限與發布仍由程式強制

配置只能選擇已實作的政策，不能用任意 shell 表達式建立新閘門：

- `READ_ONLY`：只允許 runtime 自身狀態寫入，不開放產品目錄。
- `WORKSPACE`：僅獨立 clone 可寫，必須配 `IMPLEMENTATION` 契約。
- `LIMITED`：只給配置匹配到的目錄及該 clone 的 Git 狀態寫入；不把整個專案交給測試角色。
  不含萬用字元的明確目錄（例如 `service/build/**`）不存在時，派工前先建立空目錄再納入可寫範圍；
  路徑祖先是 symlink 時停止。含萬用字元的政策不猜測缺少的目錄，全部找不到時停止（fail closed）。
  適用所有 LIMITED 節點：只改文件、沒跑建置的任務 `build/` 不存在，否則驗收的操作預檢會判定「寫入超出沙箱政策」而停下。
  在沙箱外逐筆核對新增 commit、未提交與未追蹤路徑，攔截 Git index 繞道及「修改後再還原」；
  單階段超過 100 個新增 commit 會停止要求查核。沒有可寫目錄時拒絕，不自動放寬。
  工作區內符合 `project.protectedPaths` 的檔案與目錄會逐一釘成唯讀（`ConfiguredExecutor.protectedPins`）；
  走訪時不進入 `node_modules`，因為裡面是安裝產物，不是產品檔案，驗收的 `npm ci` 還要整個刪掉重裝。
  沒有 git 追蹤檔的操作輸出目錄（`writes`，判斷同下方「操作輸出目錄自動排除」，每次都重新問 git，不讀 `.git/info/exclude`）也整個不釘選。
  理由：QA 副本被釘成唯讀時，下一輪清理會得到 `Device or resource busy`；進入 `node_modules` 會釘上千個檔案，
  Codex 的 permissions 單一參數會超過 Linux 單一參數 128 KB 上限，以 `Argument list too long` 啟動失敗。

路徑使用 `/`、`*` 與 `**`，例如 `tests/**` 或 `**/src/test/**`。
測試產物目錄應在實作階段依專案建置正常建立；限寫階段不能自行建立未獲授權的父目錄。
快取權限只容許 `.m2/repository`、`.npm`、`.gradle/caches` 或 `.cache/<單層名稱>`；不允許以快取名義寫入 `.ssh`。

驗證政策是「工作契約」，不是「角色名稱」：

- `REPORT`：共用完成／失敗報告契約。
- `REQUIREMENTS`（v2）：唯一需求基準，含穩定需求 ID、原子驗收項目與維度、範圍及待確認問題。
- `IMPLEMENTATION`：成功狀態、建置品質結果、無範圍衝突。
- `TEST_PLAN`：情境須覆蓋所有原子驗收 ID，並提供能區分弱實作的反例。
- `VERIFICATION`：逐情境實際結果與證據，無未核實事項；引用 `plan` 節點。
- `REVIEW`：獨立核實完成、有檢查紀錄、無阻擋事項或 HIGH finding。

`APPROVAL` 節點必須指定 `requires`，至少涵蓋上述四種實作／測試／審查契約。
v2 額外要求需求基準、所有間接前置契約，以及管理者固定的獨立驗證指令收據。
真正發布前再次檢查：驗收／審查對應同一個最終 commit、工作樹乾淨、
branch／remote／基準分支未變，並且由目前白名單使用者按下核准。
新的產品寫入會撤銷先前驗收與審查收據。修改角色配置不能讓舊收據變成有效核准。
GitHub 是目前唯一發布 adapter；更換 Git hosting 或加入全新驗證政策仍需要實作 adapter／程式。
網路隔離仍未補上，不能把本次配置化誤認為已解決 agent 的所有安全邊界。

## 成功、受阻、人工與重試

AGENT 節點指定 `next`、`blocked`，可選填 `routes`。
agent 以 `route` 選擇流程分支；未知 route 明確停止。節點可另外以 `candidateRoles` 開放上游 agent
依角色的 `capabilities` 自主選擇負責人（見下節），不能任意跳過階段或建立權限。
`human` 與 `end` 是保留目標，分別代表等待人工與結束（不發布）。
範例將驗收受阻交給分流節點，能回到實作、驗收或詢問人類，仍受重試上限約束。
節點 `maxVisits` 最多 3；整輪 `maxTransitions` 最多 64。用量續跑不重建工作區，也不另算一次新節點。
節點上限計算的是「輸入完全相同的連續重跑」，不是總執行次數：工作區 commit、前置節點結果、
受阻發現或真人補充任一改變，就代表這一次有新的依據，重跑次數從 1 重新起算（`visits` 歷史次數照常累加）。
正常的返工鏈（分流→實作→驗收→審查）因此不會在還有事情可做時被擋下；原地重跑仍然三次就停。
舊快照沒有指紋欄位時一律視為相同輸入，不會因為缺欄位而繞過防循環。
啟動 runtime 時若遇到可辨識的系統性失敗（執行檔不存在、參數過長、session 不存在、DNS／連線中斷、
沙箱啟動失敗、讀取逾時），系統會自動重試最多 2 次，不計入節點次數也不佔用事件額度；
其餘失敗照舊停止並保留現場。
達一般上限後，若前一份受阻報告已透過配置的 `blocked` 明確指向責任節點，
系統可處理每任務最多 6 個有證據的獨立受阻事件；同一事件可沿配置流程經過最多 12 個節點，
不會因「分流→修復→重驗→審查」每一站都重複消耗事件額度。它不清除歷史計數、不擴權，
也不視為人工核准。原因不明、目標為 `human`、需改需求／核准，或任一上限用完仍會停止。
舊快照若含第一版逐節點 key，執行前會按證據合併為獨立事件；無法辨識的格式不會降低計數。
事件額度用完時，原驗收負責人可使用停止訊息提供的一次性授權碼增加兩個事件，最多額外六個；
授權綁定配置、目前受阻證據與 HEAD，相同現場不能重放，也不重設一般流程或節點歷史次數。
配置遷移會讓舊配置下增加的額度失效；已使用 token 與事件歷史仍保留。
LIMITED 驗收節點若只在其核准的測試路徑留下未提交變更，系統會核對實際 diff，並依該節點的
`blocked` 分流配置中 `implementation` 路由交給具 `WORKSPACE` 權限的實作節點提交；產品檔、
保護路徑、混合或無法歸責的變更一律停止給真人，不會放寬 `.git` 權限。
詳見 [adr-automatic-blocker-routing.md](adr-automatic-blocker-routing.md)。

受阻報告不寫進 `receipts`，而 `flow.context` 有長度上限會淘汰最舊段落，因此受阻發現另存於
`flow.blockedFindings`（以來源節點為鍵），由系統無條件注入下游節點提示，不佔用 context 預算。
只有該節點自己通過時才移除；下游不得以「本節點查不到」或「其他報告顯示全部通過」當作已解除。
另外，節點在本輪有工具呼叫遭拒、報告卻回報未受阻時，系統直接判定受阻並說明是「角色配置完全
沒有該工具」還是「有配置但呼叫方式不符白名單」——會呼叫工具就表示需要它，被拒後仍宣稱查核完成
不可採信。已受阻的報告不受影響，合法引用被拒工具作為阻礙原因仍然可行。
因此配置角色工具時必須與該角色的職責一致：要它核實產物就給對應的唯讀權限，否則在階段指示
明令不得宣稱執行過該工具。詳見 [adr-blocked-finding-continuity.md](adr-blocked-finding-continuity.md)。

原討論串 `@主機器人／角色 補充 #編號 答案` 會續跑原快照節點。
同一工作討論串不會因一般 @主機器人訊息再建第二個平行工作任務（原任務已結束也一樣）；新需求請開新討論串。
回覆內容依原任務狀態區分（`TaskOrchestrator.threadTakenMessage`），只有 `AWAIT_HUMAN`／`WAITING_QUOTA` 才建議「補充」，細節見 `docs-site/agent/operations.md` 的「建立任務」。
文件維護請求查到有明確依據的文件錯誤時，intake 提示要求使用 `implementation` 合併派修，
不因 intake 唯讀而把修正當成範圍外；明確只檢查不修改或詢問運作方式才維持純詢問。
這是提示層級的要求，不是關鍵字硬編碼閘門；既有任務快照不變。
純詢問（route=`question` → `end`）的回答若列了 `out_of_scope`，結束時會附「開成新需求」按鈕（`SpinoffButtons`、`FlowEngine.spinoff`），
按鈕名稱取自同一份報告的 `out_of_scope_titles`（`schemas/requirements.json` 必填、與 `out_of_scope` 等長；不等長時全部退回序號），
在新討論串開立任務，原討論串仍是一串一任務。
用量暫停走既有繼續按鈕；新模式不使用舊版「重新開始」按鈕，以免誤封存工作區或套錯流程。
配置角色的一般對話使用獨立子請求，不會覆蓋原驗收；新加入、但不在原快照裡的角色不能接手舊任務對話。

## Agent 自主派工

新 v2 配置的共用問題清單契約由 TeamCatalog 固定到 snapshot.assets，ConfiguredExecutor 擴充輸出 schema 並注入完整清單，
IssueTracker 驗證穩定 ID、需求對應及逐項證據；FlowEngine 保存新發現、續跑與承接時保留，FlowGates 在驗收／審查／完成／發布時檢查。
所有專案與角色共用同一套機制，沒有依專案名稱或特定任務編號分支；詳細欄位與限制見[工程流程](engineering-workflow.md#跨專案問題完整性2026-10-11)。

節點 `candidateRoles` 是管理者核准可承接此階段的角色集合，不是需求關鍵字路由。
上游 agent 會收到下一階段的候選角色、能力描述、runtime、工具與階段權限，自行回傳：

```json
{"handoff":{"role":"infrastructure","reason":"需求是 SIT 容器環境設計","instruction":"設計 PostgreSQL 與服務的 Compose，交付初始化與健康檢查方案"}}
```

- 模型仍先以 `route` 決定工作分支，再以 `handoff` 指派該分支下一階段的角色；原 `role` 是未啟用自主派工時的執行角色。
  目標有候選清單卻沒填有效派工時，回報受阻，不默默使用預設角色。固定角色的 AGENT 目標可填 null；若模型明確交接，僅接受完全相同的既定角色並保留交接紀錄，不開放改派。human／end 等非 AGENT 目標仍須 null。
- **handoff 角色名稱容錯（2026-10-17 起）**：`handoff.role` 不是角色 ID 時，`AgentDispatch.resolveRole` 只在下一階段的合格角色之間比對：
  顯示名稱、去掉括號或結尾 Agent 的名稱、文字中出現的完整 ID。剛好對應到一個角色才採用，並寫一筆稽核紀錄「對應為唯一合格角色」。
  對應不到、有歧義，或 handoff 缺少或多出欄位，都算格式錯誤（`AgentDispatch.HandoffFormat`）：系統附上可填的角色 ID，
  請同一節點只修正 handoff、其他判斷不變，重送一次，而且不計次（`systemRetries` 鍵尾 `#handoff-format`）。
  同一步第二次還是錯，才照原本的受阻路線處理。角色能力或工具權限不符（`validateAssignment`）屬於實質問題，不自動重送。
  常見情況是分流判斷正確，只是把 role 填成「QA（verification）」而不是角色 ID `tester`。
- 例如設計候選設為 Architecture／Infrastructure、實作候選設為 Backend／Infrastructure 時，
  PM 可將環境設計交給 Infrastructure，測試計畫 agent 再選實作負責人；受阻後由分流 agent 重新選擇。
  第一版每次交接一位角色、中央佇列依序執行，不支援一份報告建立多個平行子工作。
- 系統檢查候選資格、必要操作的工具權限，啟動時沿用沙箱預檢；階段的 scope、schema、requires、QA／Review／核准條件不變。
  實作候選與審查候選不得重疊。沒有適任角色時須說明缺少能力並受阻，不得自動擴權。
- Slack 顯示實際負責角色、派工理由與交接工作；`assignments` 保存目前選擇，`assignmentHistory` 保存歷史。
  額度與中斷續跑沿用原選擇；改派後開新階段 session。歷史最多 256 筆，既有節點／整輪／自動交接上限仍有效。
- Codex 自主派工實作完成後，系統核對實際變更，不允許工具行為／版控禁止路徑，再代為 commit；後續仍跑獨立驗證與審查。
  Infrastructure 可在任務工作區產出環境檔案，遠端 VM 部署、停止與清理仍未開放。一般直接 @ 對話仍唯讀。
- 舊配置缺少 `candidateRoles` 時沿原角色執行。新欄位會寫入快照，舊 JAR 不能讀取新版快照，回滾須搭配部署前 DB 備份。
  角色集合／流程派工政策變更不允許原地配置遷移；舊任務需由負責人使用「改用新任務繼續」。

### 共用工作規則與任務效率（2026-10-23）

頂層 `sharedInstructions` 指向所有角色共用的規則檔，內附配置都使用 `prompts/shared.md`，內容分三段：
- **工作分類**：純文字、文件網站／生成產物、程式／SQL／設定／服務各自的驗證深度。原本重複寫在 6 份角色提示中，現在只寫一次。
- **任務效率**：先確認能否縮短執行時間，工作深度與任務大小相稱；精簡不得刪減使用者要求、降低高風險檢查，或跳過必需的計畫、核准與驗收。
- **協作與自主**：先自行查核修正，需要專業判斷時走角色諮詢或受阻路線；只有產品取捨、新權限、未授權外部操作或無路可走時才交真人；交接寫清事實、證據、未解問題與下一步。

各角色提示只保留自己的職責：PM 以最小必要範圍建立需求、分流把可一次修完的小缺陷合併派出、
設計對簡單變更只寫必要修改點、測試計畫的情境數以能判斷 acceptance 為準、實作不順手重構、審查限本次變更與其影響。
這些都是提示層級的要求，流程節點與 `requires` 不變；修改會改變配置 revision，需重新部署 teams 才生效，尚未在真實任務驗證。

### 團隊名單與精簡路線（2026-10-28）

起因：需求整理只看得到三條路線、不知道團隊有誰，一行文件修改也照完整流程跑（含設計）。

- **團隊名單**（`TeamRoster`）：需求節點（gate=REQUIREMENTS）與其他節點的受阻目標（分流）在 context 收到全隊名單：
  角色 ID、顯示名稱、能力描述、runtime、負責哪些階段及是否可寫，以及受保護路徑改派對象。只是說明文字，不授權、不改派，
  也不納入 `NodeInput` 指紋。與角色諮詢共用 `collaboration` 開關：沒開的配置（node-web 對照組）與舊快照提示不變。
- **精簡路線**：需求節點的 `routes` 可設 `"light": "plan"`，範圍明確的小修改由 agent 自行選擇略過設計。
  `FlowGates.bypassed` 判定：前置節點是建議性報告（gate=REPORT）、本流程從未執行過、也沒有結果或受阻報告時，
  前置檢查與發布檢查視為「路線刻意略過」，不退回補做。跑過但無效時照舊重做；需求、測試計畫、實作、驗收、審查等契約階段永遠不能略過。
  route 列舉含 `light`，但只有配置該路線時才會出現在可選清單。內附 maven-library、gradle-fullstack 已設定；node-web 沒有。
- 狀態：程式測試通過；尚未部署，真實任務耗時尚未對照。

### 文件任務的派工限制與已知問題（2026-10-12 查核）

> 2026-10-28 更新：配置精簡路線（見上節）時，小型文件任務可略過 design；其餘說明仍成立。

文件任務採輕量**工作內容**，並未移除流程節點：有 design 節點的配置仍經 design、plan、implementation、verification、review；
plan 與 verification 固定由 tester 負責。node-web 範例仍經 builder／frontend。`candidateRoles` 只選執行者，不改 `requires`。

- PM 區分純文字、文件網站／生成產物、程式／SQL／設定與服務建置，盤點使用者與 agent 文件，不自行縮小需求。
- 設計只做與工作相關的來源、範圍及同步分析；純文字不添加 API 架構設計或無關回歸。
- 實作由具文件能力的角色承接；QA 用 `inspect-content` 合併驗證內容、新舊值及一致性，審查不追加無關情境。
- 有文件網站的專案可在 verification 加入 qa-docs-dir → qa-docs-clean → qa-docs-snapshot → qa-docs-extract → qa-docs-install → qa-docs-build：
  先清除上輪 docs-site 副本，再從最終 HEAD 匯出 docs-site 至 target/qa-docs，離線安裝並建置，原文件唯讀。實作先執行 docs-install／docs-build 準備 npm 快取。
  qa-docs-clean 為 `find target/qa-docs -mindepth 1 -delete`（清空 target/qa-docs 內容、保留目錄，須在 qa-docs-dir 之後）。
  不用 `rm -rf`：Codex CLI 會在啟動程序前拒絕 rm -f 類指令；`find -delete` 已在 VM 的 Codex 實測可執行。
  固定操作 argv 不允許空白、引號等 shell 字元，所以不能改用 `node -e` 單行腳本。
  所有團隊配置的操作都不可用 `rm` 當執行檔，`OperationContract.validate` 載入配置時就拒絕，錯誤訊息會提示改用 `find`。
  產品 repo 沒忽略 target/ 時，副本的 `package-lock.json` 會被寫入範圍稽核判成「限寫階段越權」；系統已統一處理，
  見下方「操作輸出目錄自動排除」，專案不需要自己改 `.gitignore`。各專案須提供自身操作；提示不會產生不存在的工具能力。
- 內附配置均提供 `docOnlyPaths`，實際變更仍由 Git 判斷。SQL、腳本、JSON、網站程式等非文字副檔名不能因位於 docs/** 就免驗；
  純文字限 .md／.mdx／.txt／.rst／.adoc，仍受原建置檔與 src 等強制全跑名單限制。QA 必要文件站建置不因系統略過產品建置而取消。

plan schema 要求 `protected_path_changes` 明列需跨 runtime 改派的檔案（無則 []），不再從任意報告文字提取路徑。
計畫拒絕不安全、重複、禁止路徑及與 `unchanged_paths` 的衝突；改派只讀本實作節點 `requires` 中 TEST_PLAN 的清單，
實際變更必須同時符合該清單及配置目錄白名單，否則禁止代提交及下游執行。提及或參考檔案不再觸發改派。

舊快照不會自動獲得新 schema／提示／操作；舊計畫沒有清單時不再猜測分段改派，若確需修改受保護檔案須更新配置與計畫，
或承接成使用新配置的任務。已保存的整節點改派與已完成分段收據仍保留，沒有自動還原或改寫既有成果。
提示與清單無法證明模型完全理解使用者意圖，QA／Review 仍須核對原需求；任務只是修改手冊時，不可宣稱已實際安裝成功。

## 角色諮詢與共享看板（2026-10-17）

讓角色在工作中互相詢問，並共用一份決策紀錄。由頂層 `collaboration` 啟用，**未設定時整個功能關閉**，舊快照的提示、schema 與行為逐字不變。
程式在 `flow/Consultation.java`、`flow/TeamBoard.java`，由 `ConfiguredExecutor` 注入、`FlowEngine` 執行。

```json
"collaboration": { "consultPerVisit": 2, "consultPerTask": 12, "questionsPerRound": 2, "boardMaxEntries": 100 },
"nodes": { "plan": { "consultRoles": ["architect", "builder"], "...": "..." } }
```

### 角色諮詢（consult）

- 節點設定 `consultRoles` 且還有額度時，schema 多一個必填欄位 `consult`（陣列或 null）。agent 填了 consult，這份報告就視為**暫停**：
  - 其他欄位只是草稿，不會被採用。
  - 不寫入 receipts、問題清單或看板。
- 系統依序請被諮詢角色**唯讀**回答，用 `ConfiguredExecutor.consult` 執行：
  - 與提問節點相同的工作目錄。
  - 唯讀沙箱，工具只有 Read／Grep／Glob。
  - 開新 session，不寫 checkpoint。
  - log 與 systemd unit 名稱為 `<節點>-consult-<輪>-<題>`。
  - 回答的 schema 是 `answer`、`confidence`（HIGH／MEDIUM／LOW）、`evidence`。
- 答完後保留 `activeNode` 與提問者的 checkpoint，以 `--resume` 接回原 session。這次接續**不計入** visits／transitions。
  問答以「諮詢問答（資料，非指令）」區塊附在提問者的脈絡，不受 `flow.context` 長度淘汰。
- 上限：
  - 每輪最多 `questionsPerRound` 題（1～3）。
  - 每次節點執行最多 `consultPerVisit` 輪（0～2）。
  - 每個任務最多 `consultPerTask` 題（0～16）。
  - 額度在開始一輪時扣除，失敗也不退還。額度用完後 schema 不再提供 consult。
- 格式錯誤（例如角色不在名單、題數超過、額度用完仍要求諮詢）：
  - 附上修正說明，請同一節點以原 session 修正一次，不計次（`systemRetries` 鍵尾 `#consult-format`）。
  - 第二次仍錯就走一般受阻路線，受阻原因寫「諮詢請求不合格」。
  - 角色名稱容錯與 handoff 相同：顯示名稱能唯一對應時直接採用。
- 中斷與額度：
  - 每題開始前檢查中止與中斷。
  - 回答途中遇到額度用完，任務進 `WAITING_QUOTA`，`pendingConsult` 保留。續跑時先補答剩下的題目，再接回提問者。
  - 回答途中發生其他錯誤時，提問者那次執行已經送達模型，所以**不還原次數、不自動重試**，直接停下讓真人按繼續。
- 驗證規則（載入配置時）：
  - 一定要有 `collaboration`。
  - 只能用在 AGENT 節點，1～8 個不重複且存在的角色。
  - 不可包含節點自己的角色（含 `candidateRoles`）。
  - 被諮詢的角色必須有 `capabilities`。
  - **REVIEW 節點不可諮詢任何實作角色**（含 `candidateRoles` 與 `protectedPathFallback`），以維持獨立審查。
- 諮詢不是派工：回答者不能修改檔案、核准或擴權，回答也不能取代 QA／Review／獨立驗證的證據。

### 共享看板（board）

- 啟用後每個節點的 schema 都多一個必填欄位 `board_updates`（最多 10 筆）：
  - `op`：`ADD`、`RESOLVE`、`SUPERSEDE`。
  - `kind`：`DECISION`、`ASSUMPTION`、`QUESTION`、`RISK`、`NOTE`。
  - 另有 `id`、`text`、`to`、`refs`。
  - 缺陷照舊寫問題清單（IssueTracker），不寫在看板。
- ADD 由系統配發 `B-<n>`；RESOLVE／SUPERSEDE 只能針對 OPEN 項目。QUESTION 可以用 `to` 指定要回答的角色，該角色下次執行時會看到醒目標示。
- **只收最終報告**：通過與受阻的報告都收，格式重送、需求草稿重送與諮詢暫停不收。
  - 寫錯的項目只被拒絕，並附一行「系統看板說明」到 context，**不擋流程、不交真人**。
  - 看板滿了（`boardMaxEntries`，最多 200）拒絕新增，請角色先結案。
- 每個節點的脈絡都會附上看板（對話模式除外）。OPEN 項目優先列出，最多 64000 字，已結項目只列最近 20 筆。
  看板**不納入節點輸入指紋**：角色自己寫看板不算新的輸入，防循環照常生效。
- 承接成新任務時，OPEN 項目與決策會帶到新任務並標明來源任務。PR 核准訊息與結束訊息會附上看板摘要（未結的問題與風險前 5 筆）。

### 相容與回滾

- `TeamDefinition.collaboration` 與 `Node.consultRoles` 為 null 時不序列化，未啟用配置的 revision 與修改前相同。
- FlowRun 的 `board`、`boardSeq`、`consultations`、`pendingConsult`、`consultUsed` 為空時不寫入 JSON。
  沒用到協作的任務，舊 JAR 仍讀得到；**用過協作的任務，舊 JAR 讀不到**，回滾時必須搭配部署前的 DB 備份。
- 進行中的任務不會自動套用，要用「改用新任務繼續」。

典型的諮詢設定（沒有 infrastructure 角色的配置略過該項）：
- design 可諮詢 builder／tester。
- plan 可諮詢 architect／builder／infrastructure。
- implementation 可諮詢 architect／tester。
- verification 可諮詢 architect／builder。
- review 只可諮詢 architect。
- triage 可諮詢 architect／builder／tester／infrastructure。

node-web 範例配置維持未啟用，也作為測試的對照組。被諮詢的角色必須有 `capabilities`。
**目前證據等級：程式測試通過，尚未做真實 Slack／CLI 端對端驗證。**

## 快照與相容策略

`flow_run` 是 additive 資料表，保存每個新模式任務的完整配置、prompt/schema 內容、版本雜湊、
目前節點、執行次數、證據收據、工作區位置與發布目標。Token **值**不進快照，只保存環境變數引用。
CLI session 仍存在原 runtime 狀態目錄，checkpoint 保存其 ID；環境變數所指的實際憑證仍由維運管理。

修改／移除目前配置的角色不影響舊任務快照；舊快照需要的 runtime、工具、token 引用與工作區仍須保留。
如果它們被移除，任務會明確受阻，不會自動換引擎、使用新 repo 或重建現場。
重啟時執行中、排隊或發布中的新模式任務都停下並要求人工確認，不自動重播可能已完成的外部動作。

2026-10-12 起來源的中央佇列通知涵蓋新任務、承接、續跑、角色對話與發布。FlowEngine 以帶任務身分的
Runnable 交給 TaskOrchestrator，由真正的執行中工作與 FIFO 排隊清單產生通知，不以資料庫狀態推測順序。
排入時列出執行中／前方任務，開始時另行通知；排隊通知為當下快照。現有流程、核准政策與執行順序不變。

沒有 `flow_run` 的歷史任務沒有足夠資料可自動建立可信快照。新模式會拒絕續跑／發布這些任務，**不猜測遷移**。
要處理它們，須以原專案環境停用 `AI_TEAM_DEFINITION`，走原 TaskOrchestrator 的 legacy 相容路徑，或另行做明確遷移。
因此原始 StageSpec 常數與部分 role switch 暫時仍在，但不是新模式增刪角色的入口。
待歷史未完成任務結束並驗收後，才能另做 legacy 清除。

## 尚待實機驗證

- Linux 完整測試與實際 systemd 的配置限寫政策。
- 新 Slack 角色的 App 設定、事件路由與真實身分回覆。
- 任務在真實額度中斷與重啟後的續跑。
- 以新模式走到 PR 人工閘門，再由使用者另行核准發布。
- Node 範例是配置與流程測試範例，不代表已在任意 Node repo 完成產品驗收。
## 角色的 reasoningEffort

例如 tester 角色設 `runtime: "codex"`、
`model: "gpt-5.6-sol"`、`reasoningEffort: "medium"`，涵蓋 plan、verification 與 QA 唯讀對話。
`reasoningEffort` 是角色選填欄位，目前僅 Codex 支援 low/medium/high/xhigh；
新執行與 resume 都會傳入 `-c model_reasoning_effort="medium"`。
缺少此欄位的舊快照維持 CLI 原有行為，不回寫歷史任務或全域 config.toml。
這不是自動提升推理強度功能；需要 high 時由管理者修改配置，對新任務生效。
設定依 OpenAI Docs 的 [Codex 配置參考](https://learn.chatgpt.com/docs/config-file/config-reference) 核對。

## 新專案與所有任務共用的行為

下列行為都由程式依配置的節點類型、`scope`、`blocked`／`routes` 推導，**不依節點或角色名稱**，
新增專案不需改程式。

- **下一步說明**：Slack 上的「下一步」由 route 指向的節點類型與角色顯示名稱組成，例如
  「交給 Backend Agent 實作修正（implementation）」。程式只翻譯保留字 `human`、`end`。
- **共用回報契約**：證據只能寫實際執行成功的工具輸出、未解除受阻發現優先、沒有收據不代表沒建置，
  以及需要真人決定時必填白話的 `human_decision`，都由系統注入所有角色，不必寫進專案 prompt。
- **角色工具**：其他節點受阻時交接的目標節點（例如分流）與審查節點若是 `READ_ONLY`，其角色應具備
  唯讀查核工具（例如 `Bash(git log *)`、`Bash(git show *)`）。`--validate-team` 會對缺少者輸出警告；
  否則工具遭拒時，該節點的結論會被作廢。
- **能不能繼續**：引擎擋人與停止訊息提供按鈕，都使用同一個判斷（`FlowLimits`），不會出現
  按了卻走不下去的按鈕。
- **承接成新任務**：流程總次數用盡、且目前停住的節點沒有任何延長或恢復授權能通過時，停止訊息
  提供「承接成新任務」（任務停下等人時也可隨時「改用新任務繼續」，見 [pm-triage](pm-triage.md#隨時可改用新任務繼續)）。新任務：開在頻道的新討論串；工作區從原任務分支的最新 commit 接著做
  （原任務工作區必須乾淨，HEAD 變動則拒絕）；基準 commit 設為分岔點，PR 會包含原任務成果；
  帶入原任務交接紀錄與未解除的受阻發現；次數重新計算；**不沿用任何核准**，需求、測試計畫與 PR
  皆需重新核准。只有驗收負責人可以承接，原任務之後不再接受操作，現場與歷史保留。
- **`補充 #編號 狀態`**：唯讀，重新貼出目前狀態與真的可用的選項，不啟動 agent。
- **部署**：`deploy/deploy-orchestrator.sh <隔離來源根目錄>` 不指名任何任務；前置條件是沒有任務在跑
  或排隊、沒有 agent 程序、來源 teams 疊上實例 overlay 後所有團隊配置通過驗證，並比對所有任務資料在部署前後一致；工作區逐一比對，仍在的必須完全相同，消失的只接受啟動清理依規則刪除的已結束（COMPLETED／ABORTED）任務（`deploy/bootstrap/workspace-check.sh`）。
- **卡在重試上限時**：目前這一步無法再啟動、延長也幫不上忙時，停止訊息不再提供選擇題、補充或「繼續」
  （按了都不會重新執行），而是說明原因。管理者部署修正後的配置，送「補充 #編號 狀態」會出現
  「檢視配置更新」→「核准配置更新」→「重新執行這一步」三顆依序出現的按鈕。受控恢復授權
  適用任何卡在重試上限的 agent 節點（仍需配置有能力變更、負責人核准、流程總次數未用盡、前置契約有效）。
- **選擇題的邊界**：`human_decision.options` 只能放「負責人選了、流程就能照做」的事；需要有人改配置、補環境、
  給權限的，寫進 `also_needed`。結案與改用新任務要用選項的 `action=close`／`successor`，由系統直接執行，
  不可寫成交回流程的 `decide`（流程沒有結案路線，見 [plan-approval-buttons](plan-approval-buttons.md#由-agent-本人說明需要你決定的事)）。
  同一個問題已回答過卻又被問時，不再提供 `decide` 按鈕並說明原因。
  2026-10-18 起，受阻來源是系統獨立驗證時，系統會在下游脈絡注入「這是硬閘門、真人選擇無法略過」的固定說明，
  triage 提示也明文禁止「接受失敗／記為無法核實後繼續」類選項；詳見 [engineering-workflow](engineering-workflow.md#系統獨立驗證)。
- **實作的品質檢查自陳會被核對**：實作回報成功時，系統讀 Claude／Codex CLI 自己的指令紀錄，本節點配置操作最後一次執行失敗就不採用
  這份成功回報（2026-10-18 起）。共用契約同時規定 `verify_passed=true` 只能在必要檢查都實際執行且結束碼為 0 時填寫，
  「無法核實」是驗收判定，不是實作成功的理由。
- **計畫階段與 plan_revision**：`plan_revision` 由系統在計畫完成後計算；系統會明確告知計畫節點不得因缺少它而受阻
  （同一角色常兼任計畫與驗收，會看到驗收要引用 plan_revision 的規則）。
- **操作契約與測試規範要一致**：`testInstructions` 要求的每一步都必須有對應的 `operations`，
  否則計畫節點無法綁定 `operation_ids` 而受阻。`argv` 不支援管線，需要多步驟時拆成多個操作。
- **操作輸出目錄自動排除**：每個工作區階段開始前，`FlowEngine.excludeOperationOutputs` 會收集配置中所有固定操作的 `writes` 目錄，
  交給 `WorkspaceManager.excludeOutputDirs` 寫進該任務工作區的 `.git/info/exclude`（系統管理區塊，只在本機，不會被 commit）。
  被排除的目錄不會出現在寫入範圍稽核（`auditPaths`）、未提交清單與「保存並承接」裡，所以建置產物和 QA 副本裡的
  `package.json` 之類不會被誤判成修改受保護檔案，也不會被代為提交。目錄底下只要有任何 git 追蹤的檔案就**不排除**（fail closed），
  所以 `writes` 不能拿來藏真正的原始碼。適用所有團隊與既有快照，專案不需要改 `.gitignore`。
  同一批目錄在限寫節點的沙箱裡也不會被 protectedPaths 釘成唯讀（`ConfiguredExecutor.protectedPins`），QA 才能清理和重建自己的副本。
- **主 repo 執行的節點先更新遠端記錄**：`workspace=false` 的節點（intake、design、triage 等）與以主 repo
  回答的角色對話，在 `snapshot.repository()` 主 repo 執行。主 repo 原本只在建立工作區（`WorkspaceManager.provision`）或基準驗證（`baseRef`）時 fetch，
  agent 可能以過時的 `origin/<基準分支>` 推論（例如猜錯 PR 衝突檔）。
  現在 `ConfiguredExecutor.remoteFreshness` 會在派工前呼叫 `WorkspaceManager.refreshOrigin`：
  - 執行 `git fetch --quiet --prune origin`，只更新 refs/remotes，不碰主 repo 的本機分支與工作樹；
  - 同一個 repo 一次只 fetch 一個，60 秒內成功過就沿用；
  - 提示會寫明「遠端記錄於 <台北時間> 更新」；fetch 失敗時不擋派工，但要求 agent 把涉及遠端的判斷標成「無法確認最新狀態」。

  不設開關，所有團隊適用；工作區節點不受影響。
- **實作節點動手前同步最新基準**（2026-10-24）：工作區只在建立時取一次基準，之後每一輪 `scope=WORKSPACE` 的 agent 節點開始前，
  系統先 fetch 並以一般 merge 把最新基準併進任務分支，衝突交給該輪角色先解（`BaseSync`）。
  不設開關，所有團隊適用；細節見 [工程流程](engineering-workflow.md#實作前同步最新基準2026-10-24)。
- **已開 PR 衝突同步**：見[工程流程與驗證邊界](engineering-workflow.md#已開-pr-的衝突同步2026-10-23)。
  以 `project.autonomy.pullRequestSyncRounds` 開啟；開啟後，該實例所有仍開啟、且由系統開出的 PR 都會納入檢查。


## 服務建置分支

配置 `service` 節點的團隊，intake 可選 service，由 Infrastructure 在 LIMITED 設定／手冊範圍建置並驗證服務，受阻交 service_triage。
這是 Gate.SERVICE 契約，不更動產品開發的 IMPLEMENTATION 前置測試計畫，也不能用來直接發布 PR。
未設定 serviceTarget 時明確停止等待主機與操作資訊；詳見 [服務建置](service-delivery.md)。


## 配置式 skills

AGENT 節點可設定 `skills`，最多八份不重複的 SKILL.md／references 相對路徑。TeamCatalog 在 teams 內讀取並防止路徑越界，內容寫入快照、參與 revision；ConfiguredExecutor 每次非對話派工注入相同內容，改派角色也保留。
例如 service 與 service_triage 節點可使用 `service-build`；helpers 隨 teams 部署，操作與主機授權仍由既有契約及 broker 核對。參考 [服務建置](service-delivery.md)。
