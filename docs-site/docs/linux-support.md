# Linux 環境與驗證矩陣

更新：2026-09-06。發行版辨識只是選擇診斷提示，**不是支援認證**。
目前沒有任何發行版完成新版 bootstrap 的乾淨 VM 全流程驗收。

| 環境 | 架構 | 狀態 |
|---|---|---|
| 既有 Ubuntu 26.04 VM | x86_64 | 舊環境服務運作、自檢與應用測試有紀錄；不等於新版 bootstrap 重建通過 |
| Debian／Ubuntu 新 VM | x86_64 | 2026-10-02 起以全新 Ubuntu 26.04 LTS（VM「Agent」）演練：check-linux runtime（放寬 AppArmor userns 後）、provision、install-app 已通過；登入、服務自檢與完整管線尚未完成，見 [rebuild-verification](rebuild-verification.md) |
| Rocky／AlmaLinux 新 VM | x86_64 | 優先驗證對象，尚未指定版本與完成重建 |
| 其他 glibc Linux | x86_64 | 僅能力檢查；未驗證，不承諾可用 |
| 任一 ARM64 Linux | aarch64 | 目前腳本拒絕；未備妥對應工具鏈與測試 |
| 非 glibc／非 systemd 配置 | 任意 | 不符合目前建置配置，不放行應用安裝／啟動 |

## 共用能力檢查

```bash
bash deploy/bootstrap/check-linux.sh tools
bash deploy/bootstrap/check-linux.sh runtime
```

tools 檢查 Linux x86_64／glibc、下載／解壓／checksum／鎖定工具。
runtime 再檢查 git、jq、sqlite3、systemd 相關工具、PID 1、user bus、cgroup v2 的
cpu／memory／pids controllers 與實際 `unshare -Ur true`。
os-release 當文字解析，不以 shell source 執行。未知 ID_LIKE 不會被誤判為已驗證平台。

檢查通過只表示必要前置條件可見；它不證明 user cgroup delegation、systemd 版本旗標、
檔案系統隔離、資源限制或 CLI 動態函式庫相容性。仍須跑 ExecutionSandbox 自檢、兩個 runtime 契約與端對端。
本建置配置目前要求 cgroup v2；沒有宣稱舊版 cgroup 在技術上永遠不可行。

## 發行版差異的處理邊界

- Debian／Ubuntu 家族：提示管理員依實際版本的 apt 套件目錄補項。
- Rocky／AlmaLinux 家族：提示管理員依實際版本的 dnf 套件目錄補項。
- 其餘：列出缺少的指令，由管理員確認套件來源。
- 不硬套套件名稱（例如 sqlite3 與 sqlite、iproute2 與 iproute 可能不同），不自動安裝或加套件庫。
- 不因 family 自動假設 SELinux／AppArmor 狀態；檢查可見資訊，失敗時保留原始 unshare 錯誤。
- 不自動 setenforce、停用 AppArmor 或改 sysctl。根因未知時應查安全稽核、kernel 與 user manager，不能猜。
- systemd user 設定與開機／登出生命週期，由管理員依部署政策處理，再重新驗證。

## 驗收與證據

版本、架構、libc、systemd、核心、ISO checksum、工具鏈 manifest 與冷快取 log 都必須記錄。
每個發行版／版本分開驗證；不得把 Ubuntu 成功推論成 Rocky 已成功。
發布、憑證與現有 VM 保護規則仍遵循 [INSTALL.md](INSTALL.md)。
本機 fixture 的 16 項斷言驗證分類與拒絕邏輯，不驗證真實 Linux 的 kernel 或安全政策。
選用的[瀏覽器 E2E](browser-e2e.md) 只在 Ubuntu 24.04 實測過沙箱內啟動；其他發行版的函式庫套件名稱不同，未驗證。

## Windows 主機上的 VirtualBox 停滯排查

Windows 啟用 Hyper-V 時，VirtualBox 可能改走 NEM 相容路徑；`VBox.log` 若出現
`AMD-V is not available`、`Snail execution mode`，表示沒有使用預期的直接硬體虛擬化路徑，
不能僅據此判定 BIOS 沒開虛擬化。[Oracle 7.2 疑難排解](https://docs.oracle.com/en/virtualization/virtualbox/7.2/user/Troubleshooting.html)
將 VirtualBox 與 Hyper-V 共存列為效能衝突情境；這是可疑因素，不能單憑該訊息認定它造成某次核心停滯。

Linux 的 `soft lockup`／`RCU stall` 是 CPU 或核心工作未及時推進的警訊；
計時器、中斷、排程、核心缺陷與負載都可能相關，時間跳動也可能觸發警告。
必須對照堆疊與前後事件，不能把 `OOM is now expected behavior` 當成已發生 OOM 的證據。
原因分類與堆疊判讀見 [Linux RCU 文件](https://docs.kernel.org/RCU/stallwarn.html)。

排查順序：先保存主控台、`VBox.log` 的 NEM／心跳／時間追趕紀錄與 guest kernel log，
再核對主機及客體的記憶體、CPU／I/O 壓力、同時執行的 VM 與工作負載；SSH 不通時標示無法取得的證據。
主機當下尚有記憶體，不代表客體從未缺記憶體；調低 vCPU 或冷開機能恢復，也不代表根因已排除。
用固定工作負載、相同觀察時間做單一變因對照，才區分虛擬化後端、核心與資源競爭。
停用 Hyper-V 或相依安全功能涉及主機環境與防護，需另行評估與授權，不為了通過驗證直接停用或放寬沙箱。

### 直接硬體虛擬化的可還原測試

以下是管理員評估影響後手動執行的主機操作，不是 agent 可自行停用防護的授權。
先正常關閉所有 VM、退出 Docker Desktop 並保存 WSL 工作；BIOS／UEFI 的 SVM／AMD-V 保持啟用。
停用 Windows hypervisor 期間，WSL2、依賴 Hyper-V／WSL2 的 Docker 後端，以及依賴 hypervisor 的安全功能會受影響。
若有 BitLocker／裝置加密，先確認可取得復原金鑰；不為了此測試直接關閉 Secure Boot 或解密磁碟。

以系統管理員身分開啟 PowerShell，先用 `bcdedit /enum "{current}"` 記錄原設定，
再執行 `bcdedit /set "{current}" hypervisorlaunchtype off`，成功後重新啟動 Windows。
這是持續生效的開機設定，不會在下一次重開機自動還原；語法與影響見
[Microsoft BCDEdit 文件](https://learn.microsoft.com/en-us/windows-hardware/drivers/devtest/bcdedit--set)。

重開後用 `(Get-CimInstance Win32_ComputerSystem).HypervisorPresent` 確認應為 `False`，
再啟動 VM 並檢查本次 `VBox.log` 的硬體虛擬化初始化；不能只看 VM 設定勾選狀態。
若仍為 `True` 或仍走 NEM，先查 VBS／Memory Integrity／Credential Guard 與管理政策，
不要直接連帶停用；相依關係見
[Microsoft Hyper-V 衝突說明](https://learn.microsoft.com/en-us/troubleshoot/windows-client/application-management/virtualization-apps-not-work-with-hyper-v)。
成功切換後，以相同負載與觀察時間比較停滯情形，尚未完成對照前不可宣稱根因已排除。

還原時先正常關閉 VM，再以管理員執行 `bcdedit /set "{current}" hypervisorlaunchtype auto` 並重新啟動。
若原本未設定此欄位而需精確還原，改用 `bcdedit /deletevalue "{current}" hypervisorlaunchtype`。
確認 Windows hypervisor、原有安全功能與 WSL2／Docker 狀態恢復；此指令不會重新安裝已移除的 Windows 功能。
