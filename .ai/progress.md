# 工作進度（Codex 與 Claude Code 共用）

> 由新到舊追加。每則一行到數行：日期、由誰（Codex／Claude Code）、做了什麼、驗證方式。

## 2026-10-06 — Claude Code（查證 #1023 無法上網查版本）

- bestpartner #1023「確認 langchain4j 新版本」intake 跑了 4 輪都查不到版本，停在 AWAIT_HUMAN。
- 原因：Claude Code 以 `--permission-mode dontAsk` 執行，`teams/bestpartner.json` 的 `coordinator.tools`
  沒列 `WebSearch`／`WebFetch`，呼叫一律被拒（stage_attempt 86–89 摘要有拒絕訊息原文）。不是網路被擋。
- 發現的缺漏：`configuration.md` 沒寫白名單外的工具（含網路工具）會被拒。已修正文件：`docs-site/agent/configuration.md`、`docs-site/docs/direct-agent-requests.md`（對話不受角色 tools 影響）。
- 核對過的文件：`configuration.md`、`configurable-team.md`（無相關內容）。docs-site build 通過。
- 使用者同意後，兩個實例的 `coordinator.tools` 都加上 `WebSearch` 和 `WebFetch`，WebFetch 限定
  repo1.maven.org、central.sonatype.com、github.com 三個網域。check-source PASS；本機沒有 Maven，
  這次只改配置、JAR 沒動，所以沒跑 Maven 測試。
  部署（JAR 不變）：`--validate-team` 通過，舊檔備份為 `teams/<名>.json.bak-before-web-tools`；
  bestpartner PID 149196（配置 35682c41…）、starters PID 151906（配置 5b279064…）。
  兩邊 log 都是 0 ERROR、沙箱與工具鏈自檢通過，任務資料前後一致。
- 限制：直接 @PM 提問（dialogue）在程式裡寫死只能用 Read／Grep／Glob（`ConfiguredExecutor`），不受這次配置影響。
  #1023 的快照是舊配置，重跑還是查不了網路，要開新任務。真實上網查詢尚未實測。

## 2026-10-06 — Claude Code（負責人結案的任務也能開 PR）

- #1022 在 21:04（台北）先按了分流的「結案，系統外處理」（`action=close`，audit 350–352，**close 的第一筆真實證據**：直接 ABORTED、
  記 OWNER_CLOSE、未呼叫 agent，重啟後工作區仍在），21:23 才部署「開 PR」，之後送指令因 ABORTED 被拒。
- 修正：`FlowEngine.ownerPublish` 接受 audit 有 OWNER_CLOSE 的 ABORTED 任務（`claimState(from, RESUME_QUEUED)`）；檢查沒過時回到 ABORTED
  並說明「任務維持結案」；一般中止的 ABORTED 仍拒絕。FlowEngineTest +3。
- 已修正文件：review-gate、operations、pm-triage、plan-approval-buttons（含 close 真實證據）。docs build 通過。
- commit bb5e93c；本機 550 項只有既有 4 項 Windows 失敗；VM `closed-publish-build.VOnKsT` check-source PASS、550 項 0 失敗，JAR `c5b0096bb36578e501a8831392934d2992d0f2b1cfda9c1b189ca6480b777ebe`。
  部署：bestpartner PID 140689（備份 `orchestrator-backup.lCDH9Z`）、starters PID 143800（備份 `orchestrator-backup.ay2s0n`），皆 0 ERROR、5 個連線、任務資料前後一致。

## 2026-10-06 — Claude Code（Slack 命令開 PR、放行審查提醒等級）

- 使用者要求在 Slack 命令 agent 開 PR（#1022 審查實質通過但 INFO 擋發布、舊快照套不到新規則）。使用者選擇：只放行無實質問題的情況；
  文字指令、停止訊息按鈕、agent 選項三種入口都要。
- 實作：`ReviewGate.waivable`（只認 INFO／INFORMATIONAL／NOTE／NIT／NONE，CRITICAL 等不放行）；新類別 `flow/OwnerPublish`
  （以 `FlowGates.publicationReason` 試算、commit 必須等於 `attemptHeads` 的審查 commit、放行寫成帶 `owner_waiver` 的 receipt）；
  `FlowEngine.ownerPublish`（`AWAIT_PR_APPROVAL` 等同核准；`AWAIT_HUMAN` 時排入中央佇列、缺收據重跑獨立驗證、通過即發布，失敗不保存放行）；
  `approve` 抽出 `publishApproved` 共用；`prBody` 加「負責人放行的審查項目」；`HumanOptions` 固定「開 PR」按鈕與 `action=publish`；
  `SlackListener` 文字指令 `開 PR #id`／`開PR #id`；舊管線任務回覆不支援。
- 測試：ReviewGateTest +2、FlowEngineTest +4、HumanOptionsTest +1、TriageRoutingTest +3、FailureReportTest 更新。本機 547 項只有既有 4 項 Windows 失敗。
- 已修正文件：review-gate（新段落、例外說明）、plan-approval-buttons、operations（指令表與按鈕表）、pm-triage、data-model（receipts 例外）、
  project-tree（OwnerPublish）、catalog（review-gate code）；decisions 追加。已核對：configuration、system-map（不涉及設定鍵與節點）。docs build 通過。
- VM 隔離建置 `owner-publish-build.Ko6L35` check-source PASS、547 項 0 失敗；JAR `d62c1b6d0ddc236461866c4e9193837be7f2b50d560c507063a6db8e2fcde90d`。
  使用者確認後 deploy-jar-safe 部署：bestpartner PID 132552（備份 `orchestrator-backup.o0vgga`）、starters PID 135655（備份 `orchestrator-backup.1wjvV0`），
  皆 0 ERROR、5 個連線、任務資料前後一致。真實 Slack 端對端未實測。
- 另發現（未處理）：分流節點啟動時 `Traceability.invalidate` 會清掉系統獨立驗證收據，之後發布一定要重跑驗證。

## 2026-10-06 — Claude Code（#1022 真人選項「結案」做不到）

- 唯讀調查 #1022（bestpartner）：審查 INFO 被 ReviewGate 擋下後，負責人依序按了分流給的「授權發布」「我手動開 PR」
  「PR 已開，結案」「確認結案」「規則修好後重跑」（audit 326–345），每次只把選擇當補充重跑分流（約 $0.9–1.8／次）又回 AWAIT_HUMAN。
  原因：`human_decision` 選項只送「負責人決定：…」交回流程（`HumanOptions.decisionOptions`），流程沒有結案路線；分流換問法使
  `repeatedDecision` 攔不到。任務分支已在 GitHub（60b8ca5），但**沒有 PR**（bestpartner 只有 #1、#2）。
- 修正（第 1 點）：`FailureReport.INSTRUCTIONS` 禁止把結束任務／系統外開 PR 收尾／改用新任務寫成選項，改指向「中止任務」「改用新任務繼續」
  並給空 options；契約隨 JAR 注入，既有任務也適用。FailureReportTest 新增 1 項。commit d8d113b。
- 測試：本機 530 項只有既有 4 項 Windows 失敗；VM 隔離建置 `choice-close-build.USIksU` check-source PASS、530 項 0 失敗（11 略過），
  JAR `23bccc524900d3c4858f27b47401e716b51be3a1a81e9f74f8ed40a366cb7d7e`。**尚未部署**（等使用者確認）。
  教訓：`git archive` 要加 `-c core.autocrlf=false`，否則 shell 腳本變 CRLF、check-source 失敗。
- 發現的落差：無（文件原本就寫「選項按下去交由流程重新判斷」，只是沒寫結案類選項的限制）。
- 已修正文件：plan-approval-buttons（human_decision 段）、configurable-team（選擇題的邊界）。已核對：pm-triage、operations（不需改）。docs build 通過。
- 第 2 點（計畫已核准，取代第 1 點的「空 options」做法）：`human_decision.options` 加必填 `action`（decide／close／successor）。
  `HumanOptions.decisionOptions(flow, ws)` 依 action 產生按鈕：close 送 `負責人結案 <問題與選項>`、successor 送固定承接指令
  （不可承接時略過）、decide 照舊；同一題重問時只略過 decide。`FlowEngine.closeByOwner`：只限驗收負責人與 AWAIT_HUMAN，
  `claimState` 到 ABORTED、audit `OWNER_CLOSE`，不呼叫 agent。`AuditLog.has`；`TaskOrchestrator.purgeWorkspaces` 保留 OWNER_CLOSE 的工作區
  （查詢失敗也保留）。不新增 Task.State、不改 FlowRun。
- 測試：FailureReportTest（schema action enum 且 required 數＝properties 數）、HumanOptionsTest +4、FlowEngineTest +2、新增 WorkspacePurgeTest。
- 已修正文件：plan-approval-buttons（action 表、取代第 1 點段落並註明歷程）、configurable-team、operations（按鈕表）、pm-triage、data-model
  （ABORTED 例外、OWNER_CLOSE）；decisions 追加。已核對：configuration、system-map（不涉及設定鍵與流程節點，不需改）。
- commit b4ad5a9。本機 537 項只有既有 4 項 Windows 失敗；VM 隔離建置 `owner-close-build.QfWDY3` check-source PASS、537 項 0 失敗（11 略過），
  JAR `852622420efa16fc676b776548a634ae51a6eb225cc66f17fb519db55bac2940`（取代第 1 點的 `23bccc52…`，該建置目錄已刪）。
  使用者確認後以 deploy-jar-safe 部署（只換 JAR）：bestpartner PID 124273（備份 `orchestrator-backup.Xl7UsF`）、
  starters PID 127380（備份 `orchestrator-backup.wSqWu6`），皆 0 ERROR、5 個 Socket Mode 連線、沙箱自檢通過、任務資料前後一致。
  真實 Slack 端對端（close／successor 選項）尚未實測。

## 2026-10-06 — Claude Code（#1022 審查 INFO 等級擋發布）

- #1022 分段改派實作段真實通過（1621998 改派段、60b8ca5 主實作段），獨立驗證與 QA 通過；審查 APPROVE 但 findings 有 `INFO`，
  `ReviewGate` 只認 MEDIUM／LOW 而擋下；負責人兩次授權發布都無效，分流反覆停在 AWAIT_HUMAN（audit 315–329）。
- 修正：`teams/schemas/review.json` 的 severity 加 enum HIGH／MEDIUM／LOW；`teams/prompts/review.md` 限定三級；ReviewGate 不放寬。
  ReviewGateTest 新增 1 項（schema 與閘門一致）。本機 529 項只有既有 4 項 Windows 失敗；check-source PASS。
- 發現的落差：review-gate 寫「findings 沒有 HIGH 才可核准」，實際是只放行 MEDIUM／LOW。
- 已修正文件：review-gate（落差＋新段落）、multi-runtime、configurable-team（分段改派實測證據）。已核對：configuration、operations（不需改）。docs build 通過。
- commit 097d43a（Codex 額度時間）、1a3e37d（審查等級）。VM 隔離建置 `review-enum-build.fD23dJ` check-source PASS、529 項 0 失敗（11 略過），
  JAR `4589e7da5ec5cb10df243c9d55723962745c70ba5dabde8ffe1655c6657732ef`。兩實例 `teams/schemas/review.json`、`teams/prompts/review.md`
  （部署前雜湊與 repo 舊版一致）備份為 `*.bak-before-1a3e37d` 後更新，`--validate-team` 通過；deploy-jar-safe 換 JAR：
  bestpartner PID 106078（配置 a202552b…，備份 `orchestrator-backup.WP3inP`）、starters PID 108925（配置 ba1ddf63…，備份 `orchestrator-backup.WrQN5F`），
  皆 0 ERROR、自檢通過、任務資料前後一致。#1022 仍用舊快照；提示／schema 變更不能原地遷移，需改用新任務繼續或手動開 PR；Codex 額度時間換算尚無真實額度事件驗證。

## 2026-10-05 — Claude Code（Codex 額度解禁時間換算台北時間）

- 使用者要求 Codex 解禁時間換算成易讀的台灣時間。查 codex-rs 0.156.1 原始碼：`format_retry_timestamp` 以 chrono `Local` 輸出，
  不同天 `Sep 15th, 2026 1:09 PM`、同一天只有 `1:09 PM`；措辭用彎引號 `You’ve`。
- 修改：`CodexRuntime` 以 `env TZ=UTC` 啟動 Codex；`QuotaRetryTime` 以 UTC 解讀 Codex 時間（含只有時刻），移除 UTC+14／−12 邊界推理；
  `TaipeiTime.friendly`（今天／明天、星期、約多久後）；`QuotaMessage.resetLine` 兩家都換算並附原文，`QuotaAutoResume` 下次嘗試同格式；
  `QuotaExceeded.explicit` 先把 `’` 統一成 `'`。
- 發現的落差：0.156.1 的 Codex 額度訊息是彎引號，原本只認直引號，會被當成一般失敗（新 VM 上尚無實際樣本可佐證）。
- 測試：QuotaRetryTimeTest、QuotaMessageTest 改寫，QuotaExceededTest 新增 1 項；本機 528 項只有既有 4 項 Windows 失敗。
- 已修正文件：quota-continuation（供應商表、排程、Slack 呈現）、INSTALL（額度段）；decisions 追加（取代 09-10 原文照登決策）。
  已核對：codex-cli-argument-contract、multi-runtime、codex-nested-sandbox、operations（不需改）。docs build 通過。未 commit、未部署。

## 2026-10-05 — Claude Code（查證 #1021 分段改派文件仍寫不進去）

- bestpartner #1021（承接 #1020）第一次走分段改派：`builder_codex` 改派段回報 BLOCKED、沒改任何檔案，以它的報告當節點結果，
  分流後 AWAIT_HUMAN（audit 270–276）。Codex 自述「任務指示明定 `.claude/**` runtime 一律拒絕寫入，且要求不要嘗試」。
- 根因：`ConfiguredExecutor.protectedPathNotice` 以 `flow.node` 查節點原定角色（builder＝Claude）的 runtime，
  改派段也收到 Claude 的拒寫告知，與 `partNotice` 矛盾。修正為依實際執行角色（新增 actingRole 參數）。
  ConfiguredExecutorTest 新增 1 項；ConfiguredExecutorTest、FlowEngineTest、ProtectedPathFallbackTest 本機通過。
- 已修正文件：multi-runtime（改派段說明）、configurable-team（`.claude/` 文件修改列）。docs build 通過。
- commit 242d49f。VM 隔離建置 `notice-role-build.sFD9MV` check-source PASS、526 項 0 失敗（11 略過），
  JAR `8867c9fd1a2a7b701e9410a1d6b2ad729ff4159808330a9fa24212b6439b9acc`。無執行中 unit 時以 deploy-jar-safe 換 JAR（只換 JAR）：
  bestpartner PID 86276（備份 `orchestrator-backup.Dhj4kX`）、starters PID 89428（備份 `orchestrator-backup.HEjGAe`），
  皆 0 ERROR、自檢通過、任務資料前後一致。#1021 仍 AWAIT_HUMAN，需使用者在 Slack 重新執行；分段改派真實端對端尚未通過。

## 2026-10-04 — Claude Code（分段改派＋bestpartner QA 開 localTestNetwork）

- 使用者核准計畫後實作：`FlowEngine.runDelegatedPart` 先由改派角色只改 `protectedPathFallback.paths` 內的檔案
  （`ProtectedPathFallback.partViolations` 要求全部在範圍內，系統代為提交，結果存 `FlowRun.delegatedParts`），再由原角色完成其餘工作
  （`primaryNotice`）；改派段未完成就以它的報告當節點結果。續跑同一輪略過已完成的改派段，checkpoint 只交給 runtime 相符的一段；
  節點新的一輪開始時清掉紀錄。已在 `roleOverrides` 的舊任務（#1020）維持整節點改派。
- `teams/bestpartner.json` 的 `verification` 開 `localTestNetwork`（讓 Codex QA 能跑 `qa-backend-*`，依 VM 對照實測 C）。
- 測試：FlowEngineTest 改寫 1 項並新增 3 項（分段順序、越界停止、額度續跑略過改派段、舊任務沿用）；ConfiguredExecutorTest 新增 1 項；
  本機 525 項只有既有的 4 項 Windows 失敗；TeamCatalogTest 通過（新設定合法）。
- 已修正文件：multi-runtime（改派段落改寫）、configurable-team（三列）、data-model（`delegatedParts`、`roleOverrides`）、configuration、operations。
  已核對：engineering-workflow、system-map（不需改）。decisions 追加。
- commit 729f03b（分段改派）、a84128e（QA localTestNetwork）。VM 隔離建置 `split-fallback-build.V0Oc9F` check-source PASS、
  525 項 0 失敗（11 略過），JAR `159e547550c3b9e0433746b504d8902b7c075de5ae667d4d1baf465bbd08a65f`。佇列空時部署：
  bestpartner.json 備份 `bak-before-a84128e`，兩實例 `--validate-team` 通過；bestpartner PID 74870（配置 d2e5ca0a…，備份 `orchestrator-backup.i7vX23`）、
  starters PID 78060（配置 9913fb8e…，備份 `orchestrator-backup.pB7KAS`），皆 0 ERROR、自檢通過、任務資料前後一致。
  分段改派與 QA 跑 Gradle 的真實任務尚未實測；#1018（AWAIT_PR_APPROVAL）、#1020（AWAIT_HUMAN，舊快照、整節點改派）不受影響。

## 2026-10-04 — Claude Code（查證 #1020 改派後文件寫不進去）

- #1020（group 拼法）要改 `build.gradle.kts`、`.claude/skills|rules/**`、`.agents/skills/**`；整個實作節點改派給 `builder_codex`。
  只有 `build.gradle.kts` 提交成功（84f134d）；三份文件的 patch 被拒；後端測試因 Gradle 起不來沒跑；分流後停在 AWAIT_HUMAN。
- VM 實測（codex-cli 0.156.1，`codex sandbox -P :workspace`，暫存目錄已刪）：`.claude/rules`、`.claude/skills`、`src` 可寫，
  `.agents/skills` 唯讀（Read-only file system）。原因是 Codex 把 `.agents` 掛成唯讀。
- 已修正文件：configurable-team（新增「改派後 `.agents/**` 寫不進去」已知問題列）。docs build 通過。未改程式、未 commit。

## 2026-10-04 — Claude Code（查證 Codex 沙箱內 Gradle 起不來）

- 起因：#1018（純文件一行）改派 `builder_codex` 實作時自行跑 `gradle compileKotlin` 失敗，回報 BLOCKED，分流後停在 AWAIT_HUMAN。
- VM 對照實測（codex-cli 0.156.1，`codex sandbox`，暫存目錄 `gradle-probe.*` 已刪）：
  A 預設 `:workspace`＋`GRADLE_USER_HOME=~/.cache/gradle` → native library 失敗；B 快取改到可寫的工作區內 → `--version` 正常，
  但跑 `help` 出現 wildcard IP 失敗（加 `network_access=true` 也一樣）；C `localTestNetwork` 同款 profile（快取可寫＋隔離網路）→ BUILD SUCCESSFUL；
  D 只開快取可寫、不開網路 → wildcard IP 失敗。結論：兩個條件都要有，只有 VERIFICATION 節點能開 `localTestNetwork`。
- 發現：bestpartner `verification`（Codex QA）也沒開 `localTestNetwork`，qa-backend-* 操作會撞同樣問題（尚未在實際任務遇到）。
  2026-10-03 的「模擬沙箱實測」只模擬外層 systemd，沒有測 Codex 內層。
- 已修正文件：configurable-team（bestpartner 表新增「Codex 節點跑 Gradle」已知問題）。docs build 通過。未改程式、未 commit。
- 另查 #1019「核准了沒動作」：21:44:27 核准已收到（稽核有 BUTTON、狀態 PUBLISH_RUNNING），但 `FlowEngine.approve` 把發布排進
  中央佇列，`AI_TEAM_MAX_CONCURRENCY=1` 下排在 #1018（使用者回覆後重跑 implementation）之後。不是錯誤；
  發現 operations 沒寫這點（缺漏），已補在「核准，推分支並開 PR」列。
- 依使用者要求補等待訊息：`FlowEngine.approve` 按下即回覆（佇列沒有工作就說開始；否則列出本實例執行中／排隊中的任務、
  說明不需要再按一次），輪到時再貼「輪到任務 #N，開始推分支、開 PR」。FlowEngineTest 新增 2 項；本機 520 項只有既有的 4 項 Windows 失敗。
  operations 同步改寫。未 commit、未部署。
- 純文件不編譯（使用者核准 A＋B）：A `teams/bestpartner.json` 的 `docOnlyPaths` 加 `docs-site/docs/**`、`docs-site/blog/**`
  （原本不列入的理由「docs-site 會被建置」不成立，獨立驗證 5 條指令都不建置 docs-site）；B `DocOnlyChanges.implementationNotice`，
  由 `ConfiguredExecutor` 在 IMPLEMENTATION 節點、專案有 docOnlyPaths 時附加「純文件變更規則」。ConfiguredExecutorTest 新增 1 項；
  本機 521 項只有既有的 4 項 Windows 失敗。已修正文件：configurable-team（獨立驗證列、Codex Gradle 已知問題列）、engineering-workflow、configuration。
  已核對：operations、data-model（不需改）。
- commit 728d989（核准排隊訊息）、9988a7a（純文件不編譯）。VM 隔離建置 `doconly-build.h4itQZ` check-source PASS、521 項 0 失敗（11 略過），
  JAR `a5bdf1d973d39c30fcf626055147c93c3c86a32e497a82d19523573e39d3877b`。等佇列清空（#1018 AWAIT_PR_APPROVAL、#1019 PR_CREATED、
  #1020 AWAIT_HUMAN）後部署：bestpartner.json 備份 `bak-before-9988a7a`，兩實例 `--validate-team` 通過；
  bestpartner PID 64315（配置 6743989e…，備份 `orchestrator-backup.GOyqIs`）、starters PID 67509（配置 9913fb8e…，備份 `orchestrator-backup.gVxDmB`），
  皆 0 ERROR、自檢通過、任務資料前後一致。#1019 PR https://github.com/a09090443/bestpartner/pull/2 base=`dev`（基準分支改為 dev 的真實端對端通過）。
  核准排隊訊息與純文件不編譯的真實 Slack 行為尚未驗證。

## 2026-10-04 — Claude Code（開成新需求按鈕顯示短標題）

- 起因：bestpartner #1016（純詢問）結束時有 4 顆「開成新需求 1～4」，只有序號、分不出各自要做什麼；使用者選擇由 agent 產生短標題。
- 修改：`schemas/requirements.json` 新增必填 `out_of_scope_titles`；intake 提示詞要求 20 字內、開頭互異、需決定項以「決定…」開頭；
  `SpinoffButtons.blocks` 改收標題清單，按鈕「開成新需求 N：短標題」（30 字截斷、去反引號、無標題退回序號）；
  `FlowEngine.spinoffTitles`（不等長全部退回序號）、訊息內文每項粗體標題；`PrReport` 略過此欄位。
- 測試：SpinoffButtonsTest、FlowEngineTest 各新增 1 項並更新既有斷言；本機 518 項僅既有 4 項 Windows 失敗；check-source PASS；docs build 通過。
- 已核對的文件：operations、configurable-team、plan-approval-buttons、data-model、engineering-workflow、interaction-model。
  已修正：operations（提示詞與按鈕說明）、configurable-team（欄位來源）、plan-approval-buttons（按鈕名稱）；decisions 追加。
- 部署（commit cba9ab7，使用者授權）：VM 隔離建置 `spinoff-title-build.eQoZ1N`（`git -c core.autocrlf=false archive`）check-source PASS、
  518 項 0 失敗（11 略過），JAR `852779f99e8ade7dcd628382211d15b8e131c4725e44bd6a7b8dac1931ad6e36`。兩實例 `intake.md`、`requirements.json`
  備份為 `*.bak-before-cba9ab7` 後更新，`--validate-team` 通過，以 deploy-jar-safe 換 JAR：
  bestpartner PID 46239（配置 6f752df8…，備份 `orchestrator-backup.kyanHF`）、starters PID 49601（配置 9913fb8e…，備份 `orchestrator-backup.gtyis6`），
  皆 0 ERROR、自檢通過、任務資料前後一致。真實 Slack 按鈕顯示尚未驗證（需新的純詢問任務且有發現）。

## 2026-10-04 — Claude Code（bestpartner 基準分支改為 dev）

- 使用者要求：`teams/bestpartner.json` 的 `project.baseBranch` 由 `master` 改為 `dev`（任務分支從 `origin/dev` 開、PR 合併到 `dev`）。
- 發現：遠端 `a09090443/bestpartner` 目前只有 `master` 與 `feature/ai-team-task-1015`，**沒有 `dev`**；
  未建立前新任務會在建工作區的 `git fetch … refs/remotes/origin/dev` 失敗。#1015 等既有任務沿用快照的 `master`。
- 驗證：check-source PASS；docs build 通過；TeamCatalogTest 21、TeamMigrationTest 4、ConfiguredExecutorTest 31 項 0 失敗。
- `dev` 由使用者本人在 GitHub 建立（GitHub MCP 的 PAT 回 403，VM repo 無推送認證），`ls-remote` 確認 `dev` = `master` = `ae4d98a`。
- 部署（commit c27be38，只換配置、JAR 不變）：`--validate-team` 通過（revision `3d061a81…`）；舊檔備份 `teams/bestpartner.json.bak-before-c27be38`；
  bestpartner PID 39362，log 0 ERROR、沙箱與工具鏈自檢通過、新任務使用配置 `3d061a81…`，任務資料前後一致（#1015 PR_CREATED 不受影響）。
  從 `dev` 開分支的新任務尚未實測。
- 已核對的文件：configurable-team、configuration、INSTALL、cross-project-execution。已修正：configurable-team（bestpartner 段落改為 `dev`，附前置條件）。

## 2026-10-04 — Claude Code（開 PR 失敗留在等待核准 PR、重新進入核准 PR）

- 起因：816f006 部署後 #1015 驗收、審查通過，使用者核准 PR，但 bestpartner `AI_TEAM_GITHUB_TOKEN` 為空（新 VM 沿用範本 `""`）開 PR 失敗；
  explainer 改寫成「缺少認證憑證」看不出原因，且 AWAIT_HUMAN 選項中沒有能回到核准 PR 的按鈕。
- 使用者本人填入 bestpartner token，兩實例重啟：bestpartner PID 24262（token 警告消失）、starters PID 27003（token 仍未設定），皆 0 ERROR。
- 修正（commit 0567b3a，使用者核准計畫）：`FlowEngine.publishFailed`（PublishException 留 AWAIT_PR_APPROVAL、貼原文＋核准按鈕、不呼叫 explainer）；
  `HumanOptions.resumeWillProceed` 接受 APPROVAL 節點，選項顯示「重新進入核准 PR」（指令仍為「續跑」）。
- 測試：FlowEngineTest 新增 2 項；本機 516 項僅既有 4 項 Windows 失敗；VM 隔離建置 `publish-retry-build.fWcxMr` check-source PASS、
  516 項 0 失敗（11 略過），JAR `e76c625ac2344dfe8cb2343ba277c4113114caacde9109d932b20a359f797570`；docs build 通過。
- 已核對的文件：failure-reporting、operations、engineering-workflow、review-gate、system-map、interaction-model。
  已修正：failure-reporting（發布失敗不再走 explainer）、operations（按鈕表）、engineering-workflow（PR 段落）；decisions 追加。
- 部署（使用者授權）：bestpartner PID 31542（備份 `orchestrator-backup.duv9KQ`）、starters PID 34723（備份 `orchestrator-backup.mt19ts`），
  皆 0 ERROR、任務資料前後一致。待 #1015 按「重新進入核准 PR」→ 核准 PR（真實端對端尚未驗證）。

## 2026-10-04 — Claude Code（LIMITED 節點缺少的明確可寫目錄改為預先建立）

- 起因：bestpartner #1015（承接 #1014）受保護路徑改派**真實成功**（builder_codex 實作，系統代為提交 490e009），
  但驗收在派工前檢查停下：`寫入超出沙箱政策：…/task-1015/bestpartner-service/build`。實作只改文件、沒跑建置，`build/` 不存在；
  `PathRules.writableRoots` 原本只對 `localTestNetwork` 節點建立缺少的明確目錄，其他節點不納入可寫政策，`OperationPreflight` 因此拒絕。
- 修正（commit 816f006，使用者選方案 1）：明確目錄一律預先建立；萬用字元不猜測、全無匹配仍 fail closed；symlink 祖先仍拒絕。
- 測試：ConfiguredExecutorTest 改寫 1 項＋新增 1 項；本機 514 項僅既有 4 項 Windows 失敗；VM 隔離建置 `writable-dir-build.aies7p`
  check-source PASS、514 項 0 失敗（11 略過），JAR `bb5e8503b8b0090a1bb54ff57e694318ec184413507193f0721598b468ae4eea`；docs build 通過。
- 已核對的文件：configuration、configurable-team、local-test-capabilities、multi-runtime、system-map（無需改）。
  發現的落差：local-test-capabilities 寫「其他節點缺目錄即失敗」是舊設計；已修正 configuration、configurable-team、local-test-capabilities；decisions 追加。
- 部署（使用者授權後，deploy-jar-safe，只換 JAR）：bestpartner PID 17060（備份 `orchestrator-backup.IzCkOS`）、starters PID 19807（備份 `orchestrator-backup.m1J2oy`），
  皆 0 ERROR、沙箱自檢通過、任務資料前後一致。#1015 在修正前（01:37 UTC）按過「續跑」又被同錯誤擋下；verification visits 仍為 1/3。
  待使用者在 #1015 按「續跑」做真實驗收。

## 2026-10-03 — Claude Code（純文件變更略過系統獨立驗證的建置測試）

- 使用者核准計畫後實作：`Verification.docOnlyPaths`、`DocOnlyChanges`（判定與強制全跑名單、路徑驗證）、
  `IndependentVerifier`（略過時收據 `commands=[]`＋`docOnlyChanges`，`blockingReason` 重驗）、`changedPathsSinceBase` 加 `--no-renames`
  與 `core.quotePath=false`、Slack 說明、`receiptView` 與 PR 說明。bestpartner.json 啟用 `*.md`、`docs/**`、`.claude/**`、`.agents/**`。
- 測試：IndependentVerifierTest 4、TeamCatalogTest 1、ConfiguredExecutorTest 1、WorkspaceManagerTest 1（改名兩端與中文檔名）；
  本機 503 項（僅既有 4 項 Windows 失敗）；check-source PASS；docs build 通過。
- 已修正文件：engineering-workflow、configurable-team、configuration、data-model（回滾風險）、project-tree；decisions 追加。
- 部署（commit 27dd80d，JAR `5827e12e…`，VM Linux 503 項 0 失敗；bestpartner.json 以實例環境 `--validate-team` 通過）：
  bestpartner.json 備份為 `bestpartner.json.bak-before-27dd80d` 後更新；bestpartner PID 53254（配置 dafe84b2…）、starters PID 56461（配置 72e4b58f…），
  皆 0 ERROR、任務資料前後一致。#1010～#1012 為舊快照，不套用略過。待新純文件任務實測。

## 2026-10-03 — Claude Code（沙箱路徑含空白：獨立驗證 Invalid ReadOnlyPaths）

- #1012 測試計畫以 inspect-content 通過並自動核准、實作完成，但獨立驗證 check-0.log 只有
  `Failed to start transient service unit: Invalid ReadOnlyPaths`：bestpartner 有 `docs/rag/doc/tcb/General rule.pdf`、
  `transaction limit.pdf`，systemd 路徑清單以空白分隔。VM 暫存目錄實測：整段加雙引號確實唯讀；`\x20` 不報錯但仍可寫（不採用）。
- 修正 `ExecutionSandbox.pathProperty`：含空白加雙引號，雙引號／反斜線／控制字元直接拒絕；`SandboxPathPropertyTest` 3 項。
  本機 496 項（僅既有 4 項 Windows 失敗）。已修正文件：engineering-workflow（獨立驗證唯讀釘選）。
- 先前 commit 68548aa（按鈕＋輸入視窗）已部署：bestpartner PID 36758、starters PID 39867（JAR `c3df0389…`）。
- 部署 a0d84ef（JAR `c77ec4f7…`，VM Linux 496 項 0 失敗）：bestpartner PID 44998、starters PID 48127，皆 0 ERROR、任務資料前後一致。
  待使用者在 #1012 按「繼續執行」實測獨立驗證，以及實測輸入視窗。

## 2026-10-03 — Claude Code（「補充 #N …」打字操作改按鈕＋輸入視窗）

- 使用者要求三類都改：①有按鈕的不再教打字（`HumanOptions.render(..., typed)`，按鈕版 typed=false，純文字後備才附指令；
  承接說明、計畫卡、配置更新同步）；②需求要改／告訴它還缺什麼／修好了請重驗改按鈕開 Slack modal（新 `HumanInput`，
  送出組成同一指令走 `requestTriage`；重驗開窗當下由系統帶入節點與 HEAD）；③同串再 @、計畫自動核准、操作被拒、
  卡在上限時附「查看目前可用選項／檢視測試計畫／重新整理選項」按鈕（`HumanGate.single`、新選項 `STATUS`）。
- 順手修正既有 bug：計畫自動核准後叫人「檢視測試計畫」，但任務執行中一律被狀態檢查擋掉；唯讀查看改為任何狀態可用，
  已核准計畫只顯示不附核准按鈕。指令按鈕上限 4→8。
- 測試：HumanInputTest 6、HumanOptionsTest 1、FlowEngineTest 1、HumanGateTest／ThreadTakenMessageTest 調整；本機 493 項（僅既有 4 項 Windows 失敗）。
- 已修正文件：plan-approval-buttons（按鈕表、視窗、其他按鈕）、operations、interaction-model、project-tree、system-map。
  **Socket Mode 下 modal 尚未真實 Slack 實測。**

## 2026-10-03 — Claude Code（內建 inspect-content：文件修改在任何專案都能驗收）

- 起因：bestpartner #1010／#1011（改一行 SKILL.md）在測試計畫反覆受阻：operations 寫不出讀檔指令，PM 又把 shell 腳本寫成驗收項目。
  三份配置都有此缺口。使用者核准計畫後實作：`OperationContract.INSPECT_CONTENT`（保留 ID、v2 才可用、配置不得宣告）、
  `Traceability.planReason` 允許引用、`ConfiguredExecutor.inspectionNotice`（plan／verification 上下文說明）與
  `changedFiles`（驗收附系統查得的變更檔案，`WorkspaceManager.changedPathsSinceBase`）；intake／plan／verify 提示詞同步。
- 測試：OperationContractTest 2、TraceabilityTest 1、ConfiguredExecutorTest 2、FlowEngineTest 1（純文件任務走到 PR 核准）；
  本機 485 項（僅既有 4 項 Windows 失敗）；check-source PASS；docs build 通過。
- 已修正文件：qa-scenario-workflow、cross-project-execution、configurable-team（漂移掃描不得列為驗收項目）、operations；decisions 追加。
- 部署（commit 5ff0155，JAR `8fc79c73…`，VM Linux 485 項 0 失敗）：三份提示詞先備份為 `*.bak-before-5ff0155` 後更新，再以 deploy-jar-safe 換 JAR：
  bestpartner PID 26858（配置 045533c0…，備份 `orchestrator-backup.2BvRVL`）、starters PID 30031（配置 7ed70287…，備份 `orchestrator-backup.LtyLjQ`），
  皆 0 ERROR、任務資料前後一致。待使用者在 #1011 送需求變更後實測完整流程。

## 2026-10-03 — Claude Code（Claude 額度解禁時間改顯示台北時間）

- 使用者回報任務訊息「供應商指定解禁：2:30pm (UTC)」應為台灣時間。新增 `QuotaRetryTime.utcReset`（排程與顯示共用），
  `QuotaMessage.resetLine(reason, observed)` 對標明 UTC 的時間換算台北時間並附原文；Codex 未標時區仍照登（commit 1f5803e）。
- 測試：QuotaMessageTest 改寫（含跨日、格式不合不換算）；本機 479 項（僅既有 4 項 Windows 失敗）；VM Linux 479 項 0 失敗，JAR `87c31cfd…`。
- 部署：bestpartner PID 18693（備份 `orchestrator-backup.hftLcQ`）、starters PID 21854（備份 `orchestrator-backup.qtIngn`），
  皆 0 ERROR、任務資料前後一致（#1010、#1011 AWAIT_HUMAN）。已貼出的舊訊息不會改寫。
- 已修正文件：quota-continuation（Slack 呈現）、INSTALL（額度段落）。

## 2026-10-03 — Claude Code（系統訊息顯示成 zipe-ai-team）

- 現象：#bestpartner 的系統訊息顯示為 zipe-ai-team。比對兩實例主 App token（只輸出相同／不同）皆不同；bp-ai-team 的 Bot Name 正確。
  根因：`Identity.SYSTEM` 名稱寫死 `zipe-ai-team`，且系統身分無專屬 token 旗標，每次發言都被 chat:write.customize 覆寫。
- 修正（commit 784ba9b）：`SlackNotifier.overridesName` 系統身分不覆寫；SYSTEM 後備名改 `AI Team`；空白提及範例改用實際 mention。
  `SystemIdentityTest` 3 項；本機 478 項（僅既有 4 項 Windows 失敗）；VM Linux 478 項 0 失敗，JAR `7c0db7d4…`。
- 部署：starters PID 10315（備份 `orchestrator-backup.W2jK02`）；bestpartner 待 #1011 停在 AWAIT_HUMAN 後部署，PID 13678（備份 `orchestrator-backup.17NBOM`）。皆 0 ERROR、任務資料前後一致（#1010、#1011 AWAIT_HUMAN 保留）。
- 已修正文件：interaction-model（主 App 依實例、系統身分不覆寫名稱）。
- 真實 Slack 端對端：#1009（新提示詞詢問）→ 按「開成新需求 1」→ #1010 建立（新討論串、稽核 BUTTON／RECEIVED 正確）→
  使用者在 #1010 按「承接成新任務」→ #1011。開成新需求按鈕已實測可用。

## 2026-10-03 — Claude Code（intake 提示詞：同錯誤合併、需決定標示）

- 實測 bestpartner #1008：同一計數錯誤因在兩個檔案被拆成 2 顆按鈕、權限不一致未標需決定、反引號巢狀致 Slack 顯示錯亂。
  intake.md 補三條規則（commit 6d46677）；check-source PASS、相關測試 116 項通過、docs build 通過；operations 已同步。
- 部署時 VM 失去回應（journal 停在 13:30:02 UTC、無 OOM），使用者重開 VM 後唯讀確認兩服務 STOPPED、無未結束任務、
  提示詞仍為舊版，再逐一換檔啟動：bestpartner PID 3042（配置 ea77edd3…）、starters PID 4855（配置 cfe287b5…），0 ERROR。
- 發現的缺漏：文件未寫明 VM 重開機後服務不會自動啟動 → 已補在 operations 的服務啟停說明。

## 2026-10-03 — Claude Code（intake 提示詞：純詢問發現必須列入 out_of_scope）

- 使用者選 A：只改 `teams/prompts/intake.md`（commit f251bf0），不改角色對話。check-source PASS、
  TeamCatalogTest／FlowEngineTest／ConfiguredExecutorTest 116 項通過、docs build 通過；已修正 operations「建立任務」。
- 部署（使用者授權）：新 intake.md（sha256 `5a49f582…`）複製到兩個安裝目錄 `teams/prompts/`，舊檔備份為
  `intake.md.bak-before-f251bf0`，重啟：bestpartner PID 66614（配置版本 8890d3a7… → 5251bdbd…）、
  starters PID 69293（3c6ca81b… → 238a05b5…），皆 0 ERROR、沙箱自檢與 5 個角色連線正常。新規則只套用重啟後建立的任務。

## 2026-10-03 — Claude Code（純詢問發現「開成新需求」按鈕）

- 起因：bestpartner #1006（列出所有 API）為 `route=question` → `end` → COMPLETED；PM 發現文件寫 68、實際 69 個 endpoint，
  只能寫進 `out_of_scope`。使用者核准計畫後實作：`SpinoffButtons`（action `ai_team_spinoff_N`、value `任務|序號`）、
  `FlowEngine.offerSpinoffs`／`spinoff`（新討論串、新任務從 intake 走、負責人＝按的人、不沿用核准、同項只開一次）、
  `FlowRun.spinoffs`／`spinoffOf`、`SlackListener.handleSpinoffButton`（白名單＋原討論串）、`TaskOrchestrator.spinoff`。
- 測試：FlowEngineTest 新增 4 項＋舊 JSON 相容、SpinoffButtonsTest 4 項。
- 已核對：operations、configurable-team、data-model、plan-approval-buttons、system-map、agent/index、project-tree。
  已修正：operations（純詢問「已結束」的意義與按鈕）、configurable-team、data-model（新欄位與**回滾風險**：舊 JAR 讀不了新欄位）、
  plan-approval-buttons、system-map、project-tree。docs build 通過。
- 驗證：本機 475 項（僅既有 4 項 Windows 專屬失敗）；commit 350b02e；VM 隔離目錄 `~/ai-team-source-350b02e` Linux 475 項 0 失敗，
  JAR `ba3d6dcd…`。`deploy-jar-safe.sh` 部署 bestpartner（PID 60161，備份 `orchestrator-backup.3Ut3aA`）、
  starters（PID 63293，備份 `orchestrator-backup.oSxqbe`）：皆 0 ERROR、自檢通過、任務資料前後一致。**Slack 按鈕尚未實測**。

## 2026-10-03 — Claude Code（bestpartner 改用自己的角色 App；同串訊息依狀態說明）

- 查「@角色沒回應」：#bestpartner @舊角色 App 的事件被 starters 以「忽略非指定頻道」丟棄（共用角色只填 Bot Token 的已知代價）。
- 使用者升級 Slack Pro（官方定價頁：Pro 起 App 不限）。Claude Code 經使用者 Chrome 安裝 5 個 BP 角色 App、
  邀請進 #bestpartner，並把 starters 的 5 個角色 App 移出該頻道（未解除安裝；workspace 共 12 個 App、無重複）。
  10 個 token 由使用者本人以 `set-slack-tokens.sh` 填入；重啟 bestpartner（PID 46725）：5 個角色直接對話 Socket Mode 啟動、0 ERROR；
  使用者直接 @BP 角色 → bestpartner #1003、#1004 唯讀角色對話完成。starters（PID 24757）未受影響。
- 落差修正：同串再 @主機器人的回覆一律叫人「補充 #編號 答案」，但補充只對 AWAIT_HUMAN／WAITING_QUOTA 有效，且有簡體「关联」。
  新增 `TaskOrchestrator.threadTakenMessage` 依狀態給三種說明＋`ThreadTakenMessageTest`（4 項通過）。commit 0767b88。
  部署：VM 隔離目錄 `~/ai-team-source-0767b88` 建置，Linux 全部測試 467 項 0 失敗（略過 10）；JAR SHA256 `7aef28d3…`。
  以 `deploy-jar-safe.sh` 依序部署 bestpartner（PID 52025，備份 `orchestrator-backup.DRDLJQ`）與 starters
  （PID 55172，備份 `orchestrator-backup.sjKf0X`；同時更新到含 09b82bb 的版本）：皆 0 ERROR、沙箱自檢通過、
  5 個角色直接對話啟動、任務資料前後一致。新訊息在 Slack 的實際呈現尚未實測。
- 已核對：operations、configurable-team、direct-agent-requests。已修正：operations「建立任務」、configurable-team（現行做法改為各自角色 App，
  免費方案共用做法標為已被取代；同串回覆說明）。docs build 通過。

## 2026-10-03 — Claude Code（bestpartner 第二個實例）

- 新增 `teams/bestpartner.json` 與沙箱 `GRADLE_USER_HOME` 白名單（commit 09b82bb）。VM 實測：全部後端測試 262 項中 55 項失敗
  （15 個 `@QuarkusTest` 連不到 PostgreSQL、Google 憑證、CodeExecutorTest 負載逾時）；選定 23 個不需資料庫類別 205 項離線通過；
  模擬沙箱證實只開放 `~/.gradle/caches` 會「Could not initialize native services」，改 `~/.cache/gradle` 後通過；
  UI vitest 51 檔 479 項、vue-tsc 建置與漂移掃描（新漂移 0）通過；repo pwsh hooks 實測只產生非阻擋錯誤。
- VM 安裝官方 Gradle 8.14.5（官方 SHA256 核對）至 `~/.local/gradle`；install-app 到 `~/ai-team-bestpartner`（463 項 0 失敗，
  JAR `00c86a79…`）；`AI_TEAM_INSTANCE=bestpartner`、`GRADLE_USER_HOME=$HOME/.cache/gradle`、基準分支 master。
- Slack：建立 bp-ai-team 等 6 個 App，因免費方案 10 個 App 上限 BP QA／Backend 裝不上；依使用者決定改為角色沿用 starters 的
  5 個 App 且只填 Bot Token，新 BP 角色 App 解除安裝（未刪除）；建立 #bestpartner 並邀請 bp-ai-team 與 5 個舊角色。
  token 由使用者本人透過 `~/import-slack-tokens.sh`（支援目標目錄）與 `~/set-slack-tokens.sh`（不回顯、顯示字元數）填入。
- 啟動：PID 41835，0 ERROR，沙箱與工具鏈（含 gradle）自檢通過，自檢 unit `ai-team-bestpartner-selftest-…`；starters 不受影響。
- 已修正的文件：configurable-team（bestpartner 配置一節、共用角色 Bot Token 與 10 個 App 上限）、INSTALL §5.2（Gradle 前置）、
  multi-runtime（環境變數白名單）、configuration（GRADLE_USER_HOME）、project-tree、env 範本、decisions。
- 尚未實測：#bestpartner 實際對話與完整管線（Gradle 驗收、系統獨立驗證冷下載耗時）。

## 2026-10-03 — Claude Code（多個服務實例：AI_TEAM_INSTANCE 區分 systemd unit）

- 使用者決定每個專案各自一個服務。發現跨實例風險：「中斷這一輪」／「中止任務」以 `ai-team-<任務>-*` glob 停 unit，
  兩個實例同編號任務會互相誤停；`deploy-jar-safe.sh` 看全部 `ai-team-*` 且 root 寫死 vboxuser；`service.sh status` 未依 PID 過濾。
- 使用者核准計畫後實作：`AiTeamProperties.unitPrefix()`（`AI_TEAM_INSTANCE`，留空＝舊名稱）、`TaskUnits` 改收字首，
  三個 runtime、驗證、預檢、自檢、中斷／中止全部改用字首；preflight 檢查格式並在偵測到其他實例而本項為空時失敗；
  `service.sh status` 只列本 PID；`deploy-jar-safe.sh` 第三參數為安裝目錄、只看本實例 unit；env 範本加說明。
- 測試：本機 463 項僅既有 4 項 Windows 失敗；VM 隔離建置 `instance-build.g2Na1o` 463 項 0 失敗（10 opt-in 略過）、
  check-source 通過；opt-in `LinuxVerificationSmokeTest`（2 項）與 `CodexSandboxVmTest`（4 項）通過。
- 部署新 VM（PID 24757，JAR `75ab2cf99349aae4582100bf636368cfd19a9d0307ff58bd5911a51e4e616cfc`，備份 `orchestrator-backup.W5p5hb`，
  腳本備份 `scripts-backup.PVnwGa`），`team.env` 加 `AI_TEAM_INSTANCE=starters`：0 ERROR、自檢 unit 為 `ai-team-starters-selftest-…`。
- VM 實測：模擬舊格式 `ai-team-1001-builder-sim`、`ai-team-check-1001-sim` 與 `ai-team-starters-1001-builder-sim`，
  以新 glob 停止後只有 starters 的 unit 停下；暫時目錄留空實例名時 preflight 失敗、不合法名稱被拒；status 只列本 PID。
- 尚未實測：Slack「中斷這一輪」真實操作；兩個實例同時連 Slack 的端對端（待第二個專案）。preflight 不檢查兩實例同名。
- 已核對的文件：configurable-team、pm-triage、configuration、operations、INSTALL、system-map（無需改）、contributing（無需改）。
  已修正的文件：configurable-team（新增多實例一節）、pm-triage（unit 名稱）、configuration（`AI_TEAM_INSTANCE`）、
  operations（status 說明）、INSTALL §5.1、catalog（摘要與 TaskUnits 對應）、decisions（新決策）。

## 2026-10-02 — Claude Code（新 VM「Agent」乾淨重建演練，進行中）

- Ubuntu 26.04 LTS、使用者 `agent`、NAT SSH `127.0.0.1:2223`（主機指紋已與主控台核對）。使用者本人輸入 sudo 密碼：
  apt 補 openssh-server／sqlite3 等；依使用者決定放寬 AppArmor userns（`/etc/sysctl.d/60-ai-team-userns.conf`）。
- 以 `git -c core.autocrlf=false archive` 打包 f077fe3（SHA256 `62ae8c23…`），check-source、check-linux runtime、
  provision（空快取）、CLI 2.1.282／0.156.1、install-app（458 項 0 失敗，JAR `ea74a4a3…`）、
  目標 repo 冷快取 verify（303 項 0 失敗；第一次遇 Maven Central reset，`-U` 重跑通過）皆通過。
- 已填 env.sh `AI_TEAM_BASE_BRANCH=develop`、建立 team.env（spring-starters.json）。
- 發現的落差：INSTALL 打包指令在 Windows autocrlf 下產生 CRLF，且會帶入 gitignore 的 `deploy/*.tgz`。
  已修正的文件：INSTALL.md §1、rebuild-verification.md（新增證據）、linux-support.md（新 VM 列）。docs-site build 通過。
- 未完成：Claude／Codex 本人登入、獨立測試 Slack App／頻道與 env.sh token、preflight／啟動自檢、完整管線、備份還原。
- 2026-10-03 續：bashrc 開頭加 PATH；沿用正式 6 個 Slack App、新建 `#ai-agents`（C0C6ECHH44S）並邀請；
  token 由使用者本人經 `~/import-slack-tokens.sh` 從舊 VM 匯入；使用者完成 CLI 登入；preflight PASS。
  停舊 VM 服務並關機後啟動新 VM：0 ERROR、沙箱與工具鏈自檢通過、五角色 Socket Mode 啟動。
  已修正的文件：INSTALL.md §2（PATH 區塊）、rebuild-verification.md（Slack／啟動證據）。Slack 事件與完整管線尚未實測。

## 2026-09-30 — Claude Code（Claude Code 受保護路徑：查因與事先告知）

- 查 #1021：implementation 改 `.claude/rules/project-overview.md` 被拒（audit 1000），同批其他 Edit 成功。
  根因是 `ClaudeCodeRuntime` 固定 `--permission-mode dontAsk`，Claude Code 對受保護路徑（`.claude`、`.git`、`.mvn` 等）
  在 dontAsk 下一律拒絕，allow 規則開放不了（官方 permission-modes 文件）；不是沙箱或檔案權限。
  triage 提出的「開放 `.claude/rules/**` 權限後重派」依官方文件無效。
- 使用者核准計畫後實作（未部署）：`AgentRuntime.protectedWritePaths()`／`isProtectedWrite()`，
  `ClaudeCodeRuntime.PROTECTED_WRITE_PATHS` 與 `annotateDenials()`（被拒時補上真正原因），
  `ConfiguredExecutor.protectedPathNotice()`（寫檔節點：不要嘗試、不是 boundary_conflict；唯讀節點：不列為驗收、不提議開放權限）。
  另把 boundary_conflict 說明的「保護路徑」改為「沙箱／配置保護路徑」。計畫原本要改 `Diagnostics.toolFailures` 簽章，
  改為在 runtime 補 `reason`，所有讀者自動生效。`NodeInput` 指紋不含 context，不影響防循環計數。
- 驗證：Windows 本機 Maven 458 項，只有已知的 4 項 Windows 專屬失敗；新增 `ClaudeCodeProtectedPathTest` 與
  `ConfiguredExecutorTest` 3 項都通過。check-source、docs-site build 通過。**程式測試通過，真實端對端未實測。**
- 發現的缺漏：文件未記載此限制。已修正的文件：`docs-site/docs/multi-runtime.md`（新增受保護路徑段落與系統處理方式）、
  `docs-site/catalog.mjs`（multi-runtime 的 code 加入 ClaudeCodeRuntime）。
  已核對、無需修改：`configurable-team.md`、`agent/system-map.md`、`agent/project-tree.md`（新測試檔在既有 runtime/ 目錄下）。
- commit 0ac9f20 後依使用者要求部署：VM 隔離目錄 `protected-path-build.AwyMOC` 以 `git archive` 來源執行 `mvn -B package`，
  458 項 0 失敗、10 項 opt-in 略過；`deploy-jar-safe.sh` 部署成功，PID 467207，JAR `604fc19f…`，備份 `orchestrator-backup.Xqkhwu`，
  ERROR 0 行、沙箱自檢通過、任務資料前後一致。
- 部署時發現 VM 的 Claude Code OAuth 已過期（#1021 audit 1009），需使用者本人重新登入。

## 2026-09-27 — Claude Code（移除沒有用的文件內容）

- 使用者要求移除沒有用的文件內容，確認範圍為：刪除歷史文件、精簡 .ai 交接檔、清現行文件的過時段落、刪除舊部署紀錄。
- 刪除 docs-site/docs 的 12 份歷史文件（HANDOFF、INSTALL-20260904-history、vm-setup-manual、build-and-run、architecture-review、
  slack-options、phase0-report、sandbox-report、workspace-report、backend-report、qa-report、codex-runtime-report）、reference/ 目錄、
  deploy-2026-09-09-approval 紀錄，以及內容已被 system-map 取代的 corrected-architecture。仍有用的疑難排解搬進 INSTALL §10 與 slack-agent-apps。
- 刪除 deploy/ 下 34 支一次性部署腳本與 #1010／#1014 專用檔（Java 檢查程式、jq、補發訊息腳本、phase0-verify.sh）；
  保留 deploy-orchestrator.sh、deploy-jar-safe.sh、engineering-v2-team.env。deploy/README.md 改寫為目錄說明。
- 程式註解、env 範本、pom、prompts/architecture.md、task-schema.json 中指向已刪文件的引用改為直接敘述（只改註解與說明文字）。
- 重寫或精簡現行文件：multi-runtime（對照 AgentRuntime 能力宣告與 ExecutionSandbox 實作）、interaction-model（五角色、只在真人閘門停下）、
  slack-agent-apps（併入主 App 設定與疑難排解）、direct-agent-requests（去除重複步驟）、quota-continuation（去除重複段落）、
  codex-cli-argument-contract、codex-nested-sandbox、local-test-capabilities、slack-progress、pm-triage、plan-approval-buttons、
  qa-scenario-workflow、configurable-team、engineering-workflow、cross-project-execution、failure-reporting、per-agent-model、rebuild-verification。
  移除部署流水帳（PID、SHA、備份目錄、舊測試數字），行為描述保留。紀錄與決策文件只修引用，不改寫歷史。
- 發現的落差並已修正：INSTALL §6.1 與 TeamMigration 註解仍寫「模型變更一律拒絕」，程式其實允許同一 runtime 內換模型；
  slack-agent-apps 仍寫角色 App 不需要 App-Level Token；slack-progress 仍寫 UTC 時間且未提中斷按鈕；
  interaction-model 仍描述四角色、唯寫 App 與每階段閘門；README 架構表仍寫專用 user／網路白名單／worktree；per-agent-model 漏 Code Review。
- sidebars.js、llms-txt.mjs 改依文件 status 判斷歷史分類；agent/index.md 閱讀規則改為「現行文件只寫現況」。
- .ai/context.md 改寫為只含當前狀態；本檔 09-25 以前的條目壓縮為摘要。decisions.md 未動。
- 驗證：docs-site npm run build 通過（27 份文件、目錄樹 215 項一致）；check-source 通過；本機 Maven 451 項中 4 項失敗、11 略過，
  失敗皆為 Windows 環境（/bin/sh 不存在 2、symlink 權限 1、git CRLF 警告混入輸出 1），與本次無關。已 commit（d6ac1e4）並 push 至 origin/main。

## 2026-09-27 — Claude Code（AI 工具設定單一來源：規則、skills、MCP）

- `CLAUDE.md` 縮為只有 `@AGENTS.md`；原 Claude 專屬內容（開場讀 .ai 與文件、收尾、plan mode、`.ai/` 非執行設定、
  下游 prompt 位置）改寫為通用規則併入 AGENTS.md。AGENTS.md 新增「AI 工具設定（單一來源）」。
- 新增 `.agents/`：`mcp.json`（MCP 單一來源，目前無共用 server）、`skills/docs-sync/SKILL.md`（文件優先核對流程）、
  `sync-agent-config.mjs`（產生 `.mcp.json`、`.cursor/mcp.json`、`.vscode/mcp.json`、`.gemini/settings.json`、`opencode.json`，
  鏡像 skills 到 `.claude/skills/`，只替換本機 `.codex/config.toml` 的標記區塊；`--check` 檢查一致性、CLAUDE.md 只含匯入、
  VS Code `chat.useAgentsMdFile`）。`.vscode/settings.json` 開啟 `chat.useAgentsMdFile`；`.gitignore` 加 `.claude/settings.local.json`。
- 各工具讀取位置依官方文件查證（Codex skills `.agents/skills`、Codex 專案 MCP 僅受信任專案、Gemini `context.fileName`、
  VS Code `chat.useAgentsMdFile`、Cursor／opencode 同時讀 `.agents/skills` 與 `.claude/skills`、Claude Code 只讀 `.claude/skills`）。
  僅「Claude Code 與 Codex 讀規則」為本專案實際使用，其餘在 `ai-tools.md` 標為「文件記載」。
- 暫存目錄測試：stdio／http 範例轉換五種格式正確、Codex 區塊保留既有 `dbx` 且重跑冪等、同名衝突拒寫；
  負面測試（手加 `.claude/skills`、CLAUDE.md 多內容）皆使 `--check` 失敗。本機 `.codex/config.toml` 未被修改。
- 文件：新增 `docs-site/agent/ai-tools.md`；更新 project-tree（新增頂層項目，檢查範圍加入 `.agents/`、`.claude/`）、
  contributing、index、docs-site README、根 README。docs-site prebuild 加入 `--check`。
- 驗證：`npm run build` 通過（45 份文件、目錄樹 224 項、AI 工具設定一致）；check-source 通過。未 commit。

## 2026-09-27 — Claude Code（專案目錄樹與「文件優先」規則）

- 新增 `docs-site/agent/project-tree.md`：repo 全目錄樹（每個主程式類別、teams／prompts／schemas、deploy、docs-site 檔案
  附一句用途，類別說明取自各類別 Javadoc）、「我要找……」對照表、不在版控的本機產物。
- `docs-site/scripts/check-catalog.mjs` 擴充目錄樹檢查（prebuild 執行）：樹中路徑必須存在（支援 `*`），
  `orchestrator/src/main/`、`teams/`、`prompts/`、`schemas/`、`deploy/`、`docs-site/`（docs/ 除外）每個受版控或新增未忽略檔案
  必須列在樹中，頂層項目也須列出。反向測試：新增未列檔案、樹中寫不存在路徑，兩者皆使檢查失敗，已還原。
- AGENTS.md 將「功能修改必須同步文件」擴充為「文件優先（強制）」：所有工作先讀文件再讀程式；文件查不到但程式存在要補寫；
  功能與文件有落差以程式實際行為修正文件，若判斷是程式錯誤則先回報使用者、文件標明已知問題；任何功能異動都要核對文件。
  同步更新 CLAUDE.md 開場與收尾、`docs-site/agent/contributing.md`、`index.md` 路由表、docs-site README；
  AGENTS.md／README 連結文字 `docs/…` 改為 `docs-site/docs/…`。
- 驗證：`npm run build` 通過（45 份文件、目錄樹 206 項一致）。未改程式行為，未跑 Maven。未 commit。

## 2026-09-27 — Claude Code（docs/ 移入 docs-site/docs/，刪除根目錄 docs/）

- 使用者要求整理完即刪除根目錄 `docs/`。原本 docs-site 只是掛載 `../docs`，內容並未在 docs-site 內，
  因此改以逐檔 `git mv` 將 49 個檔案移到 `docs-site/docs/`（保留歷史；整個目錄 mv 被 Windows 鎖住），根目錄 `docs/` 已不存在。
- 已修正的文件與引用：AGENTS.md、根 README（連結、目錄樹）、CLAUDE.md 未涉及路徑；docs-site 的 config（`path: 'docs'`）、
  catalog 檢查、llms 外掛、`/catalog` 頁、README、agent 入口頁；`docs-site/docs/data-model.md` 對 schema.sql 的相對連結；
  `docs-site/docs/INSTALL.md` 來源打包指令由 `docs` 改為 `docs-site/docs docs-site/agent`（否則 tar 會失敗）；
  deploy/README.md、env.sh.template、pom.xml、prompts/architecture.md、impl-schema.json、schema.sql、
  12 個 Java 主程式／測試的註解路徑。目標專案的 `doc-site`、`qa-docs`、`docs/build/**` 等路徑未動。
  `.ai/` 與歷史文件（HANDOFF 等）中的舊路徑屬歷史紀錄，未改寫。
- 驗證：docs-site `npm run build` 通過（45 份登錄、無斷鏈）；check-source 通過；本機 Maven（D:\tools\apache-maven-3.9.5、
  `-o -Dmaven.repo.local=.build-m2`）451 項中 4 項失敗、11 略過，失敗皆為 Windows 環境（/bin/sh 不存在 2、symlink 權限 1、
  git CRLF 警告混入輸出 1），與本次僅改註解無關；Linux 上未重跑。未 commit。

## 2026-09-27 — Claude Code（文件網站改為以 AI agent 為主要讀者）

- 新增 `docs-site/agent/`（第二個 docs 實例，路由 `/agent/`）：快速入口與依任務找文件、系統全貌（分層、流程節點、
  任務狀態、程式碼地圖）、操作與指令（Slack 指令與按鈕、維運／開發指令）、設定參考（env 與團隊 JSON）、修改規則。
  指令字串、按鈕文字與 JSON 欄位對照 `SlackListener`、`SlackBlocks`、`FlowEngine`、`spring-starters.json` 與 env 範本核對。
- 新增 `docs-site/catalog.mjs` 登錄 docs/ 全部 45 份文件的分類、狀態（現行／紀錄／決策／歷史）、用途、何時讀與相關程式；
  sidebar、`/catalog` 頁面與 `llms.txt` 都由它產生。`scripts/check-catalog.mjs` 於 prebuild 檢查登錄與 docs/ 一致、
  相關程式路徑存在。`plugins/llms-txt.mjs` 在 build 產生 `llms.txt`、`llms-full.txt` 與每份文件的 `.md` 原文。
- `docs/` 未搬移、未修改（被 AGENTS.md、Java 註解、prompts、部署腳本以路徑引用）。首頁入口改指向 AI Agent 入口與文件目錄；
  AGENTS.md、根 README 加上指引。`npm run build` 通過，無斷鏈或斷錨點。未 commit。

## 2026-09-27 — Codex（建立 Docusaurus 文件網站）

- 新增 `docs-site/` Docusaurus Classic 網站，首頁提供建置、架構、工程流程、QA、團隊配置與驗證入口；直接掛載根目錄 `docs/`，未複製或修改原文件。
- 根 README 增加網站啟動方式；`npm run build` 通過。npm install 回報 21 筆弱點（20 moderate、1 high），未執行自動升級。未設定部署目標。

## 2026-09-26 — Claude Code（PR 驗收紀錄改 Markdown 條列）

- PR 內文原本直接貼 `flow.receipts`（Map<String,JsonNode>）與 Receipt record 的 toString，讀者看到整坨 JSON。
  新增 `PrReport`：依流程節點順序，每節點一個 `<details>`（標題附判定；驗收／審查預設展開），
  欄位轉中文標籤的巢狀條列、編號＋狀態當項目標題、多行文字接續縮排；獨立驗證列 commit、台北時間與各指令結果，
  不再外露 VM 紀錄路徑。`FlowEngine.prBody` 以 `##` 分段，審查建議改 `-` 條列（Slack 仍用「・」）。
- 長度：單節點 12,000 字、驗收紀錄總計 40,000 字，於行邊界截斷。#1019 真實資料未設上限時
  渲染超過 GitHub 65,536 字元上限的風險存在；加上限後渲染為 37,186 字。
- 隔離建置 `pr-markdown-build.haUShN`：check-source 通過、451 項 0 失敗、10 opt-in 略過；
  JAR `340121cbaab206a9ccd417816e4b47567e066ce057d580682b6d4008b881e583`。使用者同意後 22:42 台北時間部署（PID 450271，備份 `orchestrator-backup.0MXfNx`），
  0 ERROR、任務資料前後一致。實際 PR 呈現待下次發 PR 時確認。未 commit。

## 2026-09-26 — Claude Code（顯示時間改台北時間）

- 新增 `TaipeiTime`（分／秒兩種格式），`QuotaMessage.taipei` 改委派；階段心跳由
  「狀態更新時間（UTC）：ISO」改為「狀態更新時間：yyyy-MM-dd HH:mm:ss（台北時間）」；
  console log 時戳改 `Asia/Taipei`。DB 仍存 UTC。
- 驗證：本機 JDK 單檔驗證 `TaipeiTime` 跨日換算正確；新增 `TaipeiTimeTest`。
- 隔離建置 `taipei-time-build.WMvDAW`：check-source 通過；完整 package 449 項 0 失敗、10 opt-in 略過。
- 22:29 台北時間以 deploy-jar-safe.sh 部署（PID 443620，JAR `09b04b6219372357ea77ef9576f586549d59d4bf7156669054cde0f6f049a04a`，
  備份 `orchestrator-backup.RyHk3A`），0 ERROR、任務資料前後一致；log 時戳已為台北時間。
  Slack 心跳訊息尚待下次任務執行時實際確認。未 push。

## 2026-09-26 — Claude Code（系統例外白話說明、審查收斂、隨時改用新任務繼續）

2026-09-26 Claude Code：系統例外改由 agent 白話說明（已建置，待 #1019 空檔部署）。
- 新增 ErrorExplainer 與設定 AI_TEAM_EXPLAINER_MODEL（haiku）／AI_TEAM_EXPLAINER_ENABLED；FlowEngine 例外停止、發布失敗、
  操作時系統錯誤、角色對話失敗改走白話說明；步驟名稱改中文；額度停止只移除技術內容。
- VM 以 #1017 真實 error_max_turns 紀錄實測 haiku 兩輪：第一輪洩漏路徑、英文術語、自行補充選項效果（US$0.049）；
  調整規則並提供選項說明後第二輪符合（US$0.028）。
- #1019 已依使用者核准移除 REQ-016（新基準 af0fdb2f…），計畫自動核准、實作、獨立驗證通過；9/25 23:12、23:18 兩次因 Codex 串流被切斷
  停給人（手動續跑成功），已將該訊號列為系統層失敗自動重試。
- 隔離建置 `explainer-final2-build.uvX6tc`：445 項 0 失敗、10 opt-in 略過；check-source 通過；JAR `57f3a6f36d77ee357de400ceffe4bacffa9a05ba20e2a51c496cc75c97490598`。
- 部署時 #1019 正在執行（implementation），deploy-jar-safe.sh 依設計拒絕部署；待任務空檔再部署。

2026-09-26 Claude Code：讓實作／驗收／審查收斂（已建置，待 #1019 空檔部署）。
- 分析 #1019 06:00～12:16 八輪循環（見 decisions）。改動：review.md 收斂規則、verify.md 與 ConfiguredExecutor 不再要求 QA commit、
  intake.md／plan.md 控制規模、FlowEngine 代為提交 QA 測試（commitVerificationChanges）與審查建議段落（reviewSuggestions）。
- 隔離建置 `converge-build.Y4iBji`：447 項 0 失敗、10 opt-in 略過；check-source 通過；JAR `45bbbf9c3467256eb6452f9e9c2ec46a159a5e27cd9f4bc4300646f17829b806`。
- 部署時 #1019 正在驗收（工作區有 QA 未提交的 MailServiceFailoverTest.java），背景等空檔後先替換 teams 提示再以 deploy-jar-safe.sh 部署。

2026-09-26 Claude Code：收斂改動已部署；另開放隨時改用新任務繼續（已部署）。
- 13:03 背景自動部署收斂改動（JAR `45bbbf9c…`，teams 規則備份 `teams-backup.beBI2A`），0 ERROR、資料一致。
- #1019 13:01 停下：審查第 9 次退回（SC-018 測試資料 2 組 vs 計畫 3 組），自動處理 8/8；因舊快照仍用舊審查規則。
- 使用者同意：承接不再限於次數用完；停止訊息一律提供「改用新任務繼續」。隔離建置 `successor-build.idexn9` 448 項 0 失敗；
  部署 13:09（PID 421644，JAR `281ca8060e389d82064e884fbf2f79f3c9f03b267db6262c31a6d0bee6e911d4`，備份 `orchestrator-backup.5fto7Y`），0 ERROR、資料一致。
- 下一步：使用者在 #1019 送「補充 #1019 狀態」後按「改用新任務繼續」。

## 2026-09-07 ～ 09-25 摘要（2026-09-27 壓縮）

> 以下由原本逐次流水帳壓縮而成，只留「做了什麼、學到什麼、任務走向」。
> 各次的建置目錄、JAR SHA256、PID、備份目錄與測試數字請查 git 歷史中的 `.ai/progress.md`
>（2026-09-27 之前的版本）。行為細節以 `docs-site/docs/` 現行文件為準。

### 2026-09-25（Claude Code）
- 升級 VM CLI：Claude Code 2.1.252→2.1.282、Codex 0.153.4→0.156.1，重驗 Codex 沙箱／預檢 VM 測試與 Claude 旗標；builder 改 opus。
- 新增「中斷這一輪」（按鈕或 `中斷 #id`）；「中止任務」改為一併停掉程序；系統驗證 unit 改名 `ai-team-check-<id>-*`。
- 配置更新允許同一 runtime 內換模型（#1017 唯一差異為 builder sonnet→opus）。
- 延長自動交接改以已用事件數為基準，按一次即生效；修正配置更新後延長按鈕不出現（授權碼改用 pendingEvidence）。
  教訓：唯讀檢查不可自行塞假授權碼，要用系統實際產生的值驗證。
- Claude 角色提示加入允許的替代做法（分次重跑、長輸出以 Read／Grep 讀存檔、只操作工作目錄）。
- 使用者看得懂的訊息（`Diagnostics.forUser`）與「保存未提交的修改並承接」。一次部署因 `@Autowired` 被常數隔開導致啟動失敗，
  2.5 分鐘內以備份回復；新增 `SpringWiringTest` 與 `deploy/deploy-jar-safe.sh`。
- #1019（承接 #1017）卡在計畫：intake 寫入流程需求、中斷後半成品被採用。修正為中斷結果一律不採用、負責人可直接核准需求變更、
  計畫停下時提供「需求要改」、intake 不寫流程需求。

### 2026-09-17 ～ 09-19（Claude Code）
- 「承接成新任務」與跨專案通用化；首次使用通用部署腳本 `deploy/deploy-orchestrator.sh`。
- #1016（承接 #1014）：補 qa-docs 操作契約、限制選項必須可由負責人完成、卡上限時提供配置更新→重新執行。
- 提示改由 stdin 傳入（`Argument list too long`）；session 改在行程啟動後才回報，續跑遇 session 不存在改開新對話。
- ToolTrust 只在被拒工具完全不在角色配置時作廢；提示依 runtime 列出已授予的固定操作。
- 自主化：節點上限改看相同輸入的連續重跑（`NodeInput` 指紋）、系統性 runtime 失敗自動重試 2 次、
  `autoApprovePlanWhenValid`、獨立驗證失敗自動交回實作（`CheckFailed`）、缺前置結果時自動退回重做。

### 2026-09-15 ～ 09-16
- Codex：自動交接計數改為獨立事件並惰性正規化舊 key；新增一次性「核准延長自動交接」。
- Claude Code：受阻發現連續性（`FlowContext` 淘汰最舊段落、`flow.blockedFindings`、`ToolTrust`），ADR 見
  `adr-blocked-finding-continuity.md`；白話化回報與停止選項按鈕（`HumanOptions`／`HumanGate`）；`human_decision` 回報契約；
  「核准恢復前置契約」；Slack 按鈕 action_id 重複被 invalid_blocks 拒收的修正；次數用盡時改為說明而非給無效按鈕。
  教訓：部署腳本不可把會合法變動的值（任務狀態、額度）當前置斷言；`service.sh` 會覆寫 log，ERROR 檢查要看整份。
- #1014 走到設計上的硬上限（transitions 40／24），成果留在 `feature/ai-team-task-1014`（fa96f9f，未推送），改以承接新任務繼續。

### 2026-09-13 ～ 09-14（Codex）
- 修正 `codex exec resume` 選項位置（resume 退回 `:read-only` 的根因）；角色回覆預設簡要。
- 新增「核准人工修復重驗」；規畫覆蓋閘門（acceptance_items／acceptance_ids／counterexample）。
- Codex Structured Outputs 不支援 `uniqueItems`：只在送出副本移除；pre-agent 契約錯誤還原次數。
- 受阻責任自動交接（ADR `adr-automatic-blocker-routing.md`）、QA 未提交測試由系統核對後交實作節點提交。
- 依使用者授權於 #1014 工作區修正 reviewer 指出的缺口；INSTALL 文件缺口修正（teams 打包、channel 必填、工具鏈 checksum）。

### 2026-09-11（Claude Code）
- 新增資料模型文件（9 張表、Task.State、四張 JSON 快照表逐欄語意）並修正 schema.sql 被切開的註解。
  記下三件只有翻程式才知道的事：`task.pm_session_id`／`pm_result` 為遺留欄位、`stage_attempt` 只寫不讀、
  `Checkpoint.upstream`／`qaHead` 在 legacy 與 v2 存的東西不同。

### 2026-09-10（Claude Code）
- 新增 opencode 為第三個 runtime（1.18.19 實測旗標；VM 沙箱端對端未驗證）。
- 額度訊息呈現與 Codex 無時區解禁時間的邊界排程（`QuotaMessage`、`farBeyondBackoff`）。
- 階段回報摘要化（`AI_TEAM_REPORT_DETAIL`、「檢視完整報告」）。
- #1014 誤按中止後由使用者本人以 SQL 還原；INSTALL 對齊 9/7–9/9 的變更。

### 2026-09-09（Codex、Claude Code）
- 跨專案執行契約（`project.operations`、`OperationPreflight`、`PlanApproval`、`TeamMigration`）。
- 測試計畫核准按鈕，之後改為核准即自動派工；一次性 `AI_TEAM_RESUME_APPROVED_TASK`。
- Codex 雙層沙箱 `.agents`／`.codex` 掛載點準備與無模型預檢；本機測試網路 `localTestNetwork` 與文件副本建置。
- Codex CLI `sandbox`／`exec` 參數不對稱修正；`RuntimeUsageError`（未呼叫模型不計次）。
- PM 工作區查核改用任務工作區；Slack 報告可讀性（`SlackReport`）。

### 2026-09-08（Codex）
- 額度自動續跑（`QuotaAutoResume`、`quota_retry`），修正 Claude session limit 漏辨識。
- QA 改用 Codex（gpt-5.6-sol、reasoningEffort=medium）；Slack 階段定期回報（`StageProgress`）。
- 依使用者要求清除 SMTP 重測任務 #1010、#1012（可復原備份保留）。
  教訓：部署時 JVM 會繼承 deployment.lock，啟動服務要加 `8>&-`。

### 2026-09-07（Claude Code）
- 專案首次納入 git 並推上 GitHub（private，初始 commit `a50642d`），推送前掃描憑證樣式。
- 建立跨工具交接檔 `.ai/context.md`、`.ai/progress.md`、`.ai/decisions.md`。
