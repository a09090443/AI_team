# 額度暫停與人工續跑

2026-09-05：新增 WAITING_QUOTA／RESUME_QUEUED 與 Slack「繼續任務」。

只有供應商的錯誤欄位明確出現 usage_limit_reached、insufficient_quota
或已辨識的額度耗盡文字，才判定為額度問題。一般 429、逾時、網路錯誤，
以及 agent／工具輸出中引用這些文字，都不據此認定額度耗盡。
重置時間不自行推算；原始錯誤有提供時直接呈現，否則未知。

每次執行前將角色、runtime、要求的模型、上游脈絡、QA 初始 commit 存入
task_checkpoint。Claude 預先產生 --session-id 並先存入資料庫；Codex 從
thread.started 取得 ID 後立即保存，無須等最終結果。模型原本為空字串時
仍代表 CLI 預設，不代表已取得實際模型名稱。

額度耗盡時停止下游、釋放執行槽、保留原工作區與未提交修改，並在原討論串
tag PM／提出者。續跑不呼叫 WorkspaceManager.provision、不清除現場；
已驗證的上游階段沿用原報告，從中斷階段接續。QA 仍使用第一次進入 QA 前
的 commit 作越權檢查基準，不能漏掉中斷前的修改。

「繼續任務」使用 SQLite 條件更新，只允許 WAITING_QUOTA 轉為 RESUME_QUEUED；
連按、重送或中止後的舊按鈕不重複派工。服務重啟後 WAITING_QUOTA 繼續保留，
尚未執行的 RESUME_QUEUED 退回 WAITING_QUOTA，等待人再按一次，不自行重試。

沒有 session ID 時會明確通知，改以保存的需求與上游脈絡啟動同一階段的新
session。CLI 明確回報已辨識的 session 不存在訊息時，通知後只嘗試一次新
session 接手；其他錯誤保持待查，不自動改 runtime、模型或權限。
續跑 prompt 要求先核實 git status／log／未提交變更，禁止先清除現場。

每次重試前，原 stdout／stderr 改名為 .previous-UUID 保留，不被覆蓋。
「重新開始」與續跑不同：重新開始先將舊工作區移到與 workspaces 同層的
workspace-archives，再建立乾淨 clone。封存失敗則停止，不刪掉舊內容。
封存不由工作區啟動清理掃描，需日後明確決定保留期限。

PR 仍須所有階段驗證通過與人工核准。這版沒有自動排程重試、額度購買、
reset 兌換或跨 runtime 接手，也沒有修復 #1010 的沙箱初始化問題。

驗證：SQLite 持久化／冪等 DDL／重複領取，假子行程的 session 早期捕捉，
額度正反例、續跑同一 QA 階段／原始基準／禁止重新 clone，以及遺失 session
接手時保留要求的 runtime／model。未刻意耗盡真實訂閱額度。
