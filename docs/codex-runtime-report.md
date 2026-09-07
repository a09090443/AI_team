# Codex Runtime 實測報告

第二個 runtime adapter。OpenAI Codex CLI `0.153.4`，ChatGPT 訂閱帳號認證。

實測日期 2026-09-05，全部在 VM 上實機執行，**7 項通過、0 失敗**。
方法沿用 `phase0-report.md`：先把 CLI 契約釘死，再寫 adapter。

---

## 為什麼要先實測

Phase 0 的教訓是「訂閱帳號下 `--bare` 不可用」——
文件說得通的旗標，在訂閱認證下未必能用。Codex 配 ChatGPT 訂閱是同一種風險，
所以在寫任何 Java 之前先逐項驗過。

結果證明這是值得的：七項裡有兩項的行為與文件的字面印象不同，
其中一項是會靜默掛住的陷阱。

---

## 安裝與認證

```bash
npm install -g @openai/codex          # 裝到 ~/.local，不需 sudo
codex login --device-auth             # headless 專用，不需埠轉發
```

**認證只能由使用者本人完成。**
一般的 `codex login` 走瀏覽器回呼到 `localhost:1455`，VM 在 NAT 後面接不到，
會停在「starting browser login flow」之後毫無下文。`--device-auth` 給一組代碼，
在任何裝置的瀏覽器輸入即可，這是 headless 環境唯一乾淨的路徑。

認證結果落在 `~/.codex/auth.json`，`auth_mode` 為 ChatGPT、帶 `refresh_token`，
會自動續期，不需重登。

---

## 七項實測

| # | 項目 | 結果 | 備註 |
|---|---|---|---|
| T1 | `codex exec` 非互動執行 | ✅ | 訂閱帳號下可用。stdout 乾淨，用量在 stderr |
| T2 | `--json` 事件流 | ✅ | JSONL。`thread.started` 帶 `thread_id`；`turn.completed` 帶 `usage` |
| T3 | `--output-schema` 結構化輸出 | ✅ | 完全符合 schema，繁中正常 |
| T4 | `codex exec resume <id>` | ✅ | 保有前一輪 context，`thread_id` 不變 |
| T5 | `AGENTS.md` 自動載入 | ✅ | 未給任何指示就答出檔案內的專屬代號 |
| T6 | 在 systemd 沙箱內執行 | ✅ | 與自帶的 bubblewrap 巢狀無衝突 |
| T7 | 外部沙箱擋得住越界寫入 | ✅ | **最關鍵的一項**，見下 |

### T7：安全邊界確實在 runtime 之外

讓 Codex 自己完全不設限（`-s danger-full-access` 加
`--dangerously-bypass-approvals-and-sandbox`），指示它寫入沙箱外的
`$HOME/PWNED.txt`：

- 檔案**沒有**被建立
- agent 自己回報「檔案系統為唯讀」
- 工作區內的寫入照常成功

`multi-runtime.md` 那條「安全邊界必須在 runtime 之外」的原則，
對第二個 runtime 同樣成立，不需要信任它自己的權限旗標。

---

## 能力對照

| Capability | Codex | 處置 |
|---|---|---|
| `STRUCTURED_OUTPUT` | ✅ `--output-schema FILE` | 吃檔案不吃字串，adapter 先落檔 |
| `SESSION_RESUME` | ✅ `exec resume <id>` | 是子指令不是旗標 |
| `MODEL_SELECTION` | ✅ `-m` | 但模型名與 claude 不通用，見陷阱 3 |
| `NATIVE_AGENTS_MAP` | ✅ 原生載入 | 新增的能力宣告 |
| `APPEND_SYSTEM_PROMPT` | ❌ 無對應旗標 | 降級：角色定義併進 prompt 本文 |
| `MAX_TURNS` | ❌ 無 | **只能靠 timeout 控成本** |
| `COST_REPORTING` | ⚠️ 只有 token 數 | 訂閱不按次計費，`costEstimate` 回 null |
| `TOOL_PERMISSIONS` | ⚠️ 只有三級沙箱 | 不影響安全性，真正的邊界在外層 |

### 兩項降級的實質差異

**角色提示的優先級變了。** claude 的 `--append-system-prompt-file` 是系統提示，
codex 只能把角色定義當成一般使用者輸入併進 prompt。同樣的角色定義，
在兩個 runtime 下的約束力不完全等價。

**沒有回合數上限是實質風險。** claude 用盡回合會以 `error_max_turns` 停下，
codex 沒有這個機制，唯一的上限是逾時。跑失控的任務會一路燒到 timeout 才停。

---

## 四個陷阱（皆為實測踩到）

### 1. stdin 是 pipe 就會靜默掛住

`codex exec` 偵測到 stdin 是 pipe 會等 EOF，印一行
`Reading additional input from stdin...` 然後**掛到逾時**——
沒有錯誤、沒有結束碼。第一次測 `--output-schema` 就是這樣逾時的，
看起來完全像是 schema 拖慢了模型。

shell 要 `< /dev/null`，Java 端要在 `pb.start()` 後立刻關閉
`process.getOutputStream()`。

### 2. `resume` 不吃 `--cd`

`exec` 有 `--cd`，`exec resume` 沒有，傳了會直接以用法錯誤結束（碼 2）。
不指定工作目錄則會落在 home，被判定為 not a trusted directory 而失敗（碼 1）。
只能靠實際的行程 cwd —— adapter 交給 `ExecutionSandbox` 的 `--working-directory`。

### 3. 不認識的模型名不會乾脆拒絕

把 claude 的 `opus` 傳給 codex，它**不會拒絕**，而是印一個
`item.completed` 型別為 `error` 的事件、退回 fallback metadata 繼續跑，
最後才以結束碼 1 收場。

這與 claude 的行為相反（claude 遇到無效模型名直接結束碼 1，大聲失敗）。
adapter 因此要掃事件流裡的 error item 並帶進失敗訊息，
否則使用者只會看到一個看不出原因的「結束碼 1」。

**`AI_TEAM_MODEL_<角色>` 與 `AI_TEAM_RUNTIME_<角色>` 必須一起改。**

### 4. schema 的輸出形式不同

claude 回一個已驗證的 `structured_output` 欄位；
codex 是把 JSON 塞在 `agent_message` 的文字裡，adapter 要自己 parse 加驗證。

---

## 怎麼開

`~/ai-team/env.sh`：

```bash
export CODEX_BIN="$HOME/.local/node/bin/codex"
export AI_TEAM_RUNTIME_PM=codex        # 值為 AgentRuntime.id()：claude-code 或 codex
export AI_TEAM_MODEL_PM=               # 一併清掉或改成 codex 認得的名稱（見陷阱 3）
```

改完**必須重啟服務**。啟動時會回報每個角色的 runtime：

```
階段模型：PM Agent = opus（runtime claude-code）
```

留空一律退回 `claude-code`，既有部署不受影響。
設定值打錯（例如寫成 `codex-cli`）會在該階段執行時直接失敗，不會安靜退回預設 ——
安靜退回會讓人拿著一份不是該 runtime 跑出來的結果做比較。

只有真的有角色指定 codex 時才會要求 `CODEX_BIN` 可執行。

---

## 第一個使用者：Code Reviewer

新增的第五個角色 `code_review` 預設就跑在 codex 上
（`AI_TEAM_RUNTIME_CODE_REVIEW=codex`，已寫進 `env.sh`）。

會這樣配是有理由的：用另一個模型家族來看同一份程式碼，第二意見才有意義。
同一個引擎自己審自己，盲點也是同一套。

管線位置與行為：

```
Backend → QA → Code Review → 人工核准 → 推 PR
```

- **唯讀，而且是外部強制的。** 新增的 `WriteScope.READ_ONLY_WORKSPACE`
  讓它在工作區裡執行（看得到這次任務的所有 commit），但沙箱不給任何可寫路徑。
  review 就是 review，改了的話 QA 剛驗過的狀態就不是最終狀態了。
- **不阻斷。** findings 貼進討論串、結論寫進核准訊息，由人決定。
  這個階段自己失敗（逾時、格式不對）也只是略過，不會讓一輪已經 commit
  的工作停在這裡 —— 那會把「多一個意見」變成「多一個故障點」。
- 可用 `AI_TEAM_CODE_REVIEW=off` 整個關掉。

`review-schema.json` 已實測被 codex 接受。**注意 OpenAI 的 structured output 是
strict 模式，所有 property 都必須列在 `required`**，可選欄位要靠描述（例如
「不適用時給 0」）而不是靠省略。這與 claude 的 `--json-schema` 寬鬆得多的行為不同。

實測還確認了一件 adapter 的關鍵細節：**codex 會吐出多個 `agent_message`**，
中間那些是進度陳述（「我會先閱讀 A.java…」），最後一個才是結構化結果。
取第一個會拿到一句白話，然後在 JSON 解析時失敗。

## 尚未驗證

- **沒有跑過完整的端對端任務。** 上述都是 CLI 層與單元層的驗證，
  還沒有讓 Codex 以某個角色的身分實際跑完一輪 Slack 任務。
  這正是 `AGENTS.md` 那一項的教訓：CLI 能力與「在管線裡真的有效」是兩回事。
- Codex 對目標 repo 的 `.claude/skills/` 是否可用（那是 Claude Code 的格式，
  預期不可用，但 `AGENTS.md` 指向它們）。這會影響 commit 訊息格式與品質 Gate。
- 成本／耗時基準。訂閱制沒有金額，只能記 token 數，與現有的 US$ 數字無法直接比較。
