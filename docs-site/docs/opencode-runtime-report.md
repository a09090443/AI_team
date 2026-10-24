# opencode Runtime 實測報告

驗證對象：`opencode` CLI（npm 套件 `opencode-ai`）**1.18.19**
驗證日期：2026-09-10
驗證環境：**Windows 11 開發機**（npm 全域安裝），供應商為 **OpenRouter API key**
驗證方式：直接以 CLI 執行並錄下原始輸出；事件流樣本已寫進 `OpenCodeRuntimeTest`

> **這份報告的邊界**：所有結論都來自上述環境。VM（Linux、systemd 沙箱、
> `ExecutionSandbox` 包裝）上的端對端執行**尚未進行**，見文末「尚未驗證」。
> 不要把本文當成「opencode runtime 已在部署環境可用」的證據。

---

## 一、旗標契約（全部實測）

| 用法 | 結果 |
|---|---|
| `opencode run [message..]` | 非互動入口 |
| `--format json` | JSONL 事件流（`default` 是給人看的格式，不可解析） |
| `--dir DIR` | 指定工作目錄 |
| `--session ses_…` | 續接該 session，**可與 `--dir` 並用** |
| `-m provider/model` | 指定模型，格式為「供應商/模型」 |
| `--auto` | 自動核准未被明確拒絕的權限 |
| `--` | 位置參數分隔符；prompt 放在它之後 |
| `--variant` | 供應商專屬的推理強度（未使用，未實測） |

**沒有**的東西（找遍 `run --help`，不是還沒接）：

- 附加系統提示的旗標（沒有 `--append-system-prompt` 之類）
- 輸出 schema 的旗標
- 回合上限
- 細粒度工具白名單

### 能力宣告

| 能力 | opencode | 依據 |
|---|---|---|
| `SESSION_RESUME` | 有 | 實測：`--session` 續接後答得出前一輪建立的檔名 |
| `MODEL_SELECTION` | 有 | 實測：`-m` 生效，錯誤模型名會明確報錯 |
| `COST_REPORTING` | 有 | 實測：`step_finish` 事件帶 `cost`（美元） |
| `NATIVE_AGENTS_MAP` | 有 | 實測：工作目錄放 `AGENTS.md` 要求「第一個字必須是喵」，回答為「喵2」 |
| `APPEND_SYSTEM_PROMPT` | 無 | 降級：角色定義前置進 prompt 本文 |
| `STRUCTURED_OUTPUT` | 無 | 降級：schema 寫進 prompt，回來自己 parse（會拆 Markdown 圍欄） |
| `MAX_TURNS` | 無 | 降級：只能靠 wall-clock timeout |
| `TOOL_PERMISSIONS` | 無 | 邊界本來就在 `ExecutionSandbox`，不倚賴 CLI |

---

## 二、事件流格式（逐字取自實機輸出）

成功、純文字的一輪：

```json
{"type":"step_start","timestamp":…,"sessionID":"ses_f74910487ffenMfxxLtt2uH2Au","part":{"type":"step-start",…}}
{"type":"text","timestamp":…,"sessionID":"ses_f74910487ffenMfxxLtt2uH2Au","part":{"type":"text","text":"OK",…}}
{"type":"step_finish","timestamp":…,"sessionID":"ses_…","part":{"type":"step-finish","reason":"stop","tokens":{"total":7925,"input":7784,"output":9,"reasoning":52,"cache":{"write":0,"read":80}},"cost":0.00024385}}
```

含工具呼叫時，每次工具呼叫各自是一個 step，因此 **`step_finish` 會有多筆，
`cost` 必須逐筆累加**（只取最後一筆會少算，實測一輪三個 step 各約 $0.00025）：

```json
{"type":"tool_use","sessionID":"ses_…","part":{"type":"tool","tool":"write","callID":"chatcmpl-tool-…","state":{"status":"completed","input":{"content":"hi","filePath":"probe.txt"},"output":"Wrote file successfully.",…}}}
```

失敗（模型名不存在／供應商 404、429）：

```json
{"type":"error","timestamp":…,"sessionID":"ses_…","error":{"name":"APIError","data":{"message":"This model is unavailable for free…","statusCode":404,…}}}
```

要點：

1. **每一個事件都帶 `sessionID`**，包含 `error` 事件 —— 所以中斷或失敗時仍取得回 session id，
   `SessionCapture` 取第一個非空的就夠。
2. 一輪可能有多個 `text` 事件（工具呼叫前後各一段），**取最後一個**當作結論。
3. 只觀察到 `state.status = "completed"` 這一種成功終態；其餘終態一律留證但不推測成因。

---

## 三、踩過的坑

**坑 1：沒有位置參數就去讀 stdin，然後一路等到逾時。**
與 codex 完全相同的陷阱。stdin 是 pipe 時無錯誤、無結束碼，就是掛著。
`OpenCodeRuntime` 啟動後立刻關閉 stdin。

**坑 2：不認識的旗標不會報錯，而是印出整份說明後結束碼 1。**
實測 `opencode run --no-such-flag -- x`：stdout 空、stderr 是 help、結束碼 **1**。
與 codex 的 clap（結束碼 2）不同，所以 `RuntimeUsageError` 另開一個
`looksLikeYargsUsageError()`：結束碼非 0 + 事件流為空 + stderr 有用法字樣，三者全中才算。
缺訊息時的字樣是 `Error: You must provide a message or a command`。

**坑 3：prompt 一定要放在 `--` 之後。**
承坑 2 —— 以 `-` 開頭的 prompt 會被吃成旗標，然後你只會看到一份說明文字。

**坑 4：模型格式是 `provider/model`，與 claude、codex 都不通用。**
`-m opus` 之類在這裡是無效值。換 runtime 時 `AI_TEAM_MODEL_<角色>` 必須一併換掉。

**坑 5：計費模式不同。**
claude／codex 走訂閱制、事件流沒有金額；opencode 接的是 API key 供應商，
每個 `step_finish` 都有實際美元金額。這是唯一會回報 `costEstimate` 的 runtime，
也意味著誤設一個貴模型的代價是直接的。

**坑 6（非 opencode 的問題，但會擋住驗證）**：本次驗證時 OpenRouter 的
`:free` 模型全數不可用（404「已改為付費」或 429 上游限流），
成功路徑只能用付費模型錄，單次成本約 $0.00025。

---

## 四、狀態目錄與提權釘選

opencode 的狀態散在兩處，只給一處會讓認證或 resume 其中一項靜默失效：

| 路徑 | 內容 |
|---|---|
| `~/.config/opencode` | `opencode.json(c)`、`package.json`、`node_modules`（外掛以 npm 套件安裝於此） |
| `~/.local/share/opencode` | `auth.json`、`opencode.db`（session）、`log/`、`snapshot/` |
| `~/.cache/opencode` | 快取 |

可寫的是上面三個目錄；在其中釘回唯讀的提權管道為
`opencode.json`、`opencode.jsonc`、`package.json`、`node_modules`、`plugin`、`agent`、`command` ——
設定檔可定義 MCP server 與外掛，外掛目錄本身就是會被載入的程式碼。

**釘選在 VM 上的實際效果尚未驗證**（本機無 systemd）。

---

## 五、尚未驗證

以下都還不能標成可用，需要在 VM 上補：

1. **`ExecutionSandbox` 包裝下的端對端執行** —— 含 systemd unit、`ReadWritePaths` 白名單、
   提權釘選是否真的擋得住寫入 `~/.config/opencode`。
2. ~~**Linux 上的路徑**~~ —— 已於 2026-09-10 在 VM 上確認，見第七節。
   仍未確認是否會受 `XDG_CONFIG_HOME` / `XDG_DATA_HOME` 影響（VM 上兩者皆未設定）。
3. **真實角色任務** —— schema 降級（把 schema 寫進 prompt）在真正的 PM／QA schema 下
   能不能穩定回出合法 JSON，只有跑過才知道。目前只驗證了解析器吃得下正確與錯誤兩種形狀。
4. **額度／限流的樣態** —— `QuotaExceeded` 走的是既有的 JSONL 分支（逐行檢查 `error` 事件），
   對 opencode 的 `error` 事件結構成立，但實際的 429 用量訊息字樣尚未收集。
5. **`--variant`（推理強度）** —— 未使用、未實測；`TeamCatalog` 目前仍只允許 codex 設 `reasoningEffort`。

---

## 六、本機驗證紀錄

| 項目 | 結果 |
|---|---|
| `orchestrator/src/main` 全量編譯（javac，依賴取自既有 fat jar） | 通過 |
| 以實錄 JSONL 跑 `OpenCodeRuntime.parse()`（純文字／含工具／錯誤三種） | 15 項全通過 |
| `OpenCodeRuntimeTest`（JUnit） | 通過（在 VM 上跑，20 項）—— 見第七節 |
| `deploy/bootstrap/check-source.sh` | 見 git 歷史中 2026-10-24 前的 `.ai/progress.md` 當次紀錄 |

---

## 七、VM 驗證紀錄（2026-09-10）

**建置與部署**：隔離目錄 `opencode-build.TLQba7`，`mvn clean package`
→ `Tests run: 257, Failures: 0, Errors: 0, Skipped: 10`（其中 `OpenCodeRuntimeTest` 20/20，
略過的 10 項是照舊的 opt-in 實機測試）。jar SHA256
`3fb67dbc8b4f53efbd024fc544cdc95e2f300f5f425c7370a4540c42cbbbca64`，PID 75366，
備份 `opencode-backup.WhXo1u`。部署前後 task／flow_run／checkpoint／stage_result 雜湊一致。

**安裝**：`npm install --global --prefix ~/.local/node opencode-ai@1.18.19`，33 秒、3 個套件。
`~/.local/node/bin/opencode` 是 symlink 指向 `…/opencode-ai/bin/opencode.exe` ——
**在 Linux 上檔名也是 `.exe`**，`file` 確認是 ELF 64-bit x86-64 執行檔，不是裝錯平台。

**Linux 上的契約重驗**（無模型呼叫）：`--format` / `--dir` / `-s, --session` / `-m, --model` /
`--auto` 全部存在且說明與 Windows 相同；缺訊息時同樣是結束碼 1 加
`Error: You must provide a message or a command`，`looksLikeYargsUsageError()` 在 Linux 也成立。

**狀態目錄**：首次執行後自動建立 `~/.config/opencode` 與 `~/.local/share/opencode`，
與 `statePaths()` 宣告一致。

**設定**：`OPENCODE_BIN` 已寫入 `~/ai-team/env.sh`（權限維持 600，改前備份為
`env.sh.before-opencode.20260910134447`）；`preflight.sh` 已同步為含 opencode 檢查的版本並通過。

**仍然沒做的**：認證（`opencode providers` 須本人操作）、以及沙箱包裝下的實跑 ——
提權釘選是否真的擋得住寫入 `~/.config/opencode` 仍未驗證。目前沒有任何角色設為 opencode。
