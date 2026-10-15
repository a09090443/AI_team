---
title: 開發與文件維護
---

# 開發與文件維護

先讀根目錄 AGENTS.md 與 repo 指定的交接檔，再從文件入口、目錄與目錄樹找權威文件；以文件建立理解後才讀程式。
程式與 schema 是行為最終依據，發現落差同次修正文件。大範圍修改先確認計畫。

## 修改位置

| 修改內容 | 位置與注意事項 |
|---|---|
| 新角色／流程 | `orchestrator/teams/` 的 JSON、prompts、schemas；不擴充 legacy switch |
| 流程引擎與契約 | Java `flow/`；核對前置結果、路由、失效傳遞與發布閘門 |
| Slack 互動 | Java `slack/`；核對白名單、原討論串與提出者權限 |
| runtime／隔離 | Java `runtime/`、`sandbox/`；CLI 升級重驗參數與雙層沙箱 |
| 資料庫／序列化 | schema.sql、TaskStore、FlowRun；同步資料模型與相容性策略 |
| AI 規則／skills／MCP | AGENTS.md 與 `.agents/` 單一來源，產生檔不手改 |
| 公開文件 | `docs-site/public-docs/`；同步公開清單、導覽與專案目錄樹 |
| 完整內部文件 | `docs-site/docs/` 與 `agent/`；docs 新增／刪除同步 catalog |

## 驗證方式

Java 變更依影響範圍執行 Maven 測試；角色、runtime、schema、teams 或其他設定變更還須執行來源檢查。
在 repo 根目錄的 Bash 環境：

```bash
bash deploy/bootstrap/check-source.sh
```

在隔離來源的 orchestrator 目錄執行 `mvn verify`，不要在執行中服務的 target 建置。
Windows 的 shell／符號連結限制不能當成 Linux 通過；依測試結果如實回報，必要時補 Linux 驗證。

文件網站使用 Node.js 20 以上；在 docs-site：

```bash
npm ci
npm run build
npm run build:public
```

完整站 build 檢查 catalog、專案目錄樹、共用工具設定一致性與斷鏈。
公開 build 另掃描私人路徑、私網位址與已知憑證樣式，並核對機器可讀輸出僅來自公開清單。
自動掃描有範圍限制，新增內容仍須審閱。

## 維護與交付

公開站保留一般操作與架構說明；私人環境、真實部署紀錄與交接資料留在原位置。
GitHub Pages workflow 只發布 build-public；完整 build 不上傳公開託管。
新增公開頁時同步 `public-manual.mjs`，它供側欄、搜尋與 AI 索引共用。
完成後更新 repo 進度紀錄，列出已核對／修正文件與證據層級。commit 使用台灣繁體中文並遵循 AGENTS.md 格式。
