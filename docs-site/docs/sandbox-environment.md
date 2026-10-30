# 共用沙箱環境

讓各專案的 Slack agent 把負責的應用程式部署到一台**可拋棄的沙箱 VM**，供使用者在本機測試，而不是跑在 agents 所在的 Agent VM。
本文是建立、接入、重建與排錯的現行規格，以及首次導入的問題分析。沙箱目標本身的行為（`exec`、入口升級、收據）見
[服務建置：沙箱目標](service-delivery.md#沙箱目標sandbox)；AI 開發工具的操作步驟在 `.agents/skills/sandbox-setup/SKILL.md`。

## 架構

```text
VirtualBox 主機
├── Agent VM：orchestrator 實例 ×N，各自的 <實例目錄名>-ssh-deploy.service
│        │  SSH 公鑰 → 受限入口（forced command），經主機 NAT 轉送到 host-only 位址
│        ▼
└── 沙箱 VM（mode sandbox，NAT＋host-only）：rootless Docker 跑各專案的服務
         使用者從主機開 http://<host-only IP>:<埠>
```

- **誰做什麼**：管理者（或開發用 AI 工具）以腳本建立、重建沙箱 VM，並讓實例接上；Slack agent 只在沙箱上安裝、部署、修復服務。
  agent 不能建立或刪除 VM：Agent VM 碰不到 VirtualBox 主機，給它這個能力等於恢復已移除的主機端 broker。
- **權限邊界**：agent 的部署腳本以 root 在沙箱執行（才能自行安裝軟體），容器跑在一般帳號 `aiteam-docker` 的 rootless Docker。
  Slack token、Agent 私鑰與其他實例資料都不在沙箱上；沙箱沒有任何 Agent VM 的登入憑證。
- **網路**：host-only 只有主機看得到，不對區網開放。Agent VM 只有 NAT 網卡時，經 VirtualBox NAT 由主機轉送即可連到 host-only 位址（VirtualBox 7.2.16／Windows 實測）。
- **多專案共用**：多個實例可共用同一台與同一個 target 名稱。skill 規定各專案狀態放 `$AI_TEAM_SANDBOX_HOME/<repo 名稱>/`、綁埠前確認未被佔用、
  不動其他專案的東西。這是規範不是隔離；要嚴格隔離就每個專案各建一台。

## 建立沙箱 VM

1. Agent VM：`deploy/bootstrap/sandbox-target.sh keygen`，產生專用 Ed25519 金鑰（已存在沿用），只印公鑰。
   金鑰沒有 passphrase，因為部署要無人值守；它在沙箱上只能執行受限入口。
2. VirtualBox 主機（Windows 用 Git Bash）：`deploy/sandbox-vm/create-sandbox-vm.sh <管理目錄> <公鑰檔> <target>`。
   - `deploy/sandbox-vm/Vagrantfile`：固定 `bento/ubuntu-24.04` 版本；NAT＋host-only；停用共享資料夾、剪貼簿與拖放；SSH 只綁 127.0.0.1；
     磁碟搬進管理目錄的 `machine/`（只在第一次開機前搬）。
   - guest 內先重新產生 SSH 主機金鑰（bento box 內建固定的主機金鑰，公開映像的所有 VM 共用同一把），再建立鎖定帳號 `aiteam`，以 `{"mode":"sandbox"}` 執行 `install-ssh-target.sh`。
   - 經 Vagrant 管理的通道讀主機公鑰，產生 `agent-known_hosts` 並印出指紋。外部 SSH 首次用 `accept-new`，取得後改成嚴格核對。
   - 新建 VM 時，會清掉 `~/.ssh/known_hosts` 裡同一個轉送埠的舊指紋（原檔保存為 `known_hosts.old`）。
   - `AI_TEAM_SANDBOX_NAME`／`CPUS`／`MEMORY`／`IP`／`ADAPTER` 可調（預設 4 CPU、4096 MB、192.168.56.20）。已有 VM 時沿用，不 destroy。

## 讓一個實例接上沙箱

部署含本功能的 orchestrator 後，在 Agent VM 執行（可重複執行）：

```bash
deploy/bootstrap/sandbox-onboard.sh <實例 home> <target> <IP> <known_hosts 檔> [--paths a,b] [--restart]
```

| 步驟 | 做什麼 | 為什麼 |
|---|---|---|
| 快取目錄 | 建立配置 `cachePaths` 宣告、但這台機器還沒有的目錄；是 symlink 就停止 | 缺了任務會停在派工前檢查（[跨專案執行](cross-project-execution.md#各階段預檢與證據)） |
| 團隊配置 | `sandbox-overlay.py`：沒有服務路線就從內附範本補上沙箱區塊並設定 `serviceTarget`；已有就只改目標名稱，列出沙箱不支援的內建 start／health／diagnose | 每個專案手改六處 JSON 易錯；先以候選配置 `--validate-team` 通過才寫回 overlay 與 `teams/`（原檔備份） |
| 工作樹大小 | 依 `--paths`（預設整個工作樹）量要送的大小，超過 128 MiB 列出最大的頂層路徑並警告 | exec 上限 128 MiB；不擋，因為 agent 能自行縮小（見下） |
| 登錄 | `sandbox-target.sh register`：寫直連設定（`mode: sandbox`、`source_paths`）、以固定協定 `status` 確認網路／金鑰／指紋／受限入口，安裝並啟動部署執行器，等 journal 出現 `ENTRY_UPGRADE: UPGRADE_UNCHANGED` 或 `UPGRADE_PASS` | 確認目標確實是 sandbox |
| 重啟 | `--restart` 且沒有執行中的任務才重啟實例 | 新配置只對重啟後的新任務生效 |

直連設定檔名為 `~/.config/ai-team/ssh-targets/<target>-<實例目錄名>.json`；執行器以設定檔路徑加鎖，所以同一台沙箱的每個實例各用一份。

## 部署時 agent 能自己處理的事

- **工具與套件**：prepare 裝好 SSH、Docker、Compose 與 rootless Docker；其餘由 agent 的腳本安裝，不交回使用者。
- **送出的來源太大**：exec 回 `SOURCE_TOO_LARGE` 時，agent 在腳本同目錄寫 `source-paths`（一行一個路徑），重新 exec。
  只能縮小管理者的 `source_paths`，超出回 `SOURCE_PATHS_OUTSIDE`，所以不是擴權。
- **腳本錯誤**：依收據最後 30 行輸出修正後重跑；部署腳本在交付後保存到實例的 `service-context/<target>.files/`，下次部署自動還原。

## rootless Docker

sandbox 目標的 prepare 會做下列事情（restricted 目標不變）：

1. 建立鎖定帳號 `aiteam-docker`，安裝 `docker-ce-rootless-extras`、`uidmap`、`dbus-user-session`、`slirp4netns`。
2. Ubuntu 24.04 起限制非特權 user namespace，所以補上 rootlesskit 的 AppArmor 設定檔。
3. 設定 `net.ipv4.ip_unprivileged_port_start=0`，讓容器能發布 80 等低埠。
4. 停用 root 的 `docker.service`／`docker.socket`／`containerd.service`（rootless dockerd 自帶 containerd）。原本的 root 容器保留但不再執行，收據會註明數量。
5. 啟動 `aiteam-docker` 的 rootless daemon，收據多一段 `docker=rootless`。

exec 時 `DOCKER_HOST` 指向 rootless socket，腳本照常用 `docker`／`docker compose`。容器資料用 named volume；bind mount 的目錄要讓 `aiteam-docker` 讀得到。
腳本仍是 root，理論上能重新啟用 root daemon，由 skill 規範約束；保護目標是「容器被攻破拿不到 VM 的 root」。

## 重建與多台

- **重建**：確認沒有 agent 正在部署，執行
  `AI_TEAM_SANDBOX_REBUILD_CONFIRM=<VM 名稱> create-sandbox-vm.sh --rebuild <管理目錄> <公鑰檔> <target>`。
  - 沒有確認變數就拒絕；腳本以本版 Vagrantfile 重建。
  - 主機公鑰會換，各實例的執行器會拒絕連線（不自動信任新指紋），須對每個實例重跑 onboard。
  - 沙箱上的安裝與資料全部消失；部署腳本會在下次部署自動還原。
- **多台**：用不同的管理目錄、`AI_TEAM_SANDBOX_NAME`、`AI_TEAM_SANDBOX_IP` 與 target 名稱各建一次，各實例 onboard 到自己的那台。

## 首次導入的問題分析（2026-10-29）

第一次從零建立並讓三個實例接上時遇到的問題，以及現在的處理方式：

| 現象 | 根因 | 處理方式 |
|---|---|---|
| `vagrant up` 失敗在開機前的 movevm | 搬移不能重複執行 | Vagrantfile 只在尚未搬過時搬 |
| movevm 回目的資料夾不存在 | 腳本沒先建 `machine/` | 建 VM 腳本先建 |
| `vagrant ssh` 停在互動詢問 | `verify_host_key` 只管 Vagrant 內部連線，外部 OpenSSH 要另外指定 | `extra_args` 加 `StrictHostKeyChecking` |
| Host key verification failed | `known_hosts` 有舊 VM 用過同一個轉送埠 | 新建 VM 時自動清除（先備份） |
| 每個專案要手改團隊配置 | 沒有工具 | `sandbox-onboard.sh`＋`sandbox-overlay.py` |
| 任務停在「快取目錄缺失」 | 新機沒有配置宣告的 cachePaths | onboard 建立 |
| start 回 `SOURCE_TOO_LARGE` | repo 含大型文件與二進位檔，超過 128 MiB | agent 以 `source-paths` 自行縮小；onboard 先量大小警告 |
| diagnose 回 `SERVICE_CONFIG_REQUIRED` | 舊配置用內建操作，沙箱不支援 | 範本改用 exec；onboard 列出既有配置的內建操作 |
| 重建後主機指紋沒變 | bento box 內建固定主機金鑰，所有由它建立的 VM 共用（私鑰隨公開映像流出） | 佈建時重新產生主機金鑰 |
| 重建後第一次登錄連線失敗，重跑即過 | 剛開機的 VM 第一個連線暫時失敗 | register 的連線檢查最多重試三次 |
| 容器以 root 執行 | 原設計未處理 | rootless Docker |
| Agent VM 卡死（RCU stall、soft lockup） | VirtualBox 走 Hyper-V（NEM）模式、8 vCPU，加上每晚存檔、隔天還原（依 log 推斷，未實驗驗證） | 維運注意事項（見下） |

經這些修正後，第一個真實任務由 agent 自己修好兩版部署腳本，以 docker compose 起前端、後端、資料庫，service 階段 COMPLETED，
並從主機以 HTTP 200 核對使用者入口。修正前需要真人介入的有三次：建快取目錄、縮小來源、請使用者續跑。
現在前兩項已改由導入工具或 agent 自行處理。

## 維運注意事項

- 主機的 Hyper-V（Docker Desktop／WSL2 會啟用）讓 VirtualBox 改走 NEM 模式，VM 效能變差，多 vCPU 時易出現 RCU stall。
  主機關機前以 `vagrant halt` 正常關機，不要存檔後隔天還原；VM 卡死時依 virtualbox-control skill 經授權才 reset。
- Vagrant VM 的開機前自訂動作必須能重複執行，否則第二次 `vagrant up` 會失敗。
- 由 bento box 建立、但沒經本腳本佈建的 VM（例如 Agent VM）仍使用映像內建的共用主機金鑰；要讓指紋核對有意義，需在該 VM 重新產生主機金鑰並更新各處 known_hosts。

## 驗證範圍

- **程式測試**：`test_sandbox.py`／`test_direct_ssh.py`（exec 來源縮小、rootless 準備順序與 `DOCKER_HOST`）、`test-sandbox-overlay.sh`（補區塊、冪等、保留既有路線）。
- **腳本實跑**：建 VM、`--rebuild`（含主機金鑰重新產生）、三個實例 `sandbox-onboard.sh`（重跑時配置 UNCHANGED、入口 UPGRADE_PASS／UNCHANGED）已在一台 Windows／VirtualBox 7.2.16 主機實跑。
- **Slack 端對端**：同一個 Gradle＋npm 專案先以 root Docker 部署成功；重建後再以 rootless Docker 部署成功（2026-10-30）：
  - prepare 收據 `docker=rootless`；root 的 `docker.service`／`docker.socket` 為 inactive；`dockerd` 與 rootlesskit 由 `aiteam-docker` 執行，`docker info` 有 `name=rootless`。
  - 容器內程序在主機上是 subuid 對應的非特權 UID；前端、後端、資料庫三個容器由 compose 啟動，主機 HTTP 200。
  - 從派工到 COMPLETED 約 14 分鐘，沿用上次保存的部署腳本，沒有停下來等真人。
  - `source-paths` 自修尚未在真實任務出現（這次送出的來源在上限內）。
