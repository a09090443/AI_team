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
| `.claude/` 文件修改 | Claude Code 實作角色寫不了 `.claude/`；實作節點設 `protectedPathFallback`，`.claude/rules/**`、`.claude/skills/**` 改派 `builder_codex`（Codex、gpt-5.6-sol，報告仍以 Backend Agent 身分貼出）。settings／hooks 等仍須真人處理（2026-10-04 起，#1014） |
| 漂移掃描 | `scripts/harness-drift-scan.sh` 是 bash 腳本，不能當 operation 或系統驗證指令；寫在建置指示，由實作 agent 以 `bash scripts/harness-drift-scan.sh` 自行執行。**不能列為驗收項目**：測試計畫只能引用操作或內建 `inspect-content`，#1010／#1011 曾因 PM 把它寫成驗收項目而無法驗收；改由審查檢查 |
| 前端 | `npm --prefix bestpartner-ui ci`／`run build`（vue-tsc）／`run test`（vitest）；驗收用 `--offline` |
| 系統獨立驗證 | 後端 A、B 兩組＋前端 ci／build／test，共 5 條；在全新 clone 與私有 HOME 執行，會重新下載相依（第一次較慢）。`docOnlyPaths`＝`*.md`、`docs/**`、`.claude/**`、`.agents/**`：變更全部在其中時略過（2026-10-03 起；已查證程式只在註解提到 `.claude/rules/`；`docs-site/` 會被建置所以不列入） |
| repo hooks | repo 的 `.claude/settings.json` 掛了 pwsh hooks；VM 沒有 pwsh，實測只產生非阻擋的錯誤訊息，工具呼叫照常完成 |

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
agent 只回傳 `route` 的鍵，不能自行指定角色或跳至任意節點；未知 route 明確停止。
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
純詢問（route=`question` → `end`）的回答若列了 `out_of_scope`，結束時會附「開成新需求」按鈕（`SpinoffButtons`、`FlowEngine.spinoff`），
按鈕名稱取自同一份報告的 `out_of_scope_titles`（`schemas/requirements.json` 必填、與 `out_of_scope` 等長；不等長時全部退回序號），
在新討論串開立任務，原討論串仍是一串一任務。
用量暫停走既有繼續按鈕；新模式不使用舊版「重新開始」按鈕，以免誤封存工作區或套錯流程。
配置角色的一般對話使用獨立子請求，不會覆蓋原驗收；新加入、但不在原快照裡的角色不能接手舊任務對話。

## 快照與相容策略

`flow_run` 是 additive 資料表，保存每個新模式任務的完整配置、prompt/schema 內容、版本雜湊、
目前節點、執行次數、證據收據、工作區位置與發布目標。Token **值**不進快照，只保存環境變數引用。
CLI session 仍存在原 runtime 狀態目錄，checkpoint 保存其 ID；環境變數所指的實際憑證仍由維運管理。

修改／移除目前配置的角色不影響舊任務快照；舊快照需要的 runtime、工具、token 引用與工作區仍須保留。
如果它們被移除，任務會明確受阻，不會自動換引擎、使用新 repo 或重建現場。
重啟時執行中、排隊或發布中的新模式任務都停下並要求人工確認，不自動重播可能已完成的外部動作。

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
  給權限的，寫進 `also_needed`。同一個問題已回答過卻又被問時，不再提供按鈕並說明原因。
- **計畫階段與 plan_revision**：`plan_revision` 由系統在計畫完成後計算；系統會明確告知計畫節點不得因缺少它而受阻
  （同一角色常兼任計畫與驗收，會看到驗收要引用 plan_revision 的規則）。
- **操作契約與測試規範要一致**：`testInstructions` 要求的每一步都必須有對應的 `operations`，
  否則計畫節點無法綁定 `operation_ids` 而受阻。`argv` 不支援管線，需要多步驟時拆成多個操作。
