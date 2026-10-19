# 服務建置與開發分流

**2026-10-19 規格修正**：日常部署改為 Agent VM 經 SSH 公鑰直連目標 VM，Windows 不再作為必要執行節點。來源已修正，網路／公鑰初始化與線上切換尚未完成，詳見 [SSH 直連部署](direct-ssh-deployment.md)。以下 Windows broker 的執行方式屬既有線上版本與歷史證據，不是新規格。

bestpartner 的 `intake` 可選 `route=service`，交給既有 Infrastructure Agent：

```text
需求整理 → service（建置、啟動、可用性檢查）→ 完成
              ↓ 受阻
          service_triage → service 修正並重驗／真人補必要資訊
```

這條路線適用部署既有程式、SIT 與測試環境；不先撰寫 QA 測試計畫，不無條件跑完整前後端回歸。
新增功能或修改產品程式仍走 `design → plan → implementation → verification → review → approval`。
服務路線只准寫管理者列出的環境設定與手冊目錄，事後 `auditScope` 核對全部變更；不能以它跳過產品驗收。
可提交的設定由系統核對後代為提交；`.env`、真實 `.env.*`、`.secrets`、`.ssh`、`.pem`／`.key` 禁止在此路線修改。
`.env.*.example`／`.env.*.template` 可作不含真實憑證的範本。

## 部署目標與操作

`project.serviceTarget` 由管理者設定，模型不能自行增加主機。例子只示意欄位，操作須另外宣告與授權：

```json
{
  "serviceTarget": {
    "name": "sit-host",
    "checks": {
      "start": "sit-start",
      "health": "sit-health",
      "login": "sit-login",
      "persistence": "sit-persistence"
    }
  }
}
```

`checks` 必須包含 `start`、`health`，最多 12 項。每個值必須是配置給 SERVICE 節點的 `project.operations` ID。
操作的 argv、工具、腳本雜湊、沙箱寫入權限仍需核對；設定主機名稱本身不會安裝 Docker、建立 SSH 連線或新增系統權限。
目標操作須明確綁定該主機，管理者提供安全的啟動、健康檢查及必要的登入／資料保留檢查；機密透過目標環境供應，不寫進 JSON。

bestpartner 來源配置現在指定 `bestpartner-dev`，檢查為 `prepare`、`start`、`health`。
四個操作由 `/home/agent/ai-team-bestpartner/teams/skills/service-build/scripts/client.py` 接受固定參數，另外提供 `diagnose`。
這個絕對路徑是此實例的部署設定；其他安裝位置必須調整 operations 與角色工具白名單。
模型只在 `docs/docker/sit/.agent-service/` 寫入請求並等待收據，不直接取得管理者權限。

### 舊版 Windows broker 執行方式（切換前的歷史說明）

`service-build` 的唯一來源在 `.agents/skills/service-build/`，同步腳本產生 Claude 與 Slack runtime 套件。
`nodes.service.skills` 與 `service_triage.skills` 指定 SKILL.md 及 bestpartner 參考；載入時固定內容與版本，派工時注入模型提示，並非僅新增一份模型看不到的文件。
`orchestrator/teams/skills/service-build/scripts/broker.py` 由具目標連線權限的管理者啟動，經配置的 SSH 入口讀取 Agent VM 佇列。
主機設定以 `transport=virtualbox|ssh` 選擇連線模式；本次 dev 用 VirtualBox，其他 SSH 主機不需要 VirtualBox。
它以唯讀 SQLite 核對任務正在 SERVICE 節點執行且目標與主機設定相符；VirtualBox 模式再透過 Guest Additions 登入固定 UUID 的 dev VM。
不依賴 dev 的 SSH；首次 `prepare` 自行安裝／啟動 SSH、Docker 與 Compose，不要求使用者先安装它們。
登入密碼由宿主機受保護檔案供應，不從 Slack 紀錄或模型參數擷取。

管理者首次設定步驟（憑證只在受保護位置，不進版控）：

1. 複製 `assets/host.example.json` 到私人設定檔，核對 dev VM UUID、帳號、密碼檔路徑及專用 SIT VM 宣告。
2. 檢視指定任務 HEAD 的 `bestpartner-service`、`docs/docker/sit`、`docs/sql`，執行 `python scripts/enroll.py <私人設定檔> <任務 ID>` 固定逐檔雜湊。
3. SSH 模式另設 target_ssh／target_port／target_identity_file／target_known_hosts_file，採既有公鑰登入及 sudo -n；嚴格核對已登錄主機金鑰。
4. 啟動 `python scripts/broker.py <私人設定檔>`，同一目標只執行一個程序；程序必須持續運作。`--once` 可供診斷。
5. 新任務採新配置；舊任務不會自動換快照，透過 Slack 承接後才使用新操作。

`tasks` 空陣列表示自動發現符合 SERVICE／目標條件的執行中任務，也可指定 ID 限縮。
Agent 停止或完成後不接受新的部署操作；已經執行中的系統操作仍可能在有界逾時前完成。
更換主機時建立新的主機設定與獨立狀態目錄，更新身分及登入引用即可沿用 skill；不自動搬遷舊機資料。`client_path` 指向 Agent 端安裝的 skill client，無須改 skill 本文。

每個請求保存收據，重送同一 ID 不重做；發現中斷的執行紀錄時保留現場，不能盲目重跑。
接單後的長操作不會因輪詢心跳暫停被判離線；客體單次操作上限 1200 秒、client 等待 1500 秒，整個 SERVICE 節點仍受 1800 秒限制。

`prepare` 支援程式中的 Ubuntu jammy／noble／resolute 清單，使用 [Docker 官方 apt 安裝來源](https://docs.docker.com/engine/install/ubuntu/)。
現有衝突容器套件、Compose 低於 2.30 會停止並說明，不擅自移除既有 runtime。
這是實作支援範圍，尚不代表三種版本都已實測。

`start` 只接受已提交且與管理者 manifest 完全一致的來源，用 Gradle/JDK 21 容器建置後啟動 Compose。
`.env.sit` 與資料庫密碼由工具在 dev 的 `/opt/ai-team-bestpartner-sit/secrets` 產生並保存，沒有輸出到對話。
每次啟動沿用資料卷與密碼；不得以清除 volume 解決錯誤。來源變更須重新審核 manifest，未提交修正不會部署。
`health` 核對兩個容器、PostgreSQL 健康狀態與 `/view/chat` HTTP 200；**尚未提供使用者登入及資料寫入／重啟保留驗證**。
`diagnose` 只回傳容器狀態、退出碼、OOM 與 health，不輸出含環境變數的完整 inspect 或原始 logs。

首次客體登入需要管理者提供可用憑證一次；缺少憑證、broker 未啟動或來源雜湊不同時，如實回報原因。
這些操作尚未通過真實 dev VM 端對端驗證，不能以程式測試或配置存在宣稱服務已建置。

## 完成條件與證據限制

`Gate.SERVICE` 要求：

- `service_status=RUNNING`、`target` 與配置名稱完全一致，`product_code_changed=false`。
- `checks` 完整涵蓋配置的檢查名稱與 operation_id，不重複、不捏造其他操作。
- 每項 `status=PASS`、整數 `exit_code=0`、非空 `evidence`（本次 log 或工具呼叫 ID）。
- 每項列 `acceptance_ids`；全部檢查合計涵蓋需求基準的所有原子驗收項目。
- `uncertainties` 為空；缺項、失敗或僅產出檔案均不能完成。

這是角色報告與配置的機械核對，**服務檢查證據仍由 agent 回報，並非系統另行登入主機做第二次驗證**。
不產生開發流程的 IndependentVerifier 收據、不開 PR；`COMPLETED` 只表示這次服務交付報告通過契約，
不是日後持續健康保證。正式環境的監控、告警、回滾操作需另依目標主機設定。

## 自動修復與舊任務

格式、設定、啟動錯誤先在核准範圍自修並重驗；受阻時交 service_triage 定位，再派回 service。
沿用既有事件／節點／整輪預算，不無限重試。需要新權限、機密、主機或修改產品程式時，清楚說出缺口後等待人處理。
尚未建立需求基準時，沒有待確認問題、但 ID／欄位／原子驗收結構不合格的草稿會自動交回 intake 修正；
不能擅改已保存的需求基準。文字語意矛盾主要由提示與角色查核，程式不宣稱能完全辨識。

新路線只影響採用新快照的任務。#1034 的既有需求排除實際部署，不能只按續跑就變成服務建置。
部署新版後，需以新任務承接成果並明確要求實際啟動；原需求仍完整保留在歷史。
已含 SERVICE 路線的任務可用受控配置遷移核准 `serviceTarget`／operations 能力更新；節點或提示不同仍不得原地遷移。

## 部署與驗證紀錄（2026-10-10）

已部署 bestpartner；JAR SHA256 `b820051bb2c1966da6142227639b06f028d58b9222aced294079d2c3aca5df1d`。
Linux 隔離完整測試 602 項、0 失敗、0 錯誤、11 略過，服務啟動與沙箱自檢通過，任務資料與工作區部署前後一致。
服務分支的成功、失敗返工、缺目標、產品越界等以模擬 runtime 測試；指定掃描在 #1034 工作區唯讀實測新漂移 0。
此部署版本當時尚未配置實際服務主機；後續來源已補執行工具，仍未做真正遠端建置或可用性端對端驗證。

## dev VM 連線查核（2026-10-10）

VirtualBox 的 Agent 與 dev 是獨立 NAT VM，兩者客體都可能顯示 `10.0.2.15`；不能從 Agent 直接把這個位址視為 dev。
已核對宿主機既有 SSH 轉送：Agent 為 `127.0.0.1:2223 → 22`，dev 為 `127.0.0.1:2224 → 22`。
本次 dev 入口在 SSH banner／金鑰交換前中斷，尚未進入帳號驗證。後續新增 Guest Control prepare，讓工具自行安裝／啟動 SSH。
Agent 到 dev 的可用路徑、登入及服務操作仍待實測，不能把宿主機可用的 localhost 直接交給 Agent 使用。


## skill 版本部署實測（2026-10-10 23:46 台灣時間）

已部署 bestpartner：JAR `8c8538eb74bb2c8527c6cb1143ec65d9330945b0d6b17a3a8c15703be5947159`、配置 `2280bc066d87ac6e271ffaefeb08f0ed415a2e69e01abbef307e265e110616d3`、PID 78337。
備份 `/home/agent/ai-team-bestpartner/orchestrator-backup.E0Eb7Y`；隔離來源 `/home/agent/service-skill-build-20261010`。
Linux Maven 完整 604 項、0 失敗、0 錯誤、11 略過；Python 工具 15 項、來源檢查、skill 格式檢查、文件 build 通過。
Spring 啟動、沙箱工具鏈（含 python3）自檢與 Infrastructure Slack Socket Mode 啟動通過；所有任務資料及工作區部署前後一致。
#1035 仍 AWAIT_HUMAN，尚未承接新 skill 配置。尚缺宿主機可安全使用的 dev 登入憑證，broker 未啟動；沒有執行 dev 環境安裝或系統建置，不能寫成 Slack 端對端完成。


2026-10-10 #1036 真實 Slack 執行發現：PM 回傳 service 與正確 infrastructure handoff，因固定角色分支原本強制 null 而停止。已修正為接受同一既定角色的有效交接，仍拒絕其他角色、額外權限欄位與未配置轉移；不擴張派工權限。

固定角色派工修正已於 2026-10-10 23:54 部署：JAR `b1917796408c3448cd64eab0bc6f4a9bd9aa84a63b6b90108d5040027ac8eada`，配置仍 2280bc06…，PID 84418，備份 `orchestrator-backup.TIT6T4`。Linux 605 項、0 失敗、0 錯誤、11 略過；工具 15 項通過。任務資料及工作區前後一致。
#1036 以 Slack 補充按鈕續跑，已實際進入 service／Infrastructure；工作區已產生 prepare 請求。這證明 agent 有呼叫工具，不代表 dev 已安裝完成；宿主機 broker／登入仍未就緒。

23:59 #1036 實測結果：Infrastructure 的兩次 prepare 等待都回 `SERVICE_BROKER_OFFLINE`（exit 75），自動分流後為 AWAIT_HUMAN。原請求保留，需完成宿主 broker 的首次登入設定並啟動後，使用「恢復後原任務重試」。此處 broker 尚未配置啟動，不能僅描述為原有程序當機；尚無 dev 安裝／啟動證據。

### Windows 首次登入入口（2026-10-11）

`service-build/scripts/login.ps1 -Config <私人設定檔>` 提供遮蔽密碼輸入，依已配置的 password_file 自動保存；使用者不需找檔案路徑。私人目錄停用 ACL 繼承，只允許目前 Windows 帳號存取。檔案是 ACL 保護的明文，不是加密儲存；不得使用共用目錄。拒絕覆寫既有憑證、連結目錄及空密碼。保存後仍須驗證 VM 身分、登錄來源與啟動 broker，不能將保存成功視為部署成功。

2026-10-11 修正：Windows PowerShell 視窗曾因 Security 模組無法自動載入而在輸入前失敗。login.ps1 改用 .NET ACL API；Windows PowerShell 5.1 與 PowerShell 7.6.5 均以假密碼驗證權限、不洩漏輸出及不覆寫。這不代表目標 VM 登入或服務建置已成功。

2026-10-11 主機接通實測：VirtualBox dev VM 的帳號為 gary（dev 是 hostname），Guest Control id／hostname 已核實。原 VBoxManage mkdir --mode 0700 產生實際1270權限，導致 copyto 拒絕；broker 改透過客體 /usr/bin/mkdir -m 0700，實測權限700及工具檔傳輸通過。#1036 已實際呼叫 prepare／diagnose，但修復前失敗，尚不代表服務可用。

2026-10-11 後續修復：Windows 的登入檔 copyto 必須使用 Path.resolve 轉成原生路徑，原設定字串直接傳入會回 VERR_PATH_NOT_FOUND；實機傳輸／刪除已通過，傳輸失敗也納入 finally 清理。使用者明確授權目前 dev 的 sudo 與登入資料暫存後刪除。新 VM 無人工介入建置是後續驗收目標，目前尚未驗收完成。

### 2026-10-11 真實建置進度與資源修正

#1036 的 Infrastructure 已自行完成 prepare（SSH、Docker、Compose），並送出 start。首次 Gradle 容器執行中，dev 隨後無法開啟 Guest Control 或 SSH 操作工作階段；SSH驗證可完成，正常ACPI關機無回應。尚未取得啟動成功證據，無法確定失去回應的根因。宿主資源設定發現舊工具容器固定4GB而VM也只有4GB，已修正為依 MemTotal 保留1024MiB、上限4096MiB，JVM另外限額、最多2個worker、Kotlin同程序編譯；小於2500MiB拒絕建置。快取保存在目標專用 gradle-cache，重試不清除下載。17項測試通過，但修正後實機尚待驗證。強制重啟遭自動安全審查拒絕，正在等待使用者明確授權；不得把此狀態寫成系統完成或新VM自主驗收通過。

### 2026-10-11 重啟後確認與防重疊修復

使用者明確同意後已重置 dev；SSH與指令恢復。前次開機核心journal確認global OOM曾殺死兩個Java建置程序；未看到本次查詢的EXT4錯誤。僅限制每個容器記憶體不夠，逾時殘留與後續重試可重疊耗用資源。start新增Linux flock互斥；Gradle容器使用固定名字與專用label，拒絕清理非本工具容器，內部900秒逾時早於通道1200秒，finally清理已核對label的建置容器，保留資料卷、來源與快取。退出碼／逾時／OOM寫入build-result.json供diagnose，完整build.log只保留在客體私人release目錄，不直接回傳機密環境或原始日誌。Windows與Linux19項測試通過，Linux互斥另行實測通過；修復後服務完整建置仍待驗證。

### 2026-10-11 服務啟動成功、agent 等待額度

Infrastructure 實際送出的 start `d55653d5fec64cfaae0421371a58b077` 已完成：Gradle BUILD SUCCESSFUL 12m28s，build-result退出碼0、未逾時、未OOM；工具收據包含START_PASS及HEALTH_PASS，PostgreSQL/後端運行且/view/chat HTTP200。release為 `7f1bc385909e3147-6c585d4d`，來源archive SHA256 `7f1bc385909e3147d424f8e0667c7039bbbe13322b461791c37a3b0f5e22b4d2`。宿主機追加仅127.0.0.1:8088到dev:80的轉送，另測HTTP200，可在宿主開啟 `http://127.0.0.1:8088/view/chat`。

模型於11:36先遇額度停止；建置工具稍後完成，不能寫成模型已讀取結果或任務結案。#1036仍WAITING_QUOTA；既有quota_retry排2026-10-17 22:29台北時間自動嘗試（供應商22:27），恢復後需先領取成功收據再做獨立health，無需重建。broker仍需宿主機持續運作，尚未註冊開機服務；此輪新增輪詢失敗3至60秒退避，避免暫時傳輸錯誤直接退出。

實測發現前版GRADLE_USER_HOME環境變數不足以使映像使用指定cache，目標快取目錄仍空；現已改獨立/build-cache掛載及明確--gradle-user-home，需在下一輪建置實測效益，不能說此次已靠持久快取完成。Windows20項測試與check-source通過。登入、資料寫入/重啟持久化、獨立health操作與新VM無人工介入驗收尚未完成；本次確定的是agent送出工具的建置/啟動與工具內健康檢查成功。


## 部署歷史與恢復入口（2026-10-19，已部署 bestpartner）

#1046 只取得本任務脈絡與目標名稱，未取得 #1036 的歷史成功與宿主啟動入口，
所以錯把「本次未執行」描述成服務從未部署，並只要求維運者恢復工具。

`TaskStore.serviceHistory` 讀取同實例資料庫、同頻道、同目標的最近 12 筆 SERVICE 階段報告，排除本任務與分流敘述。
`ConfiguredExecutor` 在有 serviceTarget 的正式節點注入任務編號、時間、角色回報的狀態與檢查結束碼；
這些是歷史角色報告，不代表目前健康或本次檢查通過，也不能當新主機操作授權。
歷史查無資料不等於從未部署。沒有正式階段報告的舊工具收據，可由管理者登錄成帶來源的歷史摘要。

另外讀取實例目錄 `$AI_TEAM_HOME/service-context/<target>.json`，這是管理者維運說明，不是模型生成的操作設定。
允許欄位為 target、host、program、config_file、start_command、notes、history（at/task/outcome/evidence）。
檔案上限 32 KiB，歷史最多 5 筆、文字欄位最多 1500 字元；目標不符、名稱非英數底線連字號或資料損毀時明示缺漏。
所有欄位必須是不含 token、密碼及登入檔內容的資訊；未知欄位不注入。系統只呈現說明，不執行 start_command。
無此檔仍可執行服務任務，但需如實說明恢復入口尚未配置，不可猜指令。

bestpartner 的範本為 `deploy/service-context/bestpartner-dev.example.json`，包含已核對的 #1036 歷史收據索引及 Windows PowerShell 啟動方式。
管理者核對環境路徑後將其放到 bestpartner 實例的 `service-context/bestpartner-dev.json`。
這不會自動啟動 broker、部署產品或將 #1046 改成成功；恢復工具後仍須續跑原任務取得本次 prepare/start/health 結果。
新 JAR 會在每次執行時讀取此說明與資料庫，既有等待任務續跑亦適用，不改原快照／授權或完成閘門。
