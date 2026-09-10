# Codex 雙層沙箱初始化修正（2026-09-09）

## 原因與修正

#1014 的 Codex QA 有效讀取真人計畫核准，但工具命令執行前失敗：
`bwrap: Can't mkdir .../task-1014/.agents: Read-only file system`。
配置式 LIMITED QA 的外層 systemd 沙箱讓根目錄唯讀，只開放測試與建置目錄。
Codex 0.153.4 的 workspace-write 內層沙箱需為 `.agents`、`.codex` 建立唯讀掛載點；
這兩個目錄原先不存在，因此不是測試失敗或 token 不足，而是初始化失敗。

`CodexSandboxPreparation` 在呼叫模型前準備兩個空目錄，保持既有內容不變；
拒絕根目錄別名、metadata symlink／檔案及缺少 `.git` 的假工作區。
不建立假的 `.git`，不安裝 skill，也不改權限設定。
接著以相同外層 SandboxPolicy 跑 `codex sandbox -P :workspace -- /bin/pwd`；
錯誤／30 秒逾時會保留 `角色.codex.preflight.log` 並停止，不呼叫模型。
正式 `codex exec -s workspace-write` 的設定未放寬。

此 CLI 形式已在 0.153.4 實測；不同版本若不支援會預檢失敗，不退回無沙箱執行。
既有 Git worktree `.git` 檔案可通過 metadata 檢查，但本次端到端只驗證獨立 clone。
預檢成功只證明命令可啟動，不代表 Maven、文件、網路、Git 寫入或完整 QA 通過。

官方背景：[沙箱](https://learn.chatgpt.com/docs/sandboxing)、
[權限設定](https://learn.chatgpt.com/docs/permissions)。具體雙層互動原因以本次 VM 重現為證。

## 重試限制回報

原程式先增加次數再檢查上限，使被拒絕的續跑也被計入。新版先檢查，再於允許
進入節點時增加次數；舊計數保留，不自動扣除或重設。上限數值不變。
受阻報告的可讀摘要保存為 `lastBlockReason`，觸及上限時一併顯示原始原因、
節點／流程計數與「此次未啟動 agent」，不再要求使用者盲目补充或建立新任務。
舊資料沒有這個欄位時，引導查閱上一份角色報告。

## 驗證與部署證據

- 隔離 fixture `nested-sandbox-probe.oEW1ko`：缺目錄時 exit 1 並重現同樣 bwrap 錯誤；
  準備目錄後 exit 0，工作區根目錄與兩個 metadata 目錄仍唯讀。
- 隔離建置 `codex-sandbox-build.ZFDWlB`：220 項測試，217 通過、3 原有 opt-in 略過。
  含 2 項真實 Codex＋systemd 測試與 3 項真實 systemd 操作預檢；無模型呼叫。
- 真實雙層測試確認測試／target 可寫，產品目錄、根目錄、metadata、`.git` 不可寫。
- bootstrap check-source 通過；不是乾淨 VM 重建驗收。
- #1014 原 workspace 使用快照原 policy 預檢成功；Git 工作樹乾淨、HEAD `3bcf2b7`。
- 2026-09-09 21:35 台北部署，PID `34947`；備份 `codex-sandbox-backup.SxysRO`。
- jar SHA256：`7e6bc03c833c29e41032ced3d3f2f219008b035bdc2cec9b9ff98c11d8485823`。
- 啟動沙箱／工具鏈自檢及五角色 Socket Mode 通過。

## #1014 受控恢復

`CodexSandboxRecovery.java` 核對原真人核准、前置契約、工作區及剩餘 QA 次數；
只將 node 從 triage 定位 verification，補充診斷脈絡，不更動快照、計畫與核准。
部署脚本使用一次性 `AI_TEAM_RESUME_APPROVED_TASK=1014` 排入中央 worker。
原 triage=5、verification=2、transitions=12 全數保留；允許 QA 進入後變為
verification=3、transitions=13（上限仍 3／24）。其他任務與核准資料雜湊一致。
既有 `814c9b1`、`c0f1617`、`3bcf2b7` 保留，無重做、無 push。

21:35:50 Codex `gpt-5.6-sol` 啟動；21:36 已觀察到工具命令 exit 0，
不再是僅模型行程啟動或 bwrap 失敗。完整 QA 尚在執行，不宣稱產品驗收成功。
PR 仍有獨立真人核准閘門。

21:37 實際測試新增阻礙（不是 bwrap 初始化回歸）：部分純 Java 測試通過，但 GreenMail
建立 `smtp:127.0.0.1:0` socket 收到 `Operation not permitted`；文件 `npm ci`
在 `doc-site/node_modules/.bin` 收到 EROFS，`.npm/_logs` 亦不可寫。
目前只修復命令啟動，尚未補齊本機 socket 與文件建置能力。需要有範圍的操作權限
契約／內外層權限對齊及配置核准，不得關閉沙箱或把這次預檢成功宣稱為完整 QA 通過。
未修改這些權限、未再次提高次數或續跑。

回復：啟動前失敗可還原 jar 與 #1014 flow；派工啟動後不得盲目還原 DB 或強制
中止執行中任務，需先檢查現場並保留新產出。完整備份保存在上述 VM 目錄。
