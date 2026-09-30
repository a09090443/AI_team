# 多 Runtime 設計

系統同時支援多種 CLI Agent（Claude Code、Codex、opencode），架構不依賴任何單一廠商的專屬機制。
分層全貌見 [系統全貌](/agent/system-map)；本文只講 runtime 抽象與它外層的安全邊界。

---

## 安全邊界在 runtime 之外

若安全依賴各 CLI 自己的權限旗標，系統安全性就等於**所有 runtime 中最弱的那一個**。
因此每次 agent 執行都必須經過 `ExecutionSandbox` 包裝（`RuntimeRequest.sandboxPolicy` 不可為 null），
各 runtime 的權限旗標（如 Claude Code 的 `--permission-mode dontAsk` + `--allowedTools`）只是縱深防禦的第二層。
新增一個不熟悉的 CLI 時，不需要先搞懂它的權限模型才敢用。

### 沙箱實際做了什麼

`ExecutionSandbox` 把指令包成受限的 systemd user unit（`systemd-run --user`）：

| 機制 | 作用 |
|---|---|
| `ProtectSystem=strict` + `ReadWritePaths` | 檔案系統唯讀，只有白名單路徑可寫 |
| `ProtectHome=read-only` | 家目錄可讀不可寫 |
| `PrivateTmp=yes` | 獨立 `/tmp` |
| `MemoryMax`／`CPUQuota`／`TasksMax` | cgroup v2 資源上限（曾因編譯開滿八核把 VM 壓到 soft lockup） |
| 環境變數白名單 | 只帶入 `PATH`、`JAVA_HOME`、`MAVEN_OPTS`，Slack token 不會進沙箱 |
| runtime 狀態目錄 | 由各 runtime 以 `statePaths()` 宣告可寫，`escalationPins()` 把其中的設定檔／外掛目錄釘回唯讀，避免 agent 改寫自己下一次的執行條件 |

可寫角色使用**每任務獨立 clone**，不用 git worktree：worktree 的 index 與物件寫在主 repo 底下，
與沙箱只開放工作區可寫的邊界衝突（`git add` 會以 `index.lock: Read-only file system` 失敗）。

**已知限制**：

- **網路沒有隔離**。訂閱帳號認證需要連供應商 API，網路命名空間隔離會一併切斷認證。這是目前最大的殘留風險。
  例外是 Codex 節點的 `localTestNetwork`，見 [本機測試能力](local-test-capabilities.md)。
- **檔案系統隔離需要 unprivileged user namespace**。核心以 AppArmor 擋掉時
  （Ubuntu 24.04 起預設 `kernel.apparmor_restrict_unprivileged_userns=1`），systemd **不會報錯，只會靜默略過**
  上述檔案系統防護。啟動時的 `selfTest()` 會實際嘗試越界寫入，把靜默失敗變成啟動失敗；它不是可有可無的健全性檢查。

### Claude Code 角色寫不了受保護路徑（已知限制）

`ClaudeCodeRuntime` 固定以 `--permission-mode dontAsk` 執行。Claude Code 對一組**受保護路徑**的寫入
永遠不自動核准，在 `dontAsk` 模式下的結果是**直接拒絕**，錯誤訊息為
`Permission to use Edit has been denied because Claude Code is running in don't ask mode.`，
沙箱與檔案權限都沒有問題（2026-09-29 #1021 實作節點改 `.claude/rules/project-overview.md` 即為此例，
同批其他檔案的 Edit 都成功）。

- 受保護目錄（依 Claude Code 官方 permission-modes 文件，VM 上為 2.1.282）：`.git`、`.config/git`、`.vscode`、`.idea`、
  `.husky`、`.cargo`、`.devcontainer`、`.yarn`、`.mvn`、`.claude`（`.claude/worktrees` 除外）；
  另有 `.mcp.json`、`.claude.json`、`.npmrc`、`maven-wrapper.properties`、`gradle-wrapper.properties`、
  shell rc 檔、git hook 設定檔等受保護檔案。完整清單以官方文件為準。
- **把 `Edit(.claude/**)` 之類的規則加進角色 `tools`（`--allowedTools`）沒有用**：官方文件寫明安全檢查在
  allow 規則之前執行，allow 規則不會預先核准受保護路徑。只有 `bypassPermissions` 允許這類寫入，
  但該模式同時讓 `--allowedTools` 不再限制工具，會拿掉縱深防禦的第二層，目前沒有採用。
- Bash 的輸出重導與 `tee` 目標也會套用同一檢查，改用 shell 指令寫入同樣會被拒。
- 需求或驗收項目若要求改這些路徑，只能由真人在任務分支手動修改並提交，或把該檔移出需求範圍。
  目標專案的慣例：會隨版本或功能變動的資訊（版本號、模組清單）不要寫在 `.claude/rules/` 等受保護路徑，
  改寫在建置檔、`AGENTS.md` 或一般文件，受保護路徑只放穩定的規則並指向那些檔案。

系統的處理方式（2026-09-30 起）：

| 位置 | 行為 |
|---|---|
| `AgentRuntime.protectedWritePaths()`／`isProtectedWrite()` | runtime 宣告自身一律拒寫的路徑；預設空清單。只有 `ClaudeCodeRuntime` 宣告（`PROTECTED_WRITE_PATHS`，依 2.1.282 官方清單，換 CLI 版本要重新核對）。 |
| `ConfiguredExecutor.protectedPathNotice()` | 派工時注入 context。寫檔節點（`workspace=true` 且 scope 不是 READ_ONLY）只看自己 runtime 的清單：不要嘗試、把需真人修改的檔案寫進 `human_decision`／`also_needed`、不是 `boundary_conflict`。唯讀節點（intake、design、plan、triage、review）看本流程所有寫檔節點的清單聯集：不要把改這些檔案列為實作的驗收、分流不得提出「開放權限後重派」。清單全空時不輸出。 |
| `ClaudeCodeRuntime.annotateDenials()` | 解析輸出時，Edit／Write／MultiEdit／NotebookEdit 的 `file_path` 命中清單就補上 `reason`，`TOOL_DENIED` 稽核、`ToolTrust` 與失敗描述因此寫出真正原因，而不是「未提供具體拒絕理由」。Bash 被拒仍維持原文字。 |

注入文字是由程式產生的，不在 teams 提示裡，所以既有任務快照下一次執行也會看到；它只是說明，不改變任何權限。
`NodeInput` 指紋不含 context 文字，所以不會影響「相同輸入連續重跑」的計數。
狀態：程式測試通過；真實任務端對端尚未實測。

---

## Runtime 抽象畫在語意層

`AgentRuntime` 不抽象 CLI 旗標，而是抽象語意能力。每個 adapter 以 `capabilities()` 宣告自己支援什麼，
缺少的由 adapter 或呼叫端降級補足。宣告成有而實際沒有，會讓呼叫端跳過降級，失敗樣態是「安靜地少做事」。

| 能力 | Claude Code | Codex | opencode | 缺少時的降級 |
|---|---|---|---|---|
| `APPEND_SYSTEM_PROMPT` | ✔ | — | — | 角色提示前置進 prompt 本文 |
| `STRUCTURED_OUTPUT` | ✔（`--json-schema`） | ✔（`--output-schema` 吃檔案） | — | schema 寫進 prompt，回來自行 parse 與驗證 |
| `SESSION_RESUME` | ✔ | ✔ | ✔ | — |
| `MAX_TURNS` | ✔ | — | — | 只靠 wall-clock 逾時控成本 |
| `TOOL_PERMISSIONS` | ✔ | — | — | 完全交給外層沙箱 |
| `COST_REPORTING` | ✔ | — | ✔（實際金額） | 成本欄位為 null，呼叫端必須容忍 |
| `MODEL_SELECTION` | ✔ | ✔ | ✔ | — |
| `NATIVE_AGENTS_MAP` | — | ✔ | ✔ | legacy 模式由呼叫端把 `AGENTS.md` 附進 prompt；配置式模式不論能力一律附上（截斷至 16000 字） |

原則：**降級後的語意要一致**，呼叫端不需要知道底下是哪個 runtime；差別只在可靠度與成本。
各 CLI 的實際參數與陷阱見 [Codex CLI 參數契約](codex-cli-argument-contract.md)、
[opencode 實測報告](opencode-runtime-report.md)。模型名三家互不通用，換 runtime 時要一併換模型。

---

## Runtime 選擇是設定，不是 agent 的執行期決定

- 配置式模式：團隊 JSON 的 `roles[].runtime`／`model`（見 [配置式團隊](configurable-team.md)）。
- legacy 模式：`AI_TEAM_RUNTIME_<角色>`、`AI_TEAM_MODEL_<角色>`（見 [每個 Agent 指定模型](per-agent-model.md)）。

理由：角色定義要與 runtime 無關；執行期切換會讓成本、額度、稽核難以追蹤；
除錯需要「同一任務換 runtime 重跑」的能力，選擇權必須在外層。
目前**沒有**額度用盡時自動改用另一個 runtime 的 fallback；額度用盡走 [額度暫停與自動續跑](quota-continuation.md)。
