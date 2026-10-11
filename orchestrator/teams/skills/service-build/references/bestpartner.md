# bestpartner 部署參考

操作以本任務 operations 提供的實際 argv 為準：service-prepare、service-start、service-health、service-diagnose。
client 在目前 task 工作區送請求；broker 僅讀 SERVICE 執行中且目標相符的任務，固定執行所選主機設定。

Ubuntu 專用 SIT 主機由 guest.py 準備 Docker/Compose，使用已審核提交的 bestpartner-service、docs/docker/sit、docs/sql。
部署程式固定以 Gradle/JDK 21 容器建置並啟動 Compose；機密、release 與資料保留在 /opt/ai-team-bestpartner-sit。
health 檢查容器運行、PostgreSQL healthy 和 /view/chat HTTP 200；目前腳本不提供登入／資料寫入驗證，需求有要求時不能宣稱已完成。

broker 收據是本次工具的實際結果。不得修改 broker、核准 manifest、request 完成收據或假造 PASS。
SERVICE_BROKER_OFFLINE 代表宿主機執行工具未運行；DEV_LOGIN_REQUIRED 代表一次性登入未配置。
主機設定無法登入時回報具體缺口，不能要求使用者重填已保存的主機資料或自行安裝 SSH／Docker。
只部署已提交且符合 manifest 的來源；需要改產品程式走開發流程，不能繞過測試／審查。
