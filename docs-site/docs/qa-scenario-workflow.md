# QA 情境導向流程

流程：PM →（需要時 Architecture）→ QA 測試規劃 → Backend → QA 驗收 → Code Reviewer → 人工 PR 核准。
沿用 PM 的 QA 路由判斷；無 Backend 的分析／詢問任務不進實作流程。

QA 規劃在 Backend 與工作區建立之前，以需求和既有契約設計情境，不接收本輪 Backend 報告。
新任務先把每項需求拆為具 ID 與維度的原子驗收項目，再建立需求 × 操作／角色／輸入 ×
成功／失敗／邊界 × 執行機制的覆蓋矩陣。每個情境包含穩定 ID、需求與原子驗收 ID、
前置條件、資料、方法、步驟、預期結果、環境需求、`operation_ids` 及 `counterexample`。
`counterexample` 必須描述一個會通過弱測試但違反需求的實作，並說明本情境如何攔下它。
每個 `operation_id` 必須是管理者配置給對應 verification 節點的契約；
測試規畫不得只在文字中自行發明命令。契約不足時要明確受阻，不把權限缺口留到 QA 才發現。

**系統內建的 `inspect-content`**（2026-10-03 起，所有 v2 配置自動可用，不需配置）：以 QA 自己的 Read／Grep
讀工作區中**已提交**的檔案內容判定。它不是指令，不經派工前檢查、不寫檔。只適用於「從檔案內容就能判斷」的驗收項目
（文件文字、設定值、檔案是否存在、表格結構）；程式行為仍須引用真正的操作，缺操作仍要受阻。驗收時 `command` 寫
`inspect-content：<檔案>:<行或範圍>`、`evidence` 引用讀到的內容；系統同時在驗收上下文附上「本次已提交的變更檔案」
（`WorkspaceManager.changedPathsSinceBase`，系統查得）。配置不能自行宣告同名操作（`OperationContract.INSPECT_CONTENT`）。
起因：bestpartner #1010／#1011 只改一行文件，任何配置都寫不出「讀某檔某行」的固定指令，任務永遠卡在測試計畫。
intake 也規定：未配置成操作的專案腳本（例如 `bash scripts/*.sh`）不得寫成驗收項目，因為系統不允許以 shell 執行。
缺少必要需求決策時向 PM／提出者回報並停止。READY 只代表計畫就緒，不代表環境已驗證。

`qa_plan` 與 `qa` 各自持久化 stage_result、session、診斷 log 與費用。
兩階段共用 QA Slack 帳號，不需新 token；runtime 與模型在配置式模式由團隊 JSON 的測試角色決定，
legacy 模式用 `AI_TEAM_MODEL_QA`／`AI_TEAM_RUNTIME_QA`。

Backend 取得計畫，交付實作、單元測試、啟動方式、環境與限制。
QA 依原計畫逐情境執行，回報實際結果、操作及證據。讀碼僅供定位，不能取代行為驗證。
QA 仍可新增測試，不可修改產品程式碼、pom 或既有測試斷言；原沙箱與 git 路徑稽核保留。
Code Reviewer 負責程式審查，並取得原測試計畫以查核驗收證據。
對 v2 任務，系統會在 QA 前先用全新 clone 執行固定的獨立驗證；QA 與 Reviewer
只能核對綁定目前 HEAD、需求基準與配置版本的收據。後續修改產品或重做驗收會使收據失效並重跑。

發布閘門拒絕缺少計畫、空情境、重複／未知／遺漏需求或原子驗收 ID、缺少反例、
非 PASS、缺少證據、HIGH 問題、
未核實事項。人工核准及佇列發布前也重新核對，不能略過。
判斷在 `QaGate`：驗收報告的 `uncertainties` 非空就擋，不區分是不是需求基準早已約定「本環境無法核實」的項目，
負責人的 `開 PR` 也放行不了。
**已知問題（2026-10-08 查證，尚未修正）**：bestpartner 的 `testInstructions` 要求「需要 PostgreSQL、外部 API 金鑰的情境一律標為無法核實」，
QA 照做就一定會寫進 `uncertainties`，所以碰到這類功能的任務在這個環境永遠發布不了。
#1029（langchain4j＋Quarkus 升級）驗收 7 個情境全數 PASS，仍以「QA 仍有未核實事項，禁止發布」停下，最後由負責人結案。
這是結構完整性檢查，不能單憑非空 log 字串證明 agent 沒有捏造證據；仍需獨立審查。

額度暫停沿用 WAITING_QUOTA 與人工繼續：QA 規劃可續原 session，尚未建立實作工作區；
Backend 或 QA 續跑沿用原計畫，QA 保留最初的 commit 稽核基準。
舊任務若沒有 qa_plan，不能事後補寫計畫冒充事前規劃或直接發布，須重新開始完整流程。
既有 v2 任務保留建立時的 schema 與 prompt，可沿用舊欄位續跑；新規則不會竄改歷史計畫。

自動化測試使用模擬 runtime，不代表 agent 在真實任務中產出的情境計畫品質已被驗證。
