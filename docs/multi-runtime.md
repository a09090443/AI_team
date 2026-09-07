# 多 Runtime 設計

前提變更：Slack 裡可能同時存在多種 CLI Agent（Claude Code、Codex、Gemini CLI、Aider 等），架構不得依賴任何單一廠商的專屬機制。

本文修正 `architecture-review.md` 中在「只有 Claude Code」前提下寫出的幾項建議。

---

## 這個約束推翻了什麼

### 1. I4 的建議要調整

原評估說：「別自訂 `.ai-team/agents/*.md`，改用 Claude Code 原生的 `.claude/agents/` 格式。」

**在單一 runtime 前提下這是對的，在多 runtime 前提下是錯的。** `.claude/agents/` 是 Claude 專屬格式，Codex 與其他 CLI 讀不懂。

原文的自訂格式方向反而是對的，我否定得太快。

**修正做法：中立來源 + 建置時產生原生格式。**

```
roles/                          ← 唯一的事實來源（中立、廠商無關）
├── pm.yaml
├── architecture.yaml
├── backend.yaml
└── qa.yaml
        │
        │  build 時由各 runtime 的 adapter 轉譯
        ▼
├── .claude/agents/*.md         ← Claude Code 原生（含 isolation: worktree 等專屬能力）
├── .codex/...                  ← Codex 對應格式
└── prompts/*.md                ← 純文字 fallback（給沒有角色機制的 CLI）
```

這樣兩邊都拿到：**可攜性**來自中立來源，**各家的專屬強項**來自產生出來的原生格式。不需要為了可攜而放棄 `isolation: worktree`。

### 2. Phase -1 降級為參考點

官方 Claude in Slack 是 Claude 專屬、GitHub 專屬、雲端專屬。在多 runtime 前提下它不可能是最終產品。

仍然值得花 30 分鐘裝來用，但目的改變了：**當作品質與速度的基準線**，而不是「可能取代整個專案」。如果你自建的多 runtime 版本比它慢又比它笨，那是個訊號。

### 3. 安全邊界必須移出 runtime

這是最重要的一項。

原評估的權限模型建立在 `--permission-mode dontAsk` 與 `--allowedTools` 上 —— **那是 Claude Code 的旗標**。其他 CLI 的權限模型完全不同，有些甚至沒有細粒度權限控制。

如果安全依賴 runtime，那你的系統安全性等於**所有 runtime 中最弱的那一個**。

**修正：把隔離放在 runtime 之外。**

```
┌──────────────────────────────────────────┐
│ Orchestrator                              │
│                                           │
│  每個 task 啟動一個隔離執行環境：           │
│    ・專用 unix user 或容器                 │
│    ・唯讀掛載：專案唯讀副本                 │
│    ・可寫掛載：僅該 task 的 worktree        │
│    ・網路：預設拒絕，白名單放行             │
│    ・無 sudo、無 docker socket、無 SSH 金鑰 │
│                                           │
│    ┌────────────────────────────────┐    │
│    │  任意 CLI Runtime               │    │
│    │  （即使它完全沒有權限機制，       │    │
│    │    也只能在這個沙箱內造成傷害）    │    │
│    └────────────────────────────────┘    │
└──────────────────────────────────────────┘
```

各 runtime 自己的權限旗標（如 Claude Code 的 `dontAsk`）保留，但降級為**縱深防禦的第二層**，不是主要控制。

這個改動讓系統更穩健：新增一個你不熟悉的 CLI 時，不需要先搞懂它的權限模型才敢用。

#### 實作結果與上圖的落差

`ExecutionSandbox` 已完成並通過實測（`sandbox-report.md`，22 PASS）。
實際做出來的東西與上圖有三處落差，都是環境條件逼出來的：

| 上圖 | 實際 | 原因 |
|---|---|---|
| 專用 unix user 或容器 | systemd user unit（`ProtectSystem=strict` + `ReadWritePaths` 白名單） | 無免密碼 sudo，不能建使用者、不能裝 podman/docker/bwrap |
| 網路預設拒絕、白名單放行 | **未實作，網路完全開放** | 訂閱帳號認證需要連 Anthropic API；`NetworkNamespacePath` 之類的隔離會一併切斷認證。這是目前最大的殘留風險 |
| 唯讀掛載專案副本 | 唯讀角色直接以 repo 為 cwd（整個檔案系統唯讀）；可寫角色用**每任務獨立 clone** | worktree 的 index 寫在主 repo 底下，與沙箱的寫入邊界衝突，見 `workspace-report.md` |

另外多了一項上圖沒有的防護：cgroup 資源上限（`MemoryMax` / `CPUQuota` / `TasksMax`）。
這對應的是實際踩過的坑 —— 編譯開滿八核把 VM 壓到 soft lockup。

**前提條件**：檔案系統隔離需要 `kernel.apparmor_restrict_unprivileged_userns=0`。
不滿足時 systemd 會**靜默略過**所有檔案系統防護，所以 `selfTest()` 是這項設計決策的
執行條件，不是可有可無的健全性檢查。

---

## Runtime 抽象該畫在哪一層

原文提的 `AgentRuntime` 介面方向正確，但 `execute(task)` 這個簽章太粗 —— 它假設所有 runtime 能力相同，實際上差異很大。

**抽象要畫在語意層，不是旗標層。**

| 語意能力 | Claude Code | 其他 CLI 的可能狀況 |
|---|---|---|
| 附加角色系統提示 | `--append-system-prompt-file` | 有的只能取代、有的只能塞進 prompt |
| 工具/權限限制 | `--permission-mode` + `--allowedTools` | 可能只有粗粒度開關，或完全沒有 |
| 結構化輸出 | `--json-schema`（driver 層驗證） | 可能只有自由文字 |
| Session 續接 | `--resume <id>` | 可能沒有，或語意不同 |
| 回合上限 | `--max-turns` | 可能沒有 |
| 成本回報 | `total_cost_usd` | 可能沒有 |
| 工作目錄隔離 | `isolation: worktree` | 多半沒有 |

因此 runtime 抽象需要兩部分：**能力宣告**與**降級策略**。

### 能力宣告

每個 runtime adapter 宣告自己支援什麼。Orchestrator 據此決定怎麼呼叫，以及缺什麼要自己補。

### 降級策略

缺少能力時，由 Orchestrator 補上等效行為：

| 缺少 | 降級做法 |
|---|---|
| 結構化輸出 | 在 prompt 尾端要求輸出 fenced JSON，收到後**自行用 schema 驗證**；驗證失敗則重試一次，再失敗判為錯誤 |
| Session 續接 | Orchestrator 保存對話摘要，下次呼叫時把摘要重新放進 prompt |
| 回合上限 | 靠 wall-clock 逾時 + `destroyForcibly()` |
| 工具權限 | 完全交給外層沙箱（見上節） |
| 工作目錄隔離 | Orchestrator 自己開 worktree 並指定 cwd |

關鍵原則：**降級後的語意要一致**，呼叫端不需要知道底下是哪個 runtime。差別只在可靠度與成本，不在行為。

---

## 選擇 Runtime 的責任歸屬

原文提過「讓 Agent 自己選 Runtime」。在多 runtime 前提下這需要更明確：

**Runtime 選擇是 Orchestrator 的設定，不是 Agent 的執行期決定。**

理由：

- Agent 的角色定義應該與 runtime 無關，否則可攜性又沒了
- 執行期切換 runtime 會讓成本、額度、稽核都變得難以追蹤
- 除錯時你會需要「同一個任務用不同 runtime 重跑」的能力，這要求選擇權在外層

實作上是一張設定表：

```yaml
# runtime-binding.yaml
roles:
  pm:            { runtime: claude-code, model: opus }
  architecture:  { runtime: claude-code, model: opus }
  backend:       { runtime: codex }
  qa:            { runtime: claude-code, model: opus }

fallback:
  # 主 runtime 不可用時的替代（額度用盡、服務中斷）
  claude-code:   codex
```

改綁定不需要動任何 Agent 定義或 Slack 程式碼。這正是原文「Agent 是角色、Runtime 是引擎」要達到的效果。

---

## 不受影響的部分

值得明確指出：**Slack 那一層完全不變。**

- ack-first + `event_id` 去重（B1）
- 觸發者白名單（I5）
- `app_mention` + Socket Mode
- `chat.postMessage` / `chat.update` 進度回報
- 任務狀態機與人工審批閘門

這些與 runtime 無關。原文「Slack 不需要知道底下是 Codex、Claude Code 還是 OpenRouter」的判斷在這裡得到驗證 —— 這是整篇文章最站得住腳的一句話。

---

## 對 Phase 順序的影響

| Phase | 原本 | 修正後 |
|---|---|---|
| **-1** | 裝官方 Claude in Slack，評估能否取代自建 | 裝官方版**當基準線**（降級，不再是取捨點） |
| **0** | 驗證 Claude Code CLI 契約 | **驗證每個候選 CLI 的能力矩陣** |

Phase 0 的產出從「一份 Claude Code 的實測結果」變成**一張能力表**：每個候選 CLI 支援哪些語意能力、缺哪些、降級策略是否可行。

這張表直接決定 `AgentRuntime` 介面長什麼樣。**先量再設計**，不要先設計介面再去適配。

建議 Phase 0 至少涵蓋兩個 runtime（Claude Code 加上一個你真的打算用的），因為單一 runtime 量不出抽象邊界該畫在哪。

---

## 一個誠實的限制

本文關於 Claude Code 的能力全部來自官方文件核實。**其他 CLI 的能力我沒有驗證過**，上表的「其他 CLI 的可能狀況」是設計時該預留的彈性，不是對任何特定產品的斷言。

Phase 0 就是為了把這些猜測換成實測。
