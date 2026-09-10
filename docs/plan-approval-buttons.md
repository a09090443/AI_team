# 測試計畫核准按鈕

## 行為

- 完整計畫的最後一頁以系統 App 發送兩個按鈕：核准測試計畫、要求修改。
- 按鈕 value 綁定任務、計畫節點、計畫 digest、配置與需求基準。
- 只允許白名單內的原負責人在原頻道／討論串、AWAIT_HUMAN 狀態操作。
- 核准成功保存收據並自動排入中央 QA 佇列，不需另送續跑；重複點擊不重複派工，PR 仍另行核准。
- 要求修改撤回該計畫核准並等待具體意見；下一則具體補充回到計畫節點，保留重試上限。
- 重新核准視為撤回修改要求；任何新計畫仍須重新核准。
- 文字指令繼續支援。首尾反引號或 text 程式碼區塊包住的完整「補充／處理 #ID」
  指令會解開格式；不從一般問題、程式碼範例或段落中擷取指令。

## Slack App 注意事項

主系統 App 範本 `deploy/slack-app-manifest.yaml` 啟用 interactivity。
角色 App 範本目前 `is_enabled: false`，不可只因程式已註冊 handler 就宣稱角色按鈕可用。
因此卡片由主系統身分發送。程式同時註冊系統及角色 handler，供管理者另行啟用角色互動時使用。
不為此自動修改 Slack App 管理設定。

## 2026-09-09 驗證與部署狀態

21:10 後的現行行為：依使用者要求改為核准自動派工，按鈕與文字指令共用入口。
只有目前等待中的對應 VERIFICATION 節點可核准並派工；不猜測其他節點的恢復路徑。
212 測試中 209 通過、3 略過；備份 `approval-auto-backup.vxJX5L`，建置 `approval-auto-build.7WpRR5`。
jar SHA256：`45dc5e43316f5849b99224f59b4a8ef48c8b6324b9f6b2093d1658406d90ae0b`。
服務 PID 27426，Codex PID 27616；#1014 21:10:50 進入 STAGE_RUNNING、21:10:53 啟動 verification。
原核准／計畫／快照與其他 task/flow 雜湊保持，未代為建立新核准或 PR。
此次啟動一次性傳入 `AI_TEAM_RESUME_APPROVED_TASK=1014`，由中央 worker 恢復既有有效核准；
未寫入永久 env 設定，不掃描或恢復所有等待任務。任務仍在驗收，尚未宣稱 QA 通過。

下列 20:08 紀錄為先前「核准不派工」版本的歷史證據：

最終於 20:08 部署成功，PID 23108；備份 `plan-buttons-backup.D0XSGJ`。
211 測試中 208 通過、3 既有 opt-in 略過。jar SHA256：
`ca5d3a4492fc58580e7b46da618aa754b1cd3c6c38a9ced2b99655a4ca7a36c1`。
五角色 Socket 與沙箱自檢通過，task/flow/checkpoint/stage_result 雜湊不變。
系統 App 已補發卡片（ts `1788955686.694309`），API 回傳兩個正確 action ID 與按鈕文字。
尚未代使用者點擊，真人 Slack 點擊端對端仍待驗證；#1014 既有核准保留、未續跑。

以下為部署過程記錄：

隔離建置 `plan-buttons-build.2TyoAq`：測試與 package 成功（含沙箱預檢）；
涵蓋按鈕值往返、兩個 action ID、白名單／原討論串限制、過期版本、核准／撤回與不派工。
用產品 Java 方法產生補發 payload，確認為真實 actions block，不只是文字標籤。

第一次部署因 #1015 唯讀對話執行中被 service.sh 拒絕，未停止服務／替換 jar／修改 DB。
#1015 的原輸入是帶首尾反引號的 #1014 續跑指令，被舊版當成提問；本次解析修正處理此問題。
#1014 已於 19:55 保存真人核准，但未收到可解析的續跑指令，仍 AWAIT_HUMAN。
其後 #1015 自行停止至 AWAIT_HUMAN，才安全部署及補發卡片；沒有强制終止它。
