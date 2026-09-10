# 本機測試與文件副本建置能力

## 2026-09-09 修正範圍

#1014 的第二層阻礙不是 token：GreenMail 建立 localhost socket 被拒絕，
原 doc-site 的 npm／Docusaurus 安裝與輸出目錄清理也被唯讀掛載阻擋。

新增節點選項 `localTestNetwork`，預設 false；靜態驗證只允許 Codex、LIMITED、
VERIFICATION 節點使用，且須宣告 node 工具。其他 runtime 與舊快照不自動套用。

Codex 啟用受限 permission profile 與 network proxy，但 domain 允許清單為空、
不使用 upstream proxy，也不開放 Unix socket。隔離 namespace 內的本機 TCP 可運作；
外部直連與 Proxy 外連實測被拒絕。此設定不授權連接主機上的既有服務。
模型服務連線與命令網路是不同通道，不能把命令網路限制說成全程序斷網。
官方設定背景：[權限設定](https://learn.chatgpt.com/docs/permissions)。

內層維持 workspace-write 類型的根目錄可寫模型；真正的細粒度寫入範圍仍由外層
systemd 限制。若把每個子目錄都當成 Codex 可寫根，Codex 會為其建立 metadata
掛載點，使 npm ci 無法移除 node_modules/.codex（本次已重現 EBUSY）。
命令可寫快取與原核准 cachePaths 對齊，不把 runtime ~/.codex 狀態目錄開放給命令。
產品路徑、Git metadata 與原始文件的限制不撤除。

呼叫模型前，以相同內外層設定測試 localhost TCP round trip 及可寫路徑；
unit 最長 25 秒、客戶端最長 30 秒，不略過不相容 CLI，也不退回無沙箱。
目前實測 Codex 0.153.4；未宣稱所有 CLI／OS 版本已支援。

## 文件建置方式

QA 不在原始 doc-site 執行 npm ci/build。它將目前 HEAD 的文件及 llms 檔案
複製到 target/qa-docs，再於副本安裝、建置。整個 target 是可寫的產物範圍，
工具可刪除並重建其下的 node_modules／build／.docusaurus；原始檔保持唯讀。

```sh
mkdir -p target/qa-docs
git archive HEAD doc-site llms.txt llms-full.txt | tar -x -C target/qa-docs
npm --prefix target/qa-docs/doc-site ci --offline --ignore-scripts --no-audit --no-fund
npm --prefix target/qa-docs/doc-site run build
cmp llms.txt target/qa-docs/doc-site/build/llms.txt
cmp llms-full.txt target/qa-docs/doc-site/build/llms-full.txt
```

這是本專案經實測的操作契約，不推論其他專案可忽略安裝 scripts。
Maven 使用 `-o`；依賴快取不足即停止，不在本配置開放套件站外連。
只對明確核准的 localTestNetwork 節點準備精確目錄掛載根；wildcard 不猜測缺少目錄。
路徑祖先或本身若為 symlink，停止。舊節點保持原本缺目錄 fail-closed 行為。

## 既有任務核准與一次性恢復

新版能力遷移可預覽 nodes 的 writablePaths／localTestNetwork；模型、路由、scope、
保護路徑、maxVisits、maxTransitions 等仍不得夾帶變更。核准 token 綁定完整差異。

在原 Slack 討論串標記 PM Agent，依序操作：

1. `補充 #1014 檢視配置更新`，審閱後送出回覆提供的 `核准配置更新 <token>`。
2. 配置核准後，系統提供 `補充 #1014 核准恢復驗收 verification <新配置版本>`。
   這是另一個明確授權，允許該配置一次受控 QA；不重設歷史計數。
3. 系統重新呈現測試計畫與按鈕；原負責人再次核准計畫後才排入中央 worker。

只允許原負責人、停止等待狀態、已核准能力遷移且前置契約有效的驗收節點。
同一配置只可授權一次；進入節點時消耗 grant，保存稽核與累計次數。
流程總上限仍是硬限制；PR 獨立核准不變。舊計畫核准不能套用新配置。
僅更新服務或核准配置，都不會自動授予這次額外執行機會。

## 驗證與部署

- 隔離 build：`/home/vboxuser/ai-team/local-test-build.ym6i6i`。
- 最終 build-final.log：224 項測試中 221 通過、3 原有 opt-in 略過。
  包含實際 systemd／Codex 沙箱、local TCP、外連拒絕、唯讀路徑與恢復核准流程。
- `LocalTestSmoke.java` 在獨立 clone 執行 9 項真實 SMTP 測試，全通過。
- `real-tools.log`：離線安裝 1334 套件成功，Docusaurus build 成功；兩份 llms
  產物與 HEAD 檔案 cmp 相同，git 工作樹乾淨。這使用 VM 既有快取，非乾淨 VM 驗收。
- 部署時間：2026-09-09 22:05–22:06 台北；PID 47684。
- jar SHA256：`409f6a0ad84833d72574d1c7f3251009fa4237d0a4c71dde217f1435e9f34f33`。
- 備份：`/home/vboxuser/ai-team/local-test-backup.roptPp`。
- 活動配置：`d36d9dbc93e16c66ef1fed770d06581376adfa8f3d2b915891dbf395682901f7`。
- 自檢及五角色 Socket Mode 啟動通過；所有既有 task、flow、checkpoint、stage_result
  部署前後雜湊一致，沒有套用新配置、代核准或恢復 #1014。

#1014 仍 AWAIT_HUMAN、node=triage，QA 歷史 3 次、triage 5 次、transitions=13。
測試的復原例外會將 QA 累計記為 4；不是把 3 改成 0 或提高常態上限。
未 push，未取得新版配置／計畫核准，尚未通過正式 QA。

## Slack 發送限制

此 session 的安全審查阻擋代發完整配置差異訊息。已用 auth.test 核對 workspace
為使用者指定的 aiteam-jgs6617.slack.com，DB 亦對應 #1014 原 channel/thread；
但 Slack conversations.info/replies 回 missing_scope，未能再核實討論串內容。
重新審查仍拒絕，未繞過限制、未發送訊息。使用者可自行在原串送出上列檢視指令，
或明確核准分享這份配置差異至原討論串後再處理發送；不可把未發送說成已送達。
