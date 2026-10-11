# 主機設定與換機

這份參考供管理者首次登錄或更換主機；Slack agent 只執行已核准的操作。

1. 以 assets/host.example.json 建立 repo 外的私密設定；設定 target、agent_home、Agent SSH 入口與獨立 state_directory。
2. transport=virtualbox：配置 vm_uuid、guest_user、password_file、vboxmanage；需要客體 Guest Additions，SSH 可以尚未啟用。
3. transport=ssh：配置 target_ssh、target_port、target_identity_file、target_known_hosts_file；須事先有可用 SSH、公鑰登入及 sudo -n。這些是連線前提，缺 Docker／Compose 仍由 prepare 安裝。
4. dedicated_sit_vm=true 表示這是明確授權可安裝系統套件的專用 SIT 目標，不能把生產主機當測試機。
5. 檢查來源後執行 python scripts/enroll.py <設定檔> <来源任務 ID>，再執行 python scripts/broker.py <設定檔>。同一設定只運行一個 broker。

换主機時另建設定和 state_directory、更新目標身分與登入引用；skill 本文保持不變。保留舊主機資料，沒有資料搬遷授權不複製或清除舊資料卷。
需更新團隊 serviceTarget.name 及執行工具路徑時由管理者配置，以新任務快照生效；不能假設更換 JSON 就自動搬遷既有任務。
transport=ssh 的固定程序與 VirtualBox 共用 guest.py，不接受模型自訂 shell 命令。
兩個連線模式以單元測試驗證；真實換機、SSH 遠端安裝及 VirtualBox 完整建置仍需實測，不宣稱已驗收。

## Windows 首次登入入口

管理者先備好 repo 外的設定，再開啟 `powershell -NoProfile -File scripts/login.ps1 -Config <設定檔>`。使用者只在遮蔽輸入視窗輸入既有密碼一次，不需要尋找或建立檔案。腳本依 password_file 自動保存，將其所在私人目錄限為目前 Windows 帳號存取；這是 ACL 保護的明文檔，不是加密保管庫。不得將該目錄用作共用資料夾。既有檔案不覆寫，連結目錄及空密碼拒絕。登入成功保存不代表 VM 已驗證；管理者仍須測試 Guest Control 身分，再啟動 broker。

Windows 入口使用 .NET ACL API，不依賴 Set-Acl 模組自動載入；已在 Windows PowerShell 5.1 與 PowerShell 7.6.5 以假密碼驗證保存、權限及不覆寫。

建置最低客體記憶體2500MiB；工具依實際MemTotal保留系統用量、限制Gradle JVM及worker，下載快取留在目標專用目錄供重試使用。正常關機無回應不等於可任意強制重置；強制中斷须符合該目標的明確授權。

宿主broker輪詢連線/佇列暫時失敗時採3至60秒退避並保留收據，不直接退出；--once仍回報錯誤。此程序尚未自動註冊為開機服務，宿主需持續運行。Linux start以flock避免重疊建置，建置900秒內部逾時並清理核對專用label的建置容器，資料卷與快取保留。
