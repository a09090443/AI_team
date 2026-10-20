# Agent VM 以 SSH 直連部署

## 規格與目前狀態

使用者指定日常路徑為 **Slack → Agent VM → SSH 公鑰認證 → dev VM**，不依賴 Windows broker 或 Guest Control。
規格單一來源在 `.agents/skills/service-build/SKILL.md` 及 `references/host-setup.md`，同步到 Claude 與 Slack runtime 套件。
2026-10-19 已啟用 Agent VM 的固定 SSH 部署執行器；Windows 不需執行 broker。收尾時 dev VM 曾無回應，使用者核准強制重啟後已恢復並重新驗證 HTTP200（詳見下方異常紀錄）。
兩台 VM 以共享 NAT Network `ai-team-direct`（172.30.46.0/24）互通：Agent `172.30.46.10`、dev `172.30.46.20`。
原 Windows 管理入口 127.0.0.1:2223／2224 與產品 127.0.0.1:8088 保留；這些入口不參與 Agent 日常部署。
維護時三個實例均無執行中任務，先備份資料庫、優雅停止服務與關機，再切換網路；所有任務資料保持一致。
專用 Ed25519 私鑰只留 Agent VM，dev 登錄 restrict／forced-command 公鑰與限定 sudoers；主機公鑰由可信 VM 管理介面取得並固定指紋。

`ai-team-ssh-deploy.service` 為 active／enabled，Agent linger=yes；2026-10-20 使用者重啟後已確認新執行器自動 active/enabled；三個 orchestrator 仍依既有規則以 service.sh 手動恢復。

## 執行方式與權限

角色仍只呼叫固定 `client.py prepare|start|health|diagnose`，寫入原任務工作區的請求。
`ssh_executor.py` 是 Agent VM 的 systemd 使用者服務：新請求僅接受目標相符、正在執行的 SERVICE 節點。2026-10-20 來源新增獨立 journal，已受理作業可在任務暫停後繼續查詢結果；此恢復版本已部署 bestpartner，詳見下節。
它在同一台 Agent VM 執行固定 `ssh_client.py`，直接連目標 VM，不需要 Windows 程式輪詢。
這是為保留角色沙箱與私鑰管理界線；未把角色改成可任意 SSH 或執行 root shell。

SSH 固定 BatchMode、公鑰認證、獨立 known_hosts 與嚴格指紋檢查；沒有 Windows 或密碼 fallback。
目標公鑰帶 `restrict,command=...`；sudoers 只允許 root-owned `ai-team-ssh-service` 固定入口。
角色只使用 prepare/start/health/diagnose；2026-10-20 來源的受限 SSH 協定另提供 result/status/cancel，供固定執行器管理精確 job ID。目標驗證 target、操作、請求 ID 與 start 來源 SHA256，不接受模型自訂 shell。
部署來源為已授權任務的已提交HEAD。目標核對傳輸SHA256與壓縮檔結構，不再使用逐版source-manifest.json清單，也不另要求管理者核准正常版本更新。

請求 ID、target、action、來源 digest 必須與收據完全相符；成功還須有對應完成標記與整數結束碼。
相同 ID 已完成就取回收據，不重建；不確定中斷保留 running 標記與原請求。
舊 Windows worker 已接單但沒有收據時不能直接換通道重做，須核對舊現場。
歷史成功只作背景，本次需實際啟動與健康檢查；回報列程式／服務名稱、版本、目標、網址和限制。

## 一次性啟用

1. 等現有任務停止執行後安排網路維護。建立 VM 間的 Internal／Host-only 或共享 NAT Network，確認位址不同且互通；保留原有管理入口與產品資料。
2. Agent VM 保留專用私鑰（600），只把公鑰交目標管理者。從 dev VM 本機取得 SSH 主機公鑰並核對指紋，再登錄 Agent VM 的專用 known_hosts。
3. 將工具與Agent公鑰傳到 dev，以管理者權限執行 `install-ssh-target.sh <既有登入帳號> <Agent公鑰檔> bestpartner-dev`。不新增任意 sudo 或 root shell。
4. 管理者依 `assets/direct-ssh.example.json` 在 Agent VM 放 `~/.config/ai-team/ssh-targets/bestpartner-dev.json`，填真實互通位址、私鑰與 known_hosts 引用。
5. 依 `assets/ai-team-ssh-deploy.service` 安裝使用者服務，核對實例路徑後 `systemctl --user daemon-reload`、`systemctl --user enable --now ai-team-ssh-deploy.service`。沿用 INSTALL 的 linger 前置條件。
6. 驗證公鑰登入、錯誤指紋拒絕、固定入口拒絕任意命令、傳輸毀損與不合法壓縮檔拒絕，以及重啟後執行器自動可用。此時 Windows broker 應維持停止。
7. 更新服務提示、skill 與非機密恢復說明後，在 Slack 以原任務／受控配置更新或承接完成 prepare、start、health。舊快照若仍要求 Windows，不直接改資料庫或偽造核准。

只有兩台 VM 的上述實測成功才可標為已啟用；Python 單元測試不是網路、公鑰或部署端對端證據。
不在此流程中自動清空資料卷、更換資料庫密碼；正常版本更新不需維護來源清單。


## 2026-10-20 自主部署修正

使用者明確取消額外逐版清單關卡。SSH目標身分與固定部署授權仍有效，正常更新不需要第二次人工核准。安裝工具不再要求manifest，既有target.json的source_manifest欄位與舊檔案不再讀取，保留歷史備份不會阻擋新來源。
agent使用已提交且乾淨的工作區自行prepare、start、health與修復；只在缺少真正的主機權限、登入或任務範圍時請使用者處理，不要求維護清單。SHA256用於傳輸完整性與收據綁定，保留固定請求ID與避免重複啟動的機制。
取消清單時曾建議 #1048 在原討論串重試；之後已取得 start 成功收據，此建議已由下方日期更新取代。現在不得重送已成功 start；須對帳原結果並補缺少的 health 與交付驗證，不需新建任務或偽造快照。

### 上線與驗證

- 2026-10-20 已更新 dev 的固定入口及target.json，取消source_manifest讀取；目標備份 `/var/lib/ai-team-service/tool-backup-20261020-134631`。舊清單只留歷史，不參與執行。
- Agent 固定工具、service/service-triage提示、skill及最新service-context已更新，bestpartner重載新配置 `d1334fe3…`；備份 `~/ai-team-bestpartner/autonomous-deploy-backup-20261020-134803`。完整六張任務表與所有工作區HEAD/status/diff前後一致，沒有改任務快照或核准。
- 這次只重載部署工具與提示，產品來源和驗證指令未變；僅該次啟動以 `AI_TEAM_BASELINE_VERIFY=off` 避免重跑無關產品基準，未改env.sh/team.env或預設值。
- Windows/Linux各36項Python測試、Java75項相關測試、check-source與docs build通過。用#1048真實封裝 `a0c53284…` 驗證來源格式可接受而不需清單；這不是本次產品start成功證據。
- 服務啟動與沙箱自檢通過、ERROR0，SSH執行器active且NRestarts0；新health收據 `ab1c03e97bcf48018a563e70d94e2a11` exit0，PostgreSQL/後端正常，Windows8088 HTTP200。
- #1048仍AWAIT_HUMAN，未自動發Slack訊息或續跑、未重部署產品；可由原討論串補充「部署清單限制已移除，請重新執行部署與健康檢查」續跑，無需管理者更新清單或另開新任務。Slack新一輪start到結案仍待實測。

## #1048 部署成功但流程未結案的歷史查核（2026-10-20）

以下描述修正前的歷史行為與證據；新版本部署與#1048完成結果見下方「持久長作業恢復」。

以下時間皆為台灣時間。清單取消後，21:53:40進入service；第一次建置用完guest.py的900秒限制，22:09:29收到失敗收據，diagnose確認timed_out=true、oom_killed=false。agent自行診斷並再次start。
外層Codex在22:23:43因同一service階段累計30分鐘到期而被調度器判定失敗，22:24:01轉AWAIT_HUMAN；但遠端第二次start已於22:23:55成功，收據 `bda3dcac47be4a3c8e6905b83814b1db` 含START_PASS及HEALTH_PASS，來源a0c53284…，release `a0c53284074fff3b-973bf406`。
22:24:08後續health請求 `a62bbfe966f4472e878748d6424cf404` 留在工作區，沒有accepted或結果。不是已確認執行器離線：systemd顯示active/NRestarts0；ssh_executor只掃STAGE_RUNNING，client.authorized也拒絕AWAIT_HUMAN，因此不會接單。client僅以任務佇列heartbeat推斷離線，會混淆「任務已暫停」與「執行器不可用」。
模型/工具事件檔在外層逾時之後仍有收據與health操作，表示目前停止外層程序不等於所有沙箱/遠端工作已收斂；需查核systemd單位與子程序生命週期，不能把模型逾時當成部署失敗。
任務目前node/activeNode=service，transitions8/24、service repeats1/3，並非已確認的節點預算用盡。最新停止由RuntimeExecutionException走錯誤說明並等待人工，未自動讀取晚到的成功收據。已知舊lastBlockReason仍含清單問題，不能拿它取代最新時間線。
另外發現ssh_executor.poll以with sqlite3.connect處理交易但未明確close；現場455個SQLite檔案描述符，10/19日誌曾Too many open files後systemd自動重啟。這是另一長期可靠性缺陷，非本次health未接單的必要原因。

當時提出的改善方向（其後已完成部分來源實作，部署與端對端狀態見下節）：將部署作業持久化並與模型回合分開；按job ID讀進度/收據與checkpoint接續；模型逾時先對帳成功/執行中/已失敗，不直接等人；僅在真正取消時停止作業。獨立服務heartbeat、任務暫停/未授權/排隊等結構化狀態取代泛稱離線；診斷綁job/release且提供遮蔽機密後的可操作錯誤；修正SQLite close及systemd子程序生命週期。#1048應接回既有start成功收據、只做缺少的health與交付驗證，不重新建置或偽造成功。
本次只分析、補文件，未修改程式、任務、收據或重新部署；現行網頁HTTP200不代替尚未完成的任務驗收。

## 持久長作業恢復（2026-10-20 已部署 bestpartner）

本節描述已部署 bestpartner 的 Java／Python 契約。#1048已經正式流程恢復並完成；Linux精確cgroup清理、獨立SSH health與原收據對帳已實測，所有runtime／故障組合尚未全面演練。

### 模型回合與部署作業分開

1. client 在工作區 docs/docker/sit/.agent-service/ 保存請求；固定執行器另在實例 home 的 operation-jobs/&lt;task&gt;/&lt;id&gt;.json 寫入持久 journal。後者位於模型工作區外，由執行器保存，不能以模型自陳代替。
2. client 預設最多等待 30 秒；未有終態結果就回 OPERATION_PENDING（通常工具 exit 0），保留原請求 ID。模型應立即以 BLOCKED 交回，說明系統仍在執行；不要長輪詢、從 prepare 重排或新建相同 start。
3. FlowEngine 讀取綁定 task、node、目前 HEAD 的 journal。等待原 job 時沒有模型程序，但仍保留中央執行槽，其他同實例工作繼續排隊；完成或到 journal deadline 後，帶檢查點續同一 activeNode，不新增節點造訪、重跑或 transition 計數。
4. QUEUED/RUNNING/UNCERTAIN 都視為尚待對帳；到期不宣稱成功，也不自動重送 start。恢復以結果指紋限次，時間戳更新不產生新恢復機會；失敗／未知仍須診斷或沿既有受阻路由處理。

模型逾時須為 RuntimeTimedOut、phase=MODEL 且 cleanup.status=CONFIRMED，FlowEngine 才啟動逾時對帳。清理只停止本次模型 sandbox unit 的整個 cgroup，保留 stdout／session，不會停止 Agent 獨立 executor 或遠端部署 job。

### 遠端受理與可信檢查點

目標 root 固定入口把請求與來源保存於 /var/lib/ai-team-service/，以獨立 systemd system unit ai-team-service-job-&lt;id&gt;.service 執行 worker，結果原子寫入 &lt;id&gt;.json；SSH 斷線不等於 worker 失敗。固定執行器以 result/status 查原 ID，結果須核對 id、target、action、source SHA256、整數 exit code 與完成標記。只有目標明確回 UNKNOWN、授權仍有效且來源一致，才允許用同一 ID 補送；未確認的傳輸中斷不能換新 ID 重建。

只有 journal 中 verified=true、SUCCEEDED、exit 0，且 task、activeNode、target、目前 HEAD 一致的 prepare/start 可以重用；HEAD 改變不沿用舊成功。health 必須重新核實，並綁定同 HEAD／target 已驗證 start 的 release，收據附 start_id、start_source_sha256。成功 start 不等於完整交付。

executor 的獨立 operation-executor-heartbeat 不再依工作區佇列心跳判斷離線；未接單時區分 TASK_NOT_ACTIVE 與 SERVICE_EXECUTOR_OFFLINE。SQLite 使用 contextlib.closing 關閉連線，修正舊版只結束交易而未 close 的缺口。

### 重啟、指定舊任務與取消

- 服務重啟時，原本執行中或 RESUME_QUEUED、node 等於 activeNode 且有同 HEAD 有效 journal 的配置任務，經中央佇列排回原 task。其他中斷任務維持原本等待人工規則；不是重啟整台 VM 就會自動啟動 orchestrator。
- 舊 AWAIT_HUMAN 不會全面自動恢復。先由管理者使用固定 executor 的 --reconcile 查目標真實成功收據，參數順序為 ssh_executor.py &lt;實例home&gt; &lt;直連設定&gt; --reconcile &lt;task&gt; &lt;id&gt; &lt;HEAD&gt; &lt;SHA256&gt;。核對當前 HEAD 與封裝 digest 後建立 verified journal；不手寫成功收據或改資料庫。
- 再只在該次啟動設定 AI_TEAM_RESUME_OPERATION_TASK=&lt;單一task編號&gt;。入口要求配置流程、非角色對話、AWAIT_HUMAN、node=activeNode、乾淨工作區及同來源有效 journal，通過才排 RESUME_QUEUED。用後移除變數，不寫成每次啟動的常駐開關；它不核准新計畫或 PR。
- 真人明確「中斷這一輪」才產生本輪 operation-cancellations/&lt;task&gt;/&lt;id&gt;.json 取消要求；模型 timeout、OPERATION_PENDING、SSH 斷線不代表取消。executor 亦對 ABORTED／FAILED 終止任務收斂未完成作業。取消只作用於精確 job unit 與該 job 的 build，不清資料卷；無法確認停止則保留 UNCERTAIN／CANCEL_UNCONFIRMED，不偽造失敗或覆寫已成功結果。

### #1048 恢復限制（2026-10-20 補充）

歷史 start bda3dcac47be4a3c8e6905b83814b1db 已成功，來源 a0c53284074fff3b35e97d6b7444744a2b02bf2a5b8ba2a837e31e1be7057dd8，release a0c53284074fff3b-973bf406。恢復前核對原工作區 HEAD 72f694ba483a9f686453804572965c34345b39ec 與目標收據，沿用已成功結果，只補缺少的 health／交付驗證。不得因模型逾時、舊清單拒絕紀錄或未接單 health 而重建此 release。後續已依此方式恢復#1048並正式完成，證據如下。

### 2026-10-20 部署與#1048正式完成

- 核心來源60cff26，bestpartner JAR SHA256 `ad96be1c56180af5bce7505c63d7cfae8f918889a8e4a5e74e3130545b72b82b`；備份 `~/ai-team-bestpartner/orchestrator-backup.yPNuTY`。受控切換的六張任務表與全部工作區HEAD/status/diff前後一致；另外兩個實例資料未變。只更新bestpartner，未推送或合併。
- Linux完整verify 719項、零失敗／錯誤、11略過；真實cgroup測試清除owned detached子程序且另一unit持續。Python最終78項與check-source、文件build通過。Windows完整有已記錄的平台限制，沒有宣稱全過。
- dev固定工具備份 `/var/lib/ai-team-service/tool-backup-operation-20261020`；獨立health job `b1c9c59d2061485e94be6c30698e11ab` 提交後退出SSH，另一次連線仍取得同release成功結果。管理暫存登入檔已清除，不改產品資料。
- 以--reconcile從目標查證同HEAD／archive的原prepare與start；再以一次性AI_TEAM_RESUME_OPERATION_TASK=1048經正式中央佇列恢復，沒有手改DB／快照／報告或重設次數。原health `a62bbfe966f4472e878748d6424cf404` 成功，綁定start `bda3dcac47be4a3c8e6905b83814b1db` 與source SHA；沒有新增start或重建產品。
- 真實整合找到歷史serviceTarget=null使executor重啟，已修null／非物件設定相容與單筆壞job隔離，新增回歸測試後上線。暫時離線由PM自行分流回service。報告將未要求登入／持久化演練列為uncertainties後，PM再自行要求依原驗收範圍修正，未要求真人授權。
- 最終#1048=COMPLETED、node=end、activeNode=null；transitions12、service visits5／repeats1，沒有重設計次。prepare/start/health均PASS。保留驗證邊界：登入、資料寫入、重啟後持久性未額外演練，沒有以刪資料或降低原驗收條件解決。
- PostgreSQL／後端正常，同release `/view/chat` HTTP200；Windows實測 `http://127.0.0.1:8088/view/chat` HTTP200。其他任務與實例資料比對未變；executor觀測FD4、SQLite FD0、獨立heartbeat新鮮。完整證據在 `~/operation-maintenance-20261020`、`~/operation-deploy.log` 與隔離建置log。僅該次啟動使用AI_TEAM_BASELINE_VERIFY=off避免無關產品基準，未改env.sh/team.env。

## 歷史紀錄（以下逐版清單流程已於2026-10-20取消）

## 2026-10-19 實測與承接

- 實作來源 `f79f142`，Linux Maven 687 項、零失敗／錯誤、11 略過；Python 30 項與 check-source 通過。
- Agent → dev 真實 SSH：health 收據 `7a4cc6a9ad234113a98ea482d8b81e6c`，PostgreSQL／後端執行中，`/view/chat` HTTP 200；diagnose `1592ae09b17a460ea402fda65ee44930` 成功。
- 任意 `id` 命令被固定入口拒絕、未登錄主機指紋被 SSH 拒絕；合法 archive 的未核准檔案被 manifest 拒絕（`341d12cd99be4627bdf4b1fa1a0a1d75`），未啟動產品；重取健康請求得到完全相同收據。
- 網路維護前備份 `~/direct-ssh-maintenance-20261019-131926`；三實例 task／flow_run／task_checkpoint／direct_request／stage_result／quota_retry 逐列一致。
- 本輪未重建／重部署產品，也未送 Slack 訊息或續跑任務；不是新的 Slack 部署端對端成功證據。
- #1046 保存舊版 skill 快照，變更 skill 需新任務承接，不直接改快照。請在 bestpartner 頻道 @PM：
  「承接 #1046，在既有 bestpartner-dev 部署測試程式。依目前 SSH 直連規則，先查部署歷史與目前健康，再執行核准的 prepare、start、health；不要清空資料卷。完成請回報版本、服務名稱與可用網址。若來源與既有核准 manifest 不符，先列出差異並停止。」
- Agent 維運：`systemctl --user status ai-team-ssh-deploy.service`；需要恢復才用 `systemctl --user restart ai-team-ssh-deploy.service`，不需開啟 Windows 程式。


### 收尾時 dev VM 異常

13:28:55 UTC 左右，VirtualBox `VBox.log` 記錄 `Guest seems to be unresponsive`；之後 Agent ping／HTTP 與 Guest Control 均無回應，Windows 8088 也逾時。此前直連 health／diagnose 與拒絕測試均已完成成功。
VM 狀態仍 running，不把先前 HTTP200 當成目前健康，也不認定為單一 port-forward 問題。已送 ACPI 正常關機，未強制 reset／poweroff、未清除資料卷。使用者明確核准後已對 dev 執行 reset；恢復後新健康收據 `d443693b701e4555b8dd084178e39628` 成功，PostgreSQL/後端正常，Agent SSH 與 Windows http://127.0.0.1:8088/view/chat 均 HTTP200。上次核心日誌有 RCU/clock/watchdog 與既有 VirtualBox 顯示驅動警告，未見足以確認的當機根因，不能宣稱已根治；未清空資料卷。
bestpartner orchestrator 已安全升級：JAR `45879bfd7672ecb88d63798fb02293235d57b1337bd986c4508bdbbd9edda7ee`，PID8844，備份 `~/ai-team-bestpartner/orchestrator-backup.II8thl`；部署腳本完整任務／工作區快照比對通過。Agent 執行器 active／enabled，restart 驗證成功，Windows broker 程序數量0。


### #1047 來源封裝受阻（2026-10-19）

#1047 已通過 prepare 與 diagnose，SSH 執行器正常；start 三次失敗後停 AWAIT_HUMAN。
工作區基準 dev `2216d6d` 沒有版控的 `docs/docker/sit`，固定 `git archive HEAD bestpartner-service docs/docker/sit docs/sql` 因 pathspec 不存在而失敗，尚未傳送來源到 dev。
先前執行器只產生「無法取得SSH收據」的通用訊息，誤導為網路／公鑰問題。
已在 start 封裝前加入版控路徑預檢：缺件回 `SOURCE_MISSING_PATHS`，未提交變更回 `SOURCE_WORKSPACE_DIRTY`，超量回 `SOURCE_TOO_LARGE`，持久化具體失敗收據，不嘗試 SSH。
缺件需將經審閱的部署檔正式加入核准來源並提交，再比對目標的來源 manifest；不能跳過缺少路徑、直接拿舊容器當本次成功，或自動覆寫 manifest。#1047 未自動續跑、未改任務狀態／歷史收據。

診斷修正已更新至 bestpartner 的固定 ssh_client.py（備份 `ssh-preflight-backup-20261019-140920`），執行器 active；Windows／Linux 各33項Python測試、check-source與文件建置通過。#1047 真實工作區唯讀重現缺件，未續跑任務或重新部署產品。


### #1048 目標來源核准受阻（2026-10-20）

最新 bestpartner 任務 #1048 停在 AWAIT_HUMAN。工作區 HEAD `72f694b` 乾淨，已含 `docs/docker/sit/docker-compose.yml` 與 `00-schema.sql`；#1047 的缺檔問題已不再是本次卡點。
本次封裝SHA256為 `a0c53284074fff3b35e97d6b7444744a2b02bf2a5b8ba2a837e31e1be7057dd8`。prepare、health與diagnose均exit0，start兩次回 `Source approval or target setup check failed; data retained`（f66d63fe…、03a0f745…）。
可確認已到目標核准／設定檢查，不能視為新版本部署成功；現有health HTTP200只代表既有服務可用。PM判斷舊manifest尚未更新，但本次僅唯讀查任務／工作區／收據，未以root檢查或更新目標manifest，因此尚未獨立排除其他目標setup檢查失敗。
下一步由管理者核對目標manifest與已審閱來源差異，再決定更新核准清單；未更新前重按部署會再被拒絕。不得自動覆寫清單、改任務狀態或用健康收據替代start成功。


## 2026-10-20 結案後網址與追問問題（修正前實測）

#1048最終agent摘要提供 `http://172.30.46.20/`，但該位址屬VM共享NAT網段，Windows實測8秒逾時。管理者實際NAT規則是127.0.0.1:8088→172.30.46.20:80；Windows `http://127.0.0.1:8088/view/chat` HTTP200，根路徑 `http://127.0.0.1:8088/` HTTP404。Agent VM內172.30.46.20根路徑也404、/view/chat為200。因此有「存取者網路位置」與「應用路徑」兩項缺漏，不是服務全面停止。

目前ServiceTarget只含name/checks，ServiceDelivery完成閘門不檢查摘要內網址，也沒有內部健康位址與使用者入口的結構化區分。固定health證據只能證明目標端/view/chat正常，不能證明摘要提供的根網址可用。此為已知交付缺口，尚未修程式或重部署；不能再把Agent VM內可達當Windows使用者可達。

23:27:56協調服務最後重載時，啟動purgeWorkspaces依既有COMPLETED清理政策刪除task-1048；這是協調服務重載觸發的來源工作區清理，不是dev產品/資料卷消失。23:29:08使用者追問「網址無法打開」建立dialogue #1049、parentId1048。23:29:12在ConfiguredExecutor.workingDirectory→verifiedWorkspace拋「原工作區遺失或路徑不符，禁止退回主repository核實」，Infrastructure runtime尚未執行；之後只呼叫haiku錯誤說明器並轉AWAIT_HUMAN。

結案清理與結案後唯讀對話對工作區的要求不相容；此外對話未注入ServiceDeploymentContext或可信部署job證據，且只准Read/Grep/Glob，即使能啟動也不能實測HTTP。後續修正須保留可查部署資訊、明列使用者入口並驗證、讓正常來源清理不阻斷純狀態/網址追問；不能偽稱還在原工作區查碼，也不能以重新部署產品解決此對話錯誤。本輪僅分析與補文件，未恢復工作區/續跑1049/發送Slack/修改程式或線上配置。


### 新來源修正（待本輪部署與#1049實測）

ServiceSupport與固定support.py新增工作區外的service-support診斷紀錄、登錄入口探測、同一SSH固定通道的health/diagnose/repair。root repair只以原active版本的Compose執行應用up --no-deps --no-build並重驗，不重新打包或建置、不操作資料庫/資料卷。只有固定登錄明確啟用自動修復時執行；不能因根路徑404而重啟健康服務。執行中/未知結果續原ID，單次追問最多兩次repair。

service-context access_endpoints分開Windows完整入口與Agent VM探測位址。ServiceDelivery額外核對已登錄入口本次probe與摘要url，避免再交付172.30.46.20/；對話注入同一資訊並檢查回覆。完成後來源清理改用明示的證據資料夾對話，不以主repo冒充來源。正式原問題恢復入口為一次性AI_TEAM_RESUME_DIALOGUE_TASK，不修改DB或舊快照。其他主機應登錄自己的入口/修復設定，不寫死bestpartner資訊到共用Java程式。新來源測試與實測結果稍後追加，不把原問題紀錄改寫成修復成功。
