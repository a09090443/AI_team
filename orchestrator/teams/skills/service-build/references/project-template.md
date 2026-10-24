# 專案部署參考（範本）

每個啟用服務建置路線的專案，在實例的 `team-overlay/skills/service-build/references/<project>.md` 提供自己的部署參考，
並在團隊配置的 `service`／`service_triage` 節點 `skills` 引用它；不放進公開 repo。內附的 `gradle-fullstack.json` 範例引用本檔。
參考內容至少寫明：

- 操作：以本任務 operations 提供的實際 argv 為準（例如 service-prepare、service-start、service-health、service-diagnose）。
  client 在目前 task 工作區送請求；Agent VM 上的 ssh_executor 只處理 SERVICE 執行中且目標相符的任務，直接透過 SSH 連到目標 VM 的受限入口。
- 部署來源：目標主機使用哪些已審核提交的目錄、以什麼建置方式啟動（例如 Compose），機密、release 與資料保留在哪個目錄。
  這些值實際由管理者的設定提供（目標 VM 服務配置見 `assets/target-service.example.json`，Agent VM 的 `source_paths`、
  `serviceTarget.checks.start` 操作唯一的 `writes` 作為請求目錄）；參考檔只說明，不能代替設定。
- 健康檢查：health 實際檢查哪些項目（容器、資料庫、HTTP 路徑）；腳本沒有涵蓋的驗證（例如登入、資料寫入）要寫明，需求有要求時不能宣稱已完成。

共同規則：目標 VM 收據是本次工具的實際結果，不得修改固定 SSH 入口、request 完成收據或假造 PASS。
SERVICE_EXECUTOR_OFFLINE 代表 Agent VM 的 systemd 部署服務未回應；SSH 設定／網路或主機指紋缺漏須如實列出。
主機設定無法登入時回報具體缺口，不能要求使用者重填已保存的主機資料或自行安裝 SSH／Docker。
需要改產品程式走開發流程，不能繞過測試／審查。
