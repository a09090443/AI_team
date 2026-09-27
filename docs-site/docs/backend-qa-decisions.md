# Backend／QA 角色定義：四項決策

**狀態：四項全部拍板，Backend 與 QA 兩側都已實作並驗證。**

| 決策 | 結論 | 落點 |
|---|---|---|
| 1. 越界 | 停下來回報，不自行擴大範圍 | `impl-schema.json` 的 `boundary_conflict`、`TaskOrchestrator.stopAfterBackend` |
| 2. 測試責任 | Backend 寫測試並讓 verify 過；QA 檢查「有沒有測到對的東西」 | `prompts/backend.md`、`impl-schema.json` 的 `tests`／`quality_gate` |
| 3. 失敗 | 誠實回報，禁止改斷言求綠，無應用層重試 | `impl-schema.json` 的 `status`／`blockers` |
| 4. QA 權限 | 用沙箱擋，**再加一道 commit 路徑稽核** | `SandboxPolicy.qaReview`、`TaskOrchestrator.enforceQaWriteScope` |

以下保留當初的推理過程與未採用的選項 —— 日後要改決定時，需要知道當初為什麼這樣選。

這四件事必須**在寫提示詞之前**決定，因為提示詞、JSON schema、`StageSpec` 的工具權限、
`SandboxPolicy` 是互相決定的 —— 先寫提示詞會變成憑空想像，寫完再改。

現況：四個角色的提示詞與 schema 都已完成並部署。

**上游契約已經定了一半**：`architecture.md` 是假設 Backend 與 QA 存在寫的，
產出 `implementation_boundary`（給 Backend 的指令）與 `qa_criteria`（給 QA 的驗收條件），
且已要求這兩者必須寫成可直接對應到動作、可被驗證的形式。所以 Backend 的輸入介面
已經被上游定死：**消費 `implementation_boundary`，不得超出**。QA 同理。

---

## 決策 1：Backend 發現邊界不夠用時怎麼辦

Architecture 說「只改 `DataSourceConfig`，不要動 `JpaConfig`」，但 Backend 做到一半
發現不動 `JpaConfig` 就完成不了。

| 選項 | 後果 |
|---|---|
| A. 停下來回報 | 任務中斷，需要人介入或重跑 Architecture |
| B. 自行擴大範圍並記錄 | 變更範圍會安靜地膨脹，這正是 PR review 最難抓到的問題 |
| C. 回頭重跑 Architecture | 優雅，但可能無界迴圈，成本不可控 |

**決定：A。** 這是對外發布的 starter，變更範圍失控的代價高於任務中斷的代價。
Backend 輸出 `boundary_conflict` 欄位說明「需要動什麼、為什麼非動不可」，
orchestrator 停止管線並貼到 Slack。

但**必須先定義什麼算越界**，否則 A 會讓 Backend 每加一行 import 就停下來。
提示詞要明確區分：

- **邊界內的必要附帶修改**：import 語句、同檔案內的私有方法、對應的測試檔
- **真正的越界**：改動邊界外的檔案、改公開 API 簽章、新增依賴、改 `pom.xml`

連動：`impl-schema.json` 需要 `boundary_conflict` 欄位；`TaskOrchestrator` 需要
對應的停止分支（比照現有的 `stopAfterPm`）。

---

## 決策 2：測試是誰的責任

如果 Backend 自己寫測試，QA 就是在驗證 Backend 自己出的考卷。
如果全部交給 QA，Backend 實作時沒有回饋迴圈，無法判斷自己寫的東西能不能跑。

| 選項 | 問題 |
|---|---|
| A. Backend 寫實作 + 測試，QA 只審查 | QA 淪為橡皮圖章 |
| B. Backend 只寫實作，QA 寫測試 | Backend 失去自我驗證迴圈，會交出編譯不過的東西 |
| C. 兩者都寫，職責分開 | 需要把「分開」講清楚，否則會重工 |

**決定：C，並且把 QA 的問題換掉。**

- **Backend 必須寫單元測試並讓 `mvn test` 通過** —— 這是它的自我驗證迴圈，不可或缺
- **QA 不重寫 Backend 的測試**。QA 要回答的不是「測試有沒有過」，而是
  **「這些測試有沒有測到對的東西」** —— 逐條對照 `qa_criteria`，
  指出哪些條件沒有被任何測試覆蓋，並補上 Backend 漏掉的回歸情境

QA 的提示詞必須明講這句：**Backend 的測試全過，不等於驗收通過。**

連動：`StageSpec.QA` 原本沒有 `Write`／`Edit`，選 C 之後補上了，
並靠決策 4 的沙箱政策限制它只能寫測試目錄。
`StageSpec` 的 `writesCode` 布林也因此改成三態 `WriteScope`
（`NONE`／`FULL`／`TESTS_ONLY`）—— Backend 與 QA 都要寫，但範圍完全不同，
布林表達不了這個差別，只能靠提示詞自律，而那不是邊界。

---

## 決策 3：失敗與重試的定義

Agent 最糟的失敗模式不是做不出來，是**為了讓測試變綠而修改測試或刪掉斷言**。

**決定**：

- `impl-schema.json` 要有 `status: SUCCESS | PARTIAL | FAILED` 與 `blockers[]`
- 提示詞明確寫：**回報 `FAILED` 不會被視為失職；交出編譯不過或被閹割過的測試才是最糟的結果**
- **硬性規則**：不得為了讓測試通過而修改既有測試的斷言或刪除測試。
  既有測試擋住你，代表你的實作違反了既有契約，那是要回報的事
- **不另設應用層重試**。`--max-turns 40` 已是硬上限，跑完就是跑完；
  要不要重來由人決定（重做機制已經存在，`Task.MAX_REDO`）

理由：應用層自動重試會讓成本不可控，而且重試通常不會讓一個做不到的任務變成做得到。

---

## 決策 4：QA 的權限矛盾

`StageSpec.QA` 的 `writesCode = true`（要在 Backend 的工作區跑 `mvn`，會寫 `target/`），
但 QA 不該改產品程式碼。目前只靠 `allowedTools` 不給 Write／Edit —— 那是 runtime 內的防線。

**決定：用沙箱做真正的邊界。** 新增一個 QA 專用的 `SandboxPolicy`：

| 路徑 | 權限 |
|---|---|
| `<worktree>/**/target/` | 可寫（建置產物） |
| `<worktree>/**/src/test/` | 可寫（決策 2 選 C 才需要） |
| `<worktree>/**/src/main/` | **釘回唯讀** |
| `<worktree>/pom.xml` | **釘回唯讀** |

巢狀唯讀釘選已實測有效（可寫父目錄內可再釘唯讀），22 個釘選路徑全部生效：
改產品程式碼、改模組 `pom.xml`、改根 `pom.xml` 都得到 `Read-only file system`，
而新增測試與寫 `target/` 正常。

### 但沙箱擋不住 git 索引（實測發現）

`.git` 必須保持可寫 —— QA 補的回歸測試要能 commit，否則進不了 PR。
這留下一個繞道：

```
git checkout <舊commit> -- src/main
→ error: unable to unlink old ...: Read-only file system   （exit 255）
→ 但 git status 顯示 MM —— **index 已經被改掉了**
```

工作樹沒被寫，索引卻指向了舊的 blob。接著 `git commit` 就能把產品程式碼的
舊版本送進 PR，全程沒有寫過 `src/main` 一個位元組。

**檔案系統擋不住索引。** 因此加了第二道防線，在沙箱之外：
QA 執行前後各記一次 HEAD，`TaskOrchestrator.enforceQaWriteScope` 核對
這兩個 commit 之間實際變動的路徑，只要出現 `src/test/` 以外的檔案就判定越權、
中止任務、寫進稽核。這道檢查跑在 orchestrator 裡，不在 agent 能觸及的範圍內。

另外 `Bash(mvn *)` 是個殘留的洞：mvn 可以透過 exec plugin 執行任意指令。
沙箱擋得住它寫檔，但擋不住它做別的事（例如對外連線 —— 網路目前完全開放）。
這個洞的嚴重度取決於決策 1 之後 Backend 的實際行為，先記著。

---

## 前置作業已完成

工作區的設計問題（從哪個 branch 開、跑完怎麼收、失敗怎麼清、重做要不要沿用）
已經在 `WorkspaceManager` 解決（每任務獨立 clone，理由見 [多 Runtime 設計](multi-runtime.md#沙箱實際做了什麼)）。

對這份清單有影響的兩點：

- **工作區是獨立 clone，不是 worktree** —— Backend 在裡面可以自由 commit，
  主 repo 完全碰不到。決策 4 的沙箱政策要針對 clone 的目錄結構寫。
- **分支已經開好了**（`ai-team/task-<id>`），Backend 不需要也不應該自己開分支，
  提示詞要講明這件事，否則它會在分支上再開分支。

---

## 決定之後要產出的東西

| 檔案 | 內容 |
|---|---|
| `prompts/backend.md` | 消費 `implementation_boundary`、越界定義、測試責任、失敗回報規則 |
| `prompts/qa.md` | 逐條驗證 `qa_criteria`、「測試過不等於驗收過」、不得改產品程式碼 |
| `schemas/impl-schema.json` | `status`、`blockers[]`、`boundary_conflict`、變更檔案清單 |
| `schemas/qa-schema.json` | 逐條 `qa_criteria` 的驗證結果、未覆蓋項目、建議 |
| `StageSpec` | QA 的 `allowedTools`（決策 2）；Backend 的工具清單複查 |
| `SandboxPolicy` | QA 專用政策（決策 4） |
| `TaskOrchestrator` | `boundary_conflict` 的停止分支（決策 1）、Backend→QA 串接 |
