# Agent VM 以 SSH 直連部署

## 規格

日常部署路徑為 **Slack → Agent VM → SSH 公鑰認證 → 目標 VM**，不依賴 Windows broker 或 Guest Control。
規格單一來源在 `.agents/skills/service-build/SKILL.md` 及 `references/host-setup.md`，同步到 Claude 與 Slack runtime 套件。
Agent VM 與目標 VM 需要直接互通（例如 VirtualBox 共享 NAT Network、Internal 或 Host-only 網路）；原本的管理入口與產品入口可保留，
但不參與 Agent 日常部署。專用 Ed25519 私鑰只留 Agent VM，目標登錄 restrict／forced-command 公鑰與限定 sudoers；
主機公鑰由可信 VM 管理介面取得並固定指紋。

`ai-team-ssh-deploy.service` 是 Agent VM 的 systemd 使用者服務（需 linger=yes），VM 重啟後自動 active；
orchestrator 實例仍依既有規則以 `service.sh` 啟動。

## 執行方式與權限

角色仍只呼叫固定 `client.py prepare|start|health|diagnose`，寫入原任務工作區的請求。
`ssh_executor.py` 只接受目標相符、正在執行的 SERVICE 節點的新請求；另有獨立 journal，已受理的作業在任務暫停後仍可查詢結果（見下方「持久長作業恢復」）。
它在同一台 Agent VM 執行固定 `ssh_client.py`，直接連目標 VM，不需要 Windows 程式輪詢。
這是為保留角色沙箱與私鑰管理界線；未把角色改成可任意 SSH 或執行 root shell。

SSH 固定 BatchMode、公鑰認證、獨立 known_hosts 與嚴格指紋檢查；沒有 Windows 或密碼 fallback。
目標公鑰帶 `restrict,command=...`；sudoers 只允許 root-owned `ai-team-ssh-service` 固定入口。
角色只使用 prepare/start/health/diagnose；受限 SSH 協定另提供 result/status/cancel，供固定執行器管理精確 job ID。目標驗證 target、操作、請求 ID 與 start 來源 SHA256，不接受模型自訂 shell。
部署來源為已授權任務的已提交 HEAD。目標核對傳輸 SHA256 與壓縮檔結構，不使用逐版來源清單，也不另要求管理者核准正常版本更新。
agent 使用已提交且乾淨的工作區自行 prepare、start、health 與修復；只在缺少真正的主機權限、登入或任務範圍時請使用者處理。

請求 ID、target、action、來源 digest 必須與收據完全相符；成功還須有對應完成標記與整數結束碼。
相同 ID 已完成就取回收據，不重建；不確定中斷保留 running 標記與原請求。
舊 Windows worker 已接單但沒有收據時不能直接換通道重做，須核對舊現場。
歷史成功只作背景，本次需實際啟動與健康檢查；回報列程式／服務名稱、版本、目標、網址和限制。

start 封裝前會預檢版控路徑：缺件回 `SOURCE_MISSING_PATHS`，未提交變更回 `SOURCE_WORKSPACE_DIRTY`，超量回 `SOURCE_TOO_LARGE`，
持久化具體失敗收據且不嘗試 SSH，避免把來源缺件誤報成網路或公鑰問題。缺件時須把經審閱的部署檔加入來源並提交，不能跳過缺少路徑。

## 一次性啟用

1. 等現有任務停止執行後安排網路維護。建立 VM 間的 Internal／Host-only 或共享 NAT Network，確認位址不同且互通；保留原有管理入口與產品資料。
2. Agent VM 保留專用私鑰（600），只把公鑰交目標管理者。從目標 VM 本機取得 SSH 主機公鑰並核對指紋，再登錄 Agent VM 的專用 known_hosts。
3. 將工具與 Agent 公鑰傳到目標，以管理者權限執行 `install-ssh-target.sh <既有登入帳號> <Agent公鑰檔> <target名稱>`。不新增任意 sudo 或 root shell。
4. 管理者依 `assets/direct-ssh.example.json` 在 Agent VM 放 `~/.config/ai-team/ssh-targets/<target名稱>.json`，填真實互通位址、私鑰與 known_hosts 引用。
5. 依 `assets/ai-team-ssh-deploy.service` 安裝使用者服務，核對實例路徑後 `systemctl --user daemon-reload`、`systemctl --user enable --now ai-team-ssh-deploy.service`。沿用 INSTALL 的 linger 前置條件。
6. 驗證公鑰登入、錯誤指紋拒絕、固定入口拒絕任意命令、傳輸毀損與不合法壓縮檔拒絕，以及重啟後執行器自動可用。此時 Windows broker 應維持停止。
7. 更新服務提示、skill 與非機密恢復說明後，在 Slack 以原任務／受控配置更新或承接完成 prepare、start、health。舊快照若仍要求 Windows，不直接改資料庫或偽造核准。

只有兩台 VM 的上述實測成功才可標為已啟用；Python 單元測試不是網路、公鑰或部署端對端證據。
不在此流程中自動清空資料卷、更換資料庫密碼。
日常維運：`systemctl --user status ai-team-ssh-deploy.service`；需要恢復才 `systemctl --user restart ai-team-ssh-deploy.service`。

## 持久長作業恢復

Linux 精確 cgroup 清理、獨立 SSH health 與原收據對帳已實測；所有 runtime／故障組合尚未全面演練。

### 模型回合與部署作業分開

1. client 在工作區的服務部署目錄（例如 `docs/docker/sit/.agent-service/`）保存請求；固定執行器另在實例 home 的 operation-jobs/&lt;task&gt;/&lt;id&gt;.json 寫入持久 journal。後者位於模型工作區外，由執行器保存，不能以模型自陳代替。
2. client 預設最多等待 30 秒；未有終態結果就回 OPERATION_PENDING（通常工具 exit 0），保留原請求 ID。模型應立即以 BLOCKED 交回，說明系統仍在執行；不要長輪詢、從 prepare 重排或新建相同 start。
3. FlowEngine 讀取綁定 task、node、目前 HEAD 的 journal。等待原 job 時沒有模型程序，但仍保留中央執行槽，其他同實例工作繼續排隊；完成或到 journal deadline 後，帶檢查點續同一 activeNode，不新增節點造訪、重跑或 transition 計數。
4. QUEUED/RUNNING/UNCERTAIN 都視為尚待對帳；到期不宣稱成功，也不自動重送 start。恢復以結果指紋限次，時間戳更新不產生新恢復機會；失敗／未知仍須診斷或沿既有受阻路由處理。

模型逾時須為 RuntimeTimedOut、phase=MODEL 且 cleanup.status=CONFIRMED，FlowEngine 才啟動逾時對帳。清理只停止本次模型 sandbox unit 的整個 cgroup，保留 stdout／session，不會停止 Agent 獨立 executor 或遠端部署 job。
模型逾時不等於部署失敗：外層模型程序停止後，遠端作業可能仍會成功完成，必須以 job ID 對帳。

### 遠端受理與可信檢查點

目標 root 固定入口把請求與來源保存於 /var/lib/ai-team-service/，以獨立 systemd system unit ai-team-service-job-&lt;id&gt;.service 執行 worker，結果原子寫入 &lt;id&gt;.json；SSH 斷線不等於 worker 失敗。固定執行器以 result/status 查原 ID，結果須核對 id、target、action、source SHA256、整數 exit code 與完成標記。只有目標明確回 UNKNOWN、授權仍有效且來源一致，才允許用同一 ID 補送；未確認的傳輸中斷不能換新 ID 重建。

只有 journal 中 verified=true、SUCCEEDED、exit 0，且 task、activeNode、target、目前 HEAD 一致的 prepare/start 可以重用；HEAD 改變不沿用舊成功。health 必須重新核實，並綁定同 HEAD／target 已驗證 start 的 release，收據附 start_id、start_source_sha256。成功 start 不等於完整交付。

executor 的獨立 operation-executor-heartbeat 不依工作區佇列心跳判斷離線；未接單時區分 TASK_NOT_ACTIVE 與 SERVICE_EXECUTOR_OFFLINE。SQLite 使用 contextlib.closing 關閉連線。

### 重啟、指定舊任務與取消

- 服務重啟時，原本執行中或 RESUME_QUEUED、node 等於 activeNode 且有同 HEAD 有效 journal 的配置任務，經中央佇列排回原 task。其他中斷任務維持原本等待人工規則；不是重啟整台 VM 就會自動啟動 orchestrator。
- 舊 AWAIT_HUMAN 不會全面自動恢復。先由管理者使用固定 executor 的 --reconcile 查目標真實成功收據，參數順序為 ssh_executor.py &lt;實例home&gt; &lt;直連設定&gt; --reconcile &lt;task&gt; &lt;id&gt; &lt;HEAD&gt; &lt;SHA256&gt;。核對當前 HEAD 與封裝 digest 後建立 verified journal；不手寫成功收據或改資料庫。
- 再只在該次啟動設定 AI_TEAM_RESUME_OPERATION_TASK=&lt;單一task編號&gt;。入口要求配置流程、非角色對話、AWAIT_HUMAN、node=activeNode、乾淨工作區及同來源有效 journal，通過才排 RESUME_QUEUED。用後移除變數，不寫成每次啟動的常駐開關；它不核准新計畫或 PR。已成功的 start 不得因模型逾時或未接單的 health 而重建，只補缺少的 health 與交付驗證。
- 真人明確「中斷這一輪」才產生本輪 operation-cancellations/&lt;task&gt;/&lt;id&gt;.json 取消要求；模型 timeout、OPERATION_PENDING、SSH 斷線不代表取消。executor 亦對 ABORTED／FAILED 終止任務收斂未完成作業。取消只作用於精確 job unit 與該 job 的 build，不清資料卷；無法確認停止則保留 UNCERTAIN／CANCEL_UNCONFIRMED，不偽造失敗或覆寫已成功結果。

## 服務追問與使用者入口

使用者取得的網址必須是**從使用者所在位置**可開啟的完整入口（含應用路徑）。VM 內網位址或根路徑在使用者電腦上可能逾時或 404，
不能把 Agent VM 內可達當成使用者可達。

- `service-context` 的 `access_endpoints` 分開使用者完整入口（`url`、`audience`）與 Agent VM 探測位址（`probe_url`）。
  ServiceDelivery 完成閘門核對已登錄入口本次 probe 與摘要中的 url；角色對話注入同一資訊並檢查回覆。
- `ServiceSupport` 與固定 `support.py` 在工作區外保存 service-support 診斷紀錄，經同一 SSH 固定通道執行 health／diagnose／repair。
  repair 只以原 active 版本的 Compose 執行 `up --no-deps --no-build` 並重驗，不重新打包或建置、不操作資料庫／資料卷；
  只有登錄明確啟用自動修復（`auto_repair_existing_release=true`）才執行，不會因根路徑 404 重啟健康服務；單次追問最多兩次 repair。
- 任務結案後來源工作區可能已被清理；結案後的追問改用明示的證據資料夾對話，不以主 repo 冒充來源。
  正式的原問題恢復入口為一次性 `AI_TEAM_RESUME_DIALOGUE_TASK`，不修改 DB 或舊快照。
- 回覆不得給出不可執行的指令（例如要求對已結案任務「執行」重派）；缺真實錯誤資訊時只詢問該資訊。
- 其他主機應登錄自己的入口與修復設定，不寫死特定主機資訊到共用 Java 程式。
