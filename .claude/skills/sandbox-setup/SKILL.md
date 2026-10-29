---
name: sandbox-setup
description: 在 AI Team 專案建立、重建共用沙箱 VM，或讓某個 orchestrator 實例（專案）接上沙箱，使該專案的 Slack agent 能在沙箱部署服務供本機測試。使用者提到「建沙箱」「讓 <專案> 接沙箱」「重建沙箱」「沙箱部署失敗」時使用。
---

# 共用沙箱的建立與接入

規格與設計理由見 `docs-site/docs/sandbox-environment.md`。這裡只列操作步驟；先讀該文件再動手。
VM 操作一律先載入 `virtualbox-control` skill 並遵守它（核對 VBoxManage 版本、以 UUID 操作、Vagrant VM 用 Vagrant 指令、
`reset`／`poweroff`／`destroy` 要使用者明確授權）。Slack agent 不能建立或刪除 VM，這些步驟由開發用 AI 工具或管理者執行。

## 一、建立沙箱 VM（每台一次；Windows 主機用 Git Bash）

1. 核對主機：VirtualBox／Vagrant 版本、`bento/ubuntu-24.04` box 版本、可用記憶體與磁碟、host-only 介面。
2. Agent VM：`bash deploy/bootstrap/sandbox-target.sh keygen`，取回輸出的公鑰（只有公鑰）。
3. 主機：`bash deploy/sandbox-vm/create-sandbox-vm.sh <管理目錄> <公鑰檔> <target>`
   （`AI_TEAM_SANDBOX_NAME`／`CPUS`／`MEMORY`／`IP` 可調）。輸出 `PASS` 與主機指紋，產生 `<管理目錄>/agent-known_hosts`。
4. 依 virtualbox-control 核對 `showvminfo`：只有 NAT＋host-only、無共享資料夾、剪貼簿／拖放停用、SSH 綁 127.0.0.1、磁碟在管理目錄。
5. 從 Agent VM 測 `<IP>:22` 可連（`bash -c 'exec 3<>/dev/tcp/<IP>/22; head -c 20 <&3'`）。

## 二、讓一個實例接上沙箱（每個專案一次，可重複執行）

先部署含本功能的 orchestrator（`deploy-orchestrator.sh`），再在 Agent VM 執行：

```bash
bash deploy/bootstrap/sandbox-onboard.sh <實例 home> <target> <IP> <known_hosts 檔> [--paths a,b] [--restart]
```

它會建缺少的快取目錄、在 overlay 補上沙箱服務路線並驗證、量工作樹大小、登錄目標與部署執行器。
看輸出：`WARN` 表示既有配置有沙箱不支援的內建操作，或工作樹超過 128 MiB；前者要把操作改成 `client.py exec <腳本>`，
後者可加 `--paths` 或讓 agent 自己在腳本旁寫 `source-paths`。沒加 `--restart` 時，等沒有執行中的任務再重啟實例。

## 三、驗證

- 程式：`bash deploy/bootstrap/check-source.sh`、Maven 測試、`cd docs-site && npm run build`。
- 腳本實跑：onboard 輸出 `PASS`，journal 有 `ENTRY_UPGRADE: UPGRADE_UNCHANGED` 或 `UPGRADE_PASS`。
- 端對端：請使用者在該專案 Slack 下「把目前的程式部署到沙箱讓我測試」（不代發訊息），
  追蹤 `operation-jobs/<task>/` 的 prepare／exec 收據，任務 COMPLETED 後從主機開回報的網址自行核對 HTTP 狀態。
回報時分開寫這三層，未做的直接說明。

## 四、重建

確認沒有 agent 正在部署，取得使用者對刪除該 VM 的明確授權後：
`AI_TEAM_SANDBOX_REBUILD_CONFIRM=<VM 名稱> create-sandbox-vm.sh --rebuild <管理目錄> <公鑰檔> <target>`，
再對每個實例重跑 `sandbox-onboard.sh`（主機指紋會換）。agent 寫的部署腳本保存在實例的 `service-context/<target>.files/`，下次部署自動還原。

## 排錯

| 現象 | 原因 | 處理 |
|---|---|---|
| 任務停在「快取目錄缺失或含 symlink」 | 新機沒有配置宣告的 cachePaths | 重跑 onboard（會建立），再請使用者續跑任務 |
| exec 回 `SOURCE_TOO_LARGE` | 工作樹超過 128 MiB | agent 應自行寫 `source-paths`；仍失敗才以 `--paths` 重跑 onboard |
| diagnose 回 `SERVICE_CONFIG_REQUIRED` | 配置用了內建操作 | 改成 exec 腳本 |
| `vagrant ssh` 回 Host key verification failed | `~/.ssh/known_hosts` 有同埠舊指紋 | 新版建 VM 腳本會自動清；舊 VM 用 `ssh-keygen -R "[127.0.0.1]:<埠>"` |
| VM 卡死（RCU stall／soft lockup） | Hyper-V 模式＋多 vCPU＋存檔還原 | 主機關機前 `vagrant halt`；降 vCPU；必要時經授權 reset |
