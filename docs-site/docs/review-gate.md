# Code Review 發布阻擋（2026-09-05）

取代原先 Code Reviewer「不阻斷」策略。

起因：reviewer 回報 bwrap 建立 `.agents` 時遇到唯讀檔案系統，
未取得 commit、diff 或程式碼；結果為 COMMENT、空 findings、LOW confidence。
舊流程未檢查結果，且例外也會略過，因此錯誤進入 AWAIT_PR_APPROVAL。
該任務後來由使用者中止，未發布。

新報告必須提供 verification_status 與 blockers。只有 VERIFIED、
HIGH／MEDIUM confidence、APPROVE／COMMENT、空 blockers、非空 checked，
且 findings 的等級只有 MEDIUM 或 LOW（HIGH 與任何其他寫法都擋下，見下方 2026-10-06 段落）才可進入人工核准。
缺失或舊格式報告需重新 review，不能視為通過。

無法核實、要求修正及 reviewer 執行失敗時，任務轉為 AWAIT_HUMAN，
保留工作區，記錄稽核並在原 Slack 討論串 tag PM 與需求提出者，
說明原因、核實阻礙與重新審查要求。PM mention 是通知，不自動啟動 PM。

人工核准及真正發布前都重讀資料庫並檢查狀態與報告，避免舊按鈕或
排隊期間中止後繼續發布。核准不能覆蓋核實失敗；例外只有驗收負責人以「開 PR」放行的兩種：「等級寫成提醒」的審查項目（2026-10-06 起），
以及驗收其他條件全過、只剩的未核實事項（2026-10-08 起）。條件見文末「負責人命令開 PR 與放行提醒等級」。

已開 PR 衝突同步輪（2026-10-23）的審查照舊執行，要求不變；審查沒有 APPROVE 就不會推回 PR。
配置開啟 `pullRequestSyncAutoPush` 時，證據全部通過才直接推回原 PR，不停在核准 PR；這不是放行，任何審查阻擋都照常停下。
見[已開 PR 的衝突同步](engineering-workflow.md#已開-pr-的衝突同步2026-10-23)。

本修正未解除 Codex 內層沙箱的 `.agents` 初始化問題；環境仍需修復並
重新取得獨立 review 證據。不能以放行 PR 作為環境問題的替代處理。

## 審查收斂與建議（2026-09-26）

起因：一個任務連續 7 次審查都退回，且每次提出前一輪沒提過的新問題，幾乎都是「測試驗得不夠細」，而 ReviewGate 把任何 blocker 都視為不可發布。
審查規則（`teams/prompts/review.md`）改為：
- blockers 只放必須改的：產品行為錯誤、需求／驗收條件未實作、安全或資料風險、某驗收條件完全沒有測試或證據。
- 測試強度不足列為 findings MEDIUM／LOW，不擋發布；不得要求核准計畫以外的額外組合。
- 第一次審查一次列完；再次審查只核對上輪 blockers 與本次變更造成的問題，其他新發現除非屬產品錯誤或安全，一律列為建議。

MEDIUM／LOW 建議會列在 PR 核准訊息與 PR 說明的「審查建議（未擋發布）」段落，由負責人決定是否另開任務。
ReviewGate 的硬性條件不變。規則是配置資產，只套用到新任務。

另：驗收（QA，Codex）補的測試若都在驗收節點允許寫入的路徑內且不碰保護路徑，由系統代為提交並回到驗收節點重驗，
不再交給實作節點、不消耗自動處理額度（程式變更，所有任務立即生效）。

## 審查等級限定三級（2026-10-06）

起因：審查結論是 APPROVE、沒有 blockers，但 findings 有一項 `INFO`（「資料庫類測試沒有核實」的提醒）。
`ReviewGate` 只放行 MEDIUM／LOW，把 `INFO` 判為「Code Review 有 HIGH 或無法判定等級的問題」而擋下發布；
負責人兩次選「接受保留並發布」「授權發布」都繞不過（核准不能覆蓋這道閘門），分流沒有可重派的節點，只能一再停下。
原因是配置式審查 schema（`teams/schemas/review.json`）的 `severity` 只要求非空字串，提示詞也沒限定等級。

修正：schema 的 `severity` 改為 `enum` `HIGH`／`MEDIUM`／`LOW`（Structured Outputs 由 runtime 強制）；
`teams/prompts/review.md` 寫明只能用這三級，純提醒寫在 summary 或列 LOW。`ReviewGate` 本身不放寬。
這是配置資產：已在跑的任務沿用快照。提示／schema 資產變更**不能原地遷移**（`TeamMigration.preview`，見
[跨專案執行](cross-project-execution.md)），停下的任務不會出現「檢視配置更新」；要套用只能「改用新任務繼續」，或由負責人手動開 PR
（2026-10-06 起可改用下節的 `開 PR #<id>` 指令放行）。

## 負責人命令開 PR 與放行提醒等級（2026-10-06）

舊快照的任務套不到上面的新規則，流程內沒有路徑能開 PR。新增驗收負責人在 Slack 命令開 PR：
`開 PR #<id>`（或 `補充 #<id> 開 PR`）、停止訊息的「開 PR」按鈕、agent 選項 `action=publish` 都送同一串指令 `開 PR`，
由 `FlowEngine.ownerPublish` 處理，判斷集中在 `OwnerPublish.evaluate`。

- 只限驗收負責人；任務是配置式流程，狀態 `AWAIT_PR_APPROVAL`（等同按核准）、`AWAIT_HUMAN`，或負責人結案的 `ABORTED`
  （audit 有 `OWNER_CLOSE`、工作區有保留）。結案任務檢查沒過時維持結案，不會變成等人的任務；一般「中止任務」的 `ABORTED` 不接受。
- 其他證據照舊：用發布核准節點同一套 `FlowGates.publicationReason` 試算，驗收、需求基準、commit 一致性都必須有效；工作區要乾淨。
- **唯一可放行的閘門失敗**（`ReviewGate.waivable`）：審查 `VERIFIED`、把握度 HIGH／MEDIUM、APPROVE／COMMENT、沒有 blockers、
  有 checked，沒有 HIGH，而且不標準的等級只屬於提醒類（`INFO`、`INFORMATIONAL`、`NOTE`、`NIT`、`NONE`，不分大小寫）。
  `CRITICAL`、`BLOCKER` 或看不懂的寫法一律不能放行。目前 commit 必須等於審查當時看到的 commit（`attemptHeads`）。
- 放行做法：把受阻的審查報告複製成 receipt，提醒類 finding 改列 `LOW` 並記 `waived_from` 原等級，報告加 `owner_waiver`
  （放行者、時間、原因）；原始報告仍在 `stage_result`／`stage_attempt`。audit 記 `RECOVERY`。
- 系統獨立驗證收據缺少或失效時（分流節點啟動會清掉收據），先在目前 commit 的全新 clone 重新執行；不通過就不開 PR。
- 全部通過才把流程指到發布核准節點並直接推分支開 PR——指令本身就是負責人的發布核准。任何一步沒過：放行、收據與節點變更
  都不保存，任務回到原本停下的位置並說明原因。
- PR 說明新增「負責人放行的審查項目」段落；這些項目不再混進「審查建議」。
- 停止訊息的「開 PR」按鈕只在 `OwnerPublish.evaluate` 通過時出現（收據會在執行時補跑，不列入判斷）。

程式測試通過（ReviewGateTest、FlowEngineTest、HumanOptionsTest、TriageRoutingTest）；真實 Slack 端對端尚未實測。

### 放行未核實事項、補跑審查（2026-10-08）

專案的測試規範可能要求需要外部 API 金鑰、資料庫的情境一律標「無法核實」，`QaGate` 卻把任何 `uncertainties` 當成禁止發布，
連負責人的 `開 PR` 也放行不了：驗收情境全數 PASS 仍開不了 PR，審查也因此從未執行。

- **可放行的驗收失敗**（`OwnerPublish.waivableUncertainties`）：把 `uncertainties` 清空後，驗收報告能通過 `FlowGates.blockingReason`
  （判定 PASS、`verify_passed`、逐情境 PASS 且有證據、沒有 HIGH、引用目前的需求基準與測試計畫）。任何情境 FAIL 或其他缺漏都不能放行。
  目前 commit 必須等於驗收當時看到的 commit（`attemptHeads`）。
- 放行做法：受阻的驗收報告複製成 receipt，`uncertainties` 移到 `waived_uncertainties`，加 `owner_waiver`；audit 記 `RECOVERY`。
  Slack 確認訊息逐項列出放行的事項；PR 說明新增「負責人放行的未核實事項」段落，註明合併前要在具備對應環境時實測。
- **審查不能跳過**：審查節點沒有 receipt 也沒有被擋的報告（從未執行）時，`OwnerPublish` 回傳 `pendingReview`，試算時略過該節點
  （`FlowGates.publicationReason(flow, head, pending)`），其餘證據照常必須有效。系統保存驗收放行、把流程指到審查節點、
  在 `FlowRun.ownerPublishBy` 記下指令者，接著執行審查（缺系統獨立驗證收據時審查前先重跑）。
- 流程走到發布核准節點且檢查全過時，`ownerPublishBy` 有值就直接推分支開 PR，不停在 `AWAIT_PR_APPROVAL`；
  審查只剩可放行的提醒等級時也由同一個指令放行。審查有實質問題或任何原因停下等人時（`waitHuman`）清掉 `ownerPublishBy`，
  要再發布必須重新命令；這時驗收的放行已保存，不必重新放行。
- 與上一節不同：需要補跑審查時，驗收放行在審查前就保存，不是「全部通過才保存」。負責人結案（`ABORTED`）的任務在補跑審查後停下時，
  會變成 `AWAIT_HUMAN`，不回到結案。

程式測試通過（FlowEngineTest +4：放行並補跑審查後發布、審查只有 INFO 一併放行、審查 HIGH 停下並清除指令、情境 FAIL 不能放行）；
真實 Slack 端對端尚未實測。
