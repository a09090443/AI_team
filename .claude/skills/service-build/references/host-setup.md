# Agent VM 直連目標 VM 的一次性設定

日常部署固定為 Agent VM → SSH 公鑰認證 → 目標 VM。Windows 只可協助最初配置虛擬網路或傳送公鑰，不運行部署 broker。

1. 確認兩台 VM 有互通位址：同一個 Internal Network／Host-only／共享 NAT Network 均可；兩個獨立 NAT 的相同 10.0.2.15 不可當互通位址。變更虛擬網卡需維護時段，不中斷進行中任務。
2. 在 Agent VM 以 ssh-keygen 建立專用 Ed25519 金鑰，保留私鑰權限 600；不覆寫現有金鑰。只傳公鑰到目標 VM。
3. 由管理者在目標 VM 直接核對 SSH 主機公鑰指紋，再寫入 Agent VM 的專用 known_hosts。禁止把 ssh-keyscan 單獨當身分驗證，也禁止 StrictHostKeyChecking=no。
4. 確認使用者已授權目標主機與固定部署操作。來源使用本任務已提交的HEAD；正常版本更新不另建逐版manifest審核流程。
5. 於目標 VM 以 sudo 執行 scripts/install-ssh-target.sh <登入帳號> <Agent公鑰檔> <target名稱>。它安裝 root-owned guest.py、受限 forced-command SSH 入口與僅限該入口的 sudoers，不提供 root shell、不清除資料與其他公鑰。
6. 在 Agent VM 放置管理者 direct-ssh.example.json 的私人副本（例如 ~/.config/ai-team/ssh-targets/<target>.json），填實際目標位址、專用私鑰與 known_hosts 的路徑。不要貼金鑰值到設定或 Slack。
7. 安裝 assets/ai-team-ssh-deploy.service 至 Agent VM ~/.config/systemd/user/，核對本實例路徑，執行 systemctl --user daemon-reload 及 systemctl --user enable --now ai-team-ssh-deploy.service。使用者層 systemd 與 linger 的前置條件沿用 INSTALL；它核對本機 SERVICE 任務後直接 SSH，不連 Windows。
8. 以固定操作完成 prepare、health／diagnose。沒有本次 start 成功收據不能宣稱重新部署完成；來源SHA256僅驗證完整性與收據對應，不比對舊清單。

SSH_TRANSPORT、設定缺失或 SERVICE_EXECUTOR_OFFLINE 必須清楚區分：網路／主機指紋／公鑰／受限入口是連線設定；systemd 服務未運作是 Agent VM 本機執行器問題。不得要求使用者啟動 Windows broker。

請求與收據以 id、target、action、來源 digest 綁定；相同 id 不重做已完成操作，中斷標記需核對目標後再處理。舊 Windows worker 已接單但未拿到收據時，不能直接換通道重做。
更換主機要重新核對位址與主機指紋並更新私人設定，不複製舊資料卷或遺漏使用者指定的資料保留要求。

舊版 broker.py／Guest Control 僅保留歷史相容，不是新規格的日常部署方式。程式測試與設定檔存在不等於兩台 VM 直連或服務部署已實測。
