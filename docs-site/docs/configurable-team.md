# 配置式專案、角色與工作流程

跨專案操作契約、每階段沙箱預檢、真人計畫核准及受控配置遷移，見 [跨專案執行契約](cross-project-execution.md)；
需求基準、前置契約與失效傳遞、系統獨立驗證及 PR_CREATED 見 [工程流程與驗證邊界](engineering-workflow.md)。

## 狀態與範圍

既有 VM 自 2026-09-06 起以非機密 `team.env` 設定 `AI_TEAM_DEFINITION` 啟用配置式模式（version 2），
部署紀錄見 [2026-09-06 v2 部署](deploy-2026-09-06-v2.md)；憑證仍在 env.sh。

新模式將角色名稱、模型、runtime、Slack token 引用、工具清單與流程節點放入 JSON。
JSON 為可直接解析的配置格式，不代表未來綁定某個 CLI；runtime 仍由既有 adapter registry 決定。
目前每個服務實例選擇一個專案配置，不是單一程序同時管理多個專案；多個專案請見
[同一台 VM 多個服務實例](#同一台-vm-多個服務實例)。

## 啟用與配置

新模式明確 opt-in，`AI_TEAM_DEFINITION` 留空則維持 legacy 行為。
設定 VM `env.sh`（範例；不要把 token 放進 JSON）：

```bash
export AI_TEAM_DEFINITION="$AI_TEAM_HOME/teams/spring-starters.json"
```

新安裝會複製 `orchestrator/teams/`；既有服務升級須一併部署此資料夾與新版 jar，
先備份資料庫及舊版檔案，再確認無執行中／排隊任務後重啟。不可在執行中的 target/ 建置。
服務啟動時完整讀取配置與資產；修改配置後須重啟，新任務才採用新版本。

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

每個專案各跑一個服務（2026-10-03 決策，見 `.ai/decisions.md`）。一個服務只載入一個專案配置，
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

| 用途 | 留空（單一實例，舊名稱） | `AI_TEAM_INSTANCE=starters` |
|---|---|---|
| agent | `ai-team-<任務>-<角色>-…` | `ai-team-starters-<任務>-<角色>-…` |
| 系統驗證 | `ai-team-check-<任務>-…` | `ai-team-starters-check-<任務>-…` |
| 自檢／預檢 | `ai-team-selftest-…`、`ai-team-preflight-…`、`ai-team-codex-preflight-…` | 同左，前面加 `starters-` |

格式：小寫英文字母開頭、只含小寫英數、最多 16 字；不可為 `check`、`preflight`、`selftest`、`codex`
（舊名稱的固定字首）。不合法時服務啟動失敗。實例名以字母開頭、任務編號是純數字，所以新舊格式的 glob 互不配對。
不用配置的 `id` 當實例名，是因為 `node-web.json` 這類配置可給多個 repo 共用，`id` 不保證唯一。

相關檢查：`preflight.sh` 偵測到其他安裝目錄的服務在跑、而本實例 `AI_TEAM_INSTANCE` 留空時直接失敗（不檢查兩個實例是否同名，需管理者自行確保）；
`service.sh status` 只列本實例 PID 的 443 連線；`deploy/deploy-jar-safe.sh` 的第三個參數是安裝目錄，
「有 unit 在跑就拒絕部署」只看本實例的 unit。
完整部署腳本 `deploy-orchestrator.sh` 也支援只安裝 JAR 的目錄：一律備份與更新 JAR／teams，
`src/main` 只有原安裝包含來源時才備份、更新與回復，不因缺少來源目錄中止。

**現行做法（2026-10-03 起，workspace 已升級 Pro，App 數量不限）**：每個實例各自一組主 App＋角色 App，Bot Token 與
App-Level Token 都填自己的。bestpartner 使用 bp-ai-team 與 5 個「BP」角色 App，`#bestpartner` 只留這 6 個 App
（starters 的角色 App 已移出，避免 @ 到同名的別組角色而被 starters 以「忽略非指定頻道」丟棄）。
2026-10-03 換成 BP 角色 token 並重啟後，5 個角色的直接對話 Socket Mode 皆啟動、0 ERROR，
在 `#bestpartner` 直接 @BP 角色已實測由 bestpartner 收到並完成唯讀角色對話（bestpartner #1003、#1004，coordinator）。

**已被取代：Slack 免費方案時期的共用角色身分（2026-10-03 實測，升級 Pro 前）**：免費 workspace 最多安裝 10 個 App，兩個實例各 6 個裝不下。
做法是主 App 各自一個，角色沿用第一個實例的 App，但第二個實例**只填角色的 Bot Token（`SLACK_BOT_TOKEN_<角色>`）、
App-Level Token 留空**：發言與交接 tag 使用同一個角色身分，不影響第一個實例；代價是第二個實例的頻道不能直接 @角色
（事件會送到持有 App-Level Token 的第一個實例，它只記錄「忽略非指定頻道」後丟棄）。角色 App 要另外邀請進第二個頻道。
**不可**在兩個實例填同一個 App-Level Token，否則 Slack 會把事件分給任一條連線。

**驗證狀態**：unit 命名、glob 互不配對與 preflight 檢查有程式測試與 VM 實測紀錄（見 `.ai/progress.md` 2026-10-03）；
2026-10-03 已在同一台 VM 同時啟動 starters 與 bestpartner 兩個實例（各自 0 ERROR、自檢 unit 帶各自字首、443 連線分屬各自 PID）。
**兩個實例的真實 Slack 端對端（提需求→PR）尚未驗證**。

## bestpartner 配置

`orchestrator/teams/bestpartner.json`（2026-10-03）是第一個 Gradle 專案配置，供 bestpartner 實例
（Kotlin/Quarkus＋Gradle 後端、Vue/Vite 前端、Docusaurus 文件，基準分支 `dev`）使用。
2026-10-09 已先加入 `infrastructure` 角色與獨立 Slack token 引用，供直接 @ 的唯讀環境對話；Slack App
`BP Infrastructure Agent`（App ID `A0C7WUJN0JF`）已安裝到 `AI-team` 並加入 `#bestpartner`。App-Level Token 已建立，
2026-10-09 已把配置與 prompt 部署到 bestpartner 實例並以既有 JAR 通過靜態驗證；使用者已把兩個 token 寫入
bestpartner 實例的 `env.sh`，服務啟動後 Spring、沙箱自檢與 6 個角色 Socket Mode 連線皆通過、log 無 ERROR。
直接 `@BP Infrastructure Agent` 的事件送達與模型回覆仍待真人在 Slack 實測。
角色 runtime 採 Codex，模型為 `gpt-5.6-sol`、`reasoningEffort: "medium"`。
自主派工配置讓此角色可承接環境設計與工作區內的 Compose／初始化腳本／文件實作。
Preview 部署節點、外部 VM 權限與部署收據尚未實作，所以此角色目前不會自行部署、停止或刪除環境。
2026-10-04 起基準分支由 `master` 改為 `dev`：任務分支從 `origin/dev` 開，PR 合併到 `dev`。
遠端必須先有 `dev` 分支，否則建工作區時 `git fetch … refs/remotes/origin/dev` 失敗、任務停止；
改設定前建立的任務沿用快照裡的 `master`，不受影響。
角色、模型與流程節點沿用 `spring-starters.json`，差異在專案段：

| 項目 | 內容與理由 |
|---|---|
| 工具 | `requiredTools` 用 PATH 上的 `gradle`（8.14.5，與 repo 的 gradlew 同版），因為 operations 的執行檔必須是工具名稱，不能是 `./gradlew` 路徑或 shell。安裝方式見 [INSTALL §5.2](INSTALL.md#52-gradle-專案的額外前置) |
| 快取 | `cachePaths` 為 `.cache/gradle` 與 `.npm`；env 設 `GRADLE_USER_HOME=$HOME/.cache/gradle`。只開放 `~/.gradle/caches` 時 Gradle 以「Could not initialize native services」失敗（VM 實測） |
| 資料庫測試 | **不在自動驗收範圍**（使用者決定 A）：VM 沒有 PostgreSQL／Docker，15 個 `@QuarkusTest` 與需要外部憑證的測試不跑；需要它們的情境由 QA 標「無法核實」 |
| 後端測試 | 23 個不需資料庫的測試類別（含 `ArchitectureTest`）分成 `backend-test-a`、`backend-test-b`、`backend-test-code-executor` 三個操作（argv 上限 32）。清單由 VM 實跑判定，新增測試類別要手動加入 |
| CodeExecutorTest | 「無窮迴圈於 timeoutMs 後強制中斷」在負載下會超過 30 秒，單獨執行則通過；所以單獨成一個操作，也**不在系統獨立驗證內**（上限 5 條指令） |
| `.claude/` 文件修改 | Claude Code 實作角色寫不了 `.claude/`；實作節點設 `protectedPathFallback`，`.claude/rules/**`、`.claude/skills/**` 改派 `builder_codex`（Codex、gpt-5.6-sol，報告仍以 Backend Agent 身分貼出）。settings／hooks 等仍須真人處理（2026-10-04 起，#1014）。2026-10-04 改為分段改派：`builder_codex` 只改這兩個目錄的檔案，其餘（含 `.agents/`、程式、建置測試）由 `builder` 接著完成，見 [多 runtime](multi-runtime.md)。第一個分段改派任務 #1021（2026-10-04）改派段沒改任何檔案：派工說明誤把 Claude 的「`.claude/` 一律拒寫、不要嘗試」也給了 Codex，2026-10-05 修正為依實際執行角色判斷（commit 242d49f）；#1022 實測：`builder_codex` 改好 2 個 `.claude/` 檔由系統提交（1621998），`builder` 接著改 `.agents/`（60b8ca5），獨立驗證與 QA 通過 |
| 漂移掃描 | `scripts/harness-drift-scan.sh` 是 bash 腳本，不能當 operation 或系統驗證指令；寫在建置指示，由實作 agent 以 `bash scripts/harness-drift-scan.sh` 自行執行。**不能列為驗收項目**：測試計畫只能引用操作或內建 `inspect-content`，#1010／#1011 曾因 PM 把它寫成驗收項目而無法驗收；改由審查檢查 |
| 前端 | `npm --prefix bestpartner-ui ci`／`run build`（vue-tsc）／`run test`（vitest）；驗收用 `--offline` |
| 系統獨立驗證 | 後端 A、B 兩組與 CodeExecutorTest＋前端 ci／build／test，共 6 條；在全新 clone 與私有 HOME 執行，會重新下載相依（第一次較慢）。`docOnlyPaths`＝`*.md`、`docs/**`、`.claude/**`、`.agents/**`、`docs-site/docs/**`、`docs-site/blog/**`：變更全部在其中且通過文字副檔名及強制全跑檢查時略過（2026-10-03 起；已查證程式只在註解提到 `.claude/rules/`）。原本以「`docs-site/` 會被建置」為由不列入，但這 5 條指令都不建置 docs-site，#1018 只改一行 `docs-site/docs/` 卻要跑完後端與前端；2026-10-04 起加入兩個純 Markdown 目錄，docs-site 的設定檔、`src/`、`package.json` 照樣全跑 |
| Codex 節點跑 Gradle（2026-10-04 查證；已處理，真實任務尚未實測） | 沒開 `localTestNetwork` 的 Codex 節點啟動不了 Gradle。處理方式：`verification`（QA）開 `localTestNetwork`；`builder_codex` 改為分段改派的改派段，只改 `.claude/` 檔案、不跑建置（實作節點不能開 `localTestNetwork`）。以下為查證紀錄。原因在 Codex 內層沙箱，不在外層 systemd：① `-s workspace-write` 只開放工作區與 `/tmp` 可寫，`~/.cache/gradle` 寫不進去，出現「Failed to load native library 'libnative-platform.so'」；② 沒有網路 namespace 介面，Gradle 開始 build 就出現「Could not determine a usable wildcard IP」（`network_access=true` 也一樣）。VM 上以 codex-cli 0.156.1 `codex sandbox` 對照實測：快取可寫但沒有網路仍失敗；改用 `localTestNetwork` 同款 profile（快取可寫＋隔離 namespace 網路、domain 清單為空）則 `BUILD SUCCESSFUL`。上一列的「VM 實測」只模擬了外層沙箱。#1018 的改派實作因此受阻；系統獨立驗證不經 Codex，不受影響。2026-10-04 起實作節點會收到「純文件變更不編譯」的說明（見 [工程流程](engineering-workflow.md)），避開純文件任務的這個問題；QA 的 `qa-backend-*` 由 `localTestNetwork` 處理（設定屬新快照，舊任務需走配置遷移才會取得） |
| 改派後 `.agents/**` 寫不進去（2026-10-04 查證；已由分段改派處理，真實任務尚未實測） | 整節點改派時是整個實作節點交給 `builder_codex`，但 Codex 內層沙箱把工作區的 `.agents` 掛成唯讀（[雙層沙箱](codex-nested-sandbox.md) 為它建立的掛載點），`.claude/**` 與一般檔案則可寫（VM 上以 codex-cli 0.156.1 `codex sandbox` 實測）。bestpartner 的 skill 同時存在 `.claude/skills/` 與 `.agents/skills/`，改 skill 的需求一改派就一定寫不了 `.agents/**`。#1020 同時要改 `build.gradle.kts`、`.claude/**` 與 `.agents/**`：第一次的 patch 一起被拒（`writing outside of the project`，推測是同一個 patch 含 `.agents` 檔），後端測試又因上一列的 Gradle 問題跑不了，停在 AWAIT_HUMAN。Claude Code 的 `builder` 寫得了 `.agents/**` 也跑得了 Gradle，只是寫不了 `.claude/**`，所以改成分段改派：受保護檔案交給 Codex，`.agents/**` 與其餘工作由 `builder` 完成。已整節點改派的舊任務（#1020）沿用舊行為 |
| repo hooks | repo 的 `.claude/settings.json` 掛了 pwsh hooks；VM 沒有 pwsh，實測只產生非阻擋的錯誤訊息，工具呼叫照常完成 |

## dynamic-api 配置

`orchestrator/teams/dynamic-api.json`（2026-10-17）供第三個實例使用，專案為
[a09090443/dynamic-api](https://github.com/a09090443/dynamic-api)（Spring Boot 3.2.5／Java 17＋Gradle 後端、Next.js 15 管理介面、
Docusaurus 文件，基準分支 `master`）。以 bestpartner 為範本，角色、模型與審查／分流節點相同；
**未啟用**角色諮詢與共享看板（`collaboration`／`consultRoles`，在第一階段分支 feature/team-collaboration-phase1 開發中，尚未合併與部署）。其他差異如下：

| 項目 | 內容與理由 |
|---|---|
| 工具 | 沿用 PATH 上的 `gradle`（VM 共用 8.14.5），repo 的 gradlew 是 8.7；operations 的執行檔只能是工具名稱。2026-10-18 VM 實測 8.14.5 可建置 Spring Boot 3.2.5 主程式（連線與 `--offline` 皆成功） |
| 前端建置任務 | `backend/build.gradle` 的 `installNextjs`／`buildNextjs` 寫死 Windows 版 `tools/node-*-win-x64/npm.cmd`，Linux 上必定失敗；`processResources` 依賴它，所以會用到它的操作（含 `compileTestJava`、`test`）一律帶 `-x buildFrontend`。2026-10-18 實測：原本 `backend-compile` 沒帶，`compileTestJava` 觸發 `installNextjs` 而失敗，已補上。建置指示禁止 agent 改 `build.gradle`／`tools/` 繞過 |
| 後端測試 | 只有 `ResponseRepositoryTest`、`DynamicControllerServiceTest`，以單一 `backend-test`／`qa-backend-test` 執行。**已知問題（2026-10-18 實測）**：repo `master`（3f638e3）的 `ResponseRepositoryTest.java:15` 呼叫 `MockResponseDao.findByPrimaryKey` 少了 `ServiceType` 參數，測試程式編譯失敗，所以 `backend-test`、`qa-backend-test` 與系統獨立驗證的後端測試目前必定失敗，要等 repo 修正。預設設定含 `D:/tmp`、`C:/tmp`，修正後 Linux 能否通過仍未實測 |
| 前端 | `npm --prefix nextjs-dynamic-api-manager ci`／`run type-check`／`run lint`／`run build`；驗收用 `--offline`。2026-10-18 VM 實測四步皆成功（ci 約 9 分鐘、build 約 80 秒），文件站 `ci`／`build` 也成功 |
| 動態載入 | 上傳 JAR、啟用服務、Mock 命中需要啟動服務並用範例 JAR 手動驗證；沙箱內無法完成時 QA 標「無法核實」 |
| 服務建置 | **不配置** `service`／`service_triage` 節點與 `serviceTarget`（service-build skill 目前只實作 bestpartner）；intake 沒有 `service` 路線。`infrastructure` 角色只作為設計／實作候選 |
| `.claude/` 文件 | 同 bestpartner，`.claude/rules/**`、`.claude/skills/**` 分段改派 `builder_codex` |
| 系統獨立驗證 | 後端測試＋前端 ci／type-check／build 共 4 條；`docOnlyPaths` 同 bestpartner |

Slack 使用自己的一組 App（`deploy/agent-manifests/dynamic-api/`，見 [建立 Slack App](slack-agent-apps.md#dynamic-api-實例的-app-組)），頻道 `#dynamic-api`（`C0CA5CXTUHJ`，2026-10-17 建立的公開頻道）。
**目前狀態（2026-10-18）**：VM 實例已安裝在 `~/ai-team-dynamic-api`（`AI_TEAM_INSTANCE=dynamicapi`），配置在 VM 通過 `--validate-team`，
Gradle／npm 快取已在沙箱外預熱；頻道與 7 個 Slack App 已建立並加入頻道。App-Level Token 尚未產生、token 未填、服務未啟動，
沒有 Slack 端對端證據。

## 不改 Java 的增刪例子

- `orchestrator/teams/spring-starters.json`：設計、測試計畫、實作、驗收、獨立審查、人工 PR 核准。
- `orchestrator/teams/node-web.json`：改用 `AI_TEAM_NODE_REPO` 與 `main`，必要工具換為 Node/npm；
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
  不含萬用字元的明確目錄（例如 `bestpartner-service/build/**`）不存在時，派工前先建立空目錄再納入可寫範圍；
  路徑祖先是 symlink 時停止。含萬用字元的政策不猜測缺少的目錄，全部找不到時停止（fail closed）。
  2026-10-04 起適用所有 LIMITED 節點；之前只對 `localTestNetwork` 節點建立，#1015 只改文件、沒跑建置，
  `build/` 不存在，驗收的操作預檢因此判定「寫入超出沙箱政策」而停下。
  在沙箱外逐筆核對新增 commit、未提交與未追蹤路徑，攔截 Git index 繞道及「修改後再還原」；
  單階段超過 100 個新增 commit 會停止要求查核。沒有可寫目錄時拒絕，不自動放寬。
  工作區內符合 `project.protectedPaths` 的檔案與目錄會逐一釘成唯讀（`ConfiguredExecutor.protectedPins`）；
  走訪時不進入 `node_modules`，因為裡面是安裝產物，不是產品檔案，驗收的 `npm ci` 還要整個刪掉重裝。
  沒有 git 追蹤檔的操作輸出目錄（`writes`，判斷同下方「操作輸出目錄自動排除」，每次都重新問 git，不讀 `.git/info/exclude`）也整個不釘選。
  2026-10-17 起才有這個規則：#1044 的 QA 副本 `target/qa-docs/docs-site/package.json` 被釘成唯讀，下一輪 `find -delete` 得到 `Device or resource busy`，清理一直失敗。
  2026-10-08 前會進入：bestpartner #1029 實作跑過 `npm ci` 後，`**/package.json` 釘了 1643 個 `node_modules` 內的檔案。
  Codex 的 permissions 設定是單一參數，約 187 KB，超過 Linux 單一參數 128 KB 上限，QA 以 `Argument list too long` 啟動失敗。
  系統把它當系統層失敗自動重試兩次，每次都重跑約 25 分鐘的獨立驗證。

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
這是 2026-10-11 的提示修正，已部署 bestpartner（配置 5263f682…），啟動與資料完整性檢查通過；模型分類尚待新任務實測，不是關鍵字硬編碼閘門，既有任務快照不變。
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
  起因是 #1044：分流判斷正確，只是把 role 填成「QA（verification）」，系統只接受 `tester`，任務因此停下來等人。
- 目前 bestpartner 設計候選為 Architecture／Infrastructure，實作候選為 Backend／Infrastructure。
  因此 PM 可將環境設計交給 Infrastructure，測試計畫 agent 再選實作負責人；受阻後由分流 agent 重新選擇。
  第一版每次交接一位角色、中央佇列依序執行，不支援一份報告建立多個平行子工作。
- 系統檢查候選資格、必要操作的工具權限，啟動時沿用沙箱預檢；階段的 scope、schema、requires、QA／Review／核准條件不變。
  實作候選與審查候選不得重疊。沒有適任角色時須說明缺少能力並受阻，不得自動擴權。
- Slack 顯示實際負責角色、派工理由與交接工作；`assignments` 保存目前選擇，`assignmentHistory` 保存歷史。
  額度與中斷續跑沿用原選擇；改派後開新階段 session。歷史最多 256 筆，既有節點／整輪／自動交接上限仍有效。
- Codex 自主派工實作完成後，系統核對實際變更，不允許工具行為／版控禁止路徑，再代為 commit；後續仍跑獨立驗證與審查。
  Infrastructure 可在任務工作區產出環境檔案，遠端 VM 部署、停止與清理仍未開放。一般直接 @ 對話仍唯讀。
- 舊配置缺少 `candidateRoles` 時沿原角色執行。新欄位會寫入快照，舊 JAR 不能讀取新版快照，回滾須搭配部署前 DB 備份。
  角色集合／流程派工政策變更不允許原地配置遷移；#1030 等舊任務需由負責人使用「改用新任務繼續」。

### 文件任務的派工限制與已知問題（2026-10-12 查核）

2026-10-12 已在來源修正提示、操作配置與改派範圍判斷；2026-10-14 已部署 bestpartner 與 spring-starters，尚未驗收真實模型端對端。
文件任務採輕量**工作內容**，並未移除流程節點：bestpartner／spring-starters 仍經 design、plan、implementation、verification、review；
plan 與 verification 固定由 tester 負責。node-web 範例仍經 builder／frontend。`candidateRoles` 只選執行者，不改 `requires`。

- PM 區分純文字、文件網站／生成產物、程式／SQL／設定與服務建置，盤點使用者與 agent 文件，不自行縮小需求。
- 設計只做與工作相關的來源、範圍及同步分析；純文字不添加 API 架構設計或無關回歸。
- 實作由具文件能力的角色承接；QA 用 `inspect-content` 合併驗證內容、新舊值及一致性，審查不追加無關情境。
- bestpartner 新增 verification 的 qa-docs-dir → qa-docs-clean → qa-docs-snapshot → qa-docs-extract → qa-docs-install → qa-docs-build：
  先清除上輪 docs-site 副本，再從最終 HEAD 匯出 docs-site 至 target/qa-docs，離線安裝並建置，原文件唯讀。實作先執行 docs-install／docs-build 準備 npm 快取。
  qa-docs-clean 為 `find target/qa-docs -mindepth 1 -delete`（清空 target/qa-docs 內容、保留目錄，須在 qa-docs-dir 之後）。
  2026-10-16 起不用 `rm -rf`：Codex CLI 0.156.1 會在啟動程序前拒絕 rm -f 類指令，#1043 因此兩輪驗收卡在此步；`find -delete` 已在 VM 的 Codex 實測可執行。
  固定操作 argv 不允許空白、引號等 shell 字元，所以不能改用 `node -e` 單行腳本。
  2026-10-17 起所有團隊配置的操作都不可用 `rm` 當執行檔，`OperationContract.validate` 載入配置時就拒絕，錯誤訊息會提示改用 `find`。
  2026-10-16 #1044：產品 repo 沒忽略 target/，驗收後的寫入範圍稽核用 `git ls-files --others --exclude-standard` 掃到副本的
  `target/qa-docs/docs-site/package-lock.json`。它符合 protectedPaths 的 `**/package-lock.json`，所以被判成「限寫階段越權」。
  現在改由系統統一處理，見下方「操作輸出目錄自動排除」，專案不需要自己改 `.gitignore`。
  spring-starters 沿用既有隔離文件驗證。其他專案須提供自身操作；提示不會產生不存在的工具能力。
- 三份範例配置均提供 `docOnlyPaths`，實際變更仍由 Git 判斷。SQL、腳本、JSON、網站程式等非文字副檔名不能因位於 docs/** 就免驗；
  純文字限 .md／.mdx／.txt／.rst／.adoc，仍受原建置檔與 src 等強制全跑名單限制。QA 必要文件站建置不因系統略過產品建置而取消。

#1040 曾因缺少 QA 文件站建置操作受阻；#1041 的文件 7 情境通過，但系統額外提交 8 個上游禁止修改的 .claude 檔案而被 QA 攔截。
新的 plan schema 要求 `protected_path_changes` 明列需跨 runtime 改派的檔案（無則 []），不再從任意報告文字提取路徑。
計畫拒絕不安全、重複、禁止路徑及與 `unchanged_paths` 的衝突；改派只讀本實作節點 `requires` 中 TEST_PLAN 的清單，
實際變更必須同時符合該清單及配置目錄白名單，否則禁止代提交及下游執行。提及或參考檔案不再觸發改派。

舊快照不會自動獲得新 schema／提示／操作；舊計畫沒有清單時不再猜測分段改派，若確需修改受保護檔案須更新配置與計畫，
或承接成使用新配置的任務。已保存的整節點改派與已完成分段收據仍保留，沒有自動還原或改寫既有成果。
提示與清單無法證明模型完全理解使用者意圖，QA／Review 仍須核對原需求；任務只是修改手冊時，不可宣稱已實際安裝成功。

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

`spring-starters` 的 tester 使用 `runtime: "codex"`、
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
  或排隊、沒有 agent 程序、所有團隊配置通過驗證，並比對所有任務資料與所有任務工作區在部署前後一致。
- **卡在重試上限時**：目前這一步無法再啟動、延長也幫不上忙時，停止訊息不再提供選擇題、補充或「繼續」
  （按了都不會重新執行），而是說明原因。管理者部署修正後的配置，送「補充 #編號 狀態」會出現
  「檢視配置更新」→「核准配置更新」→「重新執行這一步」三顆依序出現的按鈕。受控恢復授權
  適用任何卡在重試上限的 agent 節點（仍需配置有能力變更、負責人核准、流程總次數未用盡、前置契約有效）。
- **選擇題的邊界**：`human_decision.options` 只能放「負責人選了、流程就能照做」的事；需要有人改配置、補環境、
  給權限的，寫進 `also_needed`。結案與改用新任務要用選項的 `action=close`／`successor`，由系統直接執行，
  不可寫成交回流程的 `decide`（流程沒有結案路線，見 [plan-approval-buttons](plan-approval-buttons.md#由-agent-本人說明需要你決定的事)）。
  同一個問題已回答過卻又被問時，不再提供 `decide` 按鈕並說明原因。
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


## 服務建置分支

bestpartner 的 intake 可選 service，由 Infrastructure 在 LIMITED 設定／手冊範圍建置並驗證服務，受阻交 service_triage。
這是 Gate.SERVICE 契約，不更動產品開發的 IMPLEMENTATION 前置測試計畫，也不能用來直接發布 PR。
未設定 serviceTarget 時明確停止等待主機與操作資訊；詳見 [服務建置](service-delivery.md)。


## 配置式 skills

AGENT 節點可設定 `skills`，最多八份不重複的 SKILL.md／references 相對路徑。TeamCatalog 在 teams 內讀取並防止路徑越界，內容寫入快照、參與 revision；ConfiguredExecutor 每次非對話派工注入相同內容，改派角色也保留。
目前 bestpartner 的 service 與 service_triage 使用 `service-build`；helpers 隨 teams 部署，操作與主機授權仍由既有契約及 broker 核對。參考 [服務建置](service-delivery.md)。
