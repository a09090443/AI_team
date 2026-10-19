# Agent VM 以 SSH 直連部署

## 規格與目前狀態

使用者指定日常路徑為 **Slack → Agent VM → SSH 公鑰認證 → dev VM**，不依賴 Windows broker 或 Guest Control。
規格單一來源在 `.agents/skills/service-build/SKILL.md` 及 `references/host-setup.md`，同步到 Claude 與 Slack runtime 套件。
目前來源已實作，**尚未切換線上部署通道**。兩台 VM 仍為獨立 NAT，沒有可互連的網路介面；
Agent VM 第二張網卡停用，VirtualBox 無法於開機中啟用它。#1003 執行中，未為此中斷任務或重啟 VM。
已在 Agent VM 建立專用 Ed25519 金鑰（未讀私鑰、未覆寫其他金鑰），但尚未向 dev 登錄，也尚未完成 SSH 連線驗證。

## 執行方式與權限

角色仍只呼叫固定 `client.py prepare|start|health|diagnose`，寫入原任務工作區的請求。
`ssh_executor.py` 是 Agent VM 的 systemd 使用者服務：查詢本機資料庫，僅接受目標相符、正在執行的 SERVICE 節點。
它在同一台 Agent VM 執行固定 `ssh_client.py`，直接連目標 VM，不需要 Windows 程式輪詢。
這是為保留角色沙箱與私鑰管理界線；未把角色改成可任意 SSH 或執行 root shell。

SSH 固定 BatchMode、公鑰認證、獨立 known_hosts 與嚴格指紋檢查；沒有 Windows 或密碼 fallback。
目標公鑰帶 `restrict,command=...`；sudoers 只允許 root-owned `ai-team-ssh-service` 固定入口。
目標只接受 target、prepare/start/health/diagnose、請求 ID 與 start 來源 SHA256，不接受模型自訂 shell。
部署來源在目標 root-owned manifest 做逐檔雜湊核對，失敗不能執行產品。

請求 ID、target、action、來源 digest 必須與收據完全相符；成功還須有對應完成標記與整數結束碼。
相同 ID 已完成就取回收據，不重建；不確定中斷保留 running 標記與原請求。
舊 Windows worker 已接單但沒有收據時不能直接換通道重做，須核對舊現場。
歷史成功只作背景，本次需實際啟動與健康檢查；回報列程式／服務名稱、版本、目標、網址和限制。

## 一次性啟用

1. 等現有任務停止執行後安排網路維護。建立 VM 間的 Internal／Host-only 或共享 NAT Network，確認位址不同且互通；保留原有管理入口與產品資料。
2. Agent VM 保留專用私鑰（600），只把公鑰交目標管理者。從 dev VM 本機取得 SSH 主機公鑰並核對指紋，再登錄 Agent VM 的專用 known_hosts。
3. 將已審閱的來源 manifest 與工具傳到 dev，以管理者權限執行 `install-ssh-target.sh <既有登入帳號> <Agent公鑰檔> <核准manifest> bestpartner-dev`。不新增任意 sudo 或 root shell。
4. 管理者依 `assets/direct-ssh.example.json` 在 Agent VM 放 `~/.config/ai-team/ssh-targets/bestpartner-dev.json`，填真實互通位址、私鑰與 known_hosts 引用。
5. 依 `assets/ai-team-ssh-deploy.service` 安裝使用者服務，核對實例路徑後 `systemctl --user daemon-reload`、`systemctl --user enable --now ai-team-ssh-deploy.service`。沿用 INSTALL 的 linger 前置條件。
6. 驗證公鑰登入、錯誤指紋拒絕、固定入口拒絕任意命令、來源不符拒絕，以及重啟後執行器自動可用。此時 Windows broker 應維持停止。
7. 更新服務提示、skill 與非機密恢復說明後，在 Slack 以原任務／受控配置更新或承接完成 prepare、start、health。舊快照若仍要求 Windows，不直接改資料庫或偽造核准。

只有兩台 VM 的上述實測成功才可標為已啟用；Python 單元測試不是網路、公鑰或部署端對端證據。
不在此流程中自動清空資料卷、更換資料庫密碼或覆寫未審核的來源 manifest。
