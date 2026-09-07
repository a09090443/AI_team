# QA 情境導向流程

流程：PM →（需要時 Architecture）→ QA 測試規劃 → Backend → QA 驗收 → Code Reviewer → 人工 PR 核准。
沿用 PM 的 QA 路由判斷；無 Backend 的分析／詢問任務不進實作流程。

QA 規劃在 Backend 與工作區建立之前，以需求和既有契約設計情境，不接收本輪 Backend 報告。
每個情境包含穩定 ID、需求來源、前置條件、資料、方法、步驟、預期結果與環境需求。
缺少必要需求決策時向 PM／提出者回報並停止。READY 只代表計畫就緒，不代表環境已驗證。

`qa_plan` 與 `qa` 各自持久化 stage_result、session、診斷 log 與費用。
兩階段共用 QA Slack 帳號與 `AI_TEAM_MODEL_QA`／`AI_TEAM_RUNTIME_QA`，不需新 token。
新增一次 QA 呼叫，耗時與費用須以實際任務量測。

Backend 取得計畫，交付實作、單元測試、啟動方式、環境與限制。
QA 依原計畫逐情境執行，回報實際結果、操作及證據。讀碼僅供定位，不能取代行為驗證。
QA 仍可新增測試，不可修改產品程式碼、pom 或既有測試斷言；原沙箱與 git 路徑稽核保留。
Code Reviewer 負責程式審查，並取得原測試計畫以查核驗收證據。

發布閘門拒絕缺少計畫、空情境、重複／未知／遺漏 ID、非 PASS、缺少證據、HIGH 問題、
未核實事項。人工核准及佇列發布前也重新核對，不能略過。
這是結構完整性檢查，不能單憑非空 log 字串證明 agent 沒有捏造證據；仍需獨立審查。

額度暫停沿用 WAITING_QUOTA 與人工繼續：QA 規劃可續原 session，尚未建立實作工作區；
Backend 或 QA 續跑沿用原計畫，QA 保留最初的 commit 稽核基準。
舊任務若沒有 qa_plan，不能事後補寫計畫冒充事前規劃或直接發布，須重新開始完整流程。
已中止的 #1010 不自動重啟。本次也未修復該任務的 Codex bwrap/.agents 問題。

## 驗證與部署（2026-09-05）

隔離建置 68 項測試通過，含規劃順序、阻擋、漏測、QA 規劃暫停續跑與既有 QA 稽核基準續跑。
已部署，啟動自檢通過，實際 QA 規劃設定為 sonnet／claude-code；JVM 有已建立的 443 連線。
備份：`/home/vboxuser/ai-team/qa-plan-backup.CN4WOx`（含原 jar、提示詞與 SQLite）。
尚未透過新的真實 Slack 任務驗證 agent 的情境計畫品質；自動化測試使用模擬 runtime，未消耗模型額度。
