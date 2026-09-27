# AI Team 文件網站

給 AI agent 與維護者使用的 Docusaurus 文件網站。主要讀者是 AI agent：
入口頁提供專案全貌與依任務找文件的路由，build 時另產生機器可讀的 `llms.txt`。

## 內容來源

| 路徑 | 內容 | 網址 |
|---|---|---|
| `docs/` | 專案所有原始文件（設計、流程、部署、驗收、ADR、歷史報告），唯一內容來源 | `/docs/<檔名>` |
| `agent/` | AI Agent 入口：總覽、系統全貌、操作指令、設定參考、修改規則 | `/agent/` |
| `catalog.mjs` | 每份文件的分類、狀態、用途、何時讀、相關程式 | `/catalog`、sidebar、`llms.txt` |

2026-09-27 起文件從 repo 根目錄的 `docs/` 移到 `docs-site/docs/`；AGENTS.md、README、Java 註解、
prompts、schema.sql 與部署文件中的引用已一併改為新路徑。文件中連到 repo 其他檔案的相對連結要多一層 `../`。

## 新增或刪除 docs-site/docs/ 文件時

在 `catalog.mjs` 的 `docs` 陣列新增或移除對應項目。`npm run build` 前會自動執行
`npm run check:catalog`，以下情況會讓 build 失敗：

- `docs-site/docs/` 有未登錄的 Markdown，或登錄了不存在的文件
- 分類或狀態不是已定義的值
- 非歷史文件缺少 `readWhen`
- `code` 列出的相關程式路徑不存在
- `agent/project-tree.md` 的目錄樹列了不存在的路徑，或 `orchestrator/src/main/`、`orchestrator/teams/`、
  `orchestrator/prompts/`、`orchestrator/schemas/`、`deploy/`、`docs-site/`（`docs/` 除外）有檔案沒列進樹中，
  或 repo 出現樹中沒有的頂層項目

prebuild 另執行 `node ../.agents/sync-agent-config.mjs --check`，檢查各 AI 工具的規則入口、skills 鏡像與
MCP 產生檔是否與 `.agents/` 單一來源一致（說明見 `agent/ai-tools.md`）。

狀態值：`current`（現行，可據以行動）、`record`（有日期的紀錄）、`decision`（ADR）、
`historical`（已被取代，不可照做）。

## 機器可讀輸出（build 產生）

| 檔案 | 內容 |
|---|---|
| `build/llms.txt` | 依分類的文件索引，含狀態、用途與 Markdown 原文連結 |
| `build/llms-full.txt` | agent 入口頁與所有「現行」文件全文 |
| `build/docs/<檔名>.md`、`build/agent/<頁>.md` | 每份文件的 Markdown 原文 |

產生邏輯在 `plugins/llms-txt.mjs`。連結使用 `docusaurus.config.js` 的 `url`，部署前要改成實際網址。

## 本機開發

需要 Node.js 20 或更新版本。

```bash
cd docs-site
npm ci
npm run start
```

開啟 <http://localhost:3000>。開發伺服器不會產生 `llms.txt`，要看機器可讀輸出請用 build。

## Production build

```bash
cd docs-site
npm ci
npm run build
npm run serve   # 預覽 build 結果
```

靜態輸出位於 `docs-site/build/`。部署目標尚未設定。
