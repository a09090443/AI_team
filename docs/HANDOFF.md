# 交接摘要

> 2026-09-06 更新：以下「2026-09-04 收尾」是歷史快照，並非目前狀態。#1005 已成功發布；已加入 Codex Reviewer、失敗硬閘門、額度續跑、QA 事前規劃。從零建置見 [INSTALL.md](INSTALL.md)，實際驗證狀態見 [rebuild-verification.md](rebuild-verification.md)。不要再次操作下面的旧核准指示。

給新對話用的專案現況。詳細內容見各文件，本文只講「必須知道才能接手」的部分。

---

## 立即狀態（2026-09-04 收尾）

**四個角色全部完成，管線可以從 Slack 的一句需求跑到底。** 已完成七個任務，
最近一輪 #1005 走完 PM → Backend → QA → 核准閘門，總花費 US$2.46。

| 項目 | 狀態 |
|---|---|
| 服務 | 執行中。啟停見「程式碼現況」 |
| 四個角色的 Slack 獨立帳號 | ✅ 已建立、token 已填、已邀請進頻道 |
| `AI_TEAM_GITHUB_TOKEN` | ✅ 已填（**16:22 重啟後才生效**） |
| 模型配置 | PM／Architecture = `opus`，Backend／QA = `sonnet` |
| 工作區 | `task-1003`、`task-1004`（失敗待查）、`task-1005`（等待核准） |

### 馬上要做的一件事

**任務 #1005 停在 `AWAIT_PR_APPROVAL`，等人在 Slack 按核准。**

它先前按過兩次都失敗，原因是服務在 15:26 啟動、token 在 15:56 才填，
執行中的 JVM 讀的是舊環境。16:22 已重啟，token 生效，警告歸零。
**回 Slack 討論串再按一次核准，就會真的推分支並開 PR。**

按下去會發生：把 `feature/ai-team-task-1005`（3 個 commit、6 個檔案、+135/-7）
推到 `github.com/a09090443/zipe-spring-boot-starters`，並開 PR 合併到 `develop`。
這是整條管線唯一不可逆、對外可見的動作，**推送到 GitHub 這一步至今尚未實際成功過**。

---

## 專案目標

以 Slack 為介面，建立一個由 PM / Architecture / Backend / QA 四個 AI 角色組成的軟體團隊，長期維護 `zipe-spring-boot-starters`（GitHub，13 個模組）。

起點是一篇 ChatGPT 對話的架構構想，經技術評估後大幅修正 —— 原文的分層判斷正確，但與 Claude Code CLI 和 Slack API 的實際契約有多處不符。

---

## 環境現況

| 項目 | 值 |
|---|---|
| VM | VirtualBox `AI`、Ubuntu 26.04、8 vCPU / 8 GB |
| **連線** | **`ssh -p 2222 vboxuser@127.0.0.1`**（NAT 埠轉發，固定不變） |
| VM 內部 IP | `10.0.2.15` |
| sudo | **無免密碼 sudo**，所有安裝都在 `~/.local/`，不使用 apt。唯一例外見下 |
| userns | 已以一次 sudo 開啟（`/etc/sysctl.d/99-ai-team-userns.conf`），沙箱的檔案系統隔離靠它 |
| 已裝 | node 22.21.1、JDK 21.0.12.1、Maven 3.9.16、Claude Code 2.1.252、sqlite3 3.53.4 |
| 認證 | **Claude 訂閱帳號**（非 API key）→ 不能用 `--bare`、不能設 `ANTHROPIC_API_KEY` |
| Slack | workspace `AI-team`、bot `zipe-ai-team`、白名單 1 人 |
| 設定 | `~/ai-team/env.sh`（權限 600，token 在裡面，**不要求使用者貼出來**） |

**網路**：原為橋接 Wi-Fi，只有 80 KB/s 且 IP 反覆變動；改成 NAT 後 **9.5 MB/s**。若重建環境，第一件事就是改 NAT。

---

## 已定案的設計決策

1. **Agent = 角色，Runtime = 引擎**。四個角色可共用同一個 CLI，靠 system prompt / 工具 / 權限區分。
2. **Orchestrator 用 Java + Spring Boot**。理由：訂閱帳號下官方 Agent SDK 不是背書路徑（其 Quickstart 要求 API key），換 Python/TS 換不到好處。技術債：日後若改 API key 並需要 in-process hooks，得改寫成 Python/TS。
3. **Slack 用 Socket Mode + `app_mention`**，不用 Agents 容器（其串流與狀態功能僅限 DM）。
4. **四個角色各自是 workspace 裡獨立的 Slack 使用者**（四個唯寫 App，manifest 在 `deploy/agent-manifests/`），交接時互相 tag 且 mention 是真的。但 **mention 只是可見的交接，不是觸發機制** —— 執行順序仍由 orchestrator 驅動，agent 不訂閱任何事件。沒設專屬 token 時退回名稱覆寫，mention 一併退成粗體名稱。佇列與錯誤走中性的系統身分。
5. **閘門策略：全自動，只在推 PR 前停**。（原本選「每階段都可介入」，實際使用後改為全自動。）
6. **安全邊界必須在 runtime 之外**（`ExecutionSandbox`）。若依賴各 CLI 的權限旗標，系統安全性等於最弱的那一個。
7. **角色定義要用中立格式 + 建置時產生各 runtime 的原生格式**。使用者未來可能導入多種 CLI，不能綁死 `.claude/agents/`。
8. **推分支與開 PR 一律需要人工核准** —— 唯一保留的閘門。

---

## Phase 0：CLI 契約已實測

`docs/phase0-report.md`，**13 項通過、0 失敗**。以下皆為實機驗證，可直接採用：

- `--append-system-prompt-file`（附加，非取代）
- `--permission-mode dontAsk` + `--allowedTools` 真的擋得住寫檔（有對照組）
- `--json-schema` 回傳 `structured_output`
- `--resume` 保有前一輪 context
- `--output-format json` 的 stdout 乾淨（stderr 需另外導向檔案）
- `--bare` 在訂閱帳號下確實無法運作

單次 PM 分析成本約 **US$0.03–0.15**。

---

## 程式碼現況

`~/ai-team/orchestrator/`（本機同步在 `D:\agent_team\orchestrator\`），18 個主程式 Java 檔 + 1 個測試（12 項），共 4008 行：

```
AiTeamApplication.java          啟動、設定驗證、去重表清理、啟動恢復、模型配置回報
config/AiTeamProperties.java    env.sh 的環境變數 → 設定，啟動時驗證
runtime/AgentRuntime.java       廠商無關介面（能力宣告 + 降級）
runtime/ClaudeCodeRuntime.java  Claude Code adapter
sandbox/SandboxPolicy.java      單次執行的隔離條件（可寫路徑、唯讀釘選、資源上限）
sandbox/ExecutionSandbox.java   包成受限 systemd user unit + 啟動自檢（含工具鏈）
publish/PullRequestPublisher.java 推分支與呼叫 GitHub API 開 PR
workspace/WorkspaceManager.java 每任務獨立 clone、分支、清理（不是 worktree，見報告）
agent/StageSpec.java            階段定義（PM/ARCHITECTURE/BACKEND/QA 為常數）
agent/StageRunner.java          通用階段執行器
task/Task.java                  狀態機 + 各階段產出
task/StageOutcome.java          單階段產出（sessionId / result / cost / 耗時）
task/TaskOrchestrator.java      管線串接、並行槽、稽核
store/TaskStore.java            SQLite 持久化
store/AuditLog.java             稽核紀錄
slack/SlackListener.java        ack-first 進入點 + 按鈕路由
slack/SlackNotifier.java        每角色一個 MethodsClient、解析 bot user ID、產生真 mention
slack/SlackBlocks.java          訊息渲染
```

外部檔案（不進 jar，改了不用重編譯）：
`~/ai-team/prompts/{pm,architecture,backend,qa}.md`
`~/ai-team/schemas/{task-schema,adr-schema,impl-schema,qa-schema}.json`

提示詞刻意不重複目標 repo 自己的規範。`CLAUDE.md`／`AGENTS.md`／`.claude/skills/`
是權威來源，agent 會自行套用（**Skill 工具不受 `--allowedTools` 限制**，已實測）。

資料庫 `~/ai-team/ai-team.db`：`task` / `stage_result` / `processed_event` / `audit_log`
查詢工具：`~/ai-team/audit.sh [list|<taskId>|cost]`

**啟停**：

```bash
setsid nohup bash ~/ai-team/start.sh > ~/ai-team/orchestrator.log 2>&1 </dev/null &
pkill -f "[a]i-team-orchestrator-0.1.0.jar"   # 中括號是必要的：透過 ssh 執行時，沒有它會殺掉自己
```

判斷是否存活看 **至少有一條 ESTAB 到 :443**，不要只看 log（log 可能是上一次的）。
**連線「數量」不是可靠判準**：實測 Socket Mode 會週期性輪替連線，
數量在 1（穩態）與 2（重連交接中）之間跳動，任務執行中還會多一條對 Anthropic 的。
舊文件寫死「應為 2」是錯的。要更確定就用 `auth.test`（唯讀，不送訊息）。

---

## 能動的（已實測）

ack-first + 佇列（避免 Slack 3 秒重送造成四重執行）、`event_id` 去重（已持久化）、觸發者白名單、頻道過濾、任務狀態持久化與啟動恢復、稽核紀錄、PM Agent、Architecture Agent、PM → Architecture 自動接續、自訂身分發言、互動按鈕、等待時釋放並行槽。

執行沙箱（systemd user unit：檔案系統白名單 + cgroup 資源上限 + 啟動自檢 + 工具鏈自檢）、
沙箱閘門（自檢未過則拒絕執行 Backend／QA）、
工作區管理（每任務獨立 clone、分支、啟動時清理）、
Backend Agent（改程式碼、寫測試、跑品質 Gate、commit）、
QA Agent（逐條對照驗收條件、補回歸測試、findings）、
可見的交接（每個角色以自己的身分 tag 下一棒，最後 tag 回提出需求的人）、
各角色可獨立指定模型（`AI_TEAM_MODEL_<角色>`，啟動時回報生效值）、
已 commit 的工作在階段失敗時保留（不因回報格式失敗而丟掉整輪）、
QA 越權稽核（核對 commit 路徑，沙箱擋不住的 git 索引繞道由此攔下）、
PR 閘門（推分支 + 開 PR，唯一需要人按下去的動作）、
權限拒絕稽核（agent 想用但被擋的工具會記進 audit_log）。

兩個自動停止點（非閘門，不需使用者操作）：PM 判定 `QUESTION` 或不需下游角色 → 結束；PM 自陳 `confidence: LOW` → 停下等人。

### 目標 repo 的 rules 與 skills 確實被使用（有執行紀錄佐證）

提示詞刻意寫得薄，只指向 repo 自己的規範。從 #1005 的 session 紀錄可以驗證這是有效的：

| 項目 | 實際情形 |
|---|---|
| `CLAUDE.md` | **自動載入**。實測不給任何工具、只憑 context，agent 能完整列出它指向的 11 個 rules 檔案 |
| `.claude/rules/` | 按需讀取。#1005 讀了 `doc-sync.md` 11 次、`base-starter.md` 6 次 —— 只讀跟該任務相關的 |
| `.claude/skills/` | 實際呼叫。Backend 用了 `git-commit-message` 與 `local-quality-gate`，QA 用了 `git-commit-message` |
| `Skill` 工具 | **不受 `--allowedTools` 限制**（實測 `permission_denials` 為空），所以工具白名單再緊也用得到 repo 的 skill |
| `AGENTS.md` | **不會自動載入，實測讀取次數 0** —— 見缺口清單 |

這解釋了為什麼 commit 訊息會自動符合 `<類型>(<範圍>): <主旨>` 格式：
那個規範一個字都沒寫進提示詞，是 `git-commit-message` skill 帶進去的。

但要誠實說：目前是**提示詞點名**（`backend.md` 明寫「commit 前必須執行 local-quality-gate skill」），
還沒驗證過 agent 會自己找到沒被點名的 skill。

---

## 尚未實作 / 已知缺口

| 缺口 | 嚴重度 |
|---|---|
| **推送到 GitHub 尚未實際成功過** —— token 已填、機制已就緒，但沒真的推出去過。#1005 就是那一次 | 🔴 |
| **`AGENTS.md` 沒有進到 agent 的視野** —— 實測讀取次數 0，也不會被自動載入。裡面的「相依方向 `iam → logon` 單向」只寫在這份檔案，等於現在是漏的 | 🟠 |
| **沙箱的網路隔離** —— 網路完全開放，agent 可對外連任何位址 | 🟠 |
| **`llms.txt` / `llms-full.txt` 無法由 agent 產生** —— repo 規定進版控、不可手改、須 `npm run build` 後 commit，但工作區是全新 clone、無 `node_modules`，跑 `npm ci` 代價太高。**PR 合併前需人工補跑** | 🟠 |
| **沒有「重試推送」的途徑** —— 推送失敗後只能回去按核准按鈕。實測使用者在 Slack 打「請重新推送」，PM 正確判定為詢問而結束（#1006、#1007，各花 US$0.2） | 🟠 |
| `sync-starters-docs` skill 的連鎖呼叫斷掉 —— `local-quality-gate` 內文寫「REQUIRED SUB-SKILL」，agent 讀到了（13 次）但沒實際呼叫 | 🟡 |
| 「要求重做」未收集實際回饋，只送罐頭字串 | 🟡 |
| `roles/*.yaml` 中立來源與多 runtime 轉譯 | 🟡 |
| Knowledge Graph MCP —— **repo 目前完全沒有 MCP 設定**，`mcp_servers` 為空 | 🟡 |
| 第二個 runtime adapter | 🟡 |

### PM 的判斷不穩定

同一句需求跑三次，PM 的路徑判斷三種結果：#1003 判定需要 Architecture、#1004 與 #1005 判定不需要。
**把 PM 換成 opus 也沒有改變這個判斷**（#1005 就是 opus 跑的）。

若希望「新增 public API 一律過架構」，要改的是 `pm.md` 的判準，不是換模型。
這件事會影響每一輪測試的可比性 —— 目前很難分辨「改動有沒有效」還是「這次剛好走了另一條路」。

---

## 接手時要注意的事

### 操作環境

- **不要求使用者貼 token**。需要驗證時，在 VM 上跑檢查指令並只印前 8 碼或 bot 名稱。
- **不能用密碼 SSH**，金鑰已設好。
- **改了 `~/ai-team/env.sh` 一定要重啟服務才生效** —— 實測踩過：token 填了但沒重啟，
  按核准仍報「未設定」，看起來像 token 填錯。
- **服務讀的是 VM 上的 `~/ai-team/env.sh`**，不是本機的 `deploy/env.sh.template`（那是範本）。
- **`pkill -f "ai-team-orchestrator-0.1.0.jar"` 透過 ssh 執行時會殺掉自己**
  （遠端指令字串本身含該字樣），要寫成 `pkill -f "[a]i-team-orchestrator-0.1.0.jar"`。
- **不要在任務執行中 `mvn clean package`** —— 會刪掉 target/，執行中的 JVM 若需載入尚未載入的類別會炸。
- **VM 失聯時第一個動作是截圖**：`VBoxManage controlvm "AI" screenshotpng /tmp/vm.png`。

### 查詢與診斷

- **SQLite 用單引號**：`where task_id='1005'`。雙引號會被當成識別字，
  報 `no such column: "1005"`。稽核表的時間欄位叫 `at`，不是 `ts`。
- **agent 的完整執行紀錄在 `~/.claude/projects/<編碼過的工作區路徑>/<session_id>.jsonl`**。
  session_id 在 `audit_log` 裡。要查「它到底呼叫了哪些 skill、讀了哪些 rules」就看這個。
- **失敗時 stdout 會落檔到 `<logs>/<task>/<role>.stdout.json`**。
  `subtype` 欄位才是真正的原因（`error_max_turns` / `error_max_structured_output_retries`）。

### 已知的靜默失敗陷阱

- **systemd user unit 不繼承呼叫端環境變數**：沙箱內沒有 `PATH`／`JAVA_HOME` 就找不到 `mvn`，
  Backend 會每次建置失敗且看起來像環境壞掉。已用 `--setenv` 白名單傳遞；
  **不可整包傳**，orchestrator 環境裡有 Slack token。
- **systemd 對沙箱設定是靜默失敗的**：userns 被擋時 `ProtectSystem`／`ProtectHome` 會被無聲略過，
  不可信任設定檔，只能信 `ExecutionSandbox.selfTest()` 的結果。
- **不存在的路徑會讓 unit 起不來且毫無輸出**：`ReadOnlyPaths`／`ReadWritePaths` 一律加 `-` 前綴。
- **回合數用盡與真正的錯誤在結束碼上無法區分**（都是 1），要看 `subtype`。
- **`Bash(ls *)` 這類 pattern 沒問題，但工作目錄外的路徑一律被拒絕** ——
  `dontAsk` 模式下 Claude Code 另有一層以專案目錄為界的路徑限制，與我們的沙箱獨立。
- **沙箱擋得住檔案系統，擋不住 git 索引**：`git checkout <舊commit> -- src/main` 寫不了工作樹
  但 index 會被改掉。由 `enforceQaWriteScope` 在沙箱外核對 commit 路徑補上。

### 同步與建置

- **同步程式碼到 VM 時 `tar` 不會刪除已移除的檔案**，會造成編譯失敗，要另外處理刪除。
- **`npm_config_jobs` 不是合法 npm 選項**，設了會弄壞所有 npm 指令。
  限制編譯平行度只用 `MAKEFLAGS=-j1` 與 `JOBS=1`。
- **npm 安裝原生模組會開滿核心編譯**，曾把 VM 壓到八核 soft lockup 需硬重置。
- 用 heredoc 寫 Java 檔時，**`\n` 與 `\` 在多層轉義下常被吃掉**。
  含跳脫字元的字串用 `head`/`tail` 分段 + 引號 heredoc 寫入，不要用 `perl -0pi -e`。

### Slack

- manifest 的 `long_description` 必須 **> 173 字元**。
- App-Level Token 的 scope 必須是 **`connections:write`**，選錯只能重產。
- 四個角色 App 是**唯寫**的（只有 `chat:write`），**安裝到 workspace ≠ 加入頻道**，
  四個都要 `/invite`，否則會得到 `not_in_channel`。
- **Slack 連線數不是可靠的健康判準** —— Socket Mode 會週期性輪替，數量在 1（穩態）與 2
  （重連交接中）之間跳動。看「至少一條 ESTAB 到 :443」。

## 成本參考（實測）

| 階段 | 模型 | 耗時 | 成本 |
|---|---|---|---|
| PM | opus | 67 秒 | US$0.578 |
| PM | sonnet | 53–67 秒 | US$0.15–0.20 |
| Architecture | opus | 177 秒 | US$0.479 |
| Backend | sonnet | 220–302 秒 | US$0.58–1.10 |
| QA | sonnet | 240–414 秒 | US$0.60–0.78 |

- 完整任務（PM → Backend → QA）：**US$2.46**（#1005 實測，PM 用 opus）
- 詢問類任務（PM 判定 QUESTION 即結束）：US$0.15–0.24
- `mvn -B verify` 全 reactor：暖快取 118 秒、冷快取 217 秒；單模組 `-pl X -am` 16 秒

換 opus 對 PM 的成本影響約 2.9 倍，但**沒有改變它的路徑判斷**。

---

## 建議的下一步（依優先序）

1. **把 #1005 推出去** —— 按核准，驗證 PR 流程真的能跑完。這是唯一還沒實際成功過的一環
2. **把 `AGENTS.md` 附進 prompt** —— 由 `StageRunner` 讀工作目錄下的 `AGENTS.md`（存在才讀）
   附在上游脈絡裡。靠提示詞要求 agent 自己去讀已實測無效
3. **穩定 PM 的路徑判斷** —— 在 `pm.md` 把「什麼情況需要 Architecture」寫成硬判準
   （例如「新增任何 public API 一律需要」）。不做這件事，後續每一輪測試都難以比較
4. **補「重試推送」的途徑** —— 目前推送失敗只能回頭按核准按鈕，使用者在 Slack 打字沒有用
5. 網路隔離、`roles/*.yaml` 中立來源、第二個 runtime adapter —— 都還沒動

---

## 文件索引

| 文件 | 內容 |
|---|---|
| `INSTALL.md` | **從零安裝的唯一入口**，十一個步驟 |
| `vm-setup-manual.md` | VM 環境，T1–T10 疑難排解（T10 是網路，最重要） |
| `build-and-run.md` | Orchestrator 建置運維，B-T1–B-T7 |
| `architecture-review.md` | 原始架構評估：4 Blocker、6 重要、4 建議 |
| `corrected-architecture.md` | 修正後的架構圖與職責邊界 |
| `multi-runtime.md` | 多 runtime 設計、安全邊界外移 |
| `interaction-model.md` | Slack 互動模型（閘門策略已改為全自動） |
| `slack-options.md` | Slack 方案評估（自建 vs 官方、Socket Mode、付費方案） |
| `phase0-report.md` | CLI 契約實測報告 |
| `sandbox-report.md` | 執行沙箱實測報告：可用機制、AppArmor 阻礙、三個靜默失敗陷阱 |
| `workspace-report.md` | 工作區實測報告：為何 worktree 不可行、改用獨立 clone 的決定 |
| `backend-report.md` | **Backend Agent 端對端驗證**：實際產出、獨立驗證、揪出的三個問題、成本數字 |
| `qa-report.md` | **QA Agent 與 PR 閘門驗證**：抓出上游說謊、沙箱擋不住 git 索引的發現、PR 發布設計 |
| `slack-agent-apps.md` | 建立四個角色 Slack App 的逐步教學與疑難排解 |
| `per-agent-model.md` | 每個 agent 指定模型：設定方式、實測的別名對應、選擇建議 |
| `backend-qa-decisions.md` | **四項角色決策**（越界、測試責任、失敗、QA 權限），已全部拍板並實作，保留推理過程 |
| `../deploy/` | 主 Slack manifest、env 範本、phase0 驗證腳本 |
| `../deploy/agent-manifests/` | 四個角色 App 的 manifest（唯寫，只有 `chat:write`） |
| `reference/` | **歷史示意碼**，架構評估階段的產物，非正式實作。`agents/*.md` 已確定不採用（改用 `orchestrator/prompts/`），worktree 方案也已被推翻 |
