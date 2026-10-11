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
先保留 stage_result，再停止下游、保留現場並向 PM／需求提出者回報。
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

說明 agent 沒有任何工具，只看得到錯誤紀錄本身；紀錄沒寫出根因時，它只能照實寫「目前無法確定原因」。
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
