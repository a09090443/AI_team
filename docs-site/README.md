# AI Team 文件網站

給使用者、管理者、開發者與 AI agent 使用的 Docusaurus 文件網站：
入口頁提供專案全貌與依任務找文件的路由，build 時另產生機器可讀的 `llms.txt`。

## 內容來源

| 路徑 | 內容 | 網址 |
|---|---|---|
| `docs/` | 專案所有原始文件（設計、流程、部署、驗收、ADR），唯一內容來源 | `/docs/<檔名>` |
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

靜態輸出位於 `docs-site/build/`。

## GitHub Pages

網站網址為 <https://a09090443.github.io/AI_team/>。2026-10-15 已啟用 GitHub Actions 作為 Pages 來源。
首次發布 [Actions run 38759864099](https://github.com/a09090443/AI_team/actions/runs/38759864099) 建置與部署成功；
首頁、architecture/、workflow/ 實測 HTTP200，agent/、llms.txt 為 HTTP404。
`.github/workflows/docs-pages.yml` 在 main push 或手動 workflow_dispatch 時執行 npm ci 與 npm run build:public，
通過文件、目錄樹、skills 同步、斷鏈與公開產物掃描後，只上傳 `docs-site/build-public/`，由 deploy-pages 發布。
GitHub 儲存庫 Settings → Pages → Build and deployment → Source 須先設為 GitHub Actions。
原私有儲存庫的帳號方案不支援 Pages；使用者確認公開範圍後，已將整個儲存庫改為 public。
公開網站只載入下述審閱過的一般手冊；儲存庫本身的程式、內部文件、交接紀錄與 Git 歷史則均可公開讀取。

`docusaurus.config.js` 使用 url `https://a09090443.github.io`、baseUrl `/AI_team/` 及 trailingSlash，
機器可讀索引與頁面資源均沿用此子路徑。本機 npm run start／serve 也使用 `/AI_team/`。
發布後檢查首頁、`architecture/` 與 `workflow/`，並確認 Actions 的 deploy 成功。
部署權限只授予 deploy job 的 pages:write 與 id-token:write，不需另設 PAT。

### 公開範圍與機密檢查

2026-10-15 審閱發現內部文件包含真實 VM 連線、登入帳號與部署紀錄，因此公開版改以獨立設定
`docusaurus.public.config.js` 只載入 `public-docs/` 人工審閱的一般手冊。2026-10-15 依使用者核准擴充為十頁，涵蓋使用、管理、架構與 AI 入口。
公開版停用內部 docs、agent、catalog、內部自訂頁面、static 目錄與內部 llms 全文外掛；
不修改或刪除內部原始證據。`npm run build` 仍建立完整內部站台，**不得將 build/ 上傳公開託管**。

`npm run build:public` 建立獨立 build-public/ 並執行 `scripts/check-public-output.mjs`，
阻擋內部路由、私鑰檔與 source map，並核對公開 AI 輸出只來自公開清單，掃描已知憑證樣式、私人路徑及私網位址。
掃描只顯示檔案位置與規則名，不輸出疑似秘密。自動掃描不能保證找出所有機密，
新增公開文件仍須人工審閱；不得把內部原文或部署紀錄直接搬入 public-docs。
public-docs 是獨立公開手冊，不屬於內部 docs 文件目錄；其檔案列於 project-tree 並由既有檢查涵蓋。

### 公開站版型

公開站採 Docusaurus classic 首頁格式：綠色橫幅、閱讀按鈕與三欄特色插圖，
入口在 `public-pages/index.js`，公開介紹位於 `/intro/`，架構與流程網址沿用原路徑。
`sidebars-public.js` 提供左側文件分類，右側由標題產生章節目錄；頁尾與 GitHub 導覽由公開設定控制。
`src/css/public.css` 僅影響公開站，內部站沿用 custom.css。
`public-theme/theme/SearchBar/index.js` 搜尋公開清單中全部文件的標題與人工設定關鍵字，無全文索引或外部搜尋服務。
新增公開頁時須同步補搜尋項目並人工審閱。手機導覽沿用 classic 的收合選單。

## 公開 AI 輸出（2026-10-15）

public-manual.mjs 是側欄、搜尋與 plugins/public-llms.mjs 共用清單。公開版產生 llms.txt、llms-full.txt 與 manual/<id>.md，僅取 public-docs 原文，不引用內部 catalog 或交接資料。檢查器核對清單完整性與輸出逐位元一致，保留機密樣式掃描。新增頁面需更新清單與目錄樹並人工審閱。先前 llms.txt HTTP404 為首次發布證據，新版發布後須另驗線上結果。
