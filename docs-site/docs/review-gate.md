# Code Review 發布阻擋（2026-09-05）

取代原先 Code Reviewer「不阻斷」策略。

任務 #1010 的 reviewer 回報 bwrap 建立 `.agents` 時遇到唯讀檔案系統，
未取得 commit、diff 或程式碼；結果為 COMMENT、空 findings、LOW confidence。
舊流程未檢查結果，且例外也會略過，因此錯誤進入 AWAIT_PR_APPROVAL。
使用者隨後中止 #1010，log 未記錄發布，工作區仍保留。

新報告必須提供 verification_status 與 blockers。只有 VERIFIED、
HIGH／MEDIUM confidence、APPROVE／COMMENT、空 blockers、非空 checked，
且 findings 沒有 HIGH 才可進入人工核准。
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
