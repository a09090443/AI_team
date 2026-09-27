# Backend Agent 端對端驗證報告

- 執行時間：2026-09-02
- 模型：`claude-sonnet-5`（Claude Code CLI 的預設）
- 任務：在 `base-spring-boot-starter` 新增計算 SHA-256 的工具方法

第一個會實際修改程式碼的角色。以完全等同正式管線的參數執行 ——
`WorkspaceManager.provision` 的 git 步驟、`SandboxPolicy.writable` 的沙箱政策、
`StageRunner` 組出的提示與 schema，全部照抄。

---

## 1. 結果

| 項目 | 數值 |
|---|---|
| 結束狀態 | `is_error=false`、`subtype=success` |
| 自陳 status | `SUCCESS`、`confidence=HIGH` |
| 耗時 | 290 秒 |
| 成本 | **US$0.58** |
| 使用 turns | **40 / 40（用滿）** |
| 權限拒絕 | 2 次（皆為 `npm`） |

產出：`Sha256Util.java`、`Sha256UtilTest.java`（5 個測試）、三份 doc-site 文件、
一個 commit `e022a41 新增(base-starter): 新增 SHA-256 雜湊工具 Sha256Util`。

## 2. 獨立驗證（不採信 agent 自陳）

| 結果 | 主張 | 觀察 |
|---|---|---|
| PASS | 分支基準正確 | 從 `develop`（`03c0075`）開出 `feature/ai-team-task-E2E1` |
| PASS | 實際有 commit | `e022a41`，工作樹乾淨無殘留 |
| PASS | commit 訊息符合 repo 慣例 | `新增(base-starter): …`，未被提示詞規定，來自 repo 自己的 skill |
| PASS | `mvn -B verify` 真的通過 | 重跑一次：**BUILD SUCCESS**，94.8 秒 |
| PASS | 新測試真的執行且通過 | `Sha256UtilTest` — Tests run: 5, Failures: 0 |
| PASS | 未越界改動既有類別 | 六個異動檔案全部是新增或文件 |
| PASS | 未新增相依 | `pom.xml` 未變更 |

## 3. 值得記錄的行為

**repo 自己的 harness 被正確使用。** 提示詞刻意不重複 commit 格式與建置步驟，
只指向 `CLAUDE.md`／`AGENTS.md`／`.claude/skills/`。agent 自行套用了
`git-commit-message` 與 `local-quality-gate`，`spotless:apply` 與 `verify` 都有跑。
**Skill 工具不受 `--allowedTools` 限制**（實測 `permission_denials` 為空），
所以 repo 的 skill 對 agent 完全可用。

**誠實回報而非粉飾。** 三項 `uncertainties` 都是實質的：

1. doc-sync 要求跑 `npm run build` 重新產生 `llms.txt`／`llms-full.txt`，沙箱擋掉 npm，
   它改為手動鏡射並主動建議由人跑一次
2. Architecture 說「比照 Md5Util」，但 Md5Util 有 L16／U16（中段截斷），
   對 SHA-256 沒有意義，因此只提供 L64／U64
3. `HexUtil.byte2hex` 輸出大寫，故 L64 再轉小寫以符合 Md5Util 的預設格式

第 2、3 項是設計判斷而非藉口，正是 `uncertainties` 該有的內容。

## 4. 測試揪出的三個問題

### 4.1 沙箱內找不到 `mvn`（已修）

`systemd-run --user` 的 unit **不繼承呼叫端的環境變數**。
沙箱內 `PATH` 是系統預設、`JAVA_HOME` 未設定，Backend 每次建置都會失敗，
而且失敗會看起來像「環境壞掉」而不是「沙箱設定漏了」。

修正：`ExecutionSandbox` 以 `--setenv` 傳遞白名單變數（`PATH`、`JAVA_HOME`、`MAVEN_OPTS`）。
**用白名單而非全部帶入**，因為 orchestrator 的環境裡有 Slack token ——
整包倒進去等於把「以 bot 身分發言」的能力交給一個會改程式碼的 agent。

自檢也加上 `command -v mvn`，工具鏈與檔案系統隔離分開判斷：
前者是能力問題，後者是安全問題，混在同一個旗標會讓診斷變困難。

### 4.2 文件同步與實作邊界互相衝突（已修）

Architecture 的邊界寫「只在 `base-spring-boot-starter` 的 crypto 套件新增」，
但 repo 的 `AGENTS.md` 明訂文件同步是 commit 前置條件，而 `doc-site/` 在邊界之外。
照原本的提示詞，agent 每次都得在「違反邊界」與「跳過強制文件更新」之間二選一。

修正：把 doc-sync 要求的**來源**文件明確列為邊界內的必要附帶修改；
同時禁止手動編輯**由建置產生**的檔案（`llms.txt`、`llms-full.txt`）——
沙箱跑不了 npm，手動鏡射看起來對但會與真正的建置輸出產生偏差，
應改在 `uncertainties` 註明需要人重新產生。

### 4.3 turns 用滿（已修）

一個「新增一個工具類 + 測試 + 文件同步」的小任務就用掉 40/40。
雖然這次仍完成，但沒有餘裕，稍大的任務會被截斷。已調整為 60。

## 5. 留給 QA 的觀察

`Sha256Util` 用 `str.getBytes()`，採用 JVM 預設字元集 ——
同樣的輸入在不同平台可能得到不同雜湊。這是比照 `Md5Util` 的既有寫法，
不算越界，但確實是潛在缺陷。

而測試只驗證了 `"abc"` 對上 NIST 標準值，純 ASCII 剛好掩蓋這個問題。

**這正是 QA 存在的理由**：Backend 的測試全綠，不代表測到了對的東西
（見 `backend-qa-decisions.md` 決策 2）。第一次真實執行就出現了這個案例。

## 6. 成本與時間參考

| 項目 | 數值 |
|---|---|
| 單次 Backend（小任務） | US$0.58、290 秒 |
| `mvn -B verify` 全 reactor（暖快取） | 118 秒 |
| `mvn -B verify` 全 reactor（冷快取） | 217 秒 |
| `mvn -B -pl <module> -am verify` | 16 秒 |
| `~/.m2` 大小 | 369 MB |

Backend 階段逾時因此設為 1800 秒：一輪可能跑 2-3 次 verify，
900 秒會在還沒寫完程式前就逾時。

---

## 7. 正式環境的第一次失敗：#1003

2026-09-02，使用者從 Slack 發出真實需求（與第 1 節同樣是 SHA-256 工具），
PM 這次判定**需要 Architecture**，四個角色全部上場。

| 階段 | 耗時 | 成本 | 結果 |
|---|---|---|---|
| PM | 62 秒 | US$0.19 | `required_agents: [architecture, backend, qa]` |
| Architecture | 177 秒 | US$0.48 | 產出 13 條 `implementation_boundary` |
| Backend | 302 秒 | US$0.88 | **用盡當時的 60 回合上限被中止** |

Architecture 的產出品質很高：實際讀了 `Md5Util.java:16-45`、`HexUtil.java:22-29`、
`AesUtil.java:128-155`，抓到 `HexUtil` 輸出大寫而 `Md5Util` 預設小寫的慣例衝突，
也發現 `commons-codec` 是傳遞性依賴而非顯式宣告，然後定案用 JDK 17 的 `HexFormat`
而非重用大寫的 `HexUtil`。

Backend 建立了 `Sha256Util.java`、測試與四份文件同步，**但沒有 commit 就被中止**。

### 診斷過程本身暴露的問題

失敗訊息只有一句「Claude Code 結束碼 1」加上被截斷到 1500 字元的輸出 ——
而被截掉的正是 `subtype` 與 `num_turns` 這些說明原因的欄位。stderr 是空的。

實驗確認（`--max-turns 2` 加上一個做不完的任務）：
**回合數用盡時 Claude Code 也是結束碼 1**，`subtype: error_max_turns`、`is_error: true`，
與真正的錯誤在結束碼上無法區分。

修正：

- stdout 一律落檔到 `<logs>/<task>/<role>.stdout.json`，不再只靠例外訊息
- `describeFailure()` 解析 `subtype`，把 `error_max_turns` 翻成
  「用盡回合數上限就被中止（已用 N 回合），工作未完成且未 commit」

### 為什麼用光回合

三個原因疊加：

1. **工具權限過嚴。** `ls` 不在 `allowedTools` 裡，每次嘗試都被拒絕並浪費一個回合。
   收緊 runtime 權限不會增加安全性 —— 沙箱才是真正的邊界 ——
   但會把回合燒在被拒絕的呼叫上。已補上 `ls`／`cat`／`find`／`head`／`tail`／`wc`／`diff`。
2. **邊界要求了沙箱做不到的事。** Architecture 的邊界寫明要在 doc-site 執行
   `npm run build` 重新產生 `llms.txt`，但沙箱不允許 npm。Backend 在嘗試與被拒絕
   之間反覆消耗回合。已在 `architecture.md` 加上約束：邊界的每一條都必須是
   Backend 用手上的工具做得到的動作，需要人另外執行的步驟寫進 `risks`。
3. **邊界條目過多。** 13 條。已要求 Architecture 把「必要的約束」與「順帶提醒」分開。

回合上限同步調高：Backend 60 → 80，QA 40 → 60。

### 這次失敗證明了什麼是對的

- **失敗沒有造成任何損害。** 主 repo 未被觸碰，工作區隔離有效
- **失敗的工作區被保留下來**（啟動清理只刪 COMPLETED／ABORTED／孤兒），
  所以事後還能檢查 Backend 到底做到哪一步
- **狀態機正確收斂到 FAILED**，沒有卡在 `IMPL_RUNNING`
