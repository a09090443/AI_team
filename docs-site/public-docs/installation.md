---
title: 安裝與驗證
---

# 安裝與驗證

本頁提供不綁定私人主機的安裝路線；詳細腳本契約以儲存庫 `docs-site/docs/INSTALL.md` 為準。
目前尚未完成乾淨 VM 的完整重建驗收，以下步驟不能視為已驗證的一鍵安裝。

## 環境與帳號

執行服務使用 Linux、systemd user unit、可用 user bus 與 user namespace。各發行版／架構的證據見 `docs-site/docs/linux-support.md`，未實測不可推定支援。
準備 Java 21、Maven、Node.js、Git，以及 `check-linux.sh`／`preflight.sh` 要求的系統工具。
服務帳號須依 INSTALL 啟用 linger，登出後 user manager 才能持續運作；目前未設定開機自動啟動，重開機後須重新確認服務。
需要 Slack workspace 的 App 安裝權限、目標 Git repo 權限，以及所用 CLI 的有效帳號。
Windows 可用於原始碼與文件開發，不等於已支援 Windows 服務沙箱。

## 安裝順序

以下在已準備工具鏈的 Linux 環境執行，`$PWD` 須為完整原始碼根目錄；目的地必須尚不存在。

```bash
bash deploy/bootstrap/check-source.sh
bash deploy/bootstrap/check-linux.sh runtime
bash deploy/bootstrap/install-app.sh "$PWD" "$HOME/ai-team"
```

`install-app.sh` 在暫存來源建置、執行 Maven package，複製 JAR、teams、prompts、schemas 與維運腳本；拒絕覆蓋目的地，完成後不自動啟動。
若還沒準備工具鏈，先依 INSTALL 與 `deploy/bootstrap/toolchain.tsv.example` 提供固定版本、下載來源與官方 checksum，再使用 `provision.sh`。
CLI 版本升級須重驗參數、沙箱與測試網路契約，不直接換 latest。

1. 本人完成 CLI 登入；登入檔與憑證不加入來源包。
2. 建立目標 repo 的完整本機 clone，確保服務帳號可讀取所需分支。
3. 編輯安裝目錄的 `env.sh`，將 `AI_TEAM_HOME` 設為實際安裝目錄，權限設為 600。
4. 依[設定指南](configuration.md)設定主 App、頻道、白名單、repo、CLI 路徑與團隊 JSON。
5. 執行前置檢查後啟動：

```bash
bash "$HOME/ai-team/preflight.sh"
bash "$HOME/ai-team/service.sh" start
bash "$HOME/ai-team/service.sh" status
```

目前 bootstrap preflight 仍要求 `CLAUDE_BIN` 與 `CODEX_BIN` 都有效；不要因團隊只用一種 runtime 就省略。
preflight 只查本機前置，不呼叫模型或 Slack，也不證明 CLI 登入、實際沙箱、訊息送達與 PR 成功。

## 分層驗收

| 層級 | 要確認的證據 |
|---|---|
| 來源與建置 | check-source、Maven 結果、來源版本與 JAR checksum |
| 啟動與隔離 | 服務啟動、自檢、工具鏈與實際沙箱能力 |
| Slack | 白名單真人在目標頻道提問，看到正確回覆；角色逐一測試 |
| 開發流程 | 實際任務完成需求、計畫、實作、驗收與審查 |
| 發布 | 真人核准後建立正確 PR；另行確認合併與產品驗收 |
| 乾淨重建 | 新系統、無沿用暖快取，逐層保存證據；目前未完成 |

連上 Socket Mode 或程序仍在執行，不等於端對端驗收通過。

## 升級、備份與復原

先確認沒有執行中與排隊工作，備份資料與配置，在隔離目錄建置後使用既有升級流程。
`deploy/deploy-orchestrator.sh` 與 `deploy/deploy-jar-safe.sh` 用於既有服務升級，新機使用 bootstrap。
不要在執行中服務的 target 目錄 clean/build。停止被拒時先處理工作，不強制停止來繞過保護。
資料庫沒有自動遷移機制，schema 或快照型別變更前必須規劃相容性與復原。

