# QA Agent 與 PR 閘門驗證報告

- 執行時間：2026-09-02
- 模型：`claude-sonnet-5`

管線的最後兩塊：驗收角色，以及全自動流程唯一保留的人工閘門。

---

## 1. 測試設計：刻意餵它一份會出錯的輸入

不重跑 Backend，改為手動建構一個「Backend 剛做完」的工作區，並**刻意埋三個問題**：

1. 測試只驗 ASCII 的 `"abc"`，但驗收條件要求「任意字串（含非 ASCII）」
2. 完全沒有同步 doc-site 文件
3. 測試檔的 import 排序不符 spotless

再合成一份**說謊的** Backend 報告：宣稱 `spotless_applied: true`、
`verify_passed: true`、`docs_synced: true`。

這樣測的是 QA 的核心價值：它會不會照抄上游的自陳。

## 2. 結果

| 項目 | 數值 |
|---|---|
| 結束狀態 | `is_error=false` |
| 判定 | **`FAIL`**、`confidence=HIGH` |
| 耗時 | 414 秒 |
| 成本 | **US$0.60** |
| 使用 turns | 32 / 40 |

**三個埋下的問題全部被抓到，而且都指出 Backend 的報告與事實不符。**

### 逐條驗收

| 條件 | 判定 |
|---|---|
| 已知輸入與標準值一致 | `COVERED` |
| 任意字串（含非 ASCII）一致 | **`PARTIAL`** |
| 空字串行為明確 | `COVERED`（QA 補的測試） |
| null 行為與 Md5Util 一致 | `COVERED`（QA 補的測試） |
| 既有 crypto 測試仍通過 | `COVERED` |

`PARTIAL` 的理由寫得很精確：補了 CJK 與 emoji 的測試向量後在 UTF-8 平台會通過，
但 `str.getBytes()` 走平台預設 charset，非 UTF-8 平台結果會不同 ——
**而這個情境無法在不修改產品程式碼的前提下寫測試強制驗證**，所以不給 `COVERED`。

### 發現的問題

| 等級 | 內容 |
|---|---|
| HIGH | 新增 public 工具類完全未同步 doc-site，違反 repo 明列的強制 Gate。指出 `index.md` 第 45-55 行的主要類別表格缺列，並註明「Backend 報告卻宣稱 `docs_synced: true`」 |
| MEDIUM | Backend 的 commit 有 spotless import 排序違規，`mvn verify` 會直接紅。QA 已修正，並指出「這顯示 Backend 並未實際跑過 verify，其 `quality_gate` 自述不可信」 |
| MEDIUM | `str.getBytes()` 未指定字元集。給出修正方式 `StandardCharsets.UTF_8`，並註明 `Md5Util` 有相同寫法但已 `@Deprecated`，「新程式碼有機會做對」 |
| LOW | `catch (NoSuchAlgorithmException)` 後回傳 null 是實質死碼 —— SHA-256 是 JCA 保證每個 JVM 都提供的演算法 |

每一條都正確判斷「這超出 QA 權限，要 Backend 修」，沒有自己動手改產品程式碼。

## 3. 獨立驗證

| 結果 | 主張 | 觀察 |
|---|---|---|
| PASS | QA 只動測試檔 | `git diff --name-only` 僅 `Sha256UtilTest.java` |
| PASS | 產品程式碼未被更動 | `src/main` 路徑的 diff 為空 |
| PASS | 補完測試後建置通過 | 重跑 `mvn -B verify`：**BUILD SUCCESS** |
| PASS | 測試數增加 | `Sha256UtilTest` 從 2 個變成 **6 個**，全數通過 |
| PASS | commit 訊息符合 repo 慣例 | `測試(base-starter): 補齊 Sha256Util 空字串、非 ASCII 與 null 回歸測試` |
| PASS | `FAIL` 判定使管線停在 PR 之前 | 未進入核准閘門 |

## 4. 沙箱：擋得住檔案，擋不住索引

決策 4 的沙箱政策（22 個唯讀釘選）實測全部生效：

| 操作 | 結果 |
|---|---|
| 改 `src/main` 下的產品程式碼 | `Read-only file system` |
| 改模組 `pom.xml` | `Read-only file system` |
| 改根 `pom.xml` | `Read-only file system` |
| 新增 `src/test` 下的測試 | 可寫 |
| 寫 `target/` | 可寫 |
| commit | 可以（`.git` 保持可寫） |

### 但繞道測試找到一個洞

```
git checkout <舊commit> -- src/main
→ error: unable to unlink old ...: Read-only file system   （exit 255）
→ 但 git status 顯示 MM ——  index 已經被改掉了
```

工作樹沒被寫，**索引卻指向了舊的 blob**。接著 `git commit` 就能把產品程式碼的
舊版本送進 PR，全程沒有寫過 `src/main` 一個位元組。

`.git` 不能設成唯讀 —— QA 補的測試要能 commit。所以加了第二道防線，在沙箱之外：

`TaskOrchestrator.enforceQaWriteScope` 在 QA 前後各記一次 HEAD，
核對這兩個 commit 之間實際變動的路徑，只要出現 `src/test/` 以外的檔案就
判定越權、中止任務、寫進稽核。這道檢查跑在 orchestrator 裡，agent 觸及不到。

**檔案系統的邊界不等於版本控制的邊界。** 這是這次測試最有價值的發現。

## 5. PR 閘門

`PullRequestPublisher`：推分支 + 呼叫 GitHub REST API 開 PR。

- token 經環境變數與臨時 credential helper 傳入，**不寫進 `.git/config`**
  （會留在工作區檔案裡）、**不放在指令參數**（會出現在 `ps` 輸出）
- 錯誤訊息會把 token 遮蔽後才進 log 與 Slack
- PR 內文帶四個階段的摘要、花費、以及 **QA 的 findings 與無法驗證的部分**
  —— 那些是給審查者的訊息，留在 Slack 等於沒說
- PR 標題優先用 Backend 的 commit 主旨，它已經照 repo 慣例寫好了
- 合併目標讀工作區 git config 裡記的基準分支（`develop`），不猜

失敗時**刻意不把任務標成 FAILED**，狀態留在 `AWAIT_PR_APPROVAL`：
工作本身是好的，只是沒送出去，修好設定後可以再按一次核准，不必整個重跑。

核准按鈕的處理丟給工作執行緒 —— `SlackListener` 是先處理再 ack，
推送與 API 呼叫要好幾秒，擋住就會超過 Slack 的 3 秒上限而收到重送。

### 尚未端對端驗證的部分

VM 上沒有任何 GitHub 憑證（無 `gh`、無 credential helper、無 token、無 SSH key），
因此**實際推送到 GitHub 這一步沒有驗證過**。已驗證的是：

- 推送指令的形狀（對本機 bare repo 測試，分支建立與 upstream 設定正確）
- 缺 token 時啟動會警告，按下核准會得到明確的處理指引

要啟用需在 `~/ai-team/env.sh` 填入 `AI_TEAM_GITHUB_TOKEN` 後重啟。

## 6. 一個操作面的注意事項

QA 會執行 `mvn spotless:apply`。若基準分支上有未正規化的 `src/main` 檔案，
spotless 會嘗試改寫它們並因唯讀而失敗。正常流程下 Backend 已經跑過 `spotless:apply`，
不會遇到；但若基準分支本身就髒，QA 階段會卡住。

## 7. 成本

| 階段 | 成本 | 耗時 |
|---|---|---|
| PM | US$0.15 | 63 秒 |
| Backend | US$0.58 | 290 秒 |
| QA | US$0.60 | 414 秒 |

一個完整的小任務（PM → Architecture → Backend → QA）大致落在 **US$1.5–2**。
