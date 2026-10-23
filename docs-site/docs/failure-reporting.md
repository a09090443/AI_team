# 全角色具體失敗回報契約

適用所有角色（legacy 五角色與配置式流程的每個節點）。
StageRunner 在送出前統一擴充各角色 schema 並附加共用指示，包含 resume。
磁碟中的角色 schema 保持各角色職責；實際傳給 runtime 的 schema 另外包含：

- execution_status：COMPLETED 或 BLOCKED。
- failure_report：正常完成為 null；受阻則含八個非空欄位。

八個欄位是 operation、observed_error、evidence、impact、cause_status、
cause、next_action、owner。cause_status 為 CONFIRMED 或 UNKNOWN。
只知道「Read-only file system」不能宣稱已確認哪個設定導致錯誤。
agent 自陳與 runtime 實際事件分開標示，不強迫模型杜撰未知根因。

共用回報提示要求 summary 先以 2–4 句說明進度、具體原因、對使用者目標的影響與下一步處理者，
再提供路徑、需求編號、節點與證據等技術補充。明確區分檔案產出、測試通過與環境啟動，不能混稱完成。
待決事項以使用者可理解的結果與限制表達；細節見 [真人決定說明](plan-approval-buttons.md#由-agent-本人說明需要你決定的事)。
這是模型提示要求，不代表程式已能機械驗證回覆是否易懂。

共用驗證會攔下 BLOCKED、缺漏的失敗回報，以及宣稱 COMPLETED 卻仍有
FAILED/PARTIAL/FAIL/LOW、建置未過、越界或 reviewer 阻擋的矛盾結果。
先保留 stage_result，再停止下游、保留現場並向 PM／需求提出者回報。2026-10-20 來源新增的持久長作業例外：若有有效 pending journal，先由中央流程等待及對帳，再續同節點，不把 OPERATION_PENDING 的 BLOCKED 立即當成須人工處理；此版本已部署 bestpartner。
核准及真正發布前也會檢查各階段報告；舊報告缺欄位需重新核實。

執行例外以底層原因優先呈現並保留證據目錄、影響與處理者。
回合用盡只回報 subtype／回合數，不再猜測任務太大或宣稱未 commit。
stdout 即時落檔，逾時仍可追查部分輸出；stderr 分開保存。

Claude 工具拒絕與 Codex command_execution 失敗附操作、呼叫 ID、
原始錯誤摘要及 session/log 位置。runtime 未提供拒絕理由時明說未知，
runtime 本身不把一般指令非零結束碼當成權限拒絕。工具可透過替代操作恢復，
因此單一工具失敗先回報；角色無法完成時才須 BLOCKED。

**一般指令失敗分類（2026-10-10 修正，已部署 bestpartner）**：CodexRuntime 在共用 `permissionDenials` 中，
為 `command_execution` 失敗加上 `failure_kind=COMMAND_FAILURE`。ToolTrust 不拿這類事件名稱比對工具白名單；
FlowEngine 以 RUNTIME_CALL 稽核記錄，並把失敗證據帶入後續角色的 context，讓角色查核、修正與重驗。
真正的權限拒絕仍保留原判定；一般指令失敗也不代表測試通過，未解除的驗收阻礙仍須 BLOCKED。
歷史案例 #1033：`git diff --check` 因 SQL 檔尾多空白行 exit 2，舊版誤判成「完全沒有工具權限」而停止。
本修正不會改寫該任務既有報告或自動解除等待狀態。

共用提示要求角色先處理既定範圍、既有權限內的程式／設定／格式／測試問題，再重跑失敗檢查。
唯讀角色依配置回報受阻、由分流派工，不自行寫檔，也不請使用者代為轉述。
未知根因先查核或交合格角色定位，不能只因原因未知就要求真人；新權限、產品取捨與無可用路線仍需真人。
這是角色執行契約，不保證模型一定能修好；既有獨立驗證、交接預算與發布閘門不變。

對外診斷遮蔽已知環境憑證與常見 token／密碼格式；原始事件與報告留在
VM 供授權查核。格式檢查保證欄位存在，不保證 agent 自陳內容正確，
也不取代人工或獨立重現根因。

## 系統例外的白話說明

配置流程中任何例外停給人時（agent 執行失敗、CLI 用法錯誤、系統層重試用完、發布前證據或 Git 目標不符、角色對話失敗、
使用者操作時的系統錯誤），停止訊息不貼 `Diagnostics.describe` 的技術內容，而是：

1. 系統確定的事實在前（例如「次數已還原」「不需重新核准」）；
2. `ErrorExplainer` 請 agent（claude-code、預設 haiku、無任何工具）依錯誤紀錄寫「發生了什麼／為什麼／影響／你可以怎麼做」。
   原因無法從紀錄確認時標「尚未確認」；「你可以怎麼做」只能引用這次停止訊息實際提供的選項或「通知管理者」。
3. 完整技術內容一律寫進 audit（ERROR）。

說明失敗（逾時、額度、輸出不完整）時退回固定白話後備文字，不影響停止流程。
後備文字會在固定句子後附上「錯誤摘要」（例外訊息前 300 字，已遮蔽憑證），內容可能是英文原文、結束碼與 VM 檔案路徑。

### 帳號或登入被供應商拒絕（`ProviderAccessDenied`）

2026-10-14 bestpartner #1042 的分析步驟遇到 Claude Code 回報「組織停用訂閱存取」，說明 agent 也用同一帳號而同因失敗，
使用者只看到含英文原文、結束碼與路徑的後備文字。已修正（來源）：

- `ClaudeCodeRuntime`、`CodexRuntime` 在額度判定後以 `ProviderAccessDenied.check` 檢查**供應商錯誤欄位**；工具輸出、agent 訊息與成功結果不採計。
  - Claude Code（2.1.282 實查）：結果 JSON `is_error=true` 且 `api_error_code` 為 `oauth_not_allowed_for_organization`、`organization_disabled`、
    `organization_on_hold`、`claude_code_key_creator_not_member`、`ip_not_in_allowed_range`、`oauth_scope_insufficient` → 帳號／組織權限；
    `token_expired` 或 `api_error_status=401` → 登入；另比對 CLI 內建句（Not logged in、Please run /login、OAuth token revoked、Invalid API key 等）。
  - Codex（0.156.1 實查）：refresh token 失效與要求重新 `codex login` 的錯誤句 → 登入。泛用的 `401 Unauthorized` 與沒有已知代碼的 403 不判定，照一般失敗處理。
  - opencode 尚未接上（它連額度判定都還沒有）。
- 流程節點遇到時：還原本輪重跑次數、一次性受控恢復授權與自動處理額度，停在原節點等人；**不排自動續跑、不自動重試**（帳號何時恢復系統無從得知）。
- `ErrorExplainer` 看到這類錯誤時不呼叫說明 agent，直接回固定中文說明：「AI 服務帳號無法使用」、發生位置、原因（組織停用或登入失效）、
  需要系統管理者處理、處理完再用原本的選項繼續。技術原文仍寫進 audit。角色對話、開 PR 等其他走 `ErrorExplainer` 的停止訊息一併適用。
- 原本錯誤不是帳號問題、但說明 agent 自己的帳號被拒時，後備文字改說「產生說明用的 AI 服務帳號目前無法使用」，不再附英文錯誤摘要。

Linux 完整測試 636 項通過，2026-10-14 已部署 bestpartner 與 spring-starters（見[部署紀錄](/docs/deploy-2026-09-06-v2#2026-10-14-帳號或登入被拒的固定說明)）；尚未以真實停用帳號做端對端驗證。

說明 agent 沒有任何工具，只看得到錯誤紀錄本身；紀錄沒寫出根因時，它只能照實寫「目前無法確定原因」。
模型逾時由 typed timeout 與長作業對帳處理，不以「模型未啟動」退還計數；以下啟動／串流系統錯誤重試不能取代部署結果對帳。

**系統層失敗的自動重試**（`SystemFailure.transientFailure`）：錯誤訊息符合已知的暫時性特徵
（例如 `Failed to start transient service`、串流中斷），或是任何提到 `systemd-run` 的 IOException，就判定為系統沒跑起來，
還原本輪計數後自動重試，最多 `RETRY_LIMIT`（2）次，用完才停給人。
**參數過長（`Argument list too long`，E2BIG）不重試**，因為它每次必定重現。runtime、操作預檢與系統獨立驗證都以
`ArgumentListTooLong.start` 啟動子行程；遇到 E2BIG 時，錯誤紀錄會寫出參數個數與總大小、最大的參數是第幾個、多大、開頭內容，
以及沙箱唯讀釘選的數量，說明 agent 和管理者因此看得出是哪裡過長。
2026-10-08 前，只要是提到 `systemd-run` 的 IOException 就重試，錯誤紀錄也不寫診斷。bestpartner #1029 的 QA 因此重試兩次，
每次都先重跑約 25 分鐘的獨立驗證才再失敗，說明 agent 只能寫「無法確定原因」。根因是另外到 VM 上數檔案才查到的，見
[configurable-team](configurable-team.md) 的 LIMITED 段落。
額度停止不呼叫說明 agent（同帳號額度已用完）。使用者操作被拒（IllegalArgumentException）仍直接顯示本專案寫好的白話原因。

**推分支、開 PR 本身失敗**（`PullRequestPublisher.PublishException`：token 未設定、權限不足、網路、GitHub API 錯誤）
不呼叫說明 agent，也不停成 `AWAIT_HUMAN`（2026-10-04 起）：

- 任務留在 `AWAIT_PR_APPROVAL`，重新貼出錯誤原文（經 `Diagnostics.safe` 遮蔽）與核准按鈕，修好設定後直接再按核准，不必重跑。
- 原因：錯誤原文本身就是寫給管理者的操作說明（缺哪個變數、填在哪、需要什麼權限）。#1015 交給說明 agent 改寫後只剩
  「缺少認證憑證」，看不出是 GitHub token；轉成 `AWAIT_HUMAN` 後也沒有任何按鈕能回到核准 PR。
- 再按核准時仍重新檢查 commit、工作區、證據與 Git 目標；這些不符時才停給人（走上面的白話說明）。
- 已停在 approval 節點的 `AWAIT_HUMAN` 任務（例如證據不符，或本修正前就失敗的任務）會提供「重新進入核准 PR」，
  見 [operations 按鈕語意](/agent/operations#按鈕語意)。
舊版管線（`StageRunner`、remediation）目前未啟用，未接上此機制。
設定：`AI_TEAM_EXPLAINER_MODEL`（預設 haiku）、`AI_TEAM_EXPLAINER_ENABLED`（預設 true）。


## 服務建置的失敗

Gate.SERVICE 不能以設定檔產出宣稱完成；啟動或健康檢查失敗時由 service_triage 交回原角色修正並重驗。
缺少目標主機時在模型執行前明確說明，不把權限／環境缺口說成使用者未選擇驗收降級。
機械驗證的欄位與 agent 自陳證據的限制見 [服務建置](service-delivery.md)。


## 部署長作業逾時與對帳（2026-10-20 已部署 bestpartner）

#1048 的歷史舊版先因模型逾時轉 AWAIT_HUMAN，之後才有 start 成功收據；未接單 health 又被誤稱執行器離線。此時間線保留於[SSH 直連部署](direct-ssh-deployment.md)，不能從模型逾時推論部署失敗。

1. 工具等待預設 30 秒後回 OPERATION_PENDING，模型以 BLOCKED 交回。FlowEngine 以工作區外的可信 journal 判定是否等待，不只比對模型文字；保留中央執行槽，沒有模型長輪詢。
2. 真正模型逾時拋 RuntimeTimedOut extends AgentRuntime.RuntimeExecutionException，可由 RuntimeTimedOut.find(Throwable) 取得。只有 phase=MODEL 且 cleanup=CONFIRMED 才先對帳；PREFLIGHT、清理 FAILED／UNKNOWN 或沒有有效 journal 不走此自動恢復。
3. 對帳綁 task、node、HEAD 及已驗證結果；執行中查原 job，成功只補缺少步驟，失敗先診斷，UNCERTAIN 不改稱成功或另送 start。續同 activeNode 不新計次，不拿部分模型輸出當完成報告。
4. 結果指紋限制同一檢查點只恢復一次；timeout 恢復另受 SystemFailure.RETRY_LIMIT（2）限制，避免未知作業造成無限模型重開。

runtime 清理精確 sandbox unit 的 cgroup，stdout／stderr 及 session checkpoint 保留；獨立部署 executor／遠端 root job 不因模型 timeout 被殺。真人中斷產生精確 job 的取消要求；傳輸中斷或模型回合結束不等於取消，取消未確認則保留未知狀態。執行器獨立 heartbeat 區分任務未啟動與離線；連線使用 closing，避免舊版 SQLite FD 累積。

此恢復契約已部署 bestpartner。Linux cgroup與獨立SSH背景health已實測，真實模型逾時／重啟／取消的所有故障組合尚未全面演練；#1048須以正式流程最終狀態為準。

## 管理者授權的自主恢復（project.autonomy，2026-10-22，#1053）

`project.autonomy` 是選填的團隊配置；沒設定（null）時以下行為全部不啟用，照舊停下等人。各欄位可分別開關：bestpartner 全開，dynamic-api 與 spring-starters 自 2026-10-23 只開 `deferUnverifiable`（其餘為 0／false，行為與未設定相同），見[實例一覽](configurable-team.md#各專案實例的功能啟用狀態)。
起因是 #1053 一天內停了四次，每次都是系統自己能處理的事。實作在 `AutonomousRecovery`，次數記在 `systemRetries`，不新增 FlowRun 欄位。

| 欄位 | bestpartner | 行為 |
|---|---|---|
| `modelTimeoutRecoveries`（0～3） | 2 | 模型逾時（`RuntimeTimedOut`、MODEL、cleanup=CONFIRMED）且長作業對帳沒有可接續的作業時，自動沿用原 session 接著做，等同按「繼續執行」：activeNode 保留、不計節點次數。每任務最多這麼多次；同一節點在同一工作區狀態（HEAD＋未提交指紋）只接續一次，兩輪之間沒有任何變化就停給人。flow.context 會加上「上一輪跑了多久、目前工作區、不要重做已通過步驟」的說明，Slack 貼一則通知。 |
| `maxTimeoutSeconds`（0～3600） | 3600 | 自動接續過的節點之後改用這個時限（不會比節點原值短）。 |
| `verificationContinuations`（0～3） | 2 | 系統獨立驗證用完 `verification.timeoutSeconds` 時，在**同一個 clone** 從被中止的那條指令重跑，剩下的指令給新的總時限（`IndependentVerifier.verify` 內）。這一輪至少要通過一條，否則停給人；同一 commit 最多續這麼多輪。2026-10-22 第一版改成「開新 clone、重用已通過的指令」，#1054 因此 npm ci 被重用、新目錄沒有 node_modules，build 出現 `vue-tsc: not found` 被誤判為程式失敗交回實作；同日改為同一 clone 續跑。基準驗證同樣在同一次執行內續跑剩下的指令，結果的 summary 註記「自動續驗第 N 輪後通過」。 |
| `requirementChangeOptions` | true | human_decision 選項多一種 `change_requirements`：需求負責人按下即送「核准需求變更 <問題與選項>」，回到需求節點重訂基準（見[計畫核准按鈕](plan-approval-buttons.md)）。提示同時要求範圍變更一律用它，不可用 decide，也不可自己把未解問題改成 EXCLUDED。 |
| `channelCommandRouting` | true | 頻道頂層訊息「#編號 已知指令」或「補充 #編號 …」轉給該任務處理（回覆貼在原討論串），不另開新任務。已知指令：檢視配置更新、核准配置更新、狀態、檢視完整報告、檢視測試計畫、核准需求變更、核准測試計畫。只限同頻道、白名單使用者；別的任務討論串裡的指令照舊拒絕。 |
| `deferUnverifiable`（2026-10-23，缺欄位為 false） | true | 測試計畫可把「需要驗收節點操作契約沒有的能力才能驗」的驗收項目（實際部署探測、外部系統、真實資料庫、專案外檔案）列入 `deferred_acceptance`，算作已涵蓋、不停給真人；驗收提示說明它們不屬本次驗收，核准 PR 訊息與 PR 說明逐項列出「延後驗收」。見[工程流程](engineering-workflow.md#延後驗收與沿用前次結果2026-10-23)。 |
| `pullRequestSyncRounds`（2026-10-23，0～3，缺欄位為 0） | 2 | 系統開出的 PR 與基準衝突時，回原任務解衝突、重驗並推回同一個 PR，每個 PR 最多這麼多輪。讀目前配置，不需配置遷移。見[已開 PR 的衝突同步](engineering-workflow.md#已開-pr-的衝突同步2026-10-23)。 |
| `pullRequestSyncAutoPush`（2026-10-23，缺欄位為 false） | true | 同步輪的證據全部通過時直接推回原 PR，不停在核准 PR；首次開 PR 仍要真人核准。 |

另外 `modelTimeoutRecoveries` 大於 0 時，每個節點的脈絡會加上「本階段時限 N 分鐘，與本次變更無關的長檢查不要執行」，讓 agent 自己安排優先順序（2026-10-23 起只隨逾時接續啟用；只開延後驗收的團隊不加）。

不變的邊界：不擴權、不放寬受保護路徑；需求變更與首次開 PR 仍要真人核准（change_requirements 只是讓負責人按一次就送出核准；
`pullRequestSyncAutoPush` 只適用於已核准開出的 PR 的衝突同步輪）；
所有自動動作都有上限並寫稽核紀錄（RECOVERY）。舊任務需經「檢視配置更新／核准配置更新」取得 autonomy（已列為可遷移欄位）。
驗證：程式測試（AutonomousRecoveryTest、FlowEngineTest、BaselineVerifierTest、HumanOptionsTest、TriageRoutingTest 等）通過；真實任務端對端尚未實測。
