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
私鑰留在 Agent VM 的受保護路徑，目標機公鑰使用 forced command，sudo 只授權固定入口，不授權任意 root shell。
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

- bestpartner 專案：讀取 [references/bestpartner.md](references/bestpartner.md)。其他產品需提供自己的部署參考與檢查操作，不套用 bestpartner 的路徑。
- 主機執行與換機設定：讀取 [references/host-setup.md](references/host-setup.md)。
- `scripts/client.py <action>` 是角色沙箱的固定入口。`ssh_executor.py` 在 Agent VM 由 systemd 自動常駐，核對本地任務授權後呼叫 `ssh_client.py` 直接 SSH；不需 Windows 代辦。`ssh_client.py <管理者設定> <action>` 是同機固定執行器的同步直連入口；`ssh_target.py` 與 `guest.py` 是目標機 root-owned 固定入口。
- `install-ssh-target.sh` 由管理者在目標 VM 一次性執行，安裝受限公鑰、sudo 規則與目標名稱；不建立逐版來源清單；不清除資料或其他公鑰。
- `client.py` 沿用任務檔案佇列交給同機執行器；`broker.py` 僅保留舊版相容，不能在 SSH 失敗時自動退回 Windows broker。
- `assets/host.example.json` 僅是無憑證範本，實際私密設定放在 repo 外。

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
