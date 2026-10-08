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

共用驗證會攔下 BLOCKED、缺漏的失敗回報，以及宣稱 COMPLETED 卻仍有
FAILED/PARTIAL/FAIL/LOW、建置未過、越界或 reviewer 阻擋的矛盾結果。
先保留 stage_result，再停止下游、保留現場並向 PM／需求提出者回報。
核准及真正發布前也會檢查各階段報告；舊報告缺欄位需重新核實。

執行例外以底層原因優先呈現並保留證據目錄、影響與處理者。
回合用盡只回報 subtype／回合數，不再猜測任務太大或宣稱未 commit。
stdout 即時落檔，逾時仍可追查部分輸出；stderr 分開保存。

Claude 工具拒絕與 Codex command_execution 失敗附操作、呼叫 ID、
原始錯誤摘要及 session/log 位置。runtime 未提供拒絕理由時明說未知，
不把一般指令非零結束碼當成權限拒絕。工具可透過替代操作恢復，
因此單一工具失敗先回報；角色無法完成時才須 BLOCKED。

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
**已知問題（2026-10-08 查證，尚未修正）**：「提到 `systemd-run` 的 IOException」連必然重現的錯誤也算進去。
bestpartner #1029 的 QA 以 `Argument list too long`（參數超過 Linux 上限）啟動失敗，被重試兩次，每次都先重跑約 25 分鐘的獨立驗證才再失敗。
錯誤紀錄也沒有寫出是哪個參數過長，說明 agent 因此只能寫「無法確定原因」；根因是另外到 VM 上數檔案才查到的，見
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
