# QA 受阻後的 PM 處理

## 2026-09-09 工作區查核修正

配置式 READ_ONLY 節點在任務已有 workspacePath 時，使用該任務工作區，
不因 workspace=false 而查核主 repository；workspace=false 仍表示不建立新工作區。
已保存路徑遺失、不符或為 symlink 時停止，不退回其他副本。PM 仍維持唯讀。
checkpoint 的原工作目錄與新目錄不一致時禁止 resume，以免延續錯誤脈絡。
已結束階段（activeNode=null）按原流程以新 session 重跑，舊 checkpoint/log 保留證據。

spring-starters builder 僅補 npm 版本、ci、install、run build 與 node 版本指令，
沒有開放任意 npm/npx；增加 .npm 快取與 node/npm 啟動工具鏈檢查。
在 doc-site 目錄使用 npm ci 後 npm run build；含依賴 lifecycle，仍受外層沙箱限制。

07:42 台北時間已部署 PID 100553；182 項 Maven 測試中 179 通過、3 略過，
16 平台 fixture 與沙箱／工具鏈／Slack 啟動檢查通過。尚未完成真實文件建置與 QA 驗收。
#1014 以專用 repair-1014-doc-tools.jq 遷移最小工具／建置設定、追加更正，
保留模型、需求、測試計畫、歷史報告、checkpoint、AWAIT_HUMAN 狀態與 commit 814c9b1。
維修 revision 為排序 JSON（snapshot 去掉 revision，含行末換行）的 SHA256；
不同於 TeamCatalog 一般配置序列化，但不冒用舊版本，來源與新版本記錄於 audit_log。
備份 triage-fix-backup.gVpYRx 包含原資料庫與 before/after JSON、程式與配置。
本次未代為核准測試計畫或 PR，也未自動續跑。

已於 2026-09-06 11:07（台北時間）部署到既有 VM；尚未完成 Slack 問題分流端對端驗證。

2026-09-06 本機驗證：所選 10 個測試類別共 63 項通過；來源檢查與 16 項平台 fixture 斷言通過。
未在 Windows 執行依賴 `/bin/sh` 的 Linux runtime 測試，不代表完整 Linux 測試或實機驗收完成。

其後於既有 VM 的隔離目錄完成 `mvn -B package`：79 項測試，0 失敗、0 錯誤、0 略過。
新版 PID 33579，沙箱與 Maven 工具鏈自檢通過，該 JVM 有已建立的 443 連線。
啟動恢復保留 #1011 `AWAIT_HUMAN`，未自動重跑、未提交 PR、未刪除工作區。

- 建置證據：`/home/vboxuser/ai-team/pm-triage-build.SzUZFI/build.log`
- 部署前備份：`/home/vboxuser/ai-team/pm-triage-backup.kS01pt`（舊 jar、來源、prompt、schema、log、SQLite）
- 新 jar SHA-256：`898418599cfa5ebbfee5e57942c995f8a67f9bdd841cd4d5f7ac57899990623b`
- 本次專用升級腳本：`deploy/deploy-pm-triage.sh`；不是新 VM 安裝入口。

QA 受阻後會實際執行 `pm_triage`，沿用 PM 的 runtime、模型與 Slack 身分，
讀取原始需求、架構、測試計畫、實作、QA 報告及阻礙證據；不只是 tag PM。

- 既定範圍的實作或文件缺陷：PM 派 Backend 修正，再執行 QA 與已啟用的 Code Review。
- 原計畫缺少測試或證據：PM 派 QA 補驗證，再執行已啟用的 Code Review。
- 產品取捨、權限或外部環境問題：PM 列出具體問題與建議，停在 `AWAIT_HUMAN`。
- 自動修正最多兩輪；PM 自身失敗或 Reviewer 受阻時也明確停下，沒有 agent 在背景處理。

所有原本的 QA、失敗報告、Code Review 與人工 PR 核准閘門仍保留。
PM 不得放寬驗收、消除未解的不確定性或自行擴權，修正完成也不會直接推送。
QA 的工作仍依原始情境測試計畫，不改成單純檢視程式。

## 同一討論串操作

在原討論串 **@主機器人**（不是只 tag PM 角色）：

- `處理 #1011`：讓等待人工的舊任務交由 PM 分析。
- `補充 #1011 你的答案`：把實際回覆帶入 PM 問題處理。

只允許白名單使用者、原頻道與原討論串，且必須已有 QA 計畫與 QA 結果。
佇列採原子狀態轉換，重複請求不會重複派工。不新建任務或重建工作區。
Slack 會區分「已排隊」、「PM 處理中」、「已派工」與「等待你回覆」。

## 恢復與紀錄

`task_remediation` 保存目前階段、修正輪數與指示；`stage_attempt` 保留新增的每次階段產出，
原有 `stage_result` 仍保存最新一份。舊結果不會追溯補成歷次紀錄。
用量中斷走原有 checkpoint／繼續按鈕，續跑當前修正階段。
服務重啟時，執行中或排隊中的問題處理會停止並明示等待人工，不默默重跑。
若原工作區或 QA 稽核基準缺失，停止而不重建取代現場。
