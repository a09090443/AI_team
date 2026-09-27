# Phase 0 驗證報告

- 執行時間：2026-09-01T13:42:47+00:00
- 主機：Linux 7.0.0-30-generic x86_64
- claude：2.1.252 (Claude Code) 
- 測試目錄：/tmp/phase0-verify

驗證 `architecture-review.md` 中所有關於 CLI 行為的主張。

## 1. 環境

| 結果 | 主張 | 觀察 |
|---|---|---|
| INFO | node 版本 | v22.21.1  |
| INFO | java 版本 | openjdk version "21.0.12.1" 2026-08-18 LTS  |
| INFO | git 版本 | git version 2.53.0  |
| INFO | ANTHROPIC_API_KEY 是否設定 | 未設定 |

## 2. 認證（訂閱帳號）

| 結果 | 主張 | 觀察 |
|---|---|---|
| PASS | 未設 ANTHROPIC_API_KEY 時可用訂閱憑證執行 | OK |
| PASS | I6：--bare 在訂閱帳號下無法運作 | exit=1 Not logged in · Please run /login |

## 3. JSON 輸出契約

| 結果 | 主張 | 觀察 |
|---|---|---|
| PASS | stdout 是可解析的 JSON（B3：stderr 未污染） | 長度 2024 |
| PASS | JSON 含 session_id | 9f108fa0-15b5-4d62-9324-6d21bc372e9e |
| PASS | JSON 含 result | Java（專案中僅有一個 `Calculator.java` 檔案）。 |
| PASS | JSON 含 total_cost_usd | 0.0303624 |

## 4. B4：--append-system-prompt-file 是否生效

| 結果 | 主張 | 觀察 |
|---|---|---|
| PASS | 附加的角色提示生效 | 喵，1 加 1 等於 2。 |

## 5. I2：--json-schema 結構化輸出

| 結果 | 主張 | 觀察 |
|---|---|---|
| PASS | 回應含 structured_output | {"language":"Java","method_count":2} |
| PASS | structured_output 符合 schema 必填欄位 | language=Java method_count=2 |

## 6. B2：--permission-mode dontAsk 的唯讀限制

| 結果 | 主張 | 觀察 |
|---|---|---|
| PASS | 唯讀 allowedTools 下無法寫檔 | 我無法建立這個檔案。`Write` 和 `Bash` 兩個工具的權限都被拒絕了（目前處於 "don't ask" 模式），而寫入檔案本質上就需要� |
| PASS | 對照組：允許 Write 時可寫檔（證明是權限擋的，不是別的原因） | 檔案已建立 |

## 7. I3：--resume session 續接

| 結果 | 主張 | 觀察 |
|---|---|---|
| PASS | --resume 保有前一輪的 context | 4712 |

## 8. I4：.claude/agents/ subagent 是否載入

| 結果 | 主張 | 觀察 |
|---|---|---|
| PASS | 不帶 --bare 時 .claude/agents/ 會被載入 | system/init 中出現 tester |

## 9. --max-turns 是否生效

| 結果 | 主張 | 觀察 |
|---|---|---|
| INFO | --max-turns 1 的執行時間 | 4s（若明顯短於無限制的情況即為生效） |

---

## 總計

- PASS：13
- FAIL：0
- SKIP / INFO：5

**所有主張都通過。** 評估報告的 CLI 契約部分可以直接採用，繼續進行 Phase 1。
