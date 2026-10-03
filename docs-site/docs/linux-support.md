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
