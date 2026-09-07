# QA 受阻後的 PM 處理

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
