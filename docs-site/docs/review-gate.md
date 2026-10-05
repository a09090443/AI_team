# Code Review 發布阻擋（2026-09-05）

取代原先 Code Reviewer「不阻斷」策略。

任務 #1010 的 reviewer 回報 bwrap 建立 `.agents` 時遇到唯讀檔案系統，
未取得 commit、diff 或程式碼；結果為 COMMENT、空 findings、LOW confidence。
舊流程未檢查結果，且例外也會略過，因此錯誤進入 AWAIT_PR_APPROVAL。
使用者隨後中止 #1010，log 未記錄發布，工作區仍保留。

新報告必須提供 verification_status 與 blockers。只有 VERIFIED、
HIGH／MEDIUM confidence、APPROVE／COMMENT、空 blockers、非空 checked，
且 findings 的等級只有 MEDIUM 或 LOW（HIGH 與任何其他寫法都擋下，見下方 2026-10-06 段落）才可進入人工核准。
缺失或舊格式報告需重新 review，不能視為通過。

無法核實、要求修正及 reviewer 執行失敗時，任務轉為 AWAIT_HUMAN，
保留工作區，記錄稽核並在原 Slack 討論串 tag PM 與需求提出者，
說明原因、核實阻礙與重新審查要求。PM mention 是通知，不自動啟動 PM。

人工核准及真正發布前都重讀資料庫並檢查狀態與報告，避免舊按鈕或
排隊期間中止後繼續發布。核准不能覆蓋核實失敗。

本修正未解除 Codex 內層沙箱的 `.agents` 初始化問題；環境仍需修復並
重新取得獨立 review 證據。不能以放行 PR 作為環境問題的替代處理。

## 審查收斂與建議（2026-09-26）

#1019 連續 7 次審查都退回，且每次提出前一輪沒提過的新問題，幾乎都是「測試驗得不夠細」，而 ReviewGate 把任何 blocker 都視為不可發布。
審查規則（`teams/prompts/review.md`）改為：
- blockers 只放必須改的：產品行為錯誤、需求／驗收條件未實作、安全或資料風險、某驗收條件完全沒有測試或證據。
- 測試強度不足列為 findings MEDIUM／LOW，不擋發布；不得要求核准計畫以外的額外組合。
- 第一次審查一次列完；再次審查只核對上輪 blockers 與本次變更造成的問題，其他新發現除非屬產品錯誤或安全，一律列為建議。

MEDIUM／LOW 建議會列在 PR 核准訊息與 PR 說明的「審查建議（未擋發布）」段落，由負責人決定是否另開任務。
ReviewGate 的硬性條件不變。規則是配置資產，只套用到新任務。

另：驗收（QA，Codex）補的測試若都在驗收節點允許寫入的路徑內且不碰保護路徑，由系統代為提交並回到驗收節點重驗，
不再交給實作節點、不消耗自動處理額度（程式變更，所有任務立即生效）。

## 審查等級限定三級（2026-10-06）

bestpartner #1022 的審查結論是 APPROVE、沒有 blockers，但 findings 有一項 `INFO`（「資料庫類測試沒有核實」的提醒）。
`ReviewGate` 只放行 MEDIUM／LOW，把 `INFO` 判為「Code Review 有 HIGH 或無法判定等級的問題」而擋下發布；
負責人兩次選「接受保留並發布」「授權發布」都繞不過（核准不能覆蓋這道閘門），分流沒有可重派的節點，只能一再停下。
原因是配置式審查 schema（`teams/schemas/review.json`）的 `severity` 只要求非空字串，提示詞也沒限定等級。

修正：schema 的 `severity` 改為 `enum` `HIGH`／`MEDIUM`／`LOW`（Structured Outputs 由 runtime 強制）；
`teams/prompts/review.md` 寫明只能用這三級，純提醒寫在 summary 或列 LOW。`ReviewGate` 本身不放寬。
這是配置資產：已在跑的任務沿用快照。提示／schema 資產變更**不能原地遷移**（`TeamMigration.preview`，見
[跨專案執行](cross-project-execution.md)），停下的任務不會出現「檢視配置更新」；要套用只能「改用新任務繼續」，或由負責人手動開 PR。
