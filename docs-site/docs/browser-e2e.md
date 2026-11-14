# 瀏覽器 E2E 驗收

讓 QA 與系統獨立驗證可以實際開啟頁面、操作並斷言結果，而不是只看建置或單元測試。
能力完全由配置啟用，不新增節點、不放寬沙箱；以 Playwright 為例，其他工具只要符合下列條件也能用同樣方式配置。

:::warning

**目前證據等級**：ubuntu-dev 上以最小專案實測三種沙箱包裝都通過（見下方），程式測試通過；
**尚未有真實 Slack 任務的端對端證據**，也還沒有任何實例啟用。

:::

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

## 配置片段（node-web）

以下片段經 `TeamCatalogTest.browserE2eSnippetFromDocsIsValid` 驗證可載入。專案的 `package.json` 需有 `test:e2e` 腳本（例如 `playwright test`）。

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
