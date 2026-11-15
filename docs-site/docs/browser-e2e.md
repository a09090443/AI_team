# 瀏覽器 E2E 驗收

讓 QA 與系統獨立驗證可以實際開啟頁面、操作並斷言結果，而不是只看建置或單元測試。
能力完全由配置啟用，不新增節點、不放寬沙箱；以 Playwright 為例。

:::warning

**目前證據等級**：
- **已就緒專案的路徑有真實 Slack 任務端對端證據**（2026-11-15，一個 Next.js 前端專案）：計畫判定已就緒並排入 `e2e`；
  前端角色（claude-code，權限 `Bash(npm --prefix <前端目錄> *)`）接到實作；系統獨立驗證在全新 clone 追加兩個指令並通過
  （首次下載 chromium，9 個測試在 2 個 worker 下約 59 秒）；QA 以收據引用 `test:e2e` 判定通過，審查無阻擋。
- 同一輪實測先發現並修正兩個問題：操作的工作目錄寫法讓 claude-code 角色被判缺權限、`~/.cache/ms-playwright` 不存在而停在派工前檢查（見下方「一行啟用」）。
- **截圖貼到討論串有真實證據**（2026-11-15，同一專案開啟 `screenshot: 'on'` 的任務）：獨立驗證保留 9 張（每個測試一張）並上傳，收據 `e2eScreenshots=9`。
  實測發現主機沒有 CJK 字型時截圖的中文全是方框，見下方「主機前置」。`screenshot: 'on'` 拍的是測試結束時的畫面，不是每個斷言步驟。
- **詢問按鈕與安裝任務仍沒有真實任務證據**，只有程式測試。

:::

## 一行啟用（2026-11-15 起，建議）

管理者在實例配置加一行，填前端目錄（repository 相對路徑，前端在根目錄時填 `.`）：

```json
"project": { "browserE2e": { "directory": "<前端目錄>" } }
```

之後流程如下，不需要再改配置或重新部署：

1. **載入時展開**：`BrowserE2e.expand` 把固定的一組設定加進配置，再照常經全部安全檢查：
   - `e2e-browsers` 操作（`npm --prefix <前端目錄> exec -- playwright install --only-shell chromium`，給實作類節點）；
   - `e2e` 操作（`npm --prefix <前端目錄> run test:e2e`，給實作類與驗收節點）；
   - `.cache/ms-playwright` 快取、`<前端目錄>/test-results`、`playwright-report` 可寫路徑與附檔允許路徑。

   兩個操作都在 repository 根目錄執行，與系統獨立驗證追加的指令完全相同（前端在根目錄時沒有 `--prefix`）。
   claude-code 角色的權限要涵蓋它們，例如 `Bash(npm --prefix <前端目錄> *)` 或 `Bash(npm *)`；
   涵蓋不到時，派工前檢查會拒絕把實作派給該角色。2026-11-15 之前的版本是在前端目錄跑 `npm exec`／`npm run`，
   只寫 `npm --prefix` 權限的角色因此被拒，已改正。
   `~/.cache/ms-playwright` 不存在時由系統在派工前檢查建立（只限這一個路徑，路徑上有 symlink 時不建立）；其他 `cachePaths` 仍由管理者建立。

   管理者已寫同名操作時以管理者為準；`attachments` 已有 `allow` 時合併，沒有 `allow`（全允許）時不動。
2. **專案還沒有 Playwright 時**：
   - 計畫與驗收階段的提示會告知「尚未加入」，頁面驗收項目照延後驗收或受阻處理。
   - 測試計畫判斷需求有頁面驗收時，在 `browser_e2e_offer` 填理由，討論串會貼一則詢問與「啟用瀏覽器 E2E」按鈕。
   - 這則詢問不擋本任務，只有驗收負責人能按，任何任務狀態都能按。
   - 按下後系統在頻道另開**安裝任務**：加入 `@playwright/test`、`playwright.config.ts`（`webServer` 綁 127.0.0.1）、`test:e2e` script、用 `page.route` 攔截 API 的基本測試。
   - 安裝任務照常走計畫、實作、驗收、審查，PR 由負責人核准。
   - 同時只會有一個進行中的安裝任務；配置已關閉或專案已就緒時按鈕只回覆原因。
3. **專案就緒後自動生效**：前端目錄有 `playwright.config.{ts,js,mjs,cjs}`，且 `package.json` 有 `test:e2e` script，就算就緒。就緒後：
   - 計畫提示改為引用 `e2e` 操作；
   - 系統獨立驗證在配置指令後追加下載瀏覽器與 `test:e2e` 兩個指令。這兩個指令不佔 `verification.commands` 的 5 個上限；配置有 `commandScopes` 時，只在前端目錄有變更時才跑。
   - 獨立驗證的就緒判定讀任務 HEAD 已提交的檔案，執行與發布前核對用同一個判定。安裝任務本身也會在它自己的 HEAD 就緒後跑到 E2E。
   - 計畫階段（在主 repo 執行、尚無任務工作區）與「啟用瀏覽器 E2E」按鈕讀 `origin/<基準>` 已提交的檔案，不讀主 repo 工作樹；
     工作樹因有修改或分歧而沒快轉時，才不會把已合併的 Playwright 判成未就緒、重複詢問或重開安裝任務（2026-11-15）。

### 截圖（2026-11-15 起）

系統獨立驗證本輪實際跑了 `test:e2e`（略過或重用都不算）時：

1. clone 刪除前，系統把 `<前端目錄>/test-results` 下的 PNG 複製到 `verification-runs/<任務>/e2e-screenshots/<commit>/`，
   只收一般檔案、不跟隨 symlink，張數與單檔大小沿用 `attachments` 上限（最多 10 張、10 MB）。
2. 以主 App 貼到任務討論串，檔名是 Playwright 以測試標題命名的子目錄。沒有截圖時改貼一行說明，指出專案要開啟截圖。
3. PR 說明「系統獨立驗證」段寫截圖張數與「已貼在 Slack 討論串」。GitHub 沒有上傳圖片的 API，所以圖片不放進 PR。
4. 截圖只給人看，判定仍以測試斷言與 log 雜湊為準；複製或上傳失敗只留稽核與說明，不影響收據與流程。
5. 附檔提示（`Attachments.notice`）會告知各節點：E2E 截圖由系統統一貼出，不要再列進 attachments。
   （#1052 實測：實作與 QA 各自附了同一批截圖，加上系統貼的，討論串共 20 張。）
6. 計畫與驗收階段的系統提示會說明收據的 `e2eScreenshots` 欄位，需求要求「要留下截圖」時 QA 直接引用，不必另設檢查操作。
   （#1050 實測：提示沒寫時，QA 把這類驗收判成缺工具而停下等人。）

專案的 `playwright.config` 要在 `use` 設 `screenshot: 'on'`，通過的測試才會留下畫面（Playwright 預設 `off`）。
安裝任務的固定需求已包含這一項；先前已裝好 Playwright 的專案要另外加上。

### 怎麼確認任務真的跑了 E2E

agent 自陳不算數，以系統紀錄為準：

| 位置 | 看什麼 |
|---|---|
| PR 說明「系統獨立驗證」段 | 指令清單有 `npm --prefix <前端目錄> exec -- playwright install --only-shell chromium` 與 `npm --prefix <前端目錄> run test:e2e`，標 ✅ 執行（exit 0）並附紀錄雜湊；標 ⏭️ 略過時後面寫原因（例如變更不在前端目錄） |
| Slack 討論串 | 「系統獨立驗證」進度卡；跑了 E2E 時系統貼一則「瀏覽器 E2E 已通過」與截圖；有指令被略過或重用時，系統另貼一則「依變更檔案判定」說明 |
| 稽核紀錄（`audit_log`） | `獨立驗證收據：` 開頭的一筆，內含每個指令的 argv、exit code 與雜湊 |
| QA 驗收紀錄（PR 說明「驗收紀錄」段） | 頁面驗收項目引用 `e2e` 操作或獨立驗證收據；有附檔時是 `test-results`／`playwright-report` 下的截圖 |
| 計畫階段提示 | 寫「瀏覽器 E2E 已就緒」；若寫「尚未加入」代表系統判定未就緒，這個任務不會追加 E2E |

指令清單裡沒有這兩個指令，代表該任務沒有跑 E2E（未就緒或沒有設定 `browserE2e`）。

不要詢問時設 `"offer": false`（展開與自動生效照舊）。基準驗證（`BaselineVerifier`）不追加 E2E。
需要 `npm`；Codex 驗收節點仍需 `localTestNetwork` 才能連本機預覽伺服器（見下方）。

## 範圍

- **支援**：測試指令自己在本機啟動預覽伺服器、跑瀏覽器、結束時關閉（Playwright 的 `webServer`）。
  系統沒有背景程序機制（每個指令是一個 systemd unit，結束時整個 cgroup 終止），所以伺服器與測試必須在**同一個指令**內。
- **不支援**：對沙箱 VM 上已部署的網址跑 E2E。QA 在部署之前執行，Codex QA 的網路只開放 loopback，
  沙箱 VM 也沒有把截圖傳回的通道。部署後仍由系統實測交付網址的 HTTP 狀態碼（見[服務建置](service-delivery.md)），
  無法在本機驗的項目照[延後驗收](engineering-workflow.md)處理。
- **agent 看不到截圖**：Codex／opencode 無法讀圖。判定一律以測試斷言為準，截圖只是給人看的佐證。

## 運作方式

| 執行位置 | 瀏覽器從哪裡來 | 網路 |
|---|---|---|
| 實作／前端節點 | `cachePaths` 的 `.cache/ms-playwright`（Playwright 預設路徑），由 `e2e-browsers` 操作下載 | 依 runtime |
| QA（verification 節點） | 同一份快取，唯讀使用即可 | Codex 開 `localTestNetwork` 時只有 loopback；claude-code 不隔離 |
| 系統獨立驗證 | `<實例 home>/verification-cache/ms-playwright`（`IndependentVerifier.cacheEnvironment` 設定 `PLAYWRIGHT_BROWSERS_PATH`），首次下載後跨任務沿用 | 不隔離 |

QA 與獨立驗證都不能下載時，快取缺件代表實作階段沒有跑 `e2e-browsers`，與其他依賴快取一樣交回實作補跑。
系統獨立驗證的收據綁定 HEAD，QA 可直接引用，不必在沙箱重跑同一組 E2E。

## 手寫配置片段（進階）

要自訂指令或節點分配時可以不用 `browserE2e`，直接手寫。手寫時系統不做就緒判定，指令一律執行，所以要等專案已有 Playwright 再加。以下片段經 `TeamCatalogTest.browserE2eSnippetFromDocsIsValid` 驗證可載入。專案的 `package.json` 需有 `test:e2e` 腳本（例如 `playwright test`）。

```json
"project": {
  "cachePaths": [".npm", ".cache/ms-playwright"],
  "operations": {
    "e2e-browsers": {"nodes": ["implementation", "frontend"], "directory": ".",
      "argv": ["npm", "exec", "--", "playwright", "install", "--only-shell", "chromium"], "writes": []},
    "e2e": {"nodes": ["implementation", "frontend", "verification"], "directory": ".",
      "argv": ["npm", "run", "test:e2e"], "writes": ["test-results", "playwright-report"]}
  },
  "verification": {"commands": [
    ["npm", "ci"], ["npm", "test"], ["npm", "run", "build"],
    ["npm", "exec", "--", "playwright", "install", "--only-shell", "chromium"],
    ["npm", "run", "test:e2e"]
  ]}
},
"nodes": {"verification": {"writablePaths": ["tests/**", "node_modules/**", "coverage/**", "dist/**", "test-results/**", "playwright-report/**"]}},
"attachments": {"maxFiles": 10, "maxBytes": 10485760, "allow": ["test-results/**", "playwright-report/**"], "deny": []}
```

- 用 `npm exec` 而不是 `npx`，`requiredTools` 不必多加工具，claude-code 角色既有的 `Bash(npm *)` 也涵蓋。
- `verification.commands` 最多 5 個；放不下時保留 `test:e2e`，瀏覽器下載改由專案的 `pretest:e2e` 腳本處理。
- `attachments` 是整個團隊共用的政策：`allow` 只列測試產物時，其他角色也只能附這些路徑；已有附檔設定的團隊要合併。
- gradle-fullstack 類專案把 `directory` 換成前端目錄（例如 `frontend`），`writes` 與 `writablePaths` 跟著加上前綴；
  Codex QA 的固定操作會由系統自動加上 loopback `NO_PROXY` 前綴（見[本機測試能力](local-test-capabilities.md)）。

專案端的 `playwright.config`：

- `webServer` 啟動預覽伺服器並綁 `127.0.0.1`，`reuseExistingServer: false`。
- `use: { screenshot: 'on' }`，系統才有截圖可以貼到討論串。
- 不需要額外的 Chromium 參數：Playwright 預設 `chromiumSandbox: false`，實測在雙層沙箱內可啟動。
- `workers` 從 1 開始；agent 節點 `TasksMax=512`、獨立驗證 `TasksMax=256`，多 worker 前先觀察。

## 主機前置（管理者執行）

headless Chromium 需要系統函式庫，bootstrap 腳本不會用 sudo 安裝。Ubuntu 24.04 上最小清單（實測後 `ldd` 無缺件）：

```bash
sudo apt-get install -y --no-install-recommends libatk1.0-0t64 libatk-bridge2.0-0t64 libatspi2.0-0t64 libgbm1 libxcomposite1 libxdamage1 libxext6 libxfixes3 libxrandr2
```

截圖要顯示中文時另裝 CJK 字型（例如 `fonts-noto-cjk`），否則會是方框。完整清單可用 `npx playwright install-deps --dry-run chromium` 查詢。

## 實測紀錄（ubuntu-dev，Playwright 1.49.1，codex-cli 0.166.0）

最小專案：Node 靜態頁＋`webServer`，一個點擊斷言與截圖。

| 包裝 | 結果 | pids 峰值 | 記憶體峰值 |
|---|---|---|---|
| agent 節點外層 systemd（`ProtectSystem=strict`、`ProtectHome=read-only`、`PrivateTmp`、TasksMax 512） | 通過、產生截圖 | 95 | 約 280 MB |
| 上述＋Codex `localTestNetwork` profile（指令帶 loopback `NO_PROXY`） | 通過、產生截圖；`page.goto` 外部網站被拒 | 114 | 約 310 MB |
| 獨立驗證式 `env -i`＋私有 HOME（TasksMax 256、CPU 200%） | 通過、產生截圖 | 96 | 約 280 MB |

實際專案的 dev server、多個頁面與多 worker 會更重，資源上限是否足夠要在第一個真實任務觀察。
