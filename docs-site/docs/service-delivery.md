# 服務建置與開發分流

日常部署由 Agent VM 經 SSH 公鑰直連目標 VM（見 [SSH 直連部署](direct-ssh-deployment.md)），Windows 不是必要執行節點；
已授權測試環境的正常版本更新不需要逐版人工核准。舊版 Windows broker／Guest Control 執行方式已被取代，不再說明。

配置了 `service` 節點的團隊，`intake` 可選 `route=service`，交給 Infrastructure Agent：

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

使用內附 `service-build` skill 時，檢查為 `prepare`、`start`、`health`，四個操作由實例安裝目錄下的
`teams/skills/service-build/scripts/client.py` 接受固定參數，另外提供 `diagnose`。
operations 與角色工具白名單裡的 client 路徑是各實例的部署設定，安裝位置不同時要調整。
模型只在工作區的服務部署目錄寫入請求並等待收據，不直接取得管理者權限。

`service-build` 的唯一來源在 `.agents/skills/service-build/`，同步腳本產生 Claude 與 Slack runtime 套件。
`nodes.service.skills` 與 `service_triage.skills` 指定 SKILL.md 及參考檔；載入時固定內容與版本，派工時注入模型提示。

### 內附 skill 的操作行為

- `prepare` 支援程式中的 Ubuntu jammy／noble／resolute 清單，使用 [Docker 官方 apt 安裝來源](https://docs.docker.com/engine/install/ubuntu/)。
  現有衝突容器套件、Compose 低於 2.30 會停止並說明，不擅自移除既有 runtime。這是實作支援範圍，不代表三種版本都已實測。
- `start` 只接受已提交的來源，在容器內建置後啟動 Compose。環境檔與資料庫密碼由工具在目標產生並保存，不輸出到對話。
  每次啟動沿用資料卷與密碼；不得以清除 volume 解決錯誤，未提交修正不會部署。
  建置以 Linux flock 互斥，建置容器使用固定名字與專用 label，逾時早於通道上限並在 finally 清理已核對 label 的容器，保留資料卷、來源與快取；
  記憶體依 MemTotal 保留餘裕並設上限，太小時拒絕建置。退出碼／逾時／OOM 寫入 build-result.json 供 diagnose。
- `health` 核對容器、資料庫健康狀態與應用入口 HTTP 200；**尚未提供使用者登入及資料寫入／重啟保留驗證**。
- `diagnose` 只回傳容器狀態、退出碼、OOM 與 health，不輸出含環境變數的完整 inspect 或原始 logs。

每個請求保存收據，重送同一 ID 不重做；發現中斷的執行紀錄時保留現場，不能盲目重跑。
客體單次操作上限 1200 秒、client 等待 1500 秒，整個 SERVICE 節點仍受該節點 `timeoutSeconds` 限制（節點上限 3600 秒）。
長作業與模型回合分開保存，見 [SSH 直連部署](direct-ssh-deployment.md#持久長作業恢復)。

## 完成條件與證據限制

`Gate.SERVICE` 要求：

- `service_status=RUNNING`、`target` 與配置名稱完全一致，`product_code_changed=false`。
- `checks` 完整涵蓋配置的檢查名稱與 operation_id，不重複、不捏造其他操作。
- 每項 `status=PASS`、整數 `exit_code=0`、非空 `evidence`（本次 log 或工具呼叫 ID）。
- 每項列 `acceptance_ids`；全部檢查合計涵蓋需求基準的所有原子驗收項目。
- `uncertainties` 為空；缺項、失敗或僅產出檔案均不能完成。僅將需求基準或配置檢查要求而仍未核實的事項放入此欄；未要求的登入、資料寫入或重啟測試在摘要說明驗證邊界，不自行新增阻擋項。原需求有要求時仍須完成。
- 若 service-context 已登錄 access_endpoints，固定 support 工具的本次探測須 healthy，摘要須含使用者 url 完整路徑；不能以 Agent VM 內部根網址交付。端點診斷與既有版本自修規範見[直接追問](direct-agent-requests.md)。

角色 checks 仍為報告與配置的機械核對；已登錄的入口另有固定工具實測，兩者不能互相冒充。系統 probe 由 Agent VM 進行，不等同已從使用者電腦驗證。
不產生開發流程的 IndependentVerifier 收據、不開 PR；`COMPLETED` 只表示這次服務交付報告通過契約，
不是日後持續健康保證。正式環境的監控、告警、回滾操作需另依目標主機設定。

## 自動修復與舊任務

格式、設定、啟動錯誤先在核准範圍自修並重驗；受阻時交 service_triage 定位，再派回 service。
沿用既有事件／節點／整輪預算，不無限重試。需要新權限、機密、主機或修改產品程式時，清楚說出缺口後等待人處理。
尚未建立需求基準時，沒有待確認問題、但 ID／欄位／原子驗收結構不合格的草稿會自動交回 intake 修正；
不能擅改已保存的需求基準。文字語意矛盾主要由提示與角色查核，程式不宣稱能完全辨識。

新路線只影響採用新快照的任務。既有需求若排除實際部署，不能只按續跑就變成服務建置；
需以新任務承接成果並明確要求實際啟動，原需求仍完整保留在歷史。
已含 SERVICE 路線的任務可用受控配置遷移核准 `serviceTarget`／operations 能力更新；節點或提示不同仍不得原地遷移。

## 部署歷史與恢復入口

只給 agent 本任務脈絡時，它可能把「本次未執行」誤寫成服務從未部署，因此系統另外注入部署歷史：

`TaskStore.serviceHistory` 讀取同實例資料庫、同頻道、同目標的最近 12 筆 SERVICE 階段報告，排除本任務與分流敘述。
`ConfiguredExecutor` 在有 serviceTarget 的正式節點注入任務編號、時間、角色回報的狀態與檢查結束碼；
這些是歷史角色報告，不代表目前健康或本次檢查通過，也不能當新主機操作授權。
歷史查無資料不等於從未部署。沒有正式階段報告的舊工具收據，可由管理者登錄成帶來源的歷史摘要。

另外讀取實例目錄 `$AI_TEAM_HOME/service-context/<target>.json`，這是管理者維運說明，不是模型生成的操作設定。
允許欄位為 target、host、program、config_file、start_command、notes、history（at/task/outcome/evidence），
以及 access_endpoints（見 [SSH 直連部署](direct-ssh-deployment.md#服務追問與使用者入口)）。
檔案上限 32 KiB，歷史最多 5 筆、文字欄位最多 1500 字元；目標不符、名稱非英數底線連字號或資料損毀時明示缺漏。
所有欄位必須是不含 token、密碼及登入檔內容的資訊；未知欄位不注入。系統只呈現說明，不執行 start_command。
無此檔仍可執行服務任務，但需如實說明恢復入口尚未配置，不可猜指令。
範本放在 `deploy/service-context/`（`*.example.json`）；管理者核對環境路徑後放到實例的 `service-context/<target>.json`。
新 JAR 會在每次執行時讀取此說明與資料庫，既有等待任務續跑亦適用，不改原快照／授權或完成閘門。

## 狀態式決策與持久診斷

- ConfiguredExecutor 依父任務狀態組合對話 system；COMPLETED／PR_CREATED 不產生執行前綴，等待或執行中也不猜測續跑指令。每個角色收到系統 task／state／node／gate／configuration、target 與可用操作摘要；對話明列不能由聊天續跑父任務或改產品。原 job／版本／入口／觀察時間仍取自既有可信證據，不以模型記憶代替。
- service／service-triage／intake 與一般 triage 按「原 job 對帳→來源封裝／建置／資源／SSH 或工具／應用健康／使用者入口→具體修正→重驗」決策；實作／QA／review 補證據位置與範圍原則。提示不新增權限、節點或驗收。
- 固定 support 診斷在 FlowEngine 呼叫模型前執行，StageProgress 回報診斷階段；ConfiguredExecutor 只讀保存結果，不會因答案改寫重做修復。已有 pending 部署先交原 OperationJobs 對帳，不另啟動診斷修復。
- `support.py --job` 為 Linux 固定 worker，以每 task 程序鎖與原子 job.json 保存 generation／phase／deadline；協調器停止等待不殺遠端 job。重啟後只對原 STAGE_RUNNING／RESUME_QUEUED 服務角色對話，或同 activeNode、乾淨且同 HEAD 的 SERVICE 階段；相符 task／parent／target／generation 且未過期限的 job 排回中央佇列；一般對話、已取消／完成任務不因此自動重跑。沿用原修復 ID 及最多兩次預算。
- 相同診斷證據下已失敗的 repair 不原樣再試；repair-policy.json 記錄證據指紋。新診斷證據才可使用剩餘次數，未確認原 repair 結果先對帳。診斷失敗不視為已找到應用根因，也不自動重建產品。
- 正式對話回覆對 COMPLETED／PR_CREATED 父任務檢查不可用執行前綴，涵蓋非網址問題；改寫一次仍不合格則提供可信診斷或誠實說明缺少新證據。
- 中央執行槽仍保留，不新增並行或更換快照；既有任務的角色資產依既有配置遷移流程生效。真實產品故障演練尚未執行。

### 故障評估與證據層級

| 情境 | 目前驗證位置 | 應觀察的結果 |
|---|---|---|
| 模型逾時、遠端晚到成功 | FlowEngineTest／OperationJobsTest 既有回歸 | 同任務接結果、不新增 start、不重設計次 |
| 診斷 worker 中斷及重複呼叫 | test_service_support.py Linux 實際子程序／程序鎖 | 原 deadline 不延長、復原後重複呼叫不再 probe、無 repair |
| 未確認 repair | test_service_support.py | 先查原 repair、不另送 health／新 repair |
| 相同證據下 repair 已失敗 | test_service_support.py | 不原樣重試，保留剩餘預算及原因 |
| 新診斷進行中仍有舊 200 | ServiceSupportTest | 舊端點結果不能成為目前健康 |
| 結案後非網址追問 | ConfiguredExecutorTest／FlowEngineTest | 狀態式指示、攔截不可執行命令，不動父任務 |
| 使用者入口不可達但內部 200 | 既有 probe／入口回歸；曾有真實任務實測 | 說明觀察位置，不重啟健康應用 |

真實產品停機、SSH 斷線與協調器整體重啟的聯合演練尚未執行。評估應記錄原 task ID、終態、人工介入次數、start／repair ID 數、恢復耗時與回覆校正次數；可由既有 audit、operation-jobs、support job 與 Slack 紀錄彙整。不要以固定工具測試通過宣稱全部模型已實測遵守新提示。
