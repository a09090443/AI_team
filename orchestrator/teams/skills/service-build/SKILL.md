---
name: service-build
description: 由 Slack agent 在已授權主機準備環境、建置既有系統、啟動、檢查並修復一般部署問題；更換主機時沿用流程與更新主機設定。
---

# 系統建置

由負責建置的 agent 執行並追蹤到可用；只產出設定檔或手冊不算完成。
先使用已保存需求與主機設定，不重問使用者已提供的位址與用途。

## 判斷目標與執行方式

讀取本次 serviceTarget、operations 與產品部署參考，確認來源版本、目標身分、登入及資料保留需求。
主機位址、VM UUID、登入方式和憑證引用放在主機設定；不要寫死在本 skill 或產品程式。
正式部署必須由 Agent VM 透過 SSH 公鑰認證直接連到目標 VM，不依賴 Windows broker、Guest Control 或宿主機上的常駐 Python 程式。
VirtualBox／宿主機工具只可用於管理者一次性的網路與 SSH 初始化，不得成為日常部署通道。
使用 BatchMode=yes、PasswordAuthentication=no、StrictHostKeyChecking=yes 與獨立 known_hosts；不得用停用指紋核對解決連線問題。
私鑰留在 Agent VM 的受保護路徑，目標機公鑰使用 forced command，sudo 只授權固定入口，不授權任意 root shell；只有管理者宣告為 sandbox 的目標，固定入口才以 exec 執行 agent 腳本（見「沙箱目標」）。
檢查兩台 VM 間的真實可達位址；各自 NAT 的 10.0.2.15 不是互通位址，Windows 的 localhost 轉送也不是 VM 直連。
其他平台使用管理者已提供且核准的連線工具，沒有實際驗證就不能聲稱支援。

## 執行與修復

- 呼叫配置的 prepare，確認系統版本、權限、容器 runtime 與必要工具。缺套件由工具安裝，不把一般安裝工作交回使用者。
- 呼叫 start，建置指定版本並啟動。保持資料卷與既有機密；來源不符時查明差異，不能偷偷換版本或放寬核准範圍。
- 呼叫 health 及需求指定的額外檢查。網頁可開不等於登入、資料寫入與重啟保留都成功，逐項提供實測證據。
- 失敗先 diagnose，依退出碼與當前狀態修正核准範圍內的設定或暫時性安裝問題，再執行同一檢查。相同原因最多重試兩次。
- 請求已接單但等待逾時時，先取得原請求結果，不能另建請求重複啟動。保留不確定執行的現場。
- 只有確實需要新主機權限、可用登入憑證或超出任務範圍的產品程式修改時才說明單一缺口；skill 不會自行取得主機權限。

## 交付

回報服務位址、實際版本、哪些檢查通過、尚未驗證項目。所有必要檢查通過才回报 RUNNING。
以使用者看得懂的方式說明問題與下一步，不以僅有腳本、文件或模型推測當成功證據。

## 配套

- 專案部署參考：讀取節點 skills 引用的 `references/<project>.md`（放在實例 overlay，不在公開 repo）；撰寫要求見 [references/project-template.md](references/project-template.md)。各產品提供自己的部署參考與檢查操作，不套用其他產品的路徑。
- 產品路徑不寫在腳本：目標 VM 的 `/etc/ai-team-service/target.json` 由 `install-ssh-target.sh` 依 [assets/target-service.example.json](assets/target-service.example.json) 寫入目錄、來源路徑、Compose 專案與服務名稱；Agent VM 直連設定以 `source_paths` 列出要封裝的版控路徑；工作區請求目錄取自 `serviceTarget.checks.start` 操作唯一的 `writes`。任何一項缺少時固定工具回 `SERVICE_CONFIG_REQUIRED`／`SSH_CONFIG_REQUIRED` 並停止，不猜測；這是管理者設定缺口，如實回報，不自行改設定。
- 主機執行與換機設定：讀取 [references/host-setup.md](references/host-setup.md)。
- `scripts/client.py <action>` 是角色沙箱的固定入口。`ssh_executor.py` 在 Agent VM 由 systemd 自動常駐，核對本地任務授權後呼叫 `ssh_client.py` 直接 SSH；不需 Windows 代辦。`ssh_client.py <管理者設定> <action>` 是同機固定執行器的同步直連入口；`ssh_target.py` 與 `guest.py` 是目標機 root-owned 固定入口。
- `install-ssh-target.sh` 由管理者在目標 VM 一次性執行，安裝受限公鑰、sudo 規則與目標名稱；不建立逐版來源清單；不清除資料或其他公鑰。
- `client.py` 沿用任務檔案佇列交給同機執行器；`broker.py` 僅保留舊版相容，不能在 SSH 失敗時自動退回 Windows broker。
- `assets/host.example.json` 僅是無憑證範本，實際私密設定放在 repo 外。

## 沙箱目標（sandbox）

管理者把目標 VM 宣告為 sandbox（整台可拋棄）時，prepare 收據會顯示 `mode=sandbox`。此時不受內建建置配方限制：
- 把安裝、建置、啟動、健康檢查寫成腳本放在本節點可寫路徑（例如部署目錄下的 `sandbox/`），用 `client.py exec <腳本路徑>` 在目標以 root 執行。
  腳本在 `source/`（`source_paths` 的工作樹內容，含未提交修改）下執行；`$AI_TEAM_SANDBOX_HOME` 跨次保留，放安裝狀態、資料與機密。
- 缺的套件、runtime、資料庫由腳本自己安裝設定，不交回使用者；腳本要可重複執行（已裝就跳過），失敗時依收據最後 30 行輸出修正再跑。
- 機密在目標產生並存在 `$AI_TEAM_SANDBOX_HOME`，不要輸出到畫面或寫進 repo；腳本只操作這台目標，不連其他主機做部署。
- health 腳本要實測服務入口（HTTP 狀態、必要容器或程序），不可只 `exit 0`；系統另有登錄入口的獨立探測。
- 配置中 `serviceTarget.checks` 指向的 exec 操作（例如 start、health 腳本）就是交付檢查，checks 照常填這些操作的結果。
  腳本路徑以配置的操作 argv 為準（內附範本為 `ai-team-sandbox/start.sh`、`health.sh`、`diagnose.sh`）；檔案不存在就先寫，系統會在交付後保存、下次還原。
- 沙箱 VM 可能由多個專案共用：安裝狀態、資料與機密放在 `$AI_TEAM_SANDBOX_HOME/<repo 名稱>/`，容器與服務名稱加上 repo 名稱前綴；
  綁定埠前先查是否已被其他程序佔用，選用未佔用的埠並寫在腳本中固定沿用。不要停止、刪除或改動不屬於本專案的容器、服務與檔案。
restricted 目標回 `SANDBOX_NOT_ENABLED` 時，表示管理者沒有開放，改用固定操作，不要嘗試繞過。

## 來源封裝失敗

start 若回 SOURCE_MISSING_PATHS，表示目前HEAD缺少必要的版控部署檔；SOURCE_WORKSPACE_DIRTY表示有未提交變更。兩者都尚未執行SSH，不重試連線或重啟執行器。列出缺件，在任務既有範圍補齊部署檔並提交後重新部署；不能跳過必要檔案。正常新增檔案與版本更新不需要另請管理者核准manifest。

## 已授權主機的自主部署

使用者已授權的測試主機與固定部署操作，agent自行完成prepare、start、health與一般部署修復。已提交且工作區乾淨的來源可部署，不再逐版比對或要求管理者更新source-manifest.json。SHA256僅核對傳輸完整性和收據對應，不是第二道真人核准。舊收據的Source approval失敗屬已移除機制，不據此再次請人更新清單；取得新的本次收據。保留資料與機密，不假造成功或繞過尚未授權的其他主機操作。

## 持久作業與接續

固定工具等待最多30秒；OPERATION_PENDING表示原job已受理。本輪立即回BLOCKED等待系統接續，不要持續輪詢或重送start。系統在模型以外追蹤原job，沿用activeNode、來源與節點次數。模型逾時不代表遠端失敗；系統核對已保存結果後續剩餘步驟，不要求真人重啟執行器。
同一任務、來源HEAD與目標已有verified prepare/start時沿用檢查點，只補缺少的health。存在未取回的pending action時先呼叫該action收取結果。checks.operation_id仍填配置操作ID，job ID寫在evidence，不加到operation_id尾端。
SSH回覆遺失時查原job的result/status；固定工具可安全用同ID重新送達，不能另建ID重複部署。遠端job由獨立systemd unit執行，與SSH/模型程序分開。只有使用者明確中斷或中止才取消原job；AWAIT_HUMAN與模型逾時不代表取消。
失敗先讀固定diagnose的原job錯誤與資源/服務狀態，依具體原因修復。新版本啟動或健康失敗時固定工具嘗試回復上一個有效程式版本，保留資料與機密；回復成功也不能把原部署填成成功。沒有可用上一版或回復失敗時如實回報。

uncertainties只列需求基準或配置檢查要求、且仍未核實的事項。需求未要求的登入、資料寫入或重啟測試，可在summary說明驗證邊界；不要自行把它們升成阻擋交付的新驗收項目。需求有要求時仍須完成，不得用此規則省略必要驗證。

## 結案後追問與入口自動診斷

部署主機非機密service-context可登錄access_endpoints（audience、url、probe_url）與auto_repair_existing_release。url是使用者應開啟的完整入口，probe_url是Agent VM能探測的內部位置，不能交換或省略應用路徑。使用者問網址或服務狀態時，依系統已執行的support診斷直接回答，不把VM內部200說成已從使用者電腦驗證。來源工作區清理不等於服務/資料刪除；以保存收據與新探測作答，不要求重建產品或重填主機資料。
固定support.py在既有授權內自動probe；端點失敗先確認health/diagnose，只有既有服務健康失敗且明確啟用auto_repair_existing_release時，以固定repair操作啟動原active版本的應用容器（--no-deps --no-build），不建置來源、不更換版本、不初始化資料或憑證。結果未確認保留同job ID，單次追問最多兩次修復。URL根路徑錯誤但服務健康時直接更正已登錄入口，不重啟服務。未登錄入口時如實說明缺對應資訊，不能猜內外位址。

## 證據驅動的修復決策

每次先對帳原job，再依來源封裝、建置、資源、SSH/工具、應用健康、使用者入口分類；記錄觀察、新改變與重驗條件。相同原因且沒有新證據時不能原樣重試；先做不同的查核或交既有分流處理。已成功部署只補缺少驗證，不重新建置。

系統在模型啟動前執行固定support.py --job；service-support/<task>/job.json保存task/parent/target/generation、期限、phase與終態，current.json原子保存診斷。Linux程序鎖避免重複worker；協調器重啟可接回同一診斷，原repair ID與最多兩次預算仍有效。等待有系統進度回報，未持有模型程序；中央執行槽仍保留。模型與提示不能自行啟動此管理入口。
