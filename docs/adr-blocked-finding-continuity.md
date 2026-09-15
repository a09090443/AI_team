# ADR：受阻發現的傳遞連續性與被拒工具的可信度

日期：2026-09-15
狀態：已接受，尚未部署

## 背景

#1014 在 2026-09-15 12:57 再次停在 `AWAIT_HUMAN`。查核後確認不是崩潰、額度或部署問題：

1. 2026-09-14 15:43 review 節點回報 `REQUEST_CHANGES`，CONFIRMED HIGH：
   `MailServiceImpl.java:222` 以 `DeadlineSocketFactory` 覆蓋 SSLSocketFactory，
   預設 `overall-timeout=30000` 下正常寄送必走該路徑，隱式 TLS（465）退化為明文 TCP。
2. 該報告是受阻結果，`FlowEngine` 只在 `block == null` 時寫入 `flow.receipts`，因此沒有保存。
3. 受阻報告改 append 進 `flow.context`，但當時 `flow.context` 已達 20000 字元上限，
   而 `Diagnostics.clip` 是 `substring(0, max)` — 留頭截尾。`clip(已滿 + 新內容, 20000)`
   永遠等於原本的內容，**context 已永久凍結**，該報告整段被丟棄。
4. `lastBlockReason` 當時確實存過 review 的理由，但在下一個節點受阻時就被覆寫。
5. 今日 triage 因此只看到舊的 implementation npm 受阻與 verification 27/27 全 PASS，
   合理推論「查無可分派缺口」並 `route=human`。流程忠實執行了被截斷的輸入。

同一輪另有兩個放大問題：

- triage 使用的 coordinator 角色白名單是 `["Read","Grep","Glob"]`，完全沒有 Bash，
  但報告的證據段落寫了 `git log`／`git status`／`git rev-parse`／`git show --stat` 的輸出。
  系統只把工具遭拒記成一行稽核，沒有讓該報告失去可信度。
- triage 的 gate 是 `REPORT`，依設計不產生系統獨立驗證收據，提示中顯示為 `null`，
  被讀成「查無建置證據」，成為受阻理由的第二個支柱。

## 決策

1. **`flow.context` 改為滾動紀錄**（`FlowContext`）。單筆事件先截斷至 6000 字元，
   總量超過 20000 時以段落為單位淘汰最舊內容，並在開頭標示已淘汰。
   舊 context 沒有段落標記時退化為保留最新尾端。最新事件一定送得到下游。
2. **新增 `flow.blockedFindings`**，以來源節點為鍵保存未解除的受阻報告（因此筆數受配置節點數限制）。
   由 `ConfiguredExecutor` 無條件注入下游提示，不佔用 context 長度預算。
   只有該節點自己通過時才移除；需求變更不清除，採 fail closed。
3. **被拒工具與未受阻結論互斥**（`ToolTrust`）。本輪有工具呼叫遭拒、報告卻回報未受阻時判定受阻。
   這是機械檢查，不解讀報告文字：agent 會呼叫工具就表示它需要該工具，被拒後仍宣稱查核完成
   正是證據造假最容易發生的地方。已受阻的報告不受影響，合法引用被拒工具作為阻礙原因仍然可行。
   停止訊息會區分「角色完全沒有該工具」（配置與職責不符）與「有配置但呼叫方式不符白名單」。
4. **coordinator 補上唯讀 git 工具**（`git status`／`log`／`show`／`diff`／`rev-parse`），
   比照 inspector 的唯讀集合，讓 triage 的職責與能力一致。同時在 triage 提示明令
   證據只能寫本次實際執行成功的工具輸出。
5. **不產生收據的階段明講原因**，不顯示 `null`。

## 不做什麼

- 不放大自動交接額度，不改防循環上限，不新增任何繞過真人核准的路徑。
- 不自動判定受阻發現已解除；要解除必須有節點通過，或由後續報告說明原判定何處有誤。
- 不以文字比對猜測報告是否引用了被拒指令 —— 那會誤傷合法的受阻回報。

## 影響

- `flow.blockedFindings` 是新欄位，舊快照缺該欄位時為空 Map，不影響既有任務解析。
- 既有任務的 `flow.context` 若已飽和，新版第一次 append 就會淘汰最舊段落並開始正常運作，
  但**先前被丟棄的內容不會回來**；#1014 的 review 發現需靠 `blockedFindings` 之外的方式重新取得。
- 角色工具變更會改變配置版本；既有任務使用凍結快照，不會自動獲得新工具。
