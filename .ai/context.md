2026-09-13 21:00 Codex：Codex resume 權限與簡要回覆修正已部署。
後續唯讀檢查 #1041：線上 JVM PID 95618 實際開啟
`/home/vboxuser/ai-team/ai-team.db`，該 DB 最高任務為 #1015；task、flow、checkpoint、
stage_result、quota、audit 與 orchestrator.log 均無 #1041，因此不是既有任務失敗，
而是該編號未建立或使用者可能指的是 #1014。未修改任何線上狀態。
使用者隨後貼出 #1014 防循環回報；再次唯讀確認 task=AWAIT_HUMAN、flow.node=triage、
activeNode=null、triage visits=5、verification visits=4、transitions=14，且無 agent 程序。
本次停止是 QA 確認 SC-17／SC-25 未達標後命中 triage 上限，不是 resume、唯讀或額度故障。
使用者授權修正後，已在 #1014 工作區提交 `0b6a8b8`：Base64Util 不再記錄輸入值，
SC-17 同時擷取 MailServiceImpl／Base64Util logger，Base64UtilTest 新增敏感輸入回歸，
根 README 補多組 SMTP 容錯說明。針對測試 9/9、base 模組 91/91、完整 Maven reactor
均 BUILD SUCCESS；工作區乾淨，未 push、未改 DB、未解除流程上限。下一步需真人受控恢復 verification。
21:24 使用者澄清只要檢查安裝文件，不要執行完整重建或修改腳本。本輪已停止並清除
隔離演練目錄，撤回超出範圍的腳本／文件試改。文件審查結論：目前不能宣稱可從新環境
完整建置；INSTALL 的 source tar 漏 `orchestrator/teams`、channel 可留空與 preflight 必填
互相矛盾、正式 toolchain lock 尚缺，且 INSTALL 的 opencode Linux 狀態已過期。
使用者隨後授權修文件：已補 teams、兩端 SHA256／解壓步驟、固定工具鏈 URL/checksum、
Codex resume 防復發條目，並讓 env 範本與 preflight 統一為 channel 必填及更新 opencode 狀態。
只改 INSTALL、rebuild-verification 與 env 範本註解；未改 runtime／bootstrap 行為。
線上 PID 95618，jar SHA256
`b86c86896cec38f150b91c5d46501943d6cd4e74c46c7958d377ad1cee607f50`，
build=`resume-fix-clean.QhTyoo`，有效 backup=`resume-brief-backup.JTlbOD`。
乾淨隔離 Maven package 263 項 0 失敗、10 項 opt-in 略過；opt-in 另跑 10/10
（CodexSandbox 4、OperationPreflight 3、Linux 3），check-source 通過。

根因是 `codex exec resume` 不會套用放在父層 exec 的沙箱設定；首輪
`ai_team_local_tests` 正常，之後續跑皆退回 `:read-only`。現改為權限選項
放在 resume 子指令之後、session id 之前；一般續跑改用
`-c sandbox_mode="workspace-write"`。受阻與防循環訊息也改為簡要顯示，
直接角色對話預設最多顯示 1200 字，細節由使用者追問；完整報告仍保留。

#1014 仍 `AWAIT_HUMAN`，updated_at 未變，工作區存在且乾淨，未自動續跑。
使用者於 21:04 明確授權後，已先備份 DB 至
`/home/vboxuser/ai-team/resume-state-backup.Oh16IP`（integrity_check=ok），再只將
flow.node/activeNode 從 triage/null 恢復為 verification/verification。
遮蔽這兩欄後的 flow 雜湊前後一致；visits.verification=4、visits.triage=5、
transitions=14 均未變，並寫入 RECOVERY audit。現在可由使用者按原本的「繼續任務」一次。
使用者於 21:05 按下後，任務已進入 STAGE_RUNNING／verification；Codex resume
成功啟動 Maven `base-spring-boot-starter verify`，確認不再退回唯讀權限，目前仍執行中。
首次部署嘗試在替換檔案前因線上 prompt 實際位於 `~/ai-team/prompts/`
而停止；已先恢復舊服務健康，修正腳本後才成功部署。本次未 push、未續跑任務。

2026-09-10 15:0x Claude Code：**線上目前是 PID 81388，jar SHA256
`a4257e9b0462ea444dd01d9c90ba9515631e54bfa8eb9ff51c7f0f21ade14c63`**（階段回報摘要化）。
build=`brief-build.RN3Z3S`（261 項 0 失敗、10 項 opt-in 略過），backup=`brief-backup.3LAMuX`。
節點回報預設只貼判定／摘要／阻礙／建議後續，完整報告改用
「補充 #<ID> 檢視完整報告 [節點]」取回（只讀 stage_result）；`AI_TEAM_REPORT_DETAIL=full` 可回舊行為。
**真實 Slack 畫面與該指令尚未實測** —— 目前沒有執行中任務，最省事的驗證是在 #1014 串下
`補充 #1014 檢視完整報告 verification`，它只讀資料不動狀態。

任務現況：#1014 `WAITING_QUOTA`（重試 2026-09-16T01:11Z、attempts=5，工作區在，
三個未推送 commit 另有備份 `task-1014-rescue.20260910144216`）、#1015 已 `ABORTED`。
**警告：#1014 只要回到 ABORTED，下次服務重啟的 `purgeWorkspaces()` 就會刪掉它的工作區。**
部署腳本已加前置檢查擋這件事，但手動重啟不會擋。

以下為同日稍早的 opencode 交接。

2026-09-10 13:34 Claude Code：opencode 第三 runtime **已部署**，PID 75366，
jar SHA256 `3fb67dbc8b4f53efbd024fc544cdc95e2f300f5f425c7370a4540c42cbbbca64`
（前一版 `5d23cf8c…`；先前交接寫的「線上 409f6a0a」已過期，實際早已換過）。
build=`opencode-build.TLQba7`（257 項 0 失敗、10 項 opt-in 略過，OpenCodeRuntimeTest 20/20），
backup=`opencode-backup.WhXo1u`，腳本 `deploy/deploy-opencode-20260910.sh`。
部署前後任務雜湊一致、log 0 ERROR；1014 WAITING_QUOTA（重試 2026-09-16T01:11Z）、1015 AWAIT_HUMAN 均未動。

13:43 同日續：VM 已裝 opencode 1.18.19（`~/.local/node/bin/opencode`，Linux 上檔名也是 `.exe`，
`file` 確認為 ELF x86-64）；Linux 上重驗旗標契約與缺訊息行為，與 Windows 完全一致；
狀態目錄 `~/.config/opencode`、`~/.local/share/opencode` 首次執行後自動建立，與 `statePaths()` 相符。
`OPENCODE_BIN` 已寫入 `~/ai-team/env.sh`（權限仍 600，備份 `env.sh.before-opencode.20260910134447`），
`preflight.sh` 已同步為含 opencode 檢查的版本並通過。服務未重啟（PID 仍 75366）——
沒有任何角色用 opencode，這個變數目前不影響任何行為；要讓它進入行程環境需下次重啟。

**還沒做的（要真的用 opencode 之前必須補）**：
  1. **認證**：`opencode providers` 必須本人操作，我不會代做；沒認證前任何一次執行都會失敗；
  2. 沙箱包裝下實跑一次，確認提權釘選真的擋得住寫入 `~/.config/opencode`；
  3. 上述通過前，docs/INSTALL.md §3.1、opencode-runtime-report.md §5、AGENTS.md 的
     「尚未驗證」段落不可拿掉，也不要把正式角色切成 opencode。
本次未動 #1014、未改配置、未代核准或續跑，未 push。以下為先前交接。

2026-09-09 23:42 Claude Code：#1014 QA 失敗根因已修正，**尚未部署**。
15:20 Codex 結束碼 2、事件流 0 bytes、stderr `unexpected argument '-P' found`；
未呼叫模型，未消耗任何 token／額度。實測 codex-cli 0.153.4：`-P` 只有
`codex sandbox` 吃（且必填），`codex exec` 要 `-c default_permissions="<名稱>"`。
已拆 `CodexLocalTestPolicy.sandboxArguments()/execArguments()`，新增
`RuntimeUsageError`（事件流空＋結束碼 2＋用法字樣三者全中），FlowEngine 收到時
還原本輪 visits／transitions／一次性恢復授權並清空 activeNode。
隔離 build=usage-fix-build.Sa7L5Y：231 項 0 失敗，10 項 opt-in 略過；
opt-in 另行實跑全過（CodexSandboxVmTest 4/4 含新 CLI 契約、Preflight 3/3、Linux 3/3）；
check-source 通過。候選 jar SHA256
9d54bb4b18c4efacb7ac0e7dcce63191d97ff1963a9c3bf33a5037cceb52a3d7；
線上仍為 409f6a0a…（PID 47684）。詳 docs/codex-cli-argument-contract.md。
#1014 現況未動：AWAIT_HUMAN、node=verification、activeNode=verification（殘留）、
visits.verification=4、transitions=14、recoveryRevisions 已含 d36d9dbc…、
planApprovals.plan 的 configuration 已是 d36d9dbc…。因 activeNode 仍在，
部署修正版後只要一般「補充 #1014 續跑」即可直接恢復 verification，
不需重做配置遷移或重新核准，也不必改 DB 或調高上限。
下一步：取得使用者部署授權（部署前確認無執行中任務），部署後請使用者在原討論串續跑。
未 push、未部署、未代發 Slack。以下為先前交接。

# 當前工作（Codex 與 Claude Code 共用）

> 換手前務必更新這份檔案。內容只寫「現在正在做什麼、卡在哪」，
> 長期結論寫 `decisions.md`，已完成的寫 `progress.md`。

最後更新：2026-09-08（Codex）

## 最新交接

2026-09-09 22:06 本機 TCP／離線文件副本能力已部署，PID 47684；jar SHA256
409f6a0ad84833d72574d1c7f3251009fa4237d0a4c71dde217f1435e9f34f33。
build=local-test-build.ym6i6i、backup=local-test-backup.roptPp；224 項中 221 通過、3 略過。
活動配置 d36d9dbc93e16c66ef1fed770d06581376adfa8f3d2b915891dbf395682901f7。
9 項真實 SMTP 測試、target/qa-docs 副本 npm 離線安裝／Docusaurus build／llms cmp 通過，
原始工作樹乾淨。採 Codex network proxy 空允許清單＋隔離 namespace 本機 TCP，外連拒絕。
不開放原始 doc-site 寫入；細粒度寫入由外層維持，內層快取權限對齊。
#1014 已在 21:41 停止，仍 AWAIT_HUMAN／triage，verification=3、triage=5、transitions=13。
部署前後全部 task/flow/checkpoint/stage 雜湊一致；未套用配置、未代核准或重跑。
下一步：使用者原串 @PM Agent「補充 #1014 檢視配置更新」後核准差異；再依系統提示
「核准恢復驗收 verification 新配置版本」授權該配置一次 QA（歷史不清零），
重新審閱並核准計畫才會派工。PR 仍另核准。詳 docs/local-test-capabilities.md。
代發 Slack 差異訊息被安全審查阻擋；auth.test workspace 已驗證，但 info/replies missing_scope，
重新審核仍拒絕，尚未發送。不可繞過或宣稱已送達，需使用者明確核准分享或自行下指令。
未 push；下方 sandbox 初始修正／運行中狀態為歷史。

2026-09-09 21:37 追加：Codex 已成功執行 git／rg／Maven／npm，原 bwrap 初始化修正有效。
但真實 QA 揭露下一層權限缺口：GreenMail 127.0.0.1 socket Operation not permitted；
doc-site/node_modules/.bin EROFS，.npm/_logs 不可寫。#1014 最新仍 STAGE_RUNNING，
勿宣稱 QA 已通過。尚未擴大本機 socket／文件產物權限、修改快照或再續跑；需先取得
有範圍的配置調整授權與按新版本核准流程，不能關閉 sandbox。以下 21:36 為已完成初始化修正證據。

2026-09-09 21:36 Codex 雙層沙箱修正已部署，PID 34947，jar SHA256
7e6bc03c833c29e41032ced3d3f2f219008b035bdc2cec9b9ff98c11d8485823。
build=codex-sandbox-build.ZFDWlB、backup=codex-sandbox-backup.SxysRO；220 項中 217 通過、3 略過。
根因為外層 LIMITED 根唯讀＋缺 .agents/.codex，內層 bwrap 無法建立掛載點；已先建空目錄，
保留內外層沙箱與上限，加入無模型派工前預檢。#1014 原 policy 預檢通過、工作樹乾淨。
經使用者授權受控定位 verification 並自動排隊，21:35:50 Codex gpt-5.6-sol 開始 QA；
21:36 已有實際 command_execution exit=0，不再 bwrap 啟動失敗。尚未完整驗收完成。
核准、快照、計畫、commit 與其他任務保留；triage=5 未重設，verification 現 3、transitions=13。
上限仍 verification 3／transitions 24。新版被上限拒絕不再增次數，回報會保留上一原始阻礙。
詳 docs/codex-nested-sandbox.md；未 push。勿在 QA 執行中部署或清理工作區。
以下 21:16 等待人工狀態已成歷史。

2026-09-09 21:16 新診斷：#1014 已於 21:14 回 AWAIT_HUMAN，node=triage。
Codex QA 報告明確承認真人核准有效（SC-23 通過），其餘測試受 bwrap 啟動失敗阻擋：
Can't mkdir .../task-1014/.agents: Read-only file system；尚未定位更深層掛載原因。
退回 triage 時嘗試第 4 次，超出 maxVisits=3；21:15 再續跑一次計數到 5。
目前 visits.triage=5（包含被拒進入，不代表 PM 實跑五次），verification=2，transitions=12/24。
未調高上限、未重跑或修改沙箱。下一步應先修復 Codex 內層沙箱初始化，再受控恢復，
不要求重建任務或重做實作。以下 QA 運行中紀錄已成歷史。

2026-09-09 21:11 核准自動派工已依使用者授權部署。PID 27426，jar SHA256
45dc5e43316f5849b99224f59b4a8ef48c8b6324b9f6b2093d1658406d90ae0b。
build=approval-auto-build.7WpRR5、backup=approval-auto-backup.vxJX5L；212 項中 209 通過、3 略過。
#1014 已由一次性 AI_TEAM_RESUME_APPROVED_TASK=1014 透過中央佇列恢復，21:10:50 STAGE_RUNNING，
21:10:53 Codex gpt-5.6-sol 啟動 verification（PID 27616）。既有核准、計畫、快照與其他任務不變。
核准按鈕／文字指令現在會自動排入 QA，原 Slack 卡片文字亦更新；不需使用者再下續跑。
PR 發布仍需獨立核准，尚未 QA 完成，勿在運行中部署／清理工作區。未 push。
以下「等待人工、核准不派工」敘述為歷史狀態，以本則為準。

2026-09-09 20:08 按鈕與反引號指令辨識修正已部署，PID 23108，jar SHA256
ca5d3a4492fc58580e7b46da618aa754b1cd3c6c38a9ced2b99655a4ca7a36c1。
211 項中 208 通過、3 略過；備份 plan-buttons-backup.D0XSGJ，task/flow/checkpoint/stage 雜湊不變。
#1015 已自行停止至 AWAIT_HUMAN，未強制中止。#1014 19:55 核准保留，尚未續跑。
已由系統 App 補發兩個 actions 按鈕，Slack API ok 並回傳正確 labels/actions；
訊息 ts=1788955686.694309，原 thread=1788874480.503669；真人點擊待實測。
請使用者在原討論串重送續跑指令一次（現在可辨識整段反引號），不需再次核准。
以下部署受阻為歷史狀態；未 push。

2026-09-09 20:06 按鈕工作：使用者要求核准／要求修改按鈕，程式完成並隔離 package 通過，
build=plan-buttons-build.2TyoAq。包含首尾反引號包住「補充 #ID」的解析修正。
#1014 已在 19:55 由 U0BTYUL820H 保存計畫核准，仍 AWAIT_HUMAN（不要再次遷移或清除）。
使用者帶反引號的續跑指令被舊版誤建為唯讀 #1015，20:02 開始 dialogue opus；
因此 deploy-plan-buttons-20260909.sh 被執行中保護擋住，尚未部署，線上仍 PID 16368。
未中止 #1015、未重送 #1014。待沒有執行中任务再部署，或取得使用者中止 #1015 授權。
補發卡片必須使用主 SLACK_BOT_TOKEN（角色 manifest interactivity=false），
產品 payload 已產生於 build/button.json（Flow/Task 需在發送前重讀以防過期）。
詳 docs/plan-approval-buttons.md；不可宣稱 Slack 按鈕已上線。

2026-09-09 19:49 已依使用者授權部署核准閘門／完整計畫呈現／Slack 可讀報告，PID 16368。
建置 approval-build.xcQbUc：208 項中 205 通過、3 略過，含真實沙箱預檢；五個 Socket 啟動。
備份 approval-backup.gfAnl9。#1014 受控定位 verification，AWAIT_HUMAN，QA=Codex
gpt-5.6-sol medium，requirePlanApproval=true、planApprovals={}；不重設 triage 3/3。
保留需求、計畫、實作與 814c9b1/c0f1617/3bcf2b7，工作樹乾淨；未排隊、未代為核准。
新版 QA prompt context 帶系統核准收據，核准後需重新驗收。計畫版本 473251b7…66d5cb。
完整部署／核准指令／回復限制見 docs/deploy-2026-09-09-approval.md。尚未 push。
原 Slack 討論串已收到完整 27 情境及核准指令，30 筆發送皆 API ok；目前等使用者審閱核准。
以下未部署／舊模型／停止狀態均為歷史證據，以此則為準。

2026-09-09 19:41 台北時間 #1014 新受阻診斷（唯讀，未重跑）：服務 PID 7289 正常。
11:16 UTC 補充成功續跑；implementation 新增 c0f1617 文件同步，verification 新增
3bcf2b7 舊版相容測試；814c9b1 保留，HEAD 3bcf2b7，工作樹乾淨。
QA 自陳 26/27 情境通過，SC-23 缺可見人工計畫核准紀錄而 BLOCKED，19:40 回 AWAIT_HUMAN。
舊快照未啟用 requirePlanApproval，實際先執行 QA 才擋核准；QA 仍 claude-code sonnet。
triage visits=3/maxVisits=3，直接再補充將觸發循環上限，勿建議盲目續跑。
新版核准閘門及 Slack 格式仍未部署；需先規劃受控恢復、呈現完整計畫取得核准，
不可依 PM 建議把補核准當成驗收自動 PASS。尚未獨立重跑 QA 或查核完整 CLI 測試輸出。

2026-09-09 Slack 報告可讀性修正已實作：SlackReport 統一呈現配置流程與 legacy 泛用報告，
不再 toPrettyString 貼給使用者；結構資料及流程路由不變。隔離測試 206 項、200 通過、6 略過。
尚未部署／push；線上仍原部署版本，既有 Slack 訊息未修改。

2026-09-09 19:15 台北時間：使用者授權後已用 service.sh start 啟動原部署版本，
PID 7289；沙箱與 java/mvn/node/npm/git 自檢通過，五個角色 Socket Mode 均啟動。
#1014 仍 AWAIT_HUMAN，updated_at 未變，未觀察到先前補充自動送達。
請使用者在原 #1014 討論串重新送出原補充訊息一次（不是編輯舊訊息），尚待實測事件送達。
未部署候選版、未主動重送或重跑、未設定開機自動啟動。以下停止診斷為歷史紀錄。

2026-09-09 19:13 台北時間：使用者反映 Slack 補充 #1014 沒回應，唯讀診斷確認
service.sh status=STOPPED、無 orchestrator JVM；VM 本次開機為 11:07 UTC（19:07 台北）。
system/user unit 名稱與 crontab 查詢未發現 AI Team 自動啟動項目。
PM auth.test 的 user_id=U0C063TTEHW 與使用者 mention 相同；補充語法符合程式。
#1014 仍 AWAIT_HUMAN，updated_at=2026-09-08T18:45:57.609482009Z，沒有新補充執行紀錄。
尚未啟動服務／重送訊息／重跑任務，待使用者授權；以下 PID 100553 為歷史部署紀錄，非目前運行。

2026-09-09 跨專案共用機制已實作、未部署。專案 operations 契約／每階段相同沙箱預檢、
交接工作區與 commit、真人計畫核准收據、版本綁定的能力配置遷移已接入。
Java＋docs 與 Node 範例均宣告 operations、requirePlanApproval=true；舊快照預設不啟用，
#1014 未改動。角色對話原工作區遺失也改成 fail closed。
隔離 contracts-build.RtQKyc/build-final.log：203 項中 200 通過、3 既有 opt-in 略過；
包含 3 項真實 systemd 沙箱預檢測試（工具正常／工具不存在／唯讀 pin 拒絕）。
兩份配置用候選 jar 靜態驗證通過；Node 靜態驗證暫用既有 Java repo 路徑，非 Node E2E。
候選 jar SHA256 f0401151c4e7b2dd16286dbad722ce98fa2740adca8eb142f3ed98eda811e527。
詳 docs/cross-project-execution.md。未 commit/push/部署，線上仍是下方 PID 100553 版本。
新核准指令只記錄不續跑；遷移只允許能力設定，模型／路由／核准政策等變更拒絕。
後續發布需先確認新計畫核准流程；不把通過測試說成真實 Slack 多專案端到端完成。

2026-09-09 07:42 台北時間已修正部署 PM 工作區與文件工具權限，PID 100553。
179 測試通過、3 略過；沙箱／工具鏈含 node/npm 與 Slack 啟動通過。
新團隊 revision=12fe00a6c8236d50092ddf39c8987aaddfe03f72312680becc7c9b250cf71a2f。
#1014 最小遷移 revision=8eb98b02910c72909f7592e4a9926f242e4856665cb0e5cf990f473844c3d02a，
僅補 builder 文件工具、快取、建置說明與更正脈絡；模型仍是舊快照 Claude QA。
原模型、需求、計畫、報告、checkpoint、task 狀態以及其他 flow 已驗證保持。
原 triage activeNode=null，下次原流程會用新 session，不續用錯誤目錄的舊 session。
仍 AWAIT_HUMAN，未自動續跑、未核准 SC-23／PR；使用者須提供計畫核准或既有收據。
備份 triage-fix-backup.gVpYRx，建置 triage-fix-build.LFnvTZ；詳 docs/pm-triage.md。
未 commit/push，未完成真實文件建置與 QA 端到端驗收。

2026-09-09 07:36 台北時間唯讀診斷 #1014：自動續跑於 02:32 成功啟動，
02:43 implementation 回 PARTIAL/BLOCKED（npm 不在工具白名單），02:45 triage
轉 AWAIT_HUMAN。PM 因 triage.workspace=false 被送到主 repo，誤判 commit 消失。
實際 /home/vboxuser/ai-team/workspaces/task-1014 HEAD=814c9b1，24 檔變更且工作樹乾淨。
不得採納 PM 的「產物消失，合併主 repo 或重做」建議。應修正 triage 工作區選擇，
審慎補文件建置工具權限，處理既有快照遷移與原 triage session 錯誤脈絡。
SC-23 要求真人核准，尚未核對 Slack 是否有收據；independentEvidence 不是核准收據。
本輪僅診斷，未改程式／部署／續跑。#1014 不會因 quota_retry 殘留 next_at 再自動跑，
因狀態已非 WAITING_QUOTA。需使用者授權後修正。

2026-09-08 23:33 台北時間已部署 QA 明確模型：spring-starters tester 使用
codex / gpt-5.6-sol / medium；新增角色 reasoningEffort，fresh/resume 均傳參。
配置式 Slack 心跳標示模型與推理強度為要求值，不宣稱供應商實際模型。
PID 94564，revision 00937d722339693ef96a1636a04bcc49637dbfef11be586d75982dc5205b102c。
177 測試中 174 通過、3 略過；VM 最小模型呼叫成功，尚未完整 Slack QA 驗收。
#1014 保留原快照，仍 WAITING_QUOTA，18:32 UTC 續跑排程未變。未 commit/push。
備份 qa-model-backup.58jnRa；建置 qa-model-build.uP2dUG；部署腳本 deploy-qa-model-20260908.sh。

2026-09-08 23:07 台北時間已部署額度自動續跑，PID 90460，#1014 WAITING_QUOTA，
quota_retry.next_at=2026-09-08T18:32:00Z（台北 9/9 02:32），attempts=0。
工作區/session/flow 快照保留；新版支援 session limit 辨識與持久化退避。
最終建置 quota-build.fi1Ckq/build-final.log；170 測試通過、3 略過。
前版完整備份 quota-auto-backup.aviBTA，修正啟動接線前的中間版備份
quota-auto-backup.bYNCWU。到期真實續跑尚待觀察；本輪未 push。

2026-09-08 22:34 台北時間，spring-starters 的 tester 已部署改用 codex，model 空白
（CLI 預設），新 PID 84091。新任務適用，#1014 等既有快照未變。
配置備份 qa-codex-backup.qCPDB3，相關測試 52 項通過。尚未真實 QA Codex 端到端驗收。

Slack 定期回報已於 2026-09-08 22:23 台北時間部署，PID 80214，啟動檢查通過。
備份 progress-backup.oFdYpg；詳見 progress.md 與 docs/slack-progress.md。
尚未執行真實 Slack 五分鐘更新驗收，等待使用者續跑任務。
#1014 為 implementation 的 Claude session limit，但辨識漏掉措辭而停在 AWAIT_HUMAN；
checkpoint 與未提交實作仍保留，額度回報修正尚未實作。下方保留先前交接脈絡。

## 目標

建立 Codex 與 Claude Code 的跨工具交接機制（本檔案所屬的 `.ai/` 架構）。

## 現況

- `AGENTS.md` 為專案規範單一事實來源；`CLAUDE.md` 以 `@AGENTS.md` import 之。
- `.ai/context.md`／`decisions.md`／`progress.md` 建立完成，兩邊 agent 皆需讀寫。
- 專案已 git init 並推上 GitHub（https://github.com/a09090443/AI_team ，private，分支 `main`）。

## 已知阻礙

- 從零重建的乾淨 VM 驗收未完成，詳見 [docs/rebuild-verification.md](../docs/rebuild-verification.md)
  的「尚待完成的驗收」七項。
- source tar 傳至測試 VM 被權限審核拒絕，尚待使用者明確同意。

## 下一步

由使用者決定：是否要把 `.ai/` 的更新義務也寫進 `orchestrator/prompts/`，
讓 orchestrator 派出的下游 agent 也維護目標 repo 的 `.ai/`。

## 2026-09-13 #1014 人工修復後續跑狀態

- 任務為 `AWAIT_HUMAN`，flow.node=`triage`，activeNode=null，沒有 agent 執行。
- 工作區 `/home/vboxuser/ai-team/workspaces/task-1014` 已於 commit `0b6a8b8` 修正
  SC-17／SC-25，完整 Maven reactor 驗證成功，工作區乾淨，尚未 push。
- 當前配置 revision `d36d9dbc...` 的一次性 verification 恢復已用掉；triage visits=5。
  按「繼續任務」仍會撞防循環上限，再送同 revision 的恢復指令也會被拒絕。
- v2 flow 的 `requestTriage` 直接轉 `FlowEngine.resume`，不會走 legacy PM remediation。
  若要保留原任務續跑，應先補一個保留歷史、需負責人重新核准測試計畫、且留下 audit 的
  「人工修復後一次性重驗」正式機制；不要直接改 DB 清掉歷史計數或已用授權。

### 已完成

人工修復重驗正式機制已實作、測試並部署，線上 PID 114379，JAR SHA256
`d65a4982ea41820a0bfd1b5e353c247d6b8905dd8b572a6a21fb39a966b67f38`。
#1014 尚未授權或派工。下一步由原驗收負責人在原 Slack 討論串送出：
`補充 #1014 核准人工修復重驗 verification 0b6a8b8a1b33829830990cfdeeec310b97679855`
收到新計畫卡後再核准；核准計畫即自動排入 verification，不必再按「繼續任務」。

### 14:22 最新狀態

#1014 已完成上述重驗，verification 與系統獨立 Maven 建置通過，但 review 確認實作／測試
仍有驗收缺口，現為 AWAIT_HUMAN、node=triage、activeNode=null，沒有 agent 執行。
需另做新 commit 修正整體逾時，以及補齊附件／HTML、實際發送失敗例外鏈、sender timeout
屬性與 legacy Spring 設定載入測試；完成後才可使用新 commit 再走人工修復重驗。

### 15:28 #1014 第二次 reviewer 缺口與暫停點

- 原 QA plan 本身即有語意覆蓋缺口：SC-18 只規畫不可達／黑洞連線，未規畫 SMTP 多階段
  各自未逾時但累積超過 overall-timeout；REQ-003 也未逐一要求五個寄信方法的全失敗驗收。
- 這應由 triage route=plan 回到規畫節點，而非只在 verification 補證據；現有機械 gate 只能檢查
  scenario ID／欄位／證據結構，不能保證 acceptance 的語意組合完整。
- 使用者追問此是否應在專案規畫階段發現，因此目前先停止後續 commit。
- #1014 工作區目前有未提交變更：MailServiceImpl、MailServiceFailoverTest、
  MailServiceTimeoutAndLimitsTest。已加入整體 Future 截止、截止時 socket 關閉、多階段延遲 SMTP
  測試，以及 attached／batch 全失敗測試；針對性 21 項測試通過，但尚未跑本版完整 reactor
  verify、尚未 commit。應先由使用者決定只完成 #1014，或同時修正 AI Team 規畫流程。

### 14:43 最新狀態

#1014 reviewer 列出的程式與測試缺口已修正，commit 為
`6c80ca1d0d867e2067127089a97a47c4a48b27b5`，工作區乾淨。相關 18 項測試與完整八模組
離線 `mvn -B -o verify` 均通過；無公開 API／設定文件變更。任務本身仍為 AWAIT_HUMAN，
尚未再次授權或執行 agent。下一步由原驗收負責人在 Slack 原討論串送出：
`補充 #1014 核准人工修復重驗 verification 6c80ca1d0d867e2067127089a97a47c4a48b27b5`
收到新計畫卡後再核准。
2026-09-13 23:42 台北時間：已系統性修正「Agent 規畫只覆蓋 requirement ID、漏掉驗收維度」問題並部署。
新任務的 REQUIREMENTS 會把驗收拆成 `acceptance_items`（唯一 ID／criterion／dimension）；
TEST_PLAN 必須以 `acceptance_ids` 全覆蓋，且每個情境提供會通過弱測試的 `counterexample`。
Traceability 會拒絕漏項、跨需求錯接與無反例計畫；intake／plan／verify／review prompt 均同步要求
量詞展開與測試辨識力。舊任務仍用快照舊契約，不會自動失效或改寫。
隔離 `check-source.sh` 通過；完整 Maven clean package 272 項、0 失敗、10 項 opt-in 略過；
兩份 team 靜態驗證通過。線上 JAR SHA256=`06771046ccef8831922d1c42d44b2cad30cad5dc2ab9fe26788ef49ea61c021d`，
PID=133264，backup=`/home/vboxuser/ai-team/planning-gate-backup.sU4ndM`。部署前後 DB 與 #1014
未提交工作區雜湊一致，未核准、未排隊、未續跑。新規則只自動套用於部署後新建任務；
#1014 若要套用，需正式配置遷移／重新規畫或另建任務，不能竄改歷史計畫。

2026-09-14 08:21 台北時間：使用者在 Slack 輸入「請完成並提交 #1014 目前修改」時，
訊息被當成同討論串的新任務要求，因此只收到防平行任務提示，未觸發 #1014。Codex 已直接
接手工作區未提交修正，補上 overall-timeout=0、SocketFactory 四種連線入口、呼叫端中斷與
異常 timeout 屬性的回歸測試。針對性測試 25/25；完整八模組 `mvn -B -o verify` BUILD SUCCESS；
MailServiceImpl JaCoCo 指令 982/1016（約 96.7%）、分支 46/58（約 79.3%）。已提交
`5f1b2ef931f15408b132903fd4c67d767147e7d4`，工作區乾淨，未 push、未變更任務狀態。
下一步由原負責人在 Slack 原討論串送出：
`補充 #1014 核准人工修復重驗 verification 5f1b2ef931f15408b132903fd4c67d767147e7d4`。
計畫卡出現後先要求修改，補 SMTP 多階段累積逾時及 attached／batch 全失敗情境；新計畫
核准後才自動執行 QA。

2026-09-14 21:16 台北時間：#1014 最近兩次 plan 續跑不是使用者操作或額度問題，而是
Codex API 在 agent 啟動前拒絕舊快照 schema 的 `uniqueItems`。相容修正已部署：PID 146446，
JAR `a9637b143d0c0e438d70e98a66732577db0c86ca1a3c25299d3fb6e5a38bf334`，備份
`codex-schema-backup.h8h2sL`。275 項測試 0 失敗，兩份 team 配置驗證通過；DB、核准、報告與
工作區部署前後不變。#1014 現為 AWAIT_HUMAN、node=plan、activeNode=plan，既有恢復授權與
人工修復 commit `5f1b2ef...` 保留。使用者現在只需在原討論串再按一次「繼續任務」；
不需再次核准人工修復，也不要建立新任務。續跑後應恢復同一 plan session，完成新計畫後
仍需真人審閱／核准才會進 QA。

2026-09-14 最新交接：已在本機完成泛用自動受阻交接與驗收操作事前綁定，主要檔案為
`AutomaticHandoff.java`、`FlowEngine.java`、`Traceability.java`、兩份 team 配置及
`docs/adr-automatic-blocker-routing.md`。每任務 6 次證據式交接，依配置路由，不新增權限且
保留所有真人閘門；舊 blocked 快照可相容重建。新計畫必填 `operation_ids`，系統在 QA 前
先執行獨立驗證。針對測試 59/59 與配置靜態驗證通過。尚未完成 Linux 完整驗證或部署：
安全審查要求使用者明確同意後，才能將目前專案原始碼、測試與配置（不含 env／登入憑證）
複製到本機 VM `127.0.0.1:2222`。取得同意後應先在隔離目錄執行 `check-source.sh` 與完整
Maven package，全部通過才部署；部署前後保持 DB 與 #1014 狀態不變。現行線上仍是 PID
146446／JAR `a9637b...`；#1014 最後唯讀確認為 AWAIT_HUMAN／triage、activeNode=null、
transitions=21，沒有 agent 執行。部署後才請使用者在原串送 `補充 #1014 續跑`。

2026-09-14 22:42 台北時間：上述版本已完成 Linux 驗證並部署。完整 Maven package 281 項
0 失敗、10 略過；兩份 team 配置通過。線上 PID=156768、JAR `f7902aa8...`，備份
`automatic-handoff-backup.Np8AQd`；DB 與 #1014 工作區部署前後一致，log 無 ERROR。
#1014 仍 AWAIT_HUMAN／triage、activeNode=null、transitions=21、automaticHandoffs=0，無 agent。
使用者下一步只需在原 Slack 討論串送 `補充 #1014 續跑`。系統將以舊 lastBlockReason 重建
受阻交接並執行 triage；不要建立新任務、不要再次核准人工修復，也不要手動改 DB。

2026-09-14 23:24 台北時間：dirty verification 泛用路由修正已部署。線上 PID=165331、JAR
`b54bda10...`、備份 `dirty-route-backup.qprexg`；完整 Linux package 283 項 0 失敗、10 略過，
check-source 與兩份配置通過。#1014 的唯一未提交 MailServiceFailoverTest diff 已確認部署前後一致；
任務仍 AWAIT_HUMAN／verification、activeNode=verification、transitions=25、automaticHandoffs=4，
未自動續跑。下一次 `補充 #1014 續跑` 會先核對 dirty diff，再直接路由 implementation builder
提交，之後共用同一受阻事件額度返回 verification 與 review。禁止手動 commit／改 DB／放寬 QA
的 `.git`；若 diff 出現產品或保護路徑，新版應停給真人。
2026-09-15 07:52 Codex：已修正自動交接舊制逐跳計數未遷移問題，尚未部署。
#1014 現存 6 個 key 實際為 2 個舊證據各跨 verification／triage 加上 2 個新版 incident；
通用正規化後為 4 個獨立事件。只在 key 全可辨識且舊計數與 key 數一致時降低，否則 fail closed。
基本上限仍 6；新增綁配置／blocker／HEAD、不可重放的人工延長 token，每次 +2、最多額外 +6，
不重設 visits／transitions／核准；配置遷移會讓舊配置額外額度失效但保留防重放歷史。
交接／流程／配置遷移針對測試 40/40、隔離 Linux 完整 package 289 項 0 失敗
（10 opt-in 略過）、check-source 通過。尚未部署、未改 DB、未續跑任務。隔離建置目錄
`/home/vboxuser/ai-team/handoff-budget-build.q3hFuo`，候選 JAR SHA256
`310e578cb0ca938dab466d169659215af08dd04a30c3744574c0e730f35171dd`。

2026-09-15 08:54：上述版本已依使用者明確要求部署。線上 PID=3420，JAR SHA256
`310e578cb0ca938dab466d169659215af08dd04a30c3744574c0e730f35171dd`，備份
`/home/vboxuser/ai-team/handoff-budget-backup.BmGdTK`。部署前後主要 DB 表與 #1014 工作區雜湊
一致；沙箱／工具鏈自檢及五角色 Socket Mode 通過，log 無 ERROR。#1014 保持
AWAIT_HUMAN／triage、activeNode=null、transitions=29、automaticHandoffs=6，未自動續跑。
下次原串 `補充 #1014 續跑` 才會惰性正規化為 4 個事件、寫 RECOVERY audit 並依既有 blocker
路由；不要手動改 DB。若後續真的用完事件額度，停止訊息會提供一次性延長 token。

2026-09-15 Claude Code：受阻發現傳遞連續性修正已完成本機實作與隔離 Linux 驗證，尚未部署。
主要檔案：新增 `FlowContext.java`、`ToolTrust.java`、`docs/adr-blocked-finding-continuity.md`；
修改 `FlowRun.java`（新增 `blockedFindings`）、`FlowEngine.java`、`ConfiguredExecutor.java`、
兩份 team 配置的 coordinator 工具、`teams/prompts/triage.md`、`docs/data-model.md`、
`docs/configurable-team.md`，以及 `FlowEngineTest`／`ConfiguredExecutorTest` 與兩支新測試。
隔離目錄 `/home/vboxuser/ai-team/blocked-findings-build.aPdGyd`：check-source exit 0、
完整 package 308 項 0 失敗（10 opt-in 略過）、兩份 team 靜態驗證通過。候選 JAR SHA256
`1952e0ce3e4be29e4a8dad3bd7e543bf43b189e9766f226329195f00eb6b9d7a`。

下一步需使用者決定是否部署。部署前提醒兩點：
1. #1014 使用凍結快照 `d36d9db...`，不會自動取得新的 coordinator 工具；但 `blockedFindings`
   是執行期欄位，新版可正常運作。#1014 目前的 `blockedFindings` 為空（舊資料沒有這個欄位），
   review 在 09-14 的 HIGH 發現無法自動回填，需要在續跑前以人工方式重新交給責任節點，
   或讓 review 重跑一次產生新的受阻發現。單純再送「補充 #1014 續跑」仍會得到相同結果，
   而且會再消耗一個事件額度（目前 5／6）。
2. 線上仍為 PID 3420、JAR `310e578c...`；本輪未改線上服務、DB 或 #1014 工作區，全程唯讀查證。

2026-09-15 補充：部署候選已在 VM 隔離目錄 `/home/vboxuser/ai-team/blocked-findings-build.aPdGyd`
建置完成（check-source exit 0、完整 package 315 項 0 失敗／10 opt-in 略過、兩份 team 靜態驗證通過），
候選 JAR SHA256 `ceb72a5736c32ca065ddae635e05c2342b80f05c601bb4aada3efd966e33ac37`。
受控部署腳本 `deploy/deploy-blocked-finding-continuity-20260915.sh` 已就緒並複製到
`/home/vboxuser/ai-team/`。Claude Code 的執行環境阻擋 production deploy 動作，
因此**實際部署需由使用者自行執行**：

    ssh -p 2222 vboxuser@127.0.0.1 'bash /home/vboxuser/ai-team/deploy-blocked-finding-continuity-20260915.sh /home/vboxuser/ai-team/blocked-findings-build.aPdGyd'

腳本會一併更新 `/home/vboxuser/ai-team/teams/`（角色工具白名單在配置裡，不在 JAR）。
已確認候選與線上 teams 的差異只有 coordinator 的五條唯讀 git 工具與 triage.md 的三行補充。
線上目前仍為 PID 3420、JAR `310e578c...`；#1014 AWAIT_HUMAN／triage、automaticHandoffs=5、
工作區乾淨且 HEAD=c962a33。

2026-09-15 再補：使用者指出停止訊息只說「請補充具體答案」，沒有列出可用指令，
是 #1014 卡住三天的第二個原因。已新增 `HumanOptions`，依流程狀態列出真的可用的選項，
並明講「只寫續跑不會改變上一輪判斷依據」與「停止不等於完成」。
候選版本重建為 315 項測試、JAR SHA256
`ceb72a5736c32ca065ddae635e05c2342b80f05c601bb4aada3efd966e33ac37`，
部署腳本已同步更新並複製到 VM。仍待使用者自行執行部署指令。
