# 工作進度（Codex 與 Claude Code 共用）

> 由新到舊追加。每則一行到數行：日期、由誰（Codex／Claude Code）、做了什麼、驗證方式。

## 2026-09-11 — Claude Code（資料模型文件與 schema 註解）

使用者指出 DDL 只有零星註解、四張 JSON 快照表（task_checkpoint／direct_request／
flow_run／task_remediation）完全沒說明，`data` 裡是什麼只能翻 Java。新增
`docs/data-model.md`：9 張表逐欄說明、`Task.State` 22 個狀態與 `audit_log.action`
8 種取值列表、四個 JSON 欄位對應的 record 與逐欄語意（含 FlowRun 的 30 餘個欄位）、
關聯圖與唯讀查詢範例。同時修掉 schema.sql 裡被後來插入的 table 切開的兩段註解
（「各階段的產出。用獨立資料表而非在 task 加欄位，」原本接的是 stage_result，
中間卡了五張表），並補上 task／audit_log／stage_* 的欄位註解。

文件中記下三件翻程式碼才知道的事：`task.pm_session_id`／`pm_result` 是遺留欄位已無人讀寫；
`stage_attempt` 只寫不讀（Java 無查詢路徑）；`Checkpoint.upstream`／`qaHead` 在 legacy
與 v2 兩條路徑存的是不同東西（v2 分別放 flow.context 與 flow.beforeHead），看欄位名會猜錯。

只動註解與文件，無程式碼與 DDL 結構變更。驗證：check-source.sh 通過（2 項 PASS）。
**Maven 測試未跑** —— 本機 Windows 未安裝 mvn，只有 JDK 25；要跑得到 VM 上。未 push。

## 2026-09-10 — Claude Code（階段回報摘要化）

使用者要求 agent 回報簡短、需要細節時再問。新增 `SlackReport.brief()`：只留判定／摘要／
阻礙／建議後續，其餘欄位以「另有 N 個欄位未顯示」帶過，不做二次摘要（summary 由 agent
自己給，再壓一次只會多一層失真）。受阻原因不縮 —— 那是唯一會要求人立刻做事的內容。
新增 `AI_TEAM_REPORT_DETAIL=brief|full`（預設 brief）與「補充 #<ID> 檢視完整報告 [節點]」
指令；後者只讀 stage_result，不呼叫模型、不改狀態、不推進流程。完整報告未刪除。
驗證：VM 建置 261 項 0 失敗、10 項 opt-in 略過（SlackReportTest 3→7）；
本機另以真實報告資料實跑 brief() 12 項檢查全過；check-source 通過。
15:0x 部署 PID 81388，jar SHA256
a4257e9b0462ea444dd01d9c90ba9515631e54bfa8eb9ff51c7f0f21ade14c63（前一版 3fb67dbc…），
build=brief-build.RN3Z3S、backup=brief-backup.3LAMuX。部署前後任務雜湊一致、0 ERROR。
部署腳本加了一道前置檢查：#1014 必須仍是 WAITING_QUOTA —— 重啟會跑 purgeWorkspaces()，
它若回到 ABORTED，工作區與三個未推送 commit 會在重啟中被刪。
**真實 Slack 畫面與「檢視完整報告」指令尚未實測**（目前無執行中任務）。未 push。

## 2026-09-10 — Claude Code（#1014 誤中止還原與 #1015 移除）

使用者點中止時誤按 #1014（兩張卡同串），14:41 WAITING_QUOTA→ABORTED。
先備份工作區（task-1014-rescue.20260910144216，bundle 已驗證 complete history）與 DB，
再由使用者本人執行 SQL 還原：#1014 回 WAITING_QUOTA 且 updated_at 一併回復為
13:02:30.249984021Z —— 若只改 state，quota_retry.observed 不吻合會讓排程重算並自動重發
Slack 通知。#1015（舊版反引號解析錯誤誤建、無工作區）改為 ABORTED。
兩筆手動變更都寫入 audit_log，誤按紀錄（id=186）保留未抹除。
驗證：integrity_check=ok、quota_retry 維持 next_at 2026-09-16T01:11Z／attempts=5、
過一個 poll 週期後排程器安靜接手、未重發任何通知。
註：線上 DB 寫入被 auto mode 分類器擋下兩次，最終由使用者本人執行。

## 2026-09-10 — Claude Code（新增 opencode 為第三個 runtime）

新增 `OpenCodeRuntime`（id=opencode），旗標與事件流以本機 opencode 1.18.19 實測後才寫：
`run --format json --dir --session -m provider/model --auto --`，prompt 一律放在 `--` 之後。
能力宣告 SESSION_RESUME／MODEL_SELECTION／COST_REPORTING／NATIVE_AGENTS_MAP（四項皆實測），
缺 APPEND_SYSTEM_PROMPT／STRUCTURED_OUTPUT／MAX_TURNS／TOOL_PERMISSIONS，前兩項在 runtime 內降級。
`SessionCapture` 改由 runtime 提供 session id 取法（codex 行為不變）；
`RuntimeUsageError` 增 `looksLikeYargsUsageError()` —— opencode 的用法錯誤是結束碼 1 加印出說明，
不是 clap 的 2。設定新增 `OPENCODE_BIN`，只有真的有角色指定 opencode 才要求，既有部署不受影響。
驗證：`orchestrator/src/main` 全量 javac 編譯通過（依賴取自既有 fat jar）；
以實錄 JSONL 跑 `parse()` 的 15 項本機檢查全通過；`check-source.sh` 通過。
**`OpenCodeRuntimeTest` 尚未執行**（本機無 Maven 與 ~/.m2），
**VM 沙箱下的端對端執行也尚未進行** —— 不可視為 opencode runtime 已可用。
安裝與設定已補進 docs/INSTALL.md：新增 §3.1（opencode 安裝／認證／三個與其他兩家不同之處，
沒有角色用它就整段跳過）、§6 的 `OPENCODE_BIN` 說明、§8 驗收表 Runtime 列加註尚未實測的項目。
**13:34 已部署**（使用者授權）。隔離 build=`opencode-build.TLQba7`：
`mvn clean package` 257 項 0 失敗、10 項 opt-in 略過（含 `OpenCodeRuntimeTest` 20 項全過，
此前從未執行過）。部署前 VM 線上原始碼與本機工作樹逐檔 sha256 比對，
差異只有本次的 2 個新檔與 4 個修改檔，無其他分歧。
jar SHA256 `3fb67dbc8b4f53efbd024fc544cdc95e2f300f5f425c7370a4540c42cbbbca64`（前一版 `5d23cf8c…`），
PID 75366、backup=`opencode-backup.WhXo1u`、腳本 `deploy/deploy-opencode-20260910.sh`。
部署前後 task／flow_run／checkpoint／stage_result 雜湊一致；本次啟動 log 0 ERROR，
沙箱自檢與 INSPECTOR Socket Mode 各 1 次。1014 仍 WAITING_QUOTA（重試 2026-09-16T01:11Z）、
1015 仍 AWAIT_HUMAN；未動配置、未設 OPENCODE_BIN、未代核准或續跑。
沒有任何角色設為 opencode，因此線上行為不變。
13:43 續：VM 安裝 opencode 1.18.19（npm，33 秒／3 套件），Linux 上重驗旗標契約與缺訊息行為
與 Windows 一致，狀態目錄與 `statePaths()` 宣告相符；`OPENCODE_BIN` 寫入 env.sh（600，已備份），
`preflight.sh` 同步含 opencode 檢查並通過；服務未重啟、任務狀態未動。
**認證與沙箱端對端執行仍未做** —— 前者須本人操作，後者是提權釘選有效性的唯一證據。
未 push。詳 docs/opencode-runtime-report.md 第七節。

## 2026-09-09 — Codex（本機測試與文件副本能力）

22:06 部署 localTestNetwork 與明確 QA target 產物根、能力遷移預覽及一次性真人恢復授權。
内層 Proxy 空 allowlist、localhost 實测通過、外連拒絕；外層仍維持 LIMITED 寫入。
224 項中 221 通過、3 略過；獨立 clone 的 9 項 SMTP 測試、離線文件建置與 llms 比對通過。
PID 47684、build local-test-build.ym6i6i、backup local-test-backup.roptPp。
全部既有 task/flow/checkpoint/stage 保持不變，#1014 AWAIT_HUMAN，待新配置與計畫核准。
安全審查未允許代發 Slack 配置訊息，未發送／未繞過；使用者可自行在原串檢視配置更新。
未 push、非正式 QA 完成，詳 docs/local-test-capabilities.md。

## 2026-09-09 — Codex（重試上限訊息診斷）

21:16 唯讀核對：#1014 node=triage／AWAIT_HUMAN，triage 進入計數 5、上限 3，
verification 2/3、整體 transitions 12/24；程式先累加再檢查，拒絕進入也會增加次數。
本輪 QA 承認既有核准有效，但 bwrap 建立工作區 .agents 遇唯讀檔案系統，無法執行 shell。
QA 受阻退 PM 才碰次數上限，非 token 或人工核准問題。未修改或重跑。

## 2026-09-09 — Codex（核准自動派工與 #1014 實際續跑）

使用者明確授權變更為核准後自動 QA；按鈕與文字核准共用 CAS 排隊，避免重複點擊派工，
限定對應 VERIFICATION 等待節點、驗證原負責人／版本，PR 仍另核准。
新增單一指定任務的管理者恢復入口，僅沿用有效既有收據、不代核准或批次恢復。
212 項中 209 通過、3 略過，含沙箱預檢；21:10 部署 PID 27426，備份 approval-auto-backup.vxJX5L。
#1014 21:10:50 STAGE_RUNNING、21:10:53 Codex verification 真實啟動，PID 27616。
原計畫／核准／快照、其他 task/flow 雜湊不變；未宣稱驗收完成，未 push。


## 2026-09-09 — Codex（21:03 任務狀態查詢）

唯讀確認服务 PID 23108 正常；#1014 仍 AWAIT_HUMAN／verification、activeNode=null，
19:55 真人計畫核准保留，沒有部署後的新續跑稽核。#1015 亦 AWAIT_HUMAN。
未重送、修改、核准或啟動任何任務。

## 2026-09-09 — Codex（計畫按鈕與續跑指令解析）

最終已部署（20:08，PID 23108）：211 項中 208 通過、3 略過，沙箱與五角色連線通過。
#1015 自行停止後安全部署，備份 plan-buttons-backup.D0XSGJ；所有任務相關資料雜湊不變。
系統 App 已補發核准／要求修改卡片，API ok 回傳兩個 action 與文字；未代點按鈕或續跑。
真人點擊驗證待使用者操作，#1014 既有核准有效、不需重核准。

新增核准／要求修改 actions、系統與角色 handler、原負責人／討論串／版本檢查，
要求修改撤核准並等待具體意見，再由原計畫節點續跑；沒有自動核准 PR 或派工。
修正首尾反引號包住管理指令時被誤當提問；一般問題與程式範例維持原文。
隔離 plan-buttons-build.2TyoAq package 成功，payload 使用產品 Java 類實際產生，兩 actions 正確。
部署被 #1015 dialogue 執行中保護拒絕，未變更線上服務或 DB；真人點擊驗證待部署後執行。
#1014 核准已於 19:55 收到；帶反引號的續跑指令另建 #1015，#1014 尚未續跑。

## 2026-09-09 — Codex（核准閘門部署與受控恢復）

使用者授權後新增 PlanReview 完整情境分頁及檢視指令、QA 系統核准收據上下文，
驗證未核准不呼叫 QA、核准不排隊、triage 用滿仍可從受控 verification 節點正常續跑。
208 測試中 205 通過、3 略過，含 3 真實沙箱預檢；部署 PID 16368、五角色 Socket 啟動。
備份 approval-backup.gfAnl9；#1014 原 commit 與需求／計畫保留，QA 改 Codex Sol medium，
啟用前置核准、定位 verification 但仍等待人工，沒有代為核准或執行驗收。
原 task／其他 flow／checkpoint／stage_result SQL 雜湊保持一致，舊快照另保留歷史。
詳 docs/deploy-2026-09-09-approval.md；未 push、未宣稱真人核准與 QA 端對端通過。
完整計畫已發至原 Slack 討論串：狀態說明＋29 頁（含概覽、27 情境、核准指令），
共 30 筆 Slack API 成功收據，保存 approval-build.xcQbUc/plan-delivery.jsonl。

## 2026-09-09 — Codex（#1014 人工核准與流程上限診斷）

19:41 唯讀 DB、服務 log、Git 證據：服務正常，文件 commit c0f1617／測試 commit
3bcf2b7 已存在，814c9b1 保留且工作樹乾淨。verification 報告技術 26/27 PASS，
SC-23 未见人工核准證據導致 BLOCKED，19:40 回 AWAIT_HUMAN；非 quota 或服務停止。
舊快照 QA 仍 Claude sonnet，requirePlanApproval 缺省；新版前置閘門尚未部署。
triage 已達 3/3 次，直接補充會再撞循環上限。未修改任務、重跑、部署或代為核准。
QA 測試通過數為 agent 報告，本輪僅核實 Git 產物與流程，不視為自行完成端對端驗收。

## 2026-09-09 — Codex（Slack 報告可讀性）

定位 FlowEngine 階段回報／等待人工處理及 SlackBlocks 泛用回報直接輸出 JSON。
新增 SlackReport 共用顯示器：摘要、階段狀態、阻礙、路由標籤與巢狀清單；
讀取 JSON 文字值呈現真換行，不反解 Windows 路徑的字面反斜線；保留原始結構收據。
COMPLETED 明示不等於整體 QA 驗收；Slack 特殊字元編碼、保留憑證遮蔽、限制輸出長度。
隔離 slack-report-build.FgRq7o：來源檢查通過，Maven 206 項、200 通過、6 opt-in 略過。
新增 3 項顯示器測試；未部署／push，未宣稱 Slack 真實畫面驗收完成。

## 2026-09-09 — Codex（恢復既有 Slack 服務）

使用者授權啟動目前部署版；19:15 確認 PID 7289、沙箱與工具鏈自檢成功，
五個角色 Socket Mode 啟動。#1014 仍 AWAIT_HUMAN、updated_at 未變，未見舊訊息補送。
未部署新版、未重送任務或設定自動啟動；請使用者原討論串重新送出補充一次以驗證事件。

## 2026-09-09 — Codex（Slack 補充無回應診斷）

19:09–19:13 台北時間唯讀確認 service.sh 回報 STOPPED、無 JVM，VM 於 19:07 開機。
未在 system/user unit 名稱及 crontab 發現 AI Team 自動啟動；尚未判定先前停止的精確時間／原因。
PM bot ID 與 mention 相符、補充語法正確；#1014 仍 AWAIT_HUMAN，無本次補充執行紀錄。
未重啟、未重送、未變更任務或部署候選版。交接已標記歷史 PID 不代表目前運行。

## 2026-09-09 — Codex（跨專案執行契約）

依使用者要求從單一 #1014 維修提升為共用機制。Project 新增選填 operations 與
requirePlanApproval；操作宣告由 OperationContract 靜態驗證，OperationPreflight
在角色相同沙箱檢查原快照工具、CLI 政策、目錄／快取／可寫範圍，失敗不呼叫模型。
保存 executionContexts 的 workspace/head/config/time/log/hash；交接補 commit／路徑。
PlanApproval 綁定真人、計畫、需求基準、配置；VERIFICATION／發布前檢查，模型
approved 及獨立測試收據不能代替真人核准。Slack 核准只記錄，不自動派工。
TeamMigration 預覽精確能力差異、token 核准、保存 migrationHistory，失效舊驗收證據，
拒絕模型／角色／路由／repo／核准政策等非能力變更，保留原工作區。
Java＋文件網站與 Node 範例更新；保留 legacy 及缺少新欄位的原快照行為。
補角色對話失去原工作區不能 fallback 的測試，並新增 docs/cross-project-execution.md。

最終隔離 Maven package：203 項中 200 通過、3 略過，check-source 與 16 fixture 通過。
含 3 項不呼叫模型的真實 VM 預檢：工具正常／缺工具／外層 readonly pin 拒絕。
初次沙箱測試因 /tmp 被 PrivateTmp 隱藏而失敗，移至建置 target/preflight-smoke 並
檢查具體缺工具／拒寫訊息後重跑全綠，未將單純報錯當作安全測試成功。
隔離目錄 /home/vboxuser/ai-team/contracts-build.RtQKyc；最終 build-final.log。
jar SHA256 f0401151c4e7b2dd16286dbad722ce98fa2740adca8eb142f3ed98eda811e527。
兩配置靜態驗證通過，Node 驗證使用既有 repo 佔位，只驗證配置非 Node 端到端。
本轮未 commit/push/部署，未改 #1014；新版核准與遷移 Slack 真實驗收仍待執行。

## 2026-09-09 — Codex（PM 工作區／文件建置權限修正部署）

ConfiguredExecutor READ_ONLY 有 workspacePath 時核實並使用原工作區；缺失／不符
拒絕退回主 repo。不同 cwd checkpoint 拒絕 resume，提示獨立建置證據不等於真人核准。
spring-starters builder 補精確 npm ci/install/run build/version 與 node version，
加入 .npm 快取及 node/npm 工具鏈檢查，未開放 npm/npx 萬用權限。
5 項新回歸測試；隔離 Maven package 共 182 項，179 成功、3 略過；16 fixture 通過。
07:42 部署 PID 100553，自檢及 Slack 啟動通過，jar SHA256
a1646302d3546e769f2b6ec9366d36dc9d562ada0b07ab15e2741154ac39380a。
備份 /home/vboxuser/ai-team/triage-fix-backup.gVpYRx；建置 triage-fix-build.LFnvTZ。
#1014 維修快照只補必要文件設定與更正脈絡；排除允許欄位後 before/after JSON 一致，
task／其他 flow／checkpoint 雜湊一致。保留 814c9b1、模型與所有需求驗收內容。
未重跑任務、未冒認真人核准，待 SC-23 收據／核准；真實 doc-site build 和 QA 未驗收。
未 commit/push。

## 2026-09-09 — Codex（#1014 自動續跑後再受阻診斷）

Live log 證明 02:32:10 台北時間額度排程啟動，02:43:42 實作回報後進 triage，
02:45:57 AWAIT_HUMAN。不是再次 token limit，也尚未進 QA。
已核對原快照 builder.tools 不含 npm/npx，原始 permission_denials 有對应拒絕。
triage.workspace=false 透過 ConfiguredExecutor:29 選主 repository；PM 在該處
找不到 814c9b1 而誤判產物遺失。直接 git 查核 task-1014 工作區，commit 確實存在，
24 檔 1664 insertions/187 deletions，HEAD=814c9b1、status 乾淨，63 份 surefire XML。
未將 XML 存在或 agent 自陳等同獨立 QA 通過，未重新建置。
SC-23 確實要求真人核准；Slack 收據尚未核對，不能由 independentEvidence=null
推論無人核准。僅更新交接紀錄，未改程式／配置／任務／部署。

## 2026-09-08 — Codex（QA 明確模型與推理強度）

依使用者同意指定 spring-starters tester 為 codex/gpt-5.6-sol/medium。
Role 與 RuntimeRequest 增加選填 reasoningEffort，舊建構子／JSON 快照相容，
載入拒絕無效值與非 Codex 使用；ConfiguredExecutor 從固定快照取值，
Codex fresh/resume 均傳入 model_reasoning_effort。Slack 階段卡標示要求值。
check-source 與 16 fixture 通過；隔離 Maven package 共 177 項，174 通過、3 略過。
VM qa-model-probe.DDYdFP 唯讀最小呼叫 exit 0、回覆 OK、turn.completed，
非真實 QA 端到端驗收。23:33 部署 PID 94564，沙箱與 Slack 啟動通過，
flow_run 前後雜湊相同，#1014 quota 排程及原快照保留。
jar SHA256 111f6f536c45b11bcbf84599ee69de3773efa871ae15eaafb79e5a59994fc473。
备份 /home/vboxuser/ai-team/qa-model-backup.58jnRa；建置 qa-model-build.uP2dUG。
新配置 revision 00937d722339693ef96a1636a04bcc49637dbfef11be586d75982dc5205b102c。
未 commit/push；正式任務中的 Slack 模型顯示與完整 QA 流程待後續驗收。

## 2026-09-08 — Codex（額度自動續跑）

修正 Claude session limit 漏辨識，以及 describeFailure 遺漏供應商 result。
新增 QuotaAutoResume/QuotaRetryTime 與 quota_retry 表，持久化 UTC 重置加 2 分鐘
或未知時間 30 分鐘起至 6 小時退避，原工作佇列續跑。#1014 已依原始错误證據
改 WAITING_QUOTA，預定台北 9/9 02:32；保留 session、工作區及 flow_run 雜湊。
隔離目錄 quota-build.fi1Ckq 的最終 Maven package：173 項中 170 通過、3 opt-in
略過，來源檢查及 16 fixture 通過。初次部署發現 Slack 阻塞啟動使
ApplicationReadyEvent 不會送出，改為 recoverOnStartup 後明確啟動排程，重跑
全部測試並部署成功。PID 90460，jar SHA256
f5c0f2af38c31360a67d979c6a748f44e1ccab375432706d066e7b5425dd942d。
原版本備份 /home/vboxuser/ai-team/quota-auto-backup.aviBTA；中間啟動接線版本
備份 quota-auto-backup.bYNCWU。資料庫確認排程 18:32 UTC，attempts=0；
沙箱／Slack 啟動通過，真實額度恢復後的續跑尚未驗收。尚未 Git 提交/push。

## 2026-09-08 — Codex（QA 切換 Codex）

使用者要求 QA 換 Codex；spring-starters tester.runtime=codex、model=""，
適用 plan 與 verification，新任務生效。52 項 Maven 測試與 check-source 通過。
2026-09-08 22:34 台北時間部署，PID 84091，配置 revision
51680b8dd5b283fae6783af2d9982fd912947ff49f0ea3dc655a4dd4441ecda9。
沙箱／工具鏈與角色 Socket Mode 啟動通過，flow_run 前後雜湊一致，未遷移 #1014。
備份 /home/vboxuser/ai-team/qa-codex-backup.qCPDB3。真實 QA Codex 驗收待執行。
發現前次部署 JVM 繼承 deployment.lock；停機釋放後完成切換，兩份部署腳本
啟動服務時已加 8>&-，避免把部署鎖傳給 JVM。本輪未提交或推送 Git。

## 2026-09-08 — Codex（Slack 定期回報實作）

後續部署：使用者明確要求後，2026-09-08 22:23 台北時間部署成功，PID 80214。
jar SHA-256 a73017c0d066f0c35fe3283c821cc0b71135b7ac22b24c8aed796d751d7d90ad。
備份 /home/vboxuser/ai-team/progress-backup.oFdYpg；task 表前後雜湊一致，#1014
仍 AWAIT_HUMAN。沙箱／工具鏈與角色 Socket Mode 啟動檢查通過，真實五分鐘
更新仍待任務續跑驗收。以下保留實作時的歷史紀錄。

新增 StageProgress：每階段一張系統代報狀態卡，標明角色／節點與耗時，預設
300 秒更新同一訊息，支援 AI_TEAM_PROGRESS_INTERVAL_SECONDS（最小 30 秒）。
涵蓋 v2、legacy、新 session 接手、唯讀對話及系統獨立驗證；結束或任務不再執行
即停止，通知例外不影響任務。刻意不宣稱工具活動、完成百分比或產品已通過驗收。

隔離目錄 /home/vboxuser/ai-team/progress-build.UqhoBm 執行 mvn -B package：
167 項中 164 項通過、3 項 opt-in smoke 略過，0 失敗／錯誤，BUILD SUCCESS。
新增 4 項心跳回歸測試；check-source 與 16 項平台 fixture 通過。
未部署、未修改 #1014 或發出 Slack 測試訊息；真實五分鐘更新仍待驗收。
文件 docs/slack-progress.md。#1014 額度辨識修正仍待另行實作。

## 2026-09-08 — Codex（#1014 額度回報診斷）

唯讀確認 #1014 在 2026-09-08 22:07 台北時間於 implementation 停止，狀態
AWAIT_HUMAN。implementation.stdout.json 明確為 is_error=true、api_error_status=429、
terminal_reason=api_error，result 為 You've hit your session limit · resets 6:30pm (UTC)。
QuotaExceeded.explicit 未涵蓋 session limit 措辭；ClaudeCodeRuntime.describeFailure
未包含 result，僅呈現 errors／permission_denials，故額度原因未帶入 Slack 回報。
implementation checkpoint 與 session ID 存在，工作區保留未提交程式及測試。
本輪僅診斷，未修改程式、部署或續跑任務；需要補辨識與原始錯誤呈現的回歸測試。

## 2026-09-08 — Codex（SMTP 任務重測清理）

依使用者要求清除 Slack SMTP 重測相關任務 #1010 與 #1012；保留 #1009
單純查詢紀錄與既有 Slack 訊息。Live DB 的 task、stage_result、stage_attempt、
task_checkpoint、task_remediation、audit_log、flow_run、direct_request 關聯均已清零；
任務流水號高水位保留 1013，避免舊 Slack 按鈕誤指新任務。

#1010 工作區、log 與兩任務共 8 個專屬 Claude／Codex session 已移至可復原備份
`/home/vboxuser/ai-team/cleanup-backups/smtp-1010-1012.nA9qiF`，內含清理前 SQLite
備份。其他任務資料雜湊與 DB integrity check 通過。服務以 PID 71666 重新啟動；
配置、沙箱與工具鏈自檢通過，主入口及五角色 Socket Mode 均已啟動。

## 2026-09-07 — Claude Code（版本控制上線）

專案首次納入 git 並推上 GitHub：`git init`（預設分支 `main`）、初始 commit
`a50642d` 共 174 個檔案，遠端為 https://github.com/a09090443/AI_team （private）。
`.gitignore` 追加 `*.tgz`，排除 `deploy/engineering-v2-source.tgz` 與
`deploy/handoff-source.tgz` 兩份原始碼快照（內容與 repo 重複）。

驗證：推送前掃描 `xoxb-`／`xapp-`／`ghp_`／`github_pat_`／`sk-ant-`／私鑰樣式，
命中全為文件佔位符（`xoxb-...`、`github_pat_example`），無真實憑證；`env.sh`
不存在於工作目錄且已列在 `.gitignore`。推送後以 GitHub API 確認遠端 `main`
指向 `a50642d`，與本機一致。僅版控作業，未改動 Java 實作與 orchestrator 契約，
故未跑 Maven 測試。

## 2026-09-07 — Claude Code

建立跨工具共享記憶架構：新增 `CLAUDE.md`（import `@AGENTS.md`）與
`.ai/context.md`、`.ai/decisions.md`、`.ai/progress.md`；於 `AGENTS.md` 加入
「開場讀、收尾寫 `.ai/`」的規範。

驗證：僅為文件新增，未改動 Java 實作與 orchestrator 契約，故未跑 Maven 測試。
# 2026-09-09 — Codex（雙層沙箱修復與 #1014 恢復）

21:37 真實 QA 已跑到 Maven／npm，另發現本機 SMTP socket 被拒及文件 node_modules
唯讀缺口；初始化修正通過不代表驗收完成。未擴權或再提高重試次數，詳 context 與專題文件。

完成缺少 `.agents`／`.codex` 掛載點的初始化修正與 30 秒無模型預檢；不放寬沙箱。
修正重試上限先檢查再計數，保留原始受阻摘要，不再指示盲目重建任務。
隔離 build=codex-sandbox-build.ZFDWlB：220 項中 217 通過、3 略過，含 2 項實機雙層沙箱
及 3 項 systemd 預檢；bootstrap 通過。非乾淨 VM／產品 QA 全程驗收。
21:35 台北已部署 PID 34947，backup=codex-sandbox-backup.SxysRO；詳 docs/codex-nested-sandbox.md。
#1014 原核准、計畫、工作區與 commit 保留，受控回 verification 後進入 STAGE_RUNNING，
Codex gpt-5.6-sol 已有工具命令 exit 0；完整 QA 尚未完成，PR 仍須人工核准。未 push。

## 2026-09-09 — Claude Code（Codex CLI 參數契約修正）

#1014 於 15:20 派工 QA 立即失敗：Codex 結束碼 2、事件流 0 bytes、stderr
`error: unexpected argument '-P' found`。模型未被呼叫，未消耗 token 或額度。
根因是 `CodexLocalTestPolicy` 把同一組參數同時給 `codex sandbox` 與 `codex exec`，
但 codex-cli 0.153.4 兩者契約不對稱：`-P` 是 sandbox 必填、exec 完全不接受，
exec 要改用 `-c default_permissions="<profile>"`。預檢走 sandbox 會通過，
反而遮蔽了派工必然失敗的缺陷。

修正三項：拆 `sandboxArguments()`／`execArguments()` 共用同一份 profile 定義；
新增 `RuntimeUsageError`（事件流空＋結束碼 2＋用法字樣三者全中才成立）；
`FlowEngine` 收到它時還原本輪 visits／transitions／一次性受控恢復授權並清空
`activeNode`，Slack 明說本次未呼叫任何模型。額度仍走原 `QuotaExceeded` 路徑。

隔離 build=usage-fix-build.Sa7L5Y，未動線上 target/：`mvn -B package` 231 項
0 失敗 0 錯誤、10 項 opt-in 略過；opt-in 另行實跑全過——CodexSandboxVmTest 4/4
（含新的零模型 CLI 契約測試與本機 TCP）、OperationPreflightVmTest 3/3、
LinuxVerificationSmokeTest 3/3；check-source 通過。候選 jar SHA256
9d54bb4b18c4efacb7ac0e7dcce63191d97ff1963a9c3bf33a5037cceb52a3d7。

未部署、未 push、未改 DB、未調高上限、未代發 Slack；線上仍 409f6a0a…／PID 47684，
#1014 狀態與 updated_at 未變。真實 QA 端到端仍未通過。詳
docs/codex-cli-argument-contract.md。

## 2026-09-10 — Claude Code（建置文件對齊實況）

檢查 `docs/INSTALL.md` 是否跟得上 9/7–9/9 的變更，結論是沒有（停在 2026-09-06），
七項缺口全部修正，目標是新機照文件走不會踩到已知問題：

1. 新增 §6.1 真人核准閘門：兩份 teams 設定都是 `requirePlanApproval: true`，
   照舊文件跑全管線一定會停在等待核准。補齊四組指令（檢視／核准測試計畫、
   檢視／核准配置更新、核准恢復驗收、一般續跑）與三項限制（遷移只收能力設定、
   套用遷移會清掉舊核准、恢復授權同版本一次且不重設次數）。
2. §8 原本寫「#1010 Reviewer bwrap/.agents 未修」已過時，改為 9/9 已修正並說明重現時的處置。
3. §6 補 `team.env`（start/preflight 會讀、backup 會備份、與 env.sh 的優先序）。
4. `deploy/env.sh.template` 補 `AI_TEAM_PROGRESS_INTERVAL_SECONDS`；
   §7 補 `AI_TEAM_RESUME_APPROVED_TASK` 是一次性啟動變數、不可寫進 env.sh，
   以及額度續跑目前只解讀標了 UTC 的措辭。
5. §3 補三項 Codex 前置條件（`.agents`/`.codex` 掛載點、sandbox 與 exec 參數不對稱、
   localTestNetwork 僅支援 Codex），並連到三份專題文件。
6. §1 說明 `tar` 已進 `requiredTools`，啟動自檢缺項會直接失敗。
7. 測試項數 68 → 231（10 項 opt-in）：在 qa-scenario-workflow 與 rebuild-verification
   以補註方式更正，保留原始日期紀錄。

過程中發現並修掉一個自己造成的地雷：數值型設定寫成 `=""` 會讓 Spring 認定「有設定」
而轉型失敗、服務起不來，因此範本改為註解掉，並在 INSTALL 明說數值型不可留空字串。

`AGENTS.md` 索引補上 plan-approval-buttons、codex-nested-sandbox、
local-test-capabilities、codex-cli-argument-contract 四份。
驗證：`check-source.sh` 通過、`bash -n` 範本通過、AGENTS.md 與 INSTALL.md 內所有
相對連結目標均存在。純文件與範本變更，未改 Java 實作，故未另跑 Maven 測試；未 push。

## 2026-09-10 — Claude Code（額度訊息呈現與遠期解禁排程）

問題：Slack 上看不到「什麼時候能用」。查證後兩則訊息其實都帶時間，但都不可讀——
FlowEngine 那則把解禁時間埋在英文 JSON 原文裡，QuotaAutoResume 那則直接吐
`ZonedDateTime.toString()`（含奈秒與方括號）；而且後者只在觀測值變動時發，
純到期重試不發訊息。更根本的是 `QuotaRetryTime` 只認得 Claude 的
`resets <時刻> (UTC)`，看不懂 Codex 的 `try again at Sep 15th, 2026 1:09 PM`，
於是退回退避，導致解禁日還有六天卻每 6 小時重試一次。

修正：
- `QuotaRetryTime` 新增 Codex 措辭解析。該時間**沒有時區**，因此只取邊界不猜值：
  最早 UTC+14、最晚 UTC−12。`farBeyondBackoff` 判斷「連最早解讀都在 24 小時之後」，
  成立時改排在最壞解讀之後試一次，中間不重試；重試路徑也改用同一套判斷，
  不再無條件 +6 小時。
- 新增 `QuotaMessage`：我方時間換算台北並格式化為 `yyyy-MM-dd HH:mm`，
  供應商解禁時間一律原文照登，未標時區者明確加註「系統不推測」。
  FlowEngine 與 QuotaAutoResume 兩則訊息都改用它。

隔離 build=quota-fix-build.sToC4F：237 項 0 失敗、10 項 opt-in 略過；
opt-in 另跑 CodexSandboxVmTest 4/4、OperationPreflightVmTest 3/3；check-source 通過。
候選 jar SHA256 5d23cf8c30f89dce220fa0fcf8dafa58fcb1228da9d943fea6518b6a04cdeac8。
INSTALL §7 同步更新為新行為。部署腳本 deploy/deploy-quota-message-20260910.sh 已就位但**未執行**。

過程中修掉兩個自己造成的錯誤：以 heredoc 寫 Java 檔時反斜線被吃掉（正則失效），
以及月份縮寫手動轉小寫導致 `MMM` 解析失敗——後者由新測試抓到，已改用不分大小寫的 formatter。

部署結果：2026-09-10 07:59 台北時間部署成功，PID 67312，
jar SHA256 5d23cf8c30f89dce220fa0fcf8dafa58fcb1228da9d943fea6518b6a04cdeac8，
備份 /home/vboxuser/ai-team/quota-fix-backup.cp65nb。
沙箱自檢與工具鏈 [java, mvn, node, npm, git, tar] 通過，五角色 Socket Mode 啟動，
部署前後 task/flow/checkpoint/stage 雜湊一致。#1014 仍 WAITING_QUOTA，
visits.verification=4、transitions=14、activeNode=verification 均未變。
既有 quota_retry 排程（04:56Z）不會被追溯修正，該次仍會重試一次，
之後才由新邏輯重排到 9/16 並改用新訊息格式。

同步更新文件：docs/quota-continuation.md 新增 2026-09-10 章節（兩家措辭對照、
邊界解讀規則、Slack 呈現範例與事故背景，保留舊章節）；docs/INSTALL.md §7 改寫；
qa-scenario-workflow 與 rebuild-verification 的測試項數同步為 237。
