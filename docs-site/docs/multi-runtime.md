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
| 環境變數白名單 | 只帶入 `PATH`、`JAVA_HOME`、`MAVEN_OPTS`、`GRADLE_USER_HOME`，Slack token 不會進沙箱（`GRADLE_USER_HOME` 讓 Gradle 專案指向已核准的快取，見 [配置經驗](configurable-team.md#配置經驗gradle前端與多-runtime-專案)） |
| runtime 狀態目錄 | 由各 runtime 以 `statePaths()` 宣告可寫，`escalationPins()` 把其中的設定檔／外掛目錄釘回唯讀，避免 agent 改寫自己下一次的執行條件 |

可寫角色使用**每任務獨立 clone**，不用 git worktree：worktree 的 index 與物件寫在主 repo 底下，
與沙箱只開放工作區可寫的邊界衝突（`git add` 會以 `index.lock: Read-only file system` 失敗）。

**已知限制**：

- **網路沒有隔離**。訂閱帳號認證需要連供應商 API，網路命名空間隔離會一併切斷認證。這是目前最大的殘留風險。
  例外是 Codex 節點的 `localTestNetwork`，見 [本機測試能力](local-test-capabilities.md)。
- **檔案系統隔離需要 unprivileged user namespace**。核心以 AppArmor 擋掉時
  （Ubuntu 24.04 起預設 `kernel.apparmor_restrict_unprivileged_userns=1`），systemd **不會報錯，只會靜默略過**
  上述檔案系統防護。啟動時的 `selfTest()` 會實際嘗試越界寫入，把靜默失敗變成啟動失敗；它不是可有可無的健全性檢查。

### 共用 timeout 與精確 unit 清理（2026-10-20）

三個 adapter 的模型逾時統一拋 RuntimeTimedOut，繼承 AgentRuntime.RuntimeExecutionException，不改 execute 簽章。靜態 find(Throwable) 遍歷 cause chain（防循環），找不到回 null。可讀取 runtimeId、timeout、unit、stdoutLog、stderrLog、phase 與 cleanup；phase 為 MODEL 或 Codex 的 PREFLIGHT，cleanup 含 status／unit／detail。

unit 由既有 wrap 使用的名稱加 UUID 識別本次執行。ExecutionSandbox 明確設定 KillMode=control-group、TimeoutStopSec=3s、SendSIGKILL=yes；逾時或 Interrupted 先由 SandboxUnitCleanup 停精確 user unit，必要時對同 unit 全部程序送 SIGKILL，檢查 unit／cgroup 後再回收 systemd-run launcher，保留 interrupt 旗標。CONFIRMED 表示已確認停止／cgroup 清空，FAILED 表示已觀察到清理未完成，UNKNOWN 表示無法確認；不能只看 launcher 結束就宣稱所有子程序終止。

stdout／stderr 保留，既有 sessionStarted callback 與 checkpoint 介面維持。模型 timeout 不等於遠端操作失敗；FlowEngine 只有在 phase=MODEL、cleanup.status=CONFIRMED 且有有效 journal 時先對帳，再續同 activeNode。沒有全域 task kill，不停止獨立 Agent executor 或遠端 root job。詳見[長作業恢復](direct-ssh-deployment.md)。

隔離 Linux 完整驗證包含 AI_TEAM_SANDBOX_CLEANUP_SMOKE=true 的真實測試：清除本輪detached子程序、另一unit仍存活。獨立SSH health在提交連線結束後仍成功。這些不等同三個runtime與所有真實故障組合已全面演練。

### Claude Code 角色寫不了受保護路徑（已知限制）

`ClaudeCodeRuntime` 固定以 `--permission-mode dontAsk` 執行。Claude Code 對一組**受保護路徑**的寫入
永遠不自動核准，在 `dontAsk` 模式下的結果是**直接拒絕**，錯誤訊息為
`Permission to use Edit has been denied because Claude Code is running in don't ask mode.`，
沙箱與檔案權限都沒有問題（實測：實作節點改 `.claude/rules/project-overview.md` 被拒，
同批其他檔案的 Edit 都成功）。

- 受保護目錄（依 Claude Code 官方 permission-modes 文件，VM 上為 2.1.282）：`.git`、`.config/git`、`.vscode`、`.idea`、
  `.husky`、`.cargo`、`.devcontainer`、`.yarn`、`.mvn`、`.claude`（`.claude/worktrees` 除外）；
  另有 `.mcp.json`、`.claude.json`、`.npmrc`、`maven-wrapper.properties`、`gradle-wrapper.properties`、
  shell rc 檔、git hook 設定檔等受保護檔案。完整清單以官方文件為準。
- **把 `Edit(.claude/**)` 之類的規則加進角色 `tools`（`--allowedTools`）沒有用**：官方文件寫明安全檢查在
  allow 規則之前執行，allow 規則不會預先核准受保護路徑。只有 `bypassPermissions` 允許這類寫入，
  但該模式同時讓 `--allowedTools` 不再限制工具，會拿掉縱深防禦的第二層，目前沒有採用。
- Bash 的輸出重導與 `tee` 目標也會套用同一檢查，改用 shell 指令寫入同樣會被拒。
- 實作節點沒有設定下方的受保護路徑改派、或檔案不在改派範圍內時，需求或驗收項目若要求改這些路徑，
  只能由真人在任務分支手動修改並提交，或把該檔移出需求範圍。
  目標專案的慣例：會隨版本或功能變動的資訊（版本號、模組清單）不要寫在 `.claude/rules/` 等受保護路徑，
  改寫在建置檔、`AGENTS.md` 或一般文件，受保護路徑只放穩定的規則並指向那些檔案。

**受保護路徑改派（2026-10-04 起；整節點與分段改派都已在真實任務實測，見下）**：實作節點可設 `protectedPathFallback`
（`{"role": ..., "paths": [...]}`）。2026-10-12 來源改為只從本實作前置 TEST_PLAN 的 `protected_path_changes`
取得明確修改路徑，交集配置允許目錄與原 runtime 受保護路徑後才分段改派；不再掃描需求或報告全文。
唯讀參考、禁止修改與任意路徑提及不是授權。舊計畫沒有清單時不推測改派，需更新配置與計畫或承接新任務。
這修正了因文字提及而額外修改 `.claude` 檔案的問題；此版本尚未經真實任務驗收。
`.claude/settings*`、`hooks`、`commands`、`agents`、`plugins`、`.git`、`.mcp.json`、`.vscode`、`.idea`、`.github` 等寫死禁止，配置無法覆蓋。

**`paths` 格式與範本預設（2026-10-28 起）**：
- 每一項是「明確目錄/**」，或**根目錄的單一檔名**（不含 `/` 與萬用字元，例如 `.npmrc`、`.yarnrc.yml`）。
  根目錄檔案沒有目錄可寫成 `/**`，所以另外開放；子目錄裡的檔案仍以所在目錄表示（例如 `.mvn/**`、`gradle/wrapper/**`）。
- 會被自動執行的檔案不能列入（`ProtectedPathFallback.AUTO_EXECUTED`）：`.envrc`、shell rc（`.bash*`、`.zsh*`、`.profile` 等）、
  `.pre-commit-config.yaml`、`lefthook*`。這些不在禁止清單：計畫列到時照舊判為範圍外、停給真人，不會讓整份計畫無效。
- 內附範本：`maven-library.json` 預設 `.claude/rules/**`、`.claude/skills/**`、`.mvn/**`（Maven Wrapper 升級會改 `.mvn/wrapper/`）；
  `gradle-fullstack.json` 為 `.claude/rules/**`、`.claude/skills/**`、`gradle/wrapper/**`；`node-web.json` 是功能全關的對照組，不設定。
- 配置提醒（`TeamCatalog.warnings`）：寫檔節點的角色是 Claude Code、又沒有 `protectedPathFallback` 時，`--validate-team` 與服務啟動 log
  都會提醒「改這些路徑會停給真人」。只是提醒，不擋配置。

狀態：程式測試通過；根目錄檔案改派與 `.mvn`／`gradle/wrapper` 改派尚未經真實任務驗收。

**分段改派（2026-10-04 起，取代整節點改派）**：同一輪實作分兩段依序執行（`FlowEngine.runDelegatedPart`）：
1. 改派段：改派角色只修改 `paths` 內的檔案，不跑建置測試、不 commit（說明 `ProtectedPathFallback.partNotice`）。
   派工前的受保護路徑告知（`ConfiguredExecutor.protectedPathNotice`）依**實際執行的角色**的 runtime 判斷，改派給 Codex 時不會出現
   「`.claude/` 一律拒寫」的說明。2026-10-05 前它誤用節點原定角色（Claude），改派段因此收到互相矛盾的指示，
   只做唯讀查核就回報 BLOCKED（受保護文件一個也沒改，稽核顯示「改派段未完成」）。
   系統以 `auditPaths` 核對，變更必須**全部**落在 `paths` 內且逐檔列於 `protected_path_changes`（`partViolations`，連 `.agents/`、一般檔案也不行），越界即 `SecurityException`；
   通過後系統代為提交，結果記在 `FlowRun.delegatedParts`（起始 commit、提交後 commit、檔案、摘要）。
   改派段回報未完成（`status` 不是 SUCCESS／PARTIAL、有 `boundary_conflict` 或 `execution_status` 受阻）時，以它的報告當本節點結果，照一般受阻流程處理，不執行第二段。
   2026-10-22 起 `status=PARTIAL` 也算完成，前提是受保護檔案本輪有提交、或本任務先前已提交（`FlowEngine.delegatedFilesChanged`）：
   改派段照指示不建置，如實填 PARTIAL／`verify_passed=false` 不代表沒做完。舊判定只認 SUCCESS，改派段已改好規則仍被當成整個實作的結果，
   主實作段從未執行、流程在分流與實作間空轉。`partNotice` 同時明講 status 只代表這一段、verify_passed 依 `git diff --check` 等檢查判定。
2. 主實作段：原角色收到「受保護檔案已在 commit X 完成、不要修改」的說明（`primaryNotice`），完成其餘變更與建置測試；節點報告以這一段為準。

同一輪續跑（例如額度用完後恢復）時，`delegatedParts` 的起始 commit 等於本輪起始 commit 就略過改派段，checkpoint 只交給 runtime 相符的那一段；
節點新的一輪開始時清掉上一輪的紀錄。改派段若改錯，原角色寫不了那些檔案，由 QA 驗收擋下後返工重跑兩段。
真實實測（2026-10-05）：改派段的 Codex 角色只改 `.claude/rules/` 與 `.claude/skills/` 各一個檔案，核對通過由系統提交；
主實作段的 Claude 角色接著改 `.agents/skills/` 的對應檔案，獨立驗證與 QA 通過。發布另因審查等級被擋，見 [review-gate](review-gate.md)。
改成分段的原因：整個節點交給 Codex 時，Codex 內層沙箱把 `.agents` 掛成唯讀、也啟動不了 Gradle，只有 `build.gradle.kts` 改成。

**整節點改派（舊行為）**：2026-10-04 前已記在 `FlowRun.roleOverrides` 的任務沿用，整個節點由改派角色執行，
系統以 `violations` 核對（原 runtime 寫不了又不在 `paths` 內的路徑才算越界），通過後代為提交。新任務不再寫入 `roleOverrides`。
VM 實測（`CodexSandboxVmTest.workspaceImplementerCanWriteClaudeRulesButNotGit`，不呼叫模型）：實作節點外層政策下
Codex 可寫 `.claude/rules/`、`.claude/skills/`，`.git` 與 `.git/hooks` 不可寫。典型設定為 Codex 角色、paths 為 `.claude/rules/**`、`.claude/skills/**`。

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

**指令紀錄（`commandRuns`，2026-10-18 起）**：介面預設回空清單。Claude Code 讀 `~/.claude/projects/*/<session>.jsonl`
（只取本輪開始後的 Bash 呼叫，`is_error` 與 `Exit code N`），Codex 讀 `logs/<任務>/<角色>.codex.jsonl` 的 `command_execution`；
opencode 尚未實作，回空清單表示「沒有證據」，核對因此不生效、不會誤判。用途是核對實作自陳的品質檢查，
見 [engineering-workflow](engineering-workflow.md#系統獨立驗證)。紀錄格式屬 CLI 內部行為，換 CLI 版本要重驗。
各 CLI 的實際參數與陷阱見 [Codex CLI 參數契約](codex-cli-argument-contract.md)、
[opencode 實測報告](opencode-runtime-report.md)。模型名三家互不通用，換 runtime 時要一併換模型。

---

## Runtime 選擇是設定，不是 agent 的執行期決定

- 配置式模式：團隊 JSON 的 `roles[].runtime`／`model`（見 [配置式團隊](configurable-team.md)）。
- legacy 模式：`AI_TEAM_RUNTIME_<角色>`、`AI_TEAM_MODEL_<角色>`（見 [每個 Agent 指定模型](per-agent-model.md)）。

理由：角色定義要與 runtime 無關；執行期切換會讓成本、額度、稽核難以追蹤；
除錯需要「同一任務換 runtime 重跑」的能力，選擇權必須在外層。
目前**沒有**額度用盡時自動改用另一個 runtime 的 fallback；額度用盡走 [額度暫停與自動續跑](quota-continuation.md)。
