# 基準驗證：接入專案與部署配置時先確認驗證指令能通過

2026-10-18 起（分支 `feature/baseline-verification`，2026-10-18 已部署三個實例，尚未合併 main；部署與首次結果見 [部署紀錄](deploy-2026-09-06-v2.md)）。

## 為什麼需要

任務的系統獨立驗證（見 [engineering-workflow](engineering-workflow.md#系統獨立驗證)）在全新 clone 執行配置的
`project.verification.commands`。如果這些指令在 VM 上本來就不可能通過，任何任務都過不了，但以前要等第一個任務走到
系統獨立驗證才會發現。dynamic-api 接入時，後端測試因正式設定寫死 Windows 的 `D:/tmp` 而在 Linux 必定失敗，
#1001 因此跑了 36 分鐘、9 次 agent 執行才停下。

基準驗證在**還沒有任何任務修改的基準分支**上先跑一次同一批指令，提早把這類「配置或環境讓指令根本過不了」的問題交給管理者。

## 何時執行

| 觸發 | 條件 |
|---|---|
| 服務啟動（`TaskOrchestrator.scheduleBaselineOnStartup`） | `AI_TEAM_BASELINE_VERIFY` 不是 `off`、配置式團隊已啟用、`AI_TEAM_CHANNEL_ID` 有設定，而且「目前配置版本＋基準分支最新 commit」還沒有結果。接入新專案（第一次啟動）與部署新配置（revision 改變）都會觸發；只換 JAR、配置與基準都沒變時不重跑 |
| Slack `@主機器人 基準驗證` | 白名單使用者，在設定的頻道任何地方送出；不建立任務。基準分支有新合併、或修好問題後想重新確認時使用 |

兩者都會先在頻道貼一則根訊息，結果貼在它的討論串。工作走**中央佇列**，不與任務同時執行（避免搶 VM 記憶體），
佇列通知顯示「基準驗證」而不是任務編號。

## 怎麼跑

`flow.BaselineVerifier`：

1. 取基準分支最新 commit（`WorkspaceManager.baseRef`：先 fetch 主 repo，失敗只警告；讀 `refs/remotes/origin/<基準分支>`）。
2. 在 `verification-runs/0/baseline-*` 建全新目錄，`git init` 後從主 repo fetch 那個 ref、checkout 該 commit。
   主 repo 的基準在 `refs/remotes/origin` 下，一般 clone 帶不過來，所以與開工作區相同直接 fetch。
3. 以與任務獨立驗證**相同**的方式執行每一條驗證指令：systemd 沙箱、釘住所有版控檔（`IndependentVerifier.pinsFor`）、
   空的 HOME、專用相依快取、共用 `verification.timeoutSeconds` 總時限。
4. 與任務獨立驗證不同：**某條失敗後繼續跑其餘指令**，好讓管理者一次看到全貌。每條記為：

| 結果 | 意義 |
|---|---|
| 通過 | 結束碼 0 |
| 程式或測試失敗 | 非零結束，紀錄不像環境問題（附失敗摘要） |
| 環境缺件 | 非零結束，紀錄出現相依套件抓不到、磁碟滿、找不到指令等字樣（`IndependentVerifier.environmental`） |
| 逾時 | 用完總時限；之後的指令記為「未執行」 |
| 執行錯誤 | 其他例外 |

systemd unit 名稱是 `<字首>check-0-<uuid>`：任務從 1001 起，`0` 不會撞號；部署腳本「有 unit 在跑就拒絕部署」也會涵蓋它。

## 結果存在哪裡

`<安裝目錄>/baseline-verification/<配置版本>-<基準 commit>.json`（取不到 commit 時為 `-none`），內容含配置版本、
基準分支與 commit、起訖時間、逐條結果與紀錄路徑。紀錄檔在 `verification-runs/0/`。
**不寫資料庫、不改 `FlowRun`**，所以 JAR 回滾不受影響；目前不自動清理，與任務驗證現場相同。

## 不通過時會發生什麼

- 頻道貼出 ⚠️ 摘要，逐條列出失敗原因與紀錄路徑，請管理者修正配置或環境，或另開任務修 repo；服務 log 也寫 WARN。
- **不阻擋任務**：修好基準問題的任務本身也要能跑（例如 #1001 的目的就是修測試）。
- **自動續驗（2026-10-22，`project.autonomy.verificationContinuations`）**：用完總時限、而這一輪至少通過一條時，
  剩下的指令（含被中止那條）在同一次執行裡以新的總時限再跑，最多續配置的輪數；結果 summary 註記「自動續驗第 N 輪後通過」。
  起因是 bestpartner 五條指令平常要 22–30 分鐘，2026-10-22 那輪 30 分鐘時 npm ci 被截斷。
- 之後新任務開始時，討論串會提醒一次（`FlowEngine.prepare`）。
- 每個節點的 agent 脈絡會加入「基準驗證結果（系統事實）」（`ConfiguredExecutor`，取同一配置版本最新一份），
  讓 PM 在需求階段就說明是否把它納入範圍，而不是等系統獨立驗證失敗才回報。通過時不加任何內容。

## 限制

- 基準通過不代表任務一定能過，只能排除「配置或環境讓指令根本不可能通過」這一類。
- 第一次執行要下載相依套件，可能很慢；逾時照實回報，不算測試失敗。
- 基準分支前進後不會自動重跑，需重啟服務或手動送指令；只看「目前配置版本」最新的一份結果，即使它對應的是較舊的基準 commit。
- 只支援配置式團隊（`AI_TEAM_DEFINITION`）；沒有 `project.verification` 時記錄「沒有驗證指令」，不視為失敗警告以外的處置。
- 2026-10-18 部署後三個實例各自動跑一次：dynamic-api 抓到後端測試編譯錯誤與冷快取下 build 逾時、spring-starters 抓到 1 個時間門檻測試失敗、bestpartner 全部通過（29 分 10 秒，貼近上限）。
  頻道訊息、新任務提醒與 agent 脈絡注入的實際呈現尚未在真實任務核對。
- 首次執行會讓相依快取從冷開始；dynamic-api 四條指令冷快取合計超過 1800 秒，需觀察快取預熱後是否仍逾時，必要時調整該專案的 `timeoutSeconds`。
