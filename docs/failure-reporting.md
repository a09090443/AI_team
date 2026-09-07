# 全角色具體失敗回報契約

2026-09-05：適用 PM、Architecture、Backend、QA、Code Reviewer。
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
也不取代人工或獨立重現根因。未在本次修正中放寬沙箱權限。
