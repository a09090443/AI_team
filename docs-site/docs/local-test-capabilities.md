# 本機測試與文件副本建置能力

外層沙箱預設會擋掉兩類驗收常用的操作：建立 localhost socket（例如 GreenMail SMTP 回報
`Operation not permitted`），以及在唯讀的原始目錄執行 `npm ci`／Docusaurus 建置（`EROFS`）。
本文說明系統開放的兩種有範圍能力。

## `localTestNetwork` 節點選項

預設 false。靜態驗證只允許同時符合下列條件的節點使用，否則配置載入就會被拒絕：
Codex runtime、LIMITED 範圍、VERIFICATION 閘門、且宣告 node 工具。其他 runtime 與舊快照不自動套用；
把 QA 改成 claude-code 或 opencode 時必須一併關閉，代價是需要本機 socket 的情境會再度受阻。

啟用後 Codex 使用受限 permission profile 與 network proxy：

- domain 允許清單為空、不使用 upstream proxy、不開放 Unix socket。
- 隔離 namespace 內的本機 TCP 可運作；外部直連與經 proxy 外連實測被拒絕。
- **不**授權連接主機上的既有服務。
- 模型服務連線與指令網路是不同通道，不能把指令網路限制說成整個程序斷網。

內層維持 workspace-write 類型的「根目錄可寫」模型，細粒度寫入範圍仍由外層 systemd 限制。
若把每個子目錄都當成 Codex 可寫根，Codex 會為它們建立 metadata 掛載點，使 `npm ci` 無法移除
`node_modules/.codex`（`EBUSY`）。指令可寫的快取與核准的 `cachePaths` 對齊，不把 runtime 的 `~/.codex` 開放給指令；
產品路徑、Git metadata 與原始文件的限制不撤除。

所有 LIMITED 節點都會為明確（無萬用字元）的可寫目錄準備掛載根，不再限於 `localTestNetwork` 節點
（2026-10-04 起；原本其他節點缺目錄即失敗，見[配置式團隊](configurable-team.md)）。wildcard 不猜測缺少的目錄，
路徑祖先或本身是 symlink 時停止。

**localhost 的 HTTP 要帶 NO_PROXY（2026-10-23）**：Codex 啟用 network proxy 時，指令環境的 `NO_PROXY` 被清空、`NODE_USE_ENV_PROXY=1`，
Node 的 fetch 連 `localhost`／`127.0.0.1` 也會送進 proxy 而被拒（`Network access to "localhost" was blocked`），Vitest 因此每輪中止；
原始 TCP 不經 proxy，所以舊預檢抓不到。`shell_environment_policy` 會被 proxy 覆蓋，只有寫在指令本身的環境變數有效（codex-cli 0.156.1 於 VM 實測）。
因此系統列給 `localTestNetwork` 節點的固定操作一律加上前綴 `env NO_PROXY=localhost,127.0.0.1,::1 no_proxy=localhost,127.0.0.1,::1`（`ConfiguredExecutor.LOOPBACK_NO_PROXY`），
只放行 loopback，外連仍被拒（實測）。配置裡的 argv 不變。

呼叫模型前，以相同內外層設定測試 localhost TCP 往返、帶上述前綴以 fetch 連 localhost HTTP（2026-10-23 新增），以及可寫路徑（unit 最長 25 秒、客戶端最長 30 秒），
失敗就停止，不略過不相容的 CLI、不退回無沙箱。於 Codex 0.153.4 實測；未宣稱所有 CLI／OS 版本都支援。
官方設定背景：[權限設定](https://learn.chatgpt.com/docs/permissions)。

## 文件副本建置

QA 不在原始文件目錄執行 `npm ci`／build，而是把目前 HEAD 的文件與 llms 檔案複製到 `target/qa-docs`，
在副本安裝與建置。整個 `target` 是可寫的產物範圍，可刪除並重建其下的 `node_modules`／`build`／`.docusaurus`；原始檔保持唯讀。
以文件網站在 `doc-site/`、根目錄有 llms 檔的專案為例：

```sh
mkdir -p target/qa-docs
git archive HEAD doc-site llms.txt llms-full.txt | tar -x -C target/qa-docs
npm --prefix target/qa-docs/doc-site ci --offline --ignore-scripts --no-audit --no-fund
npm --prefix target/qa-docs/doc-site run build
cmp llms.txt target/qa-docs/doc-site/build/llms.txt
cmp llms-full.txt target/qa-docs/doc-site/build/llms-full.txt
```

這是該專案經實測的操作契約，不代表其他專案都能略過安裝 scripts。
Maven 使用 `-o`；QA 沙箱不為此開放套件站外連。依賴（含升級的新版本）由實作階段下載進共用快取：
實作節點的 Claude Code 操作跑在只限檔案系統的 systemd 沙箱，可以連外，`mvn`／`gradle`／`npm ci` 會寫進團隊配置 `cachePaths` 宣告的快取。
QA 遇到快取缺件時，代表實作階段沒有跑過對應建置，要退回實作補跑，不是維運問題。
（`testInstructions` 應這樣寫；若寫成「快取不足須回報，不得下載」，升級依賴的任務會停下等維運補快取。）

## 讓既有任務取得這項能力

既有任務使用建立時的配置快照，不會自動取得新能力。能力遷移可預覽 nodes 的 `writablePaths`／`localTestNetwork`／`timeoutSeconds`，
但不得夾帶 runtime、路由、scope、保護路徑或重跑／回合上限變更（同一 runtime 內換模型是允許的）；操作步驟（檢視配置更新 → 核准配置更新 → 核准恢復驗收 → 重新核准測試計畫）
見 [INSTALL §6.1](INSTALL.md#61-真人核准閘門必讀否則全管線驗收會停住) 與 [跨專案執行契約](cross-project-execution.md)。
僅更新服務或核准配置，都不會自動多給一次執行機會；流程總上限仍是硬限制，PR 核准獨立。
