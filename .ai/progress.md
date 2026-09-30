# 工作進度（Codex 與 Claude Code 共用）

> 由新到舊追加。每則一行到數行：日期、由誰（Codex／Claude Code）、做了什麼、驗證方式。

## 2026-09-30 — Claude Code（Claude Code 受保護路徑：查因與事先告知）

- 查 #1021：implementation 改 `.claude/rules/project-overview.md` 被拒（audit 1000），同批其他 Edit 成功。
  根因是 `ClaudeCodeRuntime` 固定 `--permission-mode dontAsk`，Claude Code 對受保護路徑（`.claude`、`.git`、`.mvn` 等）
  在 dontAsk 下一律拒絕，allow 規則開放不了（官方 permission-modes 文件）；不是沙箱或檔案權限。
  triage 提出的「開放 `.claude/rules/**` 權限後重派」依官方文件無效。
- 使用者核准計畫後實作（未部署）：`AgentRuntime.protectedWritePaths()`／`isProtectedWrite()`，
  `ClaudeCodeRuntime.PROTECTED_WRITE_PATHS` 與 `annotateDenials()`（被拒時補上真正原因），
  `ConfiguredExecutor.protectedPathNotice()`（寫檔節點：不要嘗試、不是 boundary_conflict；唯讀節點：不列為驗收、不提議開放權限）。
  另把 boundary_conflict 說明的「保護路徑」改為「沙箱／配置保護路徑」。計畫原本要改 `Diagnostics.toolFailures` 簽章，
  改為在 runtime 補 `reason`，所有讀者自動生效。`NodeInput` 指紋不含 context，不影響防循環計數。
- 驗證：Windows 本機 Maven 458 項，只有已知的 4 項 Windows 專屬失敗；新增 `ClaudeCodeProtectedPathTest` 與
  `ConfiguredExecutorTest` 3 項都通過。check-source、docs-site build 通過。**程式測試通過，真實端對端未實測。**
- 發現的缺漏：文件未記載此限制。已修正的文件：`docs-site/docs/multi-runtime.md`（新增受保護路徑段落與系統處理方式）、
  `docs-site/catalog.mjs`（multi-runtime 的 code 加入 ClaudeCodeRuntime）。
  已核對、無需修改：`configurable-team.md`、`agent/system-map.md`、`agent/project-tree.md`（新測試檔在既有 runtime/ 目錄下）。

## 2026-09-27 — Claude Code（移除沒有用的文件內容）

- 使用者要求移除沒有用的文件內容，確認範圍為：刪除歷史文件、精簡 .ai 交接檔、清現行文件的過時段落、刪除舊部署紀錄。
- 刪除 docs-site/docs 的 12 份歷史文件（HANDOFF、INSTALL-20260904-history、vm-setup-manual、build-and-run、architecture-review、
  slack-options、phase0-report、sandbox-report、workspace-report、backend-report、qa-report、codex-runtime-report）、reference/ 目錄、
  deploy-2026-09-09-approval 紀錄，以及內容已被 system-map 取代的 corrected-architecture。仍有用的疑難排解搬進 INSTALL §10 與 slack-agent-apps。
- 刪除 deploy/ 下 34 支一次性部署腳本與 #1010／#1014 專用檔（Java 檢查程式、jq、補發訊息腳本、phase0-verify.sh）；
  保留 deploy-orchestrator.sh、deploy-jar-safe.sh、engineering-v2-team.env。deploy/README.md 改寫為目錄說明。
- 程式註解、env 範本、pom、prompts/architecture.md、task-schema.json 中指向已刪文件的引用改為直接敘述（只改註解與說明文字）。
- 重寫或精簡現行文件：multi-runtime（對照 AgentRuntime 能力宣告與 ExecutionSandbox 實作）、interaction-model（五角色、只在真人閘門停下）、
  slack-agent-apps（併入主 App 設定與疑難排解）、direct-agent-requests（去除重複步驟）、quota-continuation（去除重複段落）、
  codex-cli-argument-contract、codex-nested-sandbox、local-test-capabilities、slack-progress、pm-triage、plan-approval-buttons、
  qa-scenario-workflow、configurable-team、engineering-workflow、cross-project-execution、failure-reporting、per-agent-model、rebuild-verification。
  移除部署流水帳（PID、SHA、備份目錄、舊測試數字），行為描述保留。紀錄與決策文件只修引用，不改寫歷史。
- 發現的落差並已修正：INSTALL §6.1 與 TeamMigration 註解仍寫「模型變更一律拒絕」，程式其實允許同一 runtime 內換模型；
  slack-agent-apps 仍寫角色 App 不需要 App-Level Token；slack-progress 仍寫 UTC 時間且未提中斷按鈕；
  interaction-model 仍描述四角色、唯寫 App 與每階段閘門；README 架構表仍寫專用 user／網路白名單／worktree；per-agent-model 漏 Code Review。
- sidebars.js、llms-txt.mjs 改依文件 status 判斷歷史分類；agent/index.md 閱讀規則改為「現行文件只寫現況」。
- .ai/context.md 改寫為只含當前狀態；本檔 09-25 以前的條目壓縮為摘要。decisions.md 未動。
- 驗證：docs-site npm run build 通過（27 份文件、目錄樹 215 項一致）；check-source 通過；本機 Maven 451 項中 4 項失敗、11 略過，
  失敗皆為 Windows 環境（/bin/sh 不存在 2、symlink 權限 1、git CRLF 警告混入輸出 1），與本次無關。已 commit（d6ac1e4）並 push 至 origin/main。

## 2026-09-27 — Claude Code（AI 工具設定單一來源：規則、skills、MCP）

- `CLAUDE.md` 縮為只有 `@AGENTS.md`；原 Claude 專屬內容（開場讀 .ai 與文件、收尾、plan mode、`.ai/` 非執行設定、
  下游 prompt 位置）改寫為通用規則併入 AGENTS.md。AGENTS.md 新增「AI 工具設定（單一來源）」。
- 新增 `.agents/`：`mcp.json`（MCP 單一來源，目前無共用 server）、`skills/docs-sync/SKILL.md`（文件優先核對流程）、
  `sync-agent-config.mjs`（產生 `.mcp.json`、`.cursor/mcp.json`、`.vscode/mcp.json`、`.gemini/settings.json`、`opencode.json`，
  鏡像 skills 到 `.claude/skills/`，只替換本機 `.codex/config.toml` 的標記區塊；`--check` 檢查一致性、CLAUDE.md 只含匯入、
  VS Code `chat.useAgentsMdFile`）。`.vscode/settings.json` 開啟 `chat.useAgentsMdFile`；`.gitignore` 加 `.claude/settings.local.json`。
- 各工具讀取位置依官方文件查證（Codex skills `.agents/skills`、Codex 專案 MCP 僅受信任專案、Gemini `context.fileName`、
  VS Code `chat.useAgentsMdFile`、Cursor／opencode 同時讀 `.agents/skills` 與 `.claude/skills`、Claude Code 只讀 `.claude/skills`）。
  僅「Claude Code 與 Codex 讀規則」為本專案實際使用，其餘在 `ai-tools.md` 標為「文件記載」。
- 暫存目錄測試：stdio／http 範例轉換五種格式正確、Codex 區塊保留既有 `dbx` 且重跑冪等、同名衝突拒寫；
  負面測試（手加 `.claude/skills`、CLAUDE.md 多內容）皆使 `--check` 失敗。本機 `.codex/config.toml` 未被修改。
- 文件：新增 `docs-site/agent/ai-tools.md`；更新 project-tree（新增頂層項目，檢查範圍加入 `.agents/`、`.claude/`）、
  contributing、index、docs-site README、根 README。docs-site prebuild 加入 `--check`。
- 驗證：`npm run build` 通過（45 份文件、目錄樹 224 項、AI 工具設定一致）；check-source 通過。未 commit。

## 2026-09-27 — Claude Code（專案目錄樹與「文件優先」規則）

- 新增 `docs-site/agent/project-tree.md`：repo 全目錄樹（每個主程式類別、teams／prompts／schemas、deploy、docs-site 檔案
  附一句用途，類別說明取自各類別 Javadoc）、「我要找……」對照表、不在版控的本機產物。
- `docs-site/scripts/check-catalog.mjs` 擴充目錄樹檢查（prebuild 執行）：樹中路徑必須存在（支援 `*`），
  `orchestrator/src/main/`、`teams/`、`prompts/`、`schemas/`、`deploy/`、`docs-site/`（docs/ 除外）每個受版控或新增未忽略檔案
  必須列在樹中，頂層項目也須列出。反向測試：新增未列檔案、樹中寫不存在路徑，兩者皆使檢查失敗，已還原。
- AGENTS.md 將「功能修改必須同步文件」擴充為「文件優先（強制）」：所有工作先讀文件再讀程式；文件查不到但程式存在要補寫；
  功能與文件有落差以程式實際行為修正文件，若判斷是程式錯誤則先回報使用者、文件標明已知問題；任何功能異動都要核對文件。
  同步更新 CLAUDE.md 開場與收尾、`docs-site/agent/contributing.md`、`index.md` 路由表、docs-site README；
  AGENTS.md／README 連結文字 `docs/…` 改為 `docs-site/docs/…`。
- 驗證：`npm run build` 通過（45 份文件、目錄樹 206 項一致）。未改程式行為，未跑 Maven。未 commit。

## 2026-09-27 — Claude Code（docs/ 移入 docs-site/docs/，刪除根目錄 docs/）

- 使用者要求整理完即刪除根目錄 `docs/`。原本 docs-site 只是掛載 `../docs`，內容並未在 docs-site 內，
  因此改以逐檔 `git mv` 將 49 個檔案移到 `docs-site/docs/`（保留歷史；整個目錄 mv 被 Windows 鎖住），根目錄 `docs/` 已不存在。
- 已修正的文件與引用：AGENTS.md、根 README（連結、目錄樹）、CLAUDE.md 未涉及路徑；docs-site 的 config（`path: 'docs'`）、
  catalog 檢查、llms 外掛、`/catalog` 頁、README、agent 入口頁；`docs-site/docs/data-model.md` 對 schema.sql 的相對連結；
  `docs-site/docs/INSTALL.md` 來源打包指令由 `docs` 改為 `docs-site/docs docs-site/agent`（否則 tar 會失敗）；
  deploy/README.md、env.sh.template、pom.xml、prompts/architecture.md、impl-schema.json、schema.sql、
  12 個 Java 主程式／測試的註解路徑。目標專案的 `doc-site`、`qa-docs`、`docs/build/**` 等路徑未動。
  `.ai/` 與歷史文件（HANDOFF 等）中的舊路徑屬歷史紀錄，未改寫。
- 驗證：docs-site `npm run build` 通過（45 份登錄、無斷鏈）；check-source 通過；本機 Maven（D:\tools\apache-maven-3.9.5、
  `-o -Dmaven.repo.local=.build-m2`）451 項中 4 項失敗、11 略過，失敗皆為 Windows 環境（/bin/sh 不存在 2、symlink 權限 1、
  git CRLF 警告混入輸出 1），與本次僅改註解無關；Linux 上未重跑。未 commit。

## 2026-09-27 — Claude Code（文件網站改為以 AI agent 為主要讀者）

- 新增 `docs-site/agent/`（第二個 docs 實例，路由 `/agent/`）：快速入口與依任務找文件、系統全貌（分層、流程節點、
  任務狀態、程式碼地圖）、操作與指令（Slack 指令與按鈕、維運／開發指令）、設定參考（env 與團隊 JSON）、修改規則。
  指令字串、按鈕文字與 JSON 欄位對照 `SlackListener`、`SlackBlocks`、`FlowEngine`、`spring-starters.json` 與 env 範本核對。
- 新增 `docs-site/catalog.mjs` 登錄 docs/ 全部 45 份文件的分類、狀態（現行／紀錄／決策／歷史）、用途、何時讀與相關程式；
  sidebar、`/catalog` 頁面與 `llms.txt` 都由它產生。`scripts/check-catalog.mjs` 於 prebuild 檢查登錄與 docs/ 一致、
  相關程式路徑存在。`plugins/llms-txt.mjs` 在 build 產生 `llms.txt`、`llms-full.txt` 與每份文件的 `.md` 原文。
- `docs/` 未搬移、未修改（被 AGENTS.md、Java 註解、prompts、部署腳本以路徑引用）。首頁入口改指向 AI Agent 入口與文件目錄；
  AGENTS.md、根 README 加上指引。`npm run build` 通過，無斷鏈或斷錨點。未 commit。

## 2026-09-27 — Codex（建立 Docusaurus 文件網站）

- 新增 `docs-site/` Docusaurus Classic 網站，首頁提供建置、架構、工程流程、QA、團隊配置與驗證入口；直接掛載根目錄 `docs/`，未複製或修改原文件。
- 根 README 增加網站啟動方式；`npm run build` 通過。npm install 回報 21 筆弱點（20 moderate、1 high），未執行自動升級。未設定部署目標。

## 2026-09-26 — Claude Code（PR 驗收紀錄改 Markdown 條列）

- PR 內文原本直接貼 `flow.receipts`（Map<String,JsonNode>）與 Receipt record 的 toString，讀者看到整坨 JSON。
  新增 `PrReport`：依流程節點順序，每節點一個 `<details>`（標題附判定；驗收／審查預設展開），
  欄位轉中文標籤的巢狀條列、編號＋狀態當項目標題、多行文字接續縮排；獨立驗證列 commit、台北時間與各指令結果，
  不再外露 VM 紀錄路徑。`FlowEngine.prBody` 以 `##` 分段，審查建議改 `-` 條列（Slack 仍用「・」）。
- 長度：單節點 12,000 字、驗收紀錄總計 40,000 字，於行邊界截斷。#1019 真實資料未設上限時
  渲染超過 GitHub 65,536 字元上限的風險存在；加上限後渲染為 37,186 字。
- 隔離建置 `pr-markdown-build.haUShN`：check-source 通過、451 項 0 失敗、10 opt-in 略過；
  JAR `340121cbaab206a9ccd417816e4b47567e066ce057d580682b6d4008b881e583`。使用者同意後 22:42 台北時間部署（PID 450271，備份 `orchestrator-backup.0MXfNx`），
  0 ERROR、任務資料前後一致。實際 PR 呈現待下次發 PR 時確認。未 commit。

## 2026-09-26 — Claude Code（顯示時間改台北時間）

- 新增 `TaipeiTime`（分／秒兩種格式），`QuotaMessage.taipei` 改委派；階段心跳由
  「狀態更新時間（UTC）：ISO」改為「狀態更新時間：yyyy-MM-dd HH:mm:ss（台北時間）」；
  console log 時戳改 `Asia/Taipei`。DB 仍存 UTC。
- 驗證：本機 JDK 單檔驗證 `TaipeiTime` 跨日換算正確；新增 `TaipeiTimeTest`。
- 隔離建置 `taipei-time-build.WMvDAW`：check-source 通過；完整 package 449 項 0 失敗、10 opt-in 略過。
- 22:29 台北時間以 deploy-jar-safe.sh 部署（PID 443620，JAR `09b04b6219372357ea77ef9576f586549d59d4bf7156669054cde0f6f049a04a`，
  備份 `orchestrator-backup.RyHk3A`），0 ERROR、任務資料前後一致；log 時戳已為台北時間。
  Slack 心跳訊息尚待下次任務執行時實際確認。未 push。

## 2026-09-26 — Claude Code（系統例外白話說明、審查收斂、隨時改用新任務繼續）

2026-09-26 Claude Code：系統例外改由 agent 白話說明（已建置，待 #1019 空檔部署）。
- 新增 ErrorExplainer 與設定 AI_TEAM_EXPLAINER_MODEL（haiku）／AI_TEAM_EXPLAINER_ENABLED；FlowEngine 例外停止、發布失敗、
  操作時系統錯誤、角色對話失敗改走白話說明；步驟名稱改中文；額度停止只移除技術內容。
- VM 以 #1017 真實 error_max_turns 紀錄實測 haiku 兩輪：第一輪洩漏路徑、英文術語、自行補充選項效果（US$0.049）；
  調整規則並提供選項說明後第二輪符合（US$0.028）。
- #1019 已依使用者核准移除 REQ-016（新基準 af0fdb2f…），計畫自動核准、實作、獨立驗證通過；9/25 23:12、23:18 兩次因 Codex 串流被切斷
  停給人（手動續跑成功），已將該訊號列為系統層失敗自動重試。
- 隔離建置 `explainer-final2-build.uvX6tc`：445 項 0 失敗、10 opt-in 略過；check-source 通過；JAR `57f3a6f36d77ee357de400ceffe4bacffa9a05ba20e2a51c496cc75c97490598`。
- 部署時 #1019 正在執行（implementation），deploy-jar-safe.sh 依設計拒絕部署；待任務空檔再部署。

2026-09-26 Claude Code：讓實作／驗收／審查收斂（已建置，待 #1019 空檔部署）。
- 分析 #1019 06:00～12:16 八輪循環（見 decisions）。改動：review.md 收斂規則、verify.md 與 ConfiguredExecutor 不再要求 QA commit、
  intake.md／plan.md 控制規模、FlowEngine 代為提交 QA 測試（commitVerificationChanges）與審查建議段落（reviewSuggestions）。
- 隔離建置 `converge-build.Y4iBji`：447 項 0 失敗、10 opt-in 略過；check-source 通過；JAR `45bbbf9c3467256eb6452f9e9c2ec46a159a5e27cd9f4bc4300646f17829b806`。
- 部署時 #1019 正在驗收（工作區有 QA 未提交的 MailServiceFailoverTest.java），背景等空檔後先替換 teams 提示再以 deploy-jar-safe.sh 部署。

2026-09-26 Claude Code：收斂改動已部署；另開放隨時改用新任務繼續（已部署）。
- 13:03 背景自動部署收斂改動（JAR `45bbbf9c…`，teams 規則備份 `teams-backup.beBI2A`），0 ERROR、資料一致。
- #1019 13:01 停下：審查第 9 次退回（SC-018 測試資料 2 組 vs 計畫 3 組），自動處理 8/8；因舊快照仍用舊審查規則。
- 使用者同意：承接不再限於次數用完；停止訊息一律提供「改用新任務繼續」。隔離建置 `successor-build.idexn9` 448 項 0 失敗；
  部署 13:09（PID 421644，JAR `281ca8060e389d82064e884fbf2f79f3c9f03b267db6262c31a6d0bee6e911d4`，備份 `orchestrator-backup.5fto7Y`），0 ERROR、資料一致。
- 下一步：使用者在 #1019 送「補充 #1019 狀態」後按「改用新任務繼續」。

## 2026-09-07 ～ 09-25 摘要（2026-09-27 壓縮）

> 以下由原本逐次流水帳壓縮而成，只留「做了什麼、學到什麼、任務走向」。
> 各次的建置目錄、JAR SHA256、PID、備份目錄與測試數字請查 git 歷史中的 `.ai/progress.md`
>（2026-09-27 之前的版本）。行為細節以 `docs-site/docs/` 現行文件為準。

### 2026-09-25（Claude Code）
- 升級 VM CLI：Claude Code 2.1.252→2.1.282、Codex 0.153.4→0.156.1，重驗 Codex 沙箱／預檢 VM 測試與 Claude 旗標；builder 改 opus。
- 新增「中斷這一輪」（按鈕或 `中斷 #id`）；「中止任務」改為一併停掉程序；系統驗證 unit 改名 `ai-team-check-<id>-*`。
- 配置更新允許同一 runtime 內換模型（#1017 唯一差異為 builder sonnet→opus）。
- 延長自動交接改以已用事件數為基準，按一次即生效；修正配置更新後延長按鈕不出現（授權碼改用 pendingEvidence）。
  教訓：唯讀檢查不可自行塞假授權碼，要用系統實際產生的值驗證。
- Claude 角色提示加入允許的替代做法（分次重跑、長輸出以 Read／Grep 讀存檔、只操作工作目錄）。
- 使用者看得懂的訊息（`Diagnostics.forUser`）與「保存未提交的修改並承接」。一次部署因 `@Autowired` 被常數隔開導致啟動失敗，
  2.5 分鐘內以備份回復；新增 `SpringWiringTest` 與 `deploy/deploy-jar-safe.sh`。
- #1019（承接 #1017）卡在計畫：intake 寫入流程需求、中斷後半成品被採用。修正為中斷結果一律不採用、負責人可直接核准需求變更、
  計畫停下時提供「需求要改」、intake 不寫流程需求。

### 2026-09-17 ～ 09-19（Claude Code）
- 「承接成新任務」與跨專案通用化；首次使用通用部署腳本 `deploy/deploy-orchestrator.sh`。
- #1016（承接 #1014）：補 qa-docs 操作契約、限制選項必須可由負責人完成、卡上限時提供配置更新→重新執行。
- 提示改由 stdin 傳入（`Argument list too long`）；session 改在行程啟動後才回報，續跑遇 session 不存在改開新對話。
- ToolTrust 只在被拒工具完全不在角色配置時作廢；提示依 runtime 列出已授予的固定操作。
- 自主化：節點上限改看相同輸入的連續重跑（`NodeInput` 指紋）、系統性 runtime 失敗自動重試 2 次、
  `autoApprovePlanWhenValid`、獨立驗證失敗自動交回實作（`CheckFailed`）、缺前置結果時自動退回重做。

### 2026-09-15 ～ 09-16
- Codex：自動交接計數改為獨立事件並惰性正規化舊 key；新增一次性「核准延長自動交接」。
- Claude Code：受阻發現連續性（`FlowContext` 淘汰最舊段落、`flow.blockedFindings`、`ToolTrust`），ADR 見
  `adr-blocked-finding-continuity.md`；白話化回報與停止選項按鈕（`HumanOptions`／`HumanGate`）；`human_decision` 回報契約；
  「核准恢復前置契約」；Slack 按鈕 action_id 重複被 invalid_blocks 拒收的修正；次數用盡時改為說明而非給無效按鈕。
  教訓：部署腳本不可把會合法變動的值（任務狀態、額度）當前置斷言；`service.sh` 會覆寫 log，ERROR 檢查要看整份。
- #1014 走到設計上的硬上限（transitions 40／24），成果留在 `feature/ai-team-task-1014`（fa96f9f，未推送），改以承接新任務繼續。

### 2026-09-13 ～ 09-14（Codex）
- 修正 `codex exec resume` 選項位置（resume 退回 `:read-only` 的根因）；角色回覆預設簡要。
- 新增「核准人工修復重驗」；規畫覆蓋閘門（acceptance_items／acceptance_ids／counterexample）。
- Codex Structured Outputs 不支援 `uniqueItems`：只在送出副本移除；pre-agent 契約錯誤還原次數。
- 受阻責任自動交接（ADR `adr-automatic-blocker-routing.md`）、QA 未提交測試由系統核對後交實作節點提交。
- 依使用者授權於 #1014 工作區修正 reviewer 指出的缺口；INSTALL 文件缺口修正（teams 打包、channel 必填、工具鏈 checksum）。

### 2026-09-11（Claude Code）
- 新增資料模型文件（9 張表、Task.State、四張 JSON 快照表逐欄語意）並修正 schema.sql 被切開的註解。
  記下三件只有翻程式才知道的事：`task.pm_session_id`／`pm_result` 為遺留欄位、`stage_attempt` 只寫不讀、
  `Checkpoint.upstream`／`qaHead` 在 legacy 與 v2 存的東西不同。

### 2026-09-10（Claude Code）
- 新增 opencode 為第三個 runtime（1.18.19 實測旗標；VM 沙箱端對端未驗證）。
- 額度訊息呈現與 Codex 無時區解禁時間的邊界排程（`QuotaMessage`、`farBeyondBackoff`）。
- 階段回報摘要化（`AI_TEAM_REPORT_DETAIL`、「檢視完整報告」）。
- #1014 誤按中止後由使用者本人以 SQL 還原；INSTALL 對齊 9/7–9/9 的變更。

### 2026-09-09（Codex、Claude Code）
- 跨專案執行契約（`project.operations`、`OperationPreflight`、`PlanApproval`、`TeamMigration`）。
- 測試計畫核准按鈕，之後改為核准即自動派工；一次性 `AI_TEAM_RESUME_APPROVED_TASK`。
- Codex 雙層沙箱 `.agents`／`.codex` 掛載點準備與無模型預檢；本機測試網路 `localTestNetwork` 與文件副本建置。
- Codex CLI `sandbox`／`exec` 參數不對稱修正；`RuntimeUsageError`（未呼叫模型不計次）。
- PM 工作區查核改用任務工作區；Slack 報告可讀性（`SlackReport`）。

### 2026-09-08（Codex）
- 額度自動續跑（`QuotaAutoResume`、`quota_retry`），修正 Claude session limit 漏辨識。
- QA 改用 Codex（gpt-5.6-sol、reasoningEffort=medium）；Slack 階段定期回報（`StageProgress`）。
- 依使用者要求清除 SMTP 重測任務 #1010、#1012（可復原備份保留）。
  教訓：部署時 JVM 會繼承 deployment.lock，啟動服務要加 `8>&-`。

### 2026-09-07（Claude Code）
- 專案首次納入 git 並推上 GitHub（private，初始 commit `a50642d`），推送前掃描憑證樣式。
- 建立跨工具交接檔 `.ai/context.md`、`.ai/progress.md`、`.ai/decisions.md`。
