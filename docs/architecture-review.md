# 架構評估報告：Slack + Claude Code 的 AI 軟體團隊

| 項目 | 內容 |
|---|---|
| 評估對象 | ChatGPT 對話 `chatgpt.com/share/6a95a02b-aec4-83e8-b216-0b8d2ff0421c` |
| 評估日期 | 2026-09-01 |
| 執行環境前提 | 乾淨的 Linux VM、**現有 Claude 訂閱帳號**（非 API key） |
| 目標專案 | `zipe-spring-boot-starters` |

---

> **Phase 0 已完成驗證（2026-09-01）**
>
> 本報告中所有關於 Claude Code CLI 行為的主張，原本僅來自官方文件。已在實機（Ubuntu 26.04、Claude Code 2.1.252、訂閱帳號）逐項實測：**13 項通過、0 項失敗**。
>
> 詳見 `phase0-report.md`。以下 B2、B4、I2、I3、I6 的技術前提均為實測確認，可直接採用。

## 摘要

原文的**架構分層判斷是正確的**，值得完整保留。問題出在**與 Claude Code CLI 和 Slack Events API 的實際契約對不上** —— 原文的架構圖畫得比它的程式碼可靠得多。

本報告列出 **4 個 Blocker、6 個重要問題、4 項建議**。影響最大的是 B1：照原文的程式碼，**每一個 Slack 需求會被執行四次**，產生四條分支、四倍額度消耗。

嚴重度定義：

| 等級 | 含義 |
|---|---|
| **Blocker** | 照做會跑不起來，或造成重複執行、額度暴衝 |
| **重要** | 跑得起來，但會讓系統永遠停在 Demo，無法演進成可靠 pipeline |
| **建議** | 可延後，但越早做越省事 |

---

## 一、判斷正確、應完整保留

這些是原文最有價值的部分，修正過程中不要動到。

### 1.1 Agent = 角色，Runtime = 引擎

整篇最重要的抽象。Agent 擁有 role / system prompt / tools / permission / memory；Codex、Claude Code、OpenRouter 只是可抽換的執行引擎。這個分層讓「換模型不用改 Slack」成立，也讓第一版只用 Claude Code、第二版再引入其他 runtime 的漸進路線可行。

**保留，並且要更徹底** —— 見 I4，原文自己在實作時違反了這個原則。

### 1.2 單一 Agent Gateway，不是多個 Slack Bot

正確。多個 Bot 會把路由邏輯散落在 Slack 設定裡，且無法共享任務狀態與 context。

### 1.3 Knowledge Graph 是導航系統，Repository 才是 source of truth

這是原文第二輪最好的一段。KG 會過期（原文舉的 `DataSourceConfig` 從 MySQL 改成 `DatabaseProvider` 是很真實的例子），Agent 必須回頭驗證實際原始碼。

這個定位直接決定了 KG 的落地方式應該是「可查詢的工具」而不是「塞進 prompt 的事實」—— 見 S1。

### 1.4 分階段落地，第一版只用 Claude Code

正確。原文自己勸退了「一開始就多模型」，這個克制是對的。

### 1.5 權限分級與拒絕 `--dangerously-skip-permissions`

方向完全正確：PM / Architecture 唯讀、Backend 隔離工作區、QA 讀取加測試。原文明確踩煞車不用 `--dangerously-skip-permissions`，這個判斷要保留。

問題在於**原文的程式碼並沒有實作它自己描述的權限分級** —— 見 B2。

---

## 二、Blocker

### B1. Slack 3 秒 ack 違規，同一個需求被執行四次

**原文（第 22 節）**

```java
app.event(AppMentionEvent.class, (payload, ctx) -> {
    String result = pmAgent.handle(text);   // 這裡會跑好幾分鐘
    ctx.say(result);
    return ctx.ack();                       // ack 在這裡才送出
});
```

**問題**

Slack Events API 要求 **3 秒內回應 HTTP 2xx**。逾時後 Slack 會自動重送 3 次，時程為：立即、約 1 分鐘後、約 5 分鐘後。每次重送帶 `x-slack-retry-num` 標頭。

`pmAgent.handle()` 內部啟動 Claude Code 分析整個 repository，實務上是分鐘等級。因此：

| 時間 | 事件 | 結果 |
|---|---|---|
| T+0s | 使用者 @ 提及 | 第 1 個 Claude Code process 啟動 |
| T+3s | Slack 未收到 ack | 重送 #1，第 2 個 process |
| T+1m | 仍未 ack | 重送 #2，第 3 個 process |
| T+5m | 仍未 ack | 重送 #3，第 4 個 process |

四個 Agent 同時分析（或更糟，同時修改）同一個 repository。在訂閱帳號下，這是四倍的額度消耗。

**修正**

先 ack，再處理：

1. Handler 內只做三件事：**用 `event_id` 去重、檢查觸發者白名單、丟進佇列**，然後立刻 `ack()`。
2. 背景 worker 執行 Claude Code，完成後用 `chat.postMessage` 回覆到原 thread。
3. `event_id` 去重是必要的，不是可選的 —— 網路抖動、部署重啟都會觸發重送。

連「已收到」的回覆訊息都建議交給 worker 發，讓 handler 維持在微秒等級。參考實作見 `reference/SlackListener.java`。

> 註：Socket Mode 走 WebSocket 而非 HTTP，但同樣有 ack 與重送機制，這個修正一樣必要。

---

### B2. 共用的 Runner 完全沒有傳權限旗標

**原文（第 25 節）**

`ClaudeCodeRunner.run()` 組出的指令只有三個旗標：

```
claude --system-prompt "$(cat ...)" -p "$PROMPT" --output-format json
```

沒有 `--allowedTools`，沒有 `--disallowedTools`，沒有 `--permission-mode`。而**四個 Agent 全部共用這一個 Runner**。

原文第 26 節用文字描述了完整的權限分級表，但那段從未進入程式碼。架構圖與實作之間有一道落差。

**問題**

官方文件明載：`-p` 模式的內建起始權限模式是 **Manual**，在所有方案上皆同，並要求呼叫端自行指定要的模式。headless 環境沒有人可以回答權限提示，因此連 PM Agent 最基本的 `Read` 與 `Grep` 都無法通過。

**修正**

明確傳入權限模式，並依角色給不同的工具白名單：

```
--permission-mode dontAsk --allowedTools "Read,Grep,Glob" --max-turns 15
```

`dontAsk` 是官方為 locked-down CI 設計的模式：只放行 `permissions.allow` 規則與唯讀指令集，其餘一律拒絕而非等待。這正是自動化場景要的語意。

Backend Agent 的 Bash 要收斂成前綴規則：

```
--allowedTools "Read,Write,Edit,Grep,Glob,Bash(mvn *),Bash(git *)"
```

> 注意 `*` 前的空格是官方語法要求。`Bash(git diff *)` 與 `Bash(git diff*)` 語意不同，後者會連 `git diff-index` 一起放行。

---

### B3. `redirectErrorStream(true)` 與 `--output-format json` 互斥

**原文（第 25 節）**

```java
pb.redirectErrorStream(true);
...
return output;   // 呼叫端要當成 JSON 解析
```

**問題**

stderr 被併進 stdout。Claude Code 在啟動階段會輸出警告到 stderr（MCP server 因設定無效被跳過、plugin 載入失敗等，官方文件明確列出這些情況）。任何一則警告都會讓 JSON 解析失敗，而且**失敗時機不固定**，難以除錯。

**修正**

分離兩條串流：stdout 保持 pipe 供解析，stderr 導向每個任務自己的 log 檔案。後者同時滿足稽核需求。

另外要注意 **stdout 與 stderr 同時讀取的死鎖風險**：若兩條都是 pipe 而只讀其中一條，另一條的緩衝區填滿後子行程會卡住。把 stderr 導向檔案可以一併避開這個問題。

---

### B4. `--system-prompt` 是「取代」而非「附加」

**原文（第 13、25 節）**

四個角色全部使用 `--system-prompt "$(cat <role>.md)"`。

**問題**

官方 CLI 文件對 `--system-prompt` 的定義是「**取代整份**系統提示」。

也就是說，這個做法會把 Claude Code 內建的系統提示整份丟掉，包含工具使用規範、檔案編輯慣例、搜尋策略、以及 coding agent 的核心行為指引。剩下的只有原文那份幾十行的角色描述。

結果是一個保有工具、但不知道該怎麼用工具的 agent。這會表現為「品質莫名其妙地差」，而且極難歸因。

**修正**

改用 `--append-system-prompt-file`：

```
--append-system-prompt-file /path/to/backend.md
```

這同時解決了另一個問題：**原文的 `$(cat '%s')` shell 展開不再需要**，見 I1。

`--system-prompt` 只在你真的要把 Claude Code 變成完全不同的東西時才用，本專案不屬於這種情況。

---

## 三、重要問題

### I1. Slack 文字經過 shell

**原文（第 25 節）**

```java
ProcessBuilder pb = new ProcessBuilder("bash", "-c", """
    claude --system-prompt "$(cat '%s')" -p "$PROMPT" --output-format json
    """.formatted(systemPromptFile));
pb.environment().put("PROMPT", prompt);
```

**問題**

Prompt 雖然透過環境變數傳遞（比字串內插好），但整條指令仍在 `bash -c` 下執行，命令替換也在 shell 裡完成。這個設計把 shell 放進了資料路徑，是不必要的攻擊面與跳脫地雷。

**修正**

用 `ProcessBuilder` 的**參數陣列**建構指令，完全不經 shell。Java 的 `ProcessBuilder(List<String>)` 直接把每個元素當成 `argv[]`，不做任何 shell 解析：

```java
List<String> cmd = List.of(
    claudeBinary, "-p", prompt,
    "--append-system-prompt-file", promptFile.toString(),
    "--output-format", "json",
    "--permission-mode", "dontAsk"
);
```

**但要區分兩種注入**：參數陣列消除的是 **shell 注入**。Slack 文字作為 prompt 送給一個能改 code、能跑 shell 的 agent，仍然是 **prompt 注入**面，那要靠權限模型與觸發者授權處理，見 I5。這兩件事不能混為一談。

### I2. PM 輸出是自由文字，Orchestrator 無法可靠決策

**原文**

PM Agent 回傳一段 Markdown（`Task Type: FEATURE`、`Affected Modules: ...`），Orchestrator 需要靠字串解析決定下一步派給誰。

**問題**

LLM 的自然語言輸出格式不穩定。用正則解析「這次要不要叫 Backend Agent」，等於把整條 pipeline 的可靠度押在字串比對上。

**修正**

用 `--json-schema` 取得經過驗證的結構化輸出：

```
--output-format json --json-schema "$(cat task-schema.json)"
```

結果會出現在回應的 `structured_output` 欄位。Schema 無效時 `claude` 會直接以錯誤退出，不會靜默退回非結構化文字。

這是**把 Demo 變成 Pipeline 的關鍵一步**。Schema 見 `reference/task-schema.json`。

### I3. 完全無狀態，Slack thread 追問拿不到前文

**原文**

每則 Slack 訊息都啟動一個全新的 process。使用者在同一個 thread 追問「那 MySQL 的相容性呢？」時，Agent 對前一輪一無所知。

**修正**

`--output-format json` 的回應包含 `session_id`。存下 `thread_ts` 與 `session_id` 的對應，後續訊息用 `--resume <session_id>` 續接。

官方文件註明：從 v2.1.223 起可以在不同目錄下用 ID 續接 session，不再限定同一專案目錄。

### I4. 重造了一套較弱的 Agent 抽象

**原文（第 8 至 12、27 節）**

自訂 `.ai-team/agents/{pm,architecture,backend,qa}.md` 格式，並在第 27 節手動處理工作區隔離：

```bash
git clone ~/ai-team/zipe-spring-boot-starters ~/ai-team/workspaces/task-1024
```

**問題**

Claude Code 原生就有 subagent 機制，格式是 `.claude/agents/*.md`，frontmatter 支援：

| 欄位 | 用途 |
|---|---|
| `tools` | 工具白名單 |
| `disallowedTools` | 工具黑名單 |
| `model` | 模型指定 |
| `permissionMode` | 權限模式 |
| `maxTurns` | 回合上限 |
| `memory` | 持久記憶範圍（`user` / `project` / `local`） |
| `skills` | 預載技能 |
| `hooks` | 生命週期掛鉤 |
| **`isolation: worktree`** | **在獨立 git worktree 中執行** |

最後一項直接取代原文第 27 節整段手動 clone 的設計，而且 worktree 比 clone 更省空間、更快。

原文等於重寫了一份能力較弱的版本。這同時違反了它自己在 1.1 提出的分層原則：Agent 的角色定義本來就屬於 Runtime 的能力範圍。

**修正**

- 角色定義改用原生 `.claude/agents/*.md` 格式（見 `reference/agents/`）。
- 外部 Orchestrator 只保留三件 Claude Code 不管的事：**任務狀態機、人工審批閘門、稽核紀錄**。
- 不要在 Java 裡重寫 agent 框架。

> **後續修正（多 runtime 前提）**
>
> 上述「改用原生 `.claude/agents/` 格式」的建議**只在單一 runtime 前提下成立**。若 Slack 裡會有多種 CLI Agent，`.claude/agents/` 是 Claude 專屬格式，其他 CLI 讀不懂。
>
> 正確做法是**中立來源 + 建置時產生各 runtime 的原生格式**，兩邊都拿到。詳見 `multi-runtime.md`。

### I5. 沒有觸發者授權，也沒有 prompt injection 防線

**問題**

原文的權限設計全部集中在「Agent 能用哪些工具」，完全沒有處理「誰可以觸發 Agent」。

在 Slack 頻道裡，**任何 workspace 成員**都能 @ 提及這個 bot，觸發一個會修改程式碼、執行 shell、建立 commit 的 autonomous agent。而且不可信輸入不只來自 Slack —— repository 內的 issue 內容、程式碼註解、README，在 Agent 讀取時同樣是注入來源。

**修正**

分三層：

1. **觸發者白名單**：以 Slack user ID 為準，不是頻道權限。非白名單使用者的請求直接拒絕並回覆說明。
2. **破壞性操作需人工核准**：推送分支、開 PR、任何離開隔離工作區的動作，都要在 Slack 上由真人按下確認。Agent 只能做到「準備好了」為止。
3. **工具面收斂**：Backend 的 Bash 用前綴規則限制（見 B2），明確排除 `sudo`、`docker`、`systemctl`、`rm`、`ssh`、`curl`。

### I6. 訂閱帳號的真正護欄是速率限制，不是金額

你選擇沿用現有 Claude 訂閱帳號。這帶來三個原文完全沒有提到的限制。

**1. `--bare` 不能用**

官方建議腳本與 SDK 呼叫使用 `--bare`（並註明未來會成為 `-p` 的預設值），但 `--bare` 模式**不讀 OAuth 憑證、不讀系統金鑰鏈**，需要 `ANTHROPIC_API_KEY`。訂閱帳號下無法使用。

**2. 不能用 `--bare` 的連帶後果**

`-p` 會載入工作目錄的 `.claude/settings.json` hooks 與 `.mcp.json`，而且**不顯示工作區信任對話框、不做逐一 server 核准**。這表示 repository 的內容等同於執行面 —— 任何人送 PR 進 `zipe-spring-boot-starters` 修改 `.claude/settings.json`，就能在你的 VM 上執行程式碼。這一層必須納入 code review 流程。

**3. 額度是全帳號共用**

Agent SDK 文件明載：未經事先核准，Anthropic 不允許第三方開發者以 claude.ai 登入或用量額度提供給其產品。作為**自用內部工具**是另一回事，但要清楚：四個 Agent 乘上多位 Slack 使用者共用你個人的訂閱額度，很容易吃光，而且會連帶影響你自己日常使用 Claude Code。

**修正**

在訂閱帳號下，護欄不能是金額，只能是次數與並行度：

| 護欄 | 設定 |
|---|---|
| 單次回合上限 | `--max-turns`（PM/QA 15、Backend 40） |
| 全域並行數 | **限制為 1**，用佇列排隊 |
| 觸發者 | 白名單（見 I5） |
| 單次逾時 | Java 端 `waitFor(timeout)` 搭配 `destroyForcibly()` |

並記錄清楚：**`--max-budget-usd` 與 `--bare` 要等改用 API key 才能啟用**。這兩個是你目前拿不到的工具。

---

## 四、建議

### S1. Knowledge Graph 提前做成 MCP server

原文把 KG 接入放在第 29 節（HTTP API 加上把結果塞進 prompt），MCP 化排到第 30 節的「第二階段」。

建議對調。理由：

- 寫一個 MCP server 不會比寫「HTTP client + 結果格式化 + 塞 prompt」更費工。
- MCP 讓 Agent **按需查詢**，而不是每次都先撈一批 context 塞進去。這在 KG 很大時是關鍵差異。
- 直接呼應 1.3 的定位：KG 是導航工具，工具就該做成工具。

注意：`.mcp.json` 會在不帶 `--bare` 時自動載入（見 I6），這裡剛好順勢可用。

### S2. 並行衝突與工作區清理

原文的隔離工作區解決了「Backend 不碰 production checkout」，但沒處理：

- 兩個任務同時修改同一個 module，需要 module 層級的鎖。
- 工作區累積，需要保留策略（例如成功合併後刪除、失敗保留 7 天供除錯）。

若採用 `isolation: worktree`（I4），第二點會簡單很多。

### S3. 把 repository 內容納入信任邊界審查

延續 I6 的第 2 點。`.claude/settings.json`、`.mcp.json`、`.claude/agents/` 這三個路徑在你的架構下具有執行語意，應該：

- 加入 CODEOWNERS 或等效的必審規則。
- 在 Orchestrator 啟動任務前做一次 checksum 比對，變更時要求人工確認。

### S4. 版本號安裝時再核對

原文給的 Spring Boot 3.5.0、`bolt-socket-mode` 1.51.0、Tyrus 1.20 需要以安裝當下的現況為準，不要照抄。Node.js 與 Claude Code 的最低版本要求同理。

---

## 五、語言與執行環境決定

### 結論：維持 Java + Spring Boot

原文選 Java，這個結論**維持**，但理由與原文不同。

**為什麼不換成 Python 或 TypeScript**

官方 Agent SDK 只有 Python 與 TypeScript 兩種語言，其他語言的官方途徑就是「把 CLI 當 subprocess 呼叫」，原文的方向本身正確。

換語言唯一的好處是能用 `claude-agent-sdk`，拿到 in-process 的工具審批 callback、hooks、串流事件、`interrupt()`。**但 Agent SDK 的 Quickstart 要求 API key 認證**。在你選擇的訂閱帳號路線下，Agent SDK 不是官方背書的路徑，換語言換不到實質好處。

**為什麼 Java 合適**

Orchestrator 的本質工作是：Socket Mode 長連線、任務佇列、狀態機、子行程管理、持久化、稽核。這些正好是 Spring Boot 的強項，而且是你的專業領域，維護成本最低。

hooks 仍可透過 `--settings` 傳入 JSON 設定使用，不受語言限制。

### 明確記錄的技術債

> 若日後改用 API key，並且需要 in-process 的 `canUseTool` 審批 callback、串流事件、或 `interrupt()`，必須將 Orchestrator 改寫為 Python 或 TypeScript。

這是選擇 Java 所放棄的能力。現在放棄是合理的，但要知道自己放棄了什麼。

---

## 六、修正後的落地順序

原文的 8 個 Phase 方向正確，但**起點太晚**。修正後：

| Phase | 內容 | 驗收標準 |
|---|---|---|
| **-1** | **裝官方 Claude in Slack，當作品質基準線**（見 `slack-options.md`、`multi-runtime.md`） | 你的自建版應該要比它好；比它差就是訊號 |
| **0** | **驗證候選 CLI 的能力矩陣**（不寫任何程式，見 `multi-runtime.md`） | 產出一張能力表：每個候選 CLI 支援哪些語意能力、缺哪些、降級策略是否可行。至少涵蓋兩個 runtime |
| **1** | Slack 最小 App：只做 ack、佇列、回覆「已收到」 | 故意讓背景任務跑 30 秒，**確認沒有重複訊息**（驗證 B1） |
| **2** | 接上 PM Agent（唯讀），輸出走 JSON Schema | Slack 提出需求，回傳結構化的任務分類 |
| **3** | 加入 thread session 續接 | 同一 thread 追問能拿到前文 |
| **4** | Architecture Agent（唯讀，產出 ADR 文字，由 Orchestrator 落檔） | ADR 內容合理且引用了實際程式碼 |
| **5** | Backend Agent（worktree 隔離）與 QA Agent | 完整跑完一次 feature，停在「等待人工核准」 |
| **6** | Knowledge Graph MCP server | Agent 主動查詢 KG 而非被餵 context |
| **7** | Gitea / Jira 事件源 | — |
| **8** | 多 Runtime（Codex / OpenRouter） | 需先切換到 API key |

**Phase -1 與 Phase 0 都是原文沒有的**，卻是最重要的兩步，而且都在寫任何 orchestrator 程式碼之前。

Phase 0 尤其關鍵：它的產出是一張**能力矩陣**，而那張表直接決定 `AgentRuntime` 介面長什麼樣。**先量再設計**，不要先設計介面再去適配 —— 單一 runtime 量不出抽象邊界該畫在哪，所以 Phase 0 至少要涵蓋兩個 runtime。

---

## 附錄：官方文件對照

| 主張 | 出處 |
|---|---|
| `-p` 起始權限模式為 Manual、`--allowedTools` 語法、`--json-schema`、session 續接、`--bare` 的認證限制 | `code.claude.com/docs/en/headless` |
| `--system-prompt` 為「取代」、`--append-system-prompt-file`、`--permission-mode` 可用值、`--max-budget-usd`、`--max-turns` | `code.claude.com/docs/en/cli-reference` |
| `.claude/agents/*.md` frontmatter 欄位、`isolation: worktree`、`--agents` JSON | `code.claude.com/docs/en/sub-agents` |
| Agent SDK 僅 Python 與 TypeScript、其他語言走 CLI subprocess、認證條款 | `code.claude.com/docs/en/agent-sdk/overview` |
| 3 秒 ack、3 次重送時程、`x-slack-retry-num`、建議用佇列處理 | `docs.slack.dev/apis/events-api` |
| Socket Mode 的 token 需求、不得上架 Marketplace、10 條連線上限 | `docs.slack.dev/apis/events-api/using-socket-mode` |
| Agents 容器的串流與狀態僅限 DM、所需 scope 與事件、需付費方案 | `docs.slack.dev/ai/developing-ai-apps` |
| 官方 Claude Code in Slack：Pro/Max 可用、僅支援 GitHub、跑在雲端環境、各自使用個人額度、context 注入警告 | `code.claude.com/docs/en/slack` |
| Slack 各方案的 app 數量與訊息保留限制 | `slack.com/pricing` |
| Free 方案完整限制：頻道與成員無上限、huddle 僅 1:1、90 天保留、10 個 app、Workflow Builder 停用 | `slack.com/help/articles/27204752526611` |

---

## 相關文件

- `multi-runtime.md` — **多 Runtime 設計**：修正 I4、安全邊界移出 runtime、能力宣告與降級策略
- `slack-options.md` — **Slack 方案評估**：自建 vs 官方 Claude in Slack、付費方案、Socket Mode vs HTTP、bot 型態
- `corrected-architecture.md` — 修正後的架構圖與職責邊界
- `reference/AgentRuntime.java` — 廠商無關的 runtime 介面與能力宣告
- `reference/ClaudeCodeRunner.java` — Claude Code 的 adapter 實作
- `reference/SlackListener.java` — 修正版 ack-first listener
- `reference/agents/*.md` — 原生格式的四個角色定義
- `reference/task-schema.json` — PM Agent 輸出的 JSON Schema
