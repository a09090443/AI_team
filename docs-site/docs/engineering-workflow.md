# 需求追溯、獨立驗證與交付邊界

## 狀態

僅適用配置式 `version: 2` 流程，兩份 teams 範例皆為 v2；既有 v1 快照及 legacy 任務不自動升級。
2026-09-06 起已部署於既有 VM，見 [v2 部署紀錄](deploy-2026-09-06-v2.md)。

## 需求基準與責任

### 跨專案問題完整性（2026-10-11）

新版 `TeamCatalog` 對所有新 v2 團隊快照加入 `contracts/issue-tracking-v1` 共用契約，與角色名稱、語言、專案名稱無關。
既有快照沒有此標記則維持舊契約。2026-10-11 已部署 bestpartner 與 spring-starters 的共用引擎；
Linux 完整測試 619 項、0 失敗、0 錯誤、11 略過，兩實例啟動與沙箱檢查通過。尚未執行新契約的真實模型端對端驗收。
部署版本與備份見 [部署紀錄](deploy-2026-09-06-v2.md#2026-10-11-跨專案問題完整性升級)；不能把程式測試當成模型端對端成功。

- 每階段回傳 `issue_updates`（新發現／更新）、`issue_revision`（輸入清單版本）、`issue_checks`（逐項檢查）。
  需求階段另有 `work_intent=WORK|READ_ONLY` 與 `intent_evidence`。共用提示要求目標內查核問題自動派修；明確只讀或知識問答維持唯讀。
- 每項有穩定 `id`、`description`、`evidence`、`scope`、`reason`、`requirement_ids`。範圍為 `REPAIR`、`DECISION`、`EXCLUDED`、`OBSERVATION`。
  系統加上來源任務與節點；需求必須重列完整已知清單，REPAIR 必須對應需求（service 路線例外）。WORK 不可把問題只列為 OBSERVATION 或以 question 結束。
- 完整清單另存 `FlowRun.issueInventory`，不隨對話摘要截斷；空更新不刪既有問題。ID 重複、改寫既有描述、未授權降為排除、過期清單均被拒絕。
  需求草稿的結構／分類錯誤先交回原節點自修，沿用有界重試；後續問題依原配置 blocked 路由交責任角色，無路線或預算用盡才停下。
  **記帳格式錯誤自動修正（2026-10-15 已部署）**：任何節點只因改寫既有 description、清單版本過期或缺問題追蹤欄位被拒時，
  系統附上清單原文／目前版本，請同一節點重送一次，還原本輪計數、不消耗自動交接預算；連續第二次仍錯才走原 blocked 路由。
  降級未解問題、未經真人改待決項、改需求對應等原則類錯誤不自動重送。新快照的共用契約另明寫「description 逐字沿用，進度與決定寫 evidence／reason」。
  起因是 bestpartner #1040、#1042 的 triage 把負責人決定寫進既有 description 而整份被退回、直接停給人。
- verification／review／service 必須針對全部 REPAIR／DECISION 回 `issue_checks`，每项有 id、PASS／OPEN 與非空證據。
  DECISION 不能靠 PASS 自行放行，須收到新的真人回覆後由需求階段確認；明確核准需求變更才可將待處理問題排除。
  新增或更動清單會使舊的逐項驗證版本失效。缺對應、漏驗、待決／OPEN 都不能完成或發布，既有需求→情境→驗收檢查照常。
  **例外（2026-10-17 起）**：驗收之後，如果只在清單尾端新增了不需處理的項目（`OBSERVATION`、`EXCLUDED`），拿掉這些尾端項目後的版本又正好等於驗收當時的版本，
  舊的逐項驗證仍然有效（`IssueTracker.checkedRevisionStillValid`，發布閘門與摘要共用）。REPAIR／DECISION 只要有任何新增或改動，就照樣失效。
  起因是 bestpartner #1044：QA 全部 PASS 後，審查把 LOW 提醒記成 OBSERVATION，發布閘門判定「問題清單仍有待處理／未驗證項目」，按續跑也一直擋。
  **待決說明更新（2026-10-23 起）**：清單變動只是既有 DECISION 項目更新 evidence／reason（仍是 DECISION、描述與需求對應不變）時，
  系統記錄版本別名 `FlowRun.issueRevisionAliases`，舊驗收仍有效；DECISION 本來就不能靠 PASS 放行，說明更新不改變已驗證的內容。

#### 負責人裁定待決問題（2026-10-23）

起因 bestpartner #1055：驗收 8 項全 PASS，只剩一個「部署後網址能否打開」的 DECISION 問題擋住發布。負責人選了「移到後續任務」，
但這只能借道「核准需求變更」回需求階段整輪重跑；換手 27 次已超過上限 24，1 秒內又停回等待真人，沒有 agent 啟動。

- 驗收或審查節點的受阻報告，在同一個乾淨 commit（`attemptHeads`）上，把全部 DECISION 試算移出範圍後
  除了問題清單版本與未核實事項之外都通過時，停止訊息為每個 DECISION 附「移出範圍」按鈕（`HumanOptions.issueDecisionOptions`）。
  按鈕送 `負責人裁定問題 <ID> 排除 <授權碼>`；授權碼綁定配置、清單版本、問題與 HEAD，處理後清單版本改變即失效。
- 只限需求負責人。項目改為 EXCLUDED，description 不變，reason 記裁定人與時間；原項目存 `issueEvidence["owner-decision:<ID>"]`。
  驗收版本比對會把裁定過的項目還原成裁定前內容再比，裁定本身不使驗收失效。尚未執行的需求變更核准由這個裁定取代。
- 全部 DECISION 都裁定後依試算結果（`OwnerIssueDecision.Mode`）：
  `REUSE` 報告直接通過，寫入 receipts 並接著跑下一個節點；`WAIVE` 只剩未核實事項，不排入執行，改出「開 PR」由負責人逐項放行；
  `RERUN` 驗收之後清單又被改過、無法證明是同一份，在同一個 commit 只重跑該節點，不回需求到實作。
- 往 PR 核准走的固定步數（沿 `next` 的 agent 節點）另給一次有界額度（`systemRetries` 的 `owner-forward-cap`／`owner-forward-path:<節點>`），
  不重設 `transitions`；任何節點受阻即收回。負責人「開 PR」補跑審查也用同一套額度。
- 只要還有其他未通過項目（OPEN 的 REPAIR、失敗情境、HEAD 改變或工作區不乾淨），按鈕就不出現，照舊重跑或核准需求變更。
- 分流的共用提示（`FailureReport`）同步說明：只差待決問題時系統會附按鈕，選項不必再提供「移到後續任務」。

#### 延後驗收與沿用前次結果（2026-10-23）

起因 #1053～#1055：一個只改部署設定與文件的任務花 48 小時，需求跑 13 次、設計 9 次、計畫 11 次、實作 19 次、驗收 8 次。

- **延後驗收**（`project.autonomy.deferUnverifiable`，`DeferredAcceptance`）：開啟時測試計畫 schema 多必填陣列 `deferred_acceptance`
  （acceptance_id、reason、needs）。需要驗收節點操作契約沒有的能力才能驗的項目列入其中，`Traceability.planReason` 算作已涵蓋；
  ID 必須存在、reason／needs 不得空白、不得同時有情境。驗收提示說明它們不屬本次驗收、不列 uncertainties；核准 PR 訊息與 PR 說明逐項列出。未開啟的團隊不變。
- **沿用前次結果**（`ReceiptReuse`，所有團隊）：`Traceability.invalidate` 與需求重訂清掉收據前先存進 `FlowRun.priorReceipts`。
  主線節點（entry 沿 next）要啟動時，配置版本相同、工作區乾淨且（工作區節點）HEAD 相同、前置結果（需求以外）摘要相同、報告重新通過閘門時，
  直接恢復收據、往下走，不呼叫 agent、不計換手。另外：設計（唯讀 REPORT）與實作要求需求基準相同或只刪減、沒有新增待處理問題；
  實作還要求目前 HEAD 上沒有 REPAIR 被驗收或審查判為 OPEN；計畫要求需求基準完全相同。分流不沿用。沿用時稽核與 Slack 會註明。
  沿用後仍經系統獨立驗證、驗收與審查；實作其實要改時會在那裡被抓到，直接退回實作（此時收據有效、不會再沿用）。
  限制：需求重訂時模型改寫措辭也會使基準摘要不同，此時只有「只刪減」的比對能沿用設計，計畫與實作照常重跑。
- **驗收引用系統收據**（`ConfiguredExecutor.receiptReuseNotice`）：驗收提示列出系統在目前 commit 已以結束碼 0 執行或重用的固定指令，
  要求同一工作直接引用收據（command 寫「receipt：<指令>」），不在沙箱重跑。
- 承接完整複製清單與 `issueEvidence` 歷史證據，不複製有效驗收／核准；新任務先核實已有成果，再補未完成項。
  明確從唯讀問答挑選單項建立的子任務仍是局部範圍，顯示來源總數與未涵蓋項數，不假稱整體完成，也不擅自擴張授權。
- 最多 100 個問題、清單 96000 字元；提示中的清單與歷史證據最多 128000 字元。超限明示停止，不截斷後宣稱全部處理。

機械檢查保證已登錄問題的結構、連續性與覆蓋，不保證模型找出所有問題、判斷範圍正確或證據文字真實；仍須 QA、Review、獨立操作收據與真實任務驗證。
未登錄只寫在自然語言摘要的發現，系統不能可靠地自行解析，不能宣稱「任何漏項都不可能發生」。

- 入口使用工作契約 `REQUIREMENTS`，不綁定角色名稱。
- 每項需求保存 `id`、`kind`（FUNCTIONAL／NON_FUNCTIONAL）、描述、可觀察的 `acceptance` 與 `source`。
- 新任務另將驗收拆成 `acceptance_items`；每項有全域唯一 ID、`criterion` 與 `dimension`。
  「每個／全部／各種」操作、角色、輸入、狀態及失敗模式必須逐項展開，不能以代表性路徑代替。
- 保存排除範圍 `out_of_scope` 與待確認問題 `open_questions`；有未解問題不得進實作。
- 純詢問仍可結束而不建立工作基準；不會為了回答問題強迫執行開發流程。
- 任務提出者是預設真人需求／驗收負責人。這表示責任歸屬，**不表示他已逐條核准模型第一次整理的需求**。
  保留原先全自動策略：清楚的需求自動前進，歧義與產品取捨才停下。若需要每次都人工確認需求，需另加明確需求核准閘門。
- 基準 SHA-256 由系統計算，不接受模型自訂。PM 對使用者原意的語意理解仍須角色審查與真人確認，機械檢查不能保證理解正確。

QA 測試計畫必須引用 `baseline_revision`，每個情境的 `requirement_ids` 必須存在且不重複；
所有需求都至少被一個情境涵蓋。新任務還必須以 `acceptance_ids` 覆蓋每個原子驗收項目，
並提供 `counterexample`，說明哪種錯誤實作可能通過弱測試，以及本情境如何使它失敗。
系統會列出漏掉的需求或原子驗收 ID，不只顯示「覆蓋不足」。
驗收結果再引用相同需求版本與系統計算的 `plan_revision`，逐一對應 scenario ID、指令、實際結果與證據。
即使情境 ID 沒變，預期結果改了也會使舊驗收失效。

這仍是結構追溯，不是語意正確性的證明。Reviewer 必須檢查測試內容真的驗證 acceptance，
不能只看 ID 對上。QA 可以讀程式做白箱測試，但不能只做 Code Review 或採信開發者說通過。
既有任務使用建立當下保存的 schema 與 prompt；不會在中途被新版欄位破壞，也不會自動補寫新版計畫。

## 返工與需求變更

每個節點的 `requires` 宣告前置契約；啟動驗證會拒絕相依循環、略過必要前置契約或不完整的 PR 核准清單。
重做需求、計畫、實作或驗收節點會撤銷所有相依報告／版本收據與獨立驗證，
既有歷史 stage/audit 不刪除。進入純唯讀 Review 不會撤銷 QA 前已產生的系統收據。

| PM 分流 | 行為 |
|---|---|
| implementation | 既定需求內修正實作，再驗收與審查 |
| verification | 補測試／證據，再獨立驗證與審查 |
| plan | 修正既定需求的測試計畫，再重跑下游實作／驗收／審查 |
| design | 修正設計，再測試計畫與下游；只有配置含此路線時可用 |
| requirements | 提議變更需求，停止等待原需求負責人明確核准 |
| human | 環境、權限、產品取捨或原因仍無法核實；明示目前沒有 agent 處理 |

需求變更的 Slack 回覆範例：

```text
@zipe-ai-team 補充 #編號 核准需求變更 SMTP 逾時時禁止自動重送，改回報結果未知
```

只有原需求負責人且仍在白名單內能授權；「好」或一般補充不會核准變更，也不會核准 PR。
系統保留舊基準於 `flow_run.baselineHistory`，稽核記錄核准者、具體變更與原版本。
重新建立基準後清除有效下游收據，重新跑配置流程，不清掉原工作區或既有 Git 紀錄。
重試上限不因重新規劃而歸零；用完仍需人工檢查／另建任務。

## 系統獨立驗證

計畫引用 `compare-unchanged-files` 時，系統先比較本任務實作前版本與最終 commit 的指定檔案，
通過才執行建置或依純文件規則略過建置。差異交回實作修正，查不到基準則停止；
比對收據綁定完整計畫與固定 Git 基準，發布前重驗紀錄。詳見 [QA 情境流程](qa-scenario-workflow.md)。

在 Code Review **之前**，系統從工作區的最終 commit 建立另一個全新 clone，不重用 QA 的產物／node_modules。
管理者在 `project.verification` 固定 argv 與整輪期限，例如：

```json
"verification": {
  "commands": [["mvn", "-B", "clean", "verify"]],
  "timeoutSeconds": 1800
}
```

Node 範例依序 `npm ci`、`npm test`、`npm run build`；需依實際 repo 選定非互動測試指令。
固定指令不能由 agent 的 `command` 欄位提供，不插入需求文字，不以 shell 字串執行。
這不代表建置工具不會執行 repo 裡的腳本；它們仍是需要沙箱約束的不可信程式。

- 必須通過既有沙箱自檢；使用受限 systemd user unit，2 核 CPU 配額、4 GB 記憶體與整輪最多 1800 秒。
- **專用相依套件快取（2026-10-17 起）**：配置的檢查指令（不含 clone／checkout）共用 `<安裝目錄>/verification-cache`，
  以 `GRADLE_USER_HOME`、`npm_config_cache`、`MAVEN_OPTS=-Dmaven.repo.local=…` 指過去（`IndependentVerifier.dependencyCache`／`cacheEnvironment`）。
  HOME 仍是每輪全新的空目錄，原始碼仍釘成唯讀；只有下載快取跨任務沿用，agent 的沙箱碰不到這個目錄。
  已知取捨：被驗證的專案程式在執行期間仍可寫入快取。路徑含空白、引號或反斜線時不設定（退回不用快取）。
  起因是 #1044：每輪都從空的 HOME 開始，第一個 Gradle 指令就要 16～19 分鐘，四次驗證分別花 27～30 分鐘，貼著上限。
  第一次使用要建立快取，仍然會慢；之後的效果要以 VM 實測為準。
- **超時不算測試失敗（2026-10-17 起）**：超過期限時拋出 `IndependentVerifier.VerificationTimeout`，交給真人處理，不交回實作。
  2026-10-22 起，配置啟用 `project.autonomy.verificationContinuations` 時（目前 bestpartner），本輪有通過的指令就在同一個 clone 從被中止的指令接著跑，
  不開新 clone、不以重用代替（指令之間有依賴，例如 npm ci 裝的 node_modules）；沒有進展或輪數用完才交給真人（見[失敗回報](failure-reporting.md)）。
  指令非零結束時，如果剩餘時間已不到 3 秒（systemd `RuntimeMaxSec` 以整秒向下取整，可能早約一秒砍掉），也一律算超時。
  修正前，#1044 在 00:42 的 `npm test` 被 systemd 砍掉、log 是空的，卻被當成測試失敗，派回實作多跑了一輪。
- 只開放獨立驗證目錄，釘住新 clone 的所有版控檔與 `.git`；不開放原任務工作區寫入。repo 含 symlink 暫時拒絕，需管理者評估。
  檔名含空白時，交給 systemd 的路徑整段加雙引號（`ExecutionSandbox.pathProperty`；VM 實測確實唯讀，`\x20` 跳脫實測仍可寫，不採用）；
  含雙引號、反斜線或控制字元的路徑直接拒絕。2026-10-03 前未加引號，bestpartner 的「General rule.pdf」讓獨立驗證以
  `Invalid ReadOnlyPaths` 在啟動前失敗（#1012）。

**純文件變更略過建置測試（2026-10-03 起，逐專案啟用）**：`project.verification.docOnlyPaths` 有設定時，系統以
`git diff --name-only --no-renames <工作區基準>..HEAD`（`WorkspaceManager.changedPathsSinceBase`）自行列出變更，**不採信 agent 自陳**。
以下全部成立才略過（`DocOnlyChanges.qualifies`），否則照常全跑：
- 變更清單非空，且每個檔都符合 `docOnlyPaths`；2026-10-12 起來源另外只接受 .md、.mdx、.txt、.rst、.adoc，SQL／腳本／設定／網站程式等即使在 docs 目錄也全跑；
- 沒有任何檔落在程式強制的全跑名單（配置無法覆蓋）：路徑任一段是 `src`；`pom.xml`、`build.gradle(.kts)`、`settings.gradle(.kts)`、
  `gradle.properties`、`gradlew(.bat)`、`mvnw(.cmd)`、`package.json`、`package-lock.json`、`yarn.lock`、`pnpm-lock.yaml`、`Dockerfile*`；
  或位於 `gradle/`、`.mvn/`、`.github/` 之下。
- `--no-renames` 讓「程式檔改名搬進 docs/」同時列出刪除端，不會被當成純文件。

略過時不 clone、不跑指令，收據 `commands=[]`、`docOnlyChanges` 列出檔案，仍綁定 HEAD、需求與配置版本；`blockingReason` 會重驗清單仍符合配置。
Slack 會說明「只改了文件，依專案設定略過建置與測試」，QA／審查收到的收據說明這是配置設計而非查無證據，PR 說明列出檔案。
**QA 逐項驗收（`inspect-content`）、程式審查與 PR 真人核准照常。** `docOnlyPaths` 不可為 `*`、`**`、`**/*`、絕對路徑或含 `..`、`.git`。
來源三份範例配置均啟用文字路徑（spring-starters／node-web 為 2026-10-12 新增；2026-10-14 已部署 bestpartner 與 spring-starters）；設定屬新快照，既有任務不自動更新。
實作節點（`IMPLEMENTATION` 閘門）在專案有 `docOnlyPaths` 時，系統會在脈絡加一段「純文件變更規則」（`DocOnlyChanges.implementationNotice`）：
變更全部符合上述條件時不執行後端／前端編譯與測試操作，也不因缺建置證據回報 BLOCKED；有任一檔不符合就照建置規範完整執行
（2026-10-04 起；#1018 只改一行 Markdown，改派的 Codex 角色仍自行跑 Gradle 而受阻）。這是給角色的指示，略過與否仍以系統 git diff 判定為準。

**依變更檔案只跑相關指令、重用相同輸入的結果（2026-10-17 起）**：起因是 bestpartner #1044 只改文件、網站與設定範本，
每輪仍跑 23～30 分鐘的後端與前端全套測試，回到驗收階段時同一個 commit 也重跑一次。規則在 `VerificationPlan`，不綁專案名稱：
- 配置選填 `verification.commandScopes`（與 `commands` 一一對應，每個指令負責的路徑）與 `verification.qaOnlyPaths`
  （系統沒有對應指令、由 QA 固定操作或內容檢視驗證的路徑，必須和 commandScopes 一起設定，而且不能與任何指令範圍重疊；重疊用固定前綴保守判斷）。
- 變更清單同樣來自 `changedPathsSinceBase`。每個檔案歸成：純文件（沿用 docOnlyPaths 加純文字規則）／QA 專屬（qaOnlyPaths，**包含其中的建置檔**，
  例如 `docs-site/package.json` 由 QA 的 qa-docs-* 安裝建置）／屬於某些指令範圍（範圍內的 package.json 等建置檔只觸發該範圍的指令）／都不屬於。
  只要有一個「都不屬於」，或查不到變更清單、清單為空、沒設 commandScopes，就**全跑**。
- 需要跑的指令，若同一任務、同一配置版本、同一 argv 以相同**輸入指紋**通過過，而且紀錄檔仍在、雜湊相符，就**重用**，不 clone、不重跑。
  指紋取 HEAD 的 `git ls-tree -r`：有範圍時是「該指令範圍內的檔案＋共用檔（不屬於任何範圍、也不是純文件或 QA 專屬）」，共用檔一變所有重用失效；
  沒設範圍時是整棵樹，所以 spring-starters／node-web 只有「同一份內容再驗一次」才會重用。歷史存在 `FlowRun.verificationHistory`（最多 20 筆），
  每個指令通過就記下，後面的指令失敗時，修正後下一輪仍可重用輸入沒變的那幾個。
- 收據新增 `decisions`（每個指令一筆 RUN／SKIP／REUSE、原因、命中檔案、指紋、紀錄路徑與雜湊、重用來源 commit）、`changedFiles`、`taskId`；
  `commands` 只列本輪實際執行的結果。發布前 `IndependentVerifier.decisionReason` 會**重新從工作區**算變更與指紋：
  略過的指令重算後必須仍不需要跑；執行與重用的指令指紋要相同、紀錄要在且雜湊相符，否則擋下並在續跑時重驗。
- Slack 會貼「依變更檔案判定：執行 X、略過 Y、重用 Z」；QA／審查收到的收據說明略過與重用是系統依實際變更的決定，不是查無建置證據，
  QA 專屬路徑仍須依計畫驗證；PR 說明分列執行、略過（原因）與重用（來源 commit）。QA 逐項驗收、程式審查與 PR 真人核准照常。
- bestpartner 設定：兩個 Gradle 指令範圍 `bestpartner-service/**`，三個前端指令範圍 `bestpartner-ui/**`，
  `qaOnlyPaths` 為 `docs-site/**`、`docs/sql/**`、`docs/docker/**`、`.env.example`（使用者 2026-10-17 決定；`docs/docker/**` 於 2026-10-23 加入：#1055 只改 SIT compose 檔仍因它不屬任何範圍而全跑、多跑約 26 分鐘後端測試，部署定義由 QA 讀檔與服務部署驗證）；`bestpartner-mcp-servers/**`、`scripts/**`、`skills/**` 不屬於任何範圍，改到時全跑。
  設定屬新快照，既有任務仍用舊快照（但重用是程式行為，所有任務都適用）。
- 指令使用空白環境加少量明確變數、獨立 HOME，不把 Slack/GitHub token 或 CLI session 透過環境傳入。
- 不讀個人 Maven/npm 設定；相依套件下載快取只用下方的獨立驗證專用快取，第一次建置仍可能很慢。私有依賴可能受阻，不可用複製整包憑證來解決。
- 記錄系統觀察的結束碼、argv、起訖時間、commit、配置與需求版本；log 存在可寫目錄外，單指令輸出超過 8 MB 即停止。
- 配置的檢查指令以非零結束碼結束（例如測試失敗、編譯錯誤），代表程式沒過，屬於實作責任：
  系統把失敗摘要（失敗的測試與編譯錯誤，或 log 最後 40 行）存成受阻發現，自動交回實作節點修正，
  修好後重新獨立驗證；不需真人轉達。實作節點取受阻分流節點的 `implementation` 路由，
  否則取流程中唯一具工作區寫入權的實作節點；判定不出時照舊停給真人。仍受重跑、事件額度與整輪上限約束。
  2026-10-18 起交回說明不再斷言「不是環境問題」，改為：先依失敗摘要找根因，能在範圍內修就修；根因在本次修改之外
  （例如配置的測試依賴此環境不存在的路徑）時，實作要回 `BLOCKED`、`verify_passed=false` 並附原始錯誤，
  「記為無法核實」不會讓這道驗證通過。dynamic-api #1001 兩位 agent 都指出舊說法與實際根因（Windows `D:/tmp` 路徑）矛盾。
- **硬閘門說明（2026-10-18 起）**：受阻發現的 `source` 是 `系統獨立驗證`（`IndependentVerifier.FINDING_SOURCE`）時，
  `ConfiguredExecutor.appendBlockedFindings` 會在下游所有節點的脈絡加上固定系統說明：指令通過前驗收、審查與發布都不會開始，
  可行出路只有範圍內修正、需求負責人核准擴大範圍、`close`、`successor`，配置問題寫進 `also_needed`，
  不得提供「接受失敗／記為無法核實後繼續」的 `decide` 選項。#1001 分流提供了這種選項，負責人選了之後又空轉兩輪 agent 才停下。
  這是依實際狀態注入的事實，但選項內容仍由模型產生，系統沒有逐字語意檢查。
- **實作自陳與指令紀錄交叉核對（2026-10-18 起）**：實作節點回報 `SUCCESS` 且 `verify_passed=true` 時，系統讀取 CLI 自己寫的紀錄
  （Claude Code：`~/.claude/projects/*/<session>.jsonl` 的 Bash `tool_use`／`tool_result`，失敗時 `is_error=true`、內容以 `Exit code N` 開頭，
  只取本輪開始後的紀錄；Codex：`logs/<任務>/<角色>.codex.jsonl` 的 `command_execution`，帶 `exit_code`／`status`，指令外層的
  `/bin/bash -lc '…'` 會還原）。只要某個本節點的配置操作在本輪**最後一次**執行失敗，系統就不採用這份成功回報，
  記 audit `REJECTED`、寫入脈絡並走受阻路線，不跑獨立驗證（`runtime.CommandRuns`、`flow.CommandEvidence`）。
  比對刻意保守：指令要與操作 argv 完全相同，後面只允許接重導向、管線或指令分隔；子集測試（加 `--tests`）、`cd … &&` 開頭等都不算；
  同一操作先失敗、修好後重跑成功以最後一次為準；讀不到紀錄時不判定（空清單不代表沒執行）。
  格式於 2026-10-18 以 VM 真實紀錄核對（dynamic-api #1001 的 Claude 實作、bestpartner #1041／#1042 的 Codex 事件）；
  CLI 版本更新改變紀錄格式時，這項檢查會靜默失效（退回只看自陳），換版要重驗。#1001 第一輪實作 `gradle … test` 結束碼 1 卻回報成功，
  系統因此跑了 8 分 38 秒的 Gradle（整段約 16 分鐘）注定失敗的獨立驗證。
- clone／checkout 失敗、超時、輸出過大，以及 log 顯示環境缺件（相依套件抓不到、磁碟滿、找不到指令、
  沙箱啟動失敗等）、紀錄遺失／雜湊改變、版本不同，都阻止審查或 PR；失敗現場保留並明示等待人工。
- 發布前再驗收據、log 雜湊及同一最終 commit，不因模型回報 PASS 就跳過。

**基準驗證（2026-10-18 起）**：服務啟動（配置或基準 commit 改變時）與 Slack「基準驗證」指令會在基準分支先跑同一批指令，
失敗時警告管理者並把已知失敗告訴之後的任務，不阻擋任務。見 [基準驗證](baseline-verification.md)。

收據是「配置的指令真的在該版本成功執行」的證據，**不是每項情境均正確的證明，也不是 GitHub CI Check**。
例如 `npm test` 被 repo 寫成 no-op 仍可能 exit=0，必須由 Reviewer 查核；JUnit 報告解析、測試案例到機器產物的逐項對照與外部 CI adapter 尚未加入。
目前既有沙箱網路未隔離、唯讀檔案不等於隱藏檔案，不能宣稱完全隔絕同機憑證；仍建議專用非敏感測試環境。

收據與現場在 `$AI_TEAM_HOME/verification-runs/<task>/run-*`；應用備份包含系統 log 與資料庫收據，
不複製獨立 clone／HOME 快取（可能包含大量依賴與 node_modules symlink）。原任務工作區仍依既有備份策略保存。
驗證現場目前不自動清理，應另訂保留與容量政策。
收據含絕對路徑，搬到不同安裝位置後需要重新驗證，不手動竄改資料庫讓舊證據過關。

## 服務長作業與模型回合（2026-10-20 已部署 bestpartner）

固定部署工具是模型回合之外的持久作業：executor 保存實例 home/operation-jobs 的 journal，遠端 root systemd job 以原請求 ID 執行、查詢結果。client 預設等待 30 秒後回 OPERATION_PENDING，模型應以 BLOCKED 結束本輪；此狀態表示待對帳，不表示部署失敗或需要重新核准。

FlowEngine 在一般受阻路由前讀取同 task／activeNode／HEAD 的有效 journal，等待期間釋放模型但保留中央執行槽。結果就緒或到 deadline 後，注入系統檢查點續同 activeNode，不增加節點／重跑／transition 次數，也不重置既有計數或代造完成報告。真正模型 timeout 只有在 RuntimeTimedOut 的 MODEL 階段且 cgroup cleanup=CONFIRMED 時才可走相同恢復。結果／timeout 指紋限制重複恢復，避免無限重開模型。

prepare/start 只有固定執行器 verified 的同 HEAD、同 task／node／target 成功結果可重用；health 仍須核對該 start release，不得把舊 HTTP200 當成新版本驗收。服務重啟可依有效 journal 排回原 task；舊 AWAIT_HUMAN 需管理者先核實原收據，再以單次 AI_TEAM_RESUME_OPERATION_TASK 指定恢復，不修改任務快照或核准。

模型逾時與斷線不取消遠端 job。真人明確中斷時才產生本輪取消要求，目標對精確 job 清理；終止任務也由 executor 收斂。詳見[SSH 直連部署](direct-ssh-deployment.md)。已部署 bestpartner；#1048 的既有成功 start 必須保留，不可重建。實測範圍與正式恢復結果見直連部署紀錄。

## PR 與產品交付

人工 PR 核准仍然必須存在。PR 建立成功後記為 `PR_CREATED`，保存 URL 與 publishedHead，
Slack 明示「尚未確認合併／發布／產品驗收」。它是本段自動管線的結束，不代表產品已交付。
重啟不重播發布；保留 PR_CREATED 工作區供後續查核。
推送或開 PR 本身失敗（token、權限、網路）時留在 `AWAIT_PR_APPROVAL` 並貼出錯誤原文，修好後再按核准即可；
發布前證據或 Git 目標不符時才停給人，停在 approval 節點時可按「重新進入核准 PR」，詳見 [失敗回報](failure-reporting.md)。

PR 開出去之後若與基準衝突，可以由原任務自動同步，見下一節。PR 合併或關閉後，系統只會停止追蹤，不改變任務狀態。
尚未串接合併事件、發布版本、產品驗收結果、回滾或部署監控；沒有可靠來源就不產生 MERGED／RELEASED 等成功狀態。
導入這些功能前需先定義目標 repo 的發布方法與真人責任，不自行新增自動合併或部署權限。
舊版本 jar 不認得 PR_CREATED；產生新版任務後回退須用升級前備份，不讓舊 jar 直接讀新版資料。

### 已開 PR 的衝突同步（2026-10-23）

起因 #1056：使用者合併任務 1044 的 PR 後，任務 1055 已開的 PR 跟 dev 衝突。原本的流程只能另開任務、另開新 PR，原 PR 一定解不了，最後交給真人。
PR 是系統自己開的，分支與工作區都還在，因此改由原任務接著處理（`PullRequestSync`、`FlowEngine.checkPullRequest`）。

**開關**：`project.autonomy.pullRequestSyncRounds`（0～3，0 或缺欄位＝停用）與 `pullRequestSyncAutoPush`。
開關讀管理者**目前**的配置，因為舊任務快照沒有這兩個欄位；同步開始時把當下的授權記在同步輪（`FlowRun.pullRequestSync.autoPush`）。
配置驗證要求主線（entry 沿 next）上有 gate=IMPLEMENTATION 的節點。

**偵測**：
- `PullRequestMonitor` 每 10 分鐘（服務啟動 10 分鐘後第一次，避開部署的前後一致比對）以 `AI_TEAM_GITHUB_TOKEN` 查一次 `PR_CREATED` 任務的 PR（`PullRequestPublisher.status`，GitHub `GET /pulls/{n}`）；
- 需求負責人也可以送「同步 PR #編號」立即觸發，兩者走同一套判斷（`PullRequestSync.assess`）。

| GitHub 狀態 | 處理 |
|---|---|
| 已合併或已關閉 | 記 `pullRequestClosed`，之後不再查 |
| mergeable 為 null（GitHub 還在計算） | 下次再查 |
| 可合併 | 不處理；上一輪推回後第一次確認可合併時，通知一次「不再衝突」 |
| 衝突（mergeable=false、dirty），且來源分支、head 與基準都和系統紀錄相同 | 開始同步，見下方 |
| 衝突，但分支或 head 被其他人動過，或 base 不同 | 說明一次，不自動處理，不蓋過他人的推送 |
| 衝突，但這個 PR 的同步輪數已用完 | 說明一次，請真人處理 |

**同步一輪**：
1. 任務 `PR_CREATED → RESUME_QUEUED`。合併前就失敗也計入輪數，排程才不會反覆重試同一個失敗。
2. 系統在原任務工作區以 `git merge --no-ff --no-commit` 併入 fetch 後的最新基準（`WorkspaceManager.mergeBase`），衝突檔由 git 查得。
   不 rebase、不改寫既有 commit。
3. `comparisonBase` 與工作區的 `ai-team.base-sha` 改為新的基準 commit，驗收範圍、檔案比對與獨立驗證都只看 PR 對新基準的實際差異。
4. 從主線第一個實作節點開始。系統在脈絡寫入 PR、兩側 SHA 與衝突檔，要求只改衝突段落、兩側修改都保留。
   能寫 .git 的角色自己 `git commit --no-edit`；Codex 這類沙箱不能寫 .git 的角色只改檔案，由系統代為提交合併 commit。
5. 實作後由系統核對（`WorkspaceManager.mergeProblem`），不採信自陳。不通過就照一般受阻路由處理：
   - HEAD 是「原 PR commit＋基準 commit」的合併 commit；
   - 衝突檔沒有殘留衝突標記；
   - 衝突檔以外的內容與 git 自動合併結果相同。

   這一輪的寫入稽核以 git 自動合併結果為起點（`pathsChangedFromAutoMerge`），基準帶進來的變更（例如別的 PR 改過的 `.claude/**`）不算角色的修改。
6. 沿用原任務的需求基準、測試計畫與計畫核准，接著照主線重跑系統獨立驗證、QA 驗收與審查。
   每輪另有固定的 8 次換手額度（`FlowLimits` 例外），整輪上限已用完的舊任務也能完成一輪；歷史計數不重設。
7. 到 approval 節點時，證據全部通過才會往下：
   - `pullRequestSyncAutoPush=true`：直接推回；
   - 否則停在「核准 PR」，訊息寫明會推回既有 PR。

   推回（`PullRequestPublisher.update`）前會再查一次 PR，要求仍開啟、來源分支相同、遠端 head 仍是上次推的 commit；
   推送是一般 push、不加 force，不呼叫開 PR 的 API。
8. 推回後回到 `PR_CREATED`，`publishedHead` 改成新 commit；GitHub 確認可合併後通知一次。

**邊界**：
- 第一次開 PR 一律要真人核准，不變。
- 自動推回是使用者在 2026-10-23 同意的例外：只限同步輪、需求與計畫沒變、證據全部通過，而且只推到原本已核准開出的那個 PR。
- 延後驗收項目與原 PR 相同，因為計畫沒有變。
- 衝突檔位在受保護路徑時，實作角色改不了，會照一般受阻路由停下。
- 已開過 PR 的任務一律推回同一個 PR，不會再開新 PR。
- 只處理系統開出的 PR（`flow.prUrl`），不處理真人開的 PR。

## 尚待真實端對端驗收

程式測試以子行程模擬獨立驗證，不代表 systemd 與實際建置都已在各環境通過。
2026-09-06 上線時列為待真實環境驗收的項目：Linux 的全新 clone／唯讀釘選／逾時與子行程終結、真實專案的固定驗證指令、
Slack 的需求變更核准及 PM 返工、額度續跑，以及 PR 人工核准。之後的實測狀態以 [從零重建驗證紀錄](rebuild-verification.md) 為準，未列為通過者不可宣稱已驗收。
