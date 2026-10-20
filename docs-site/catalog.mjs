// 文件目錄：docs-site/docs/ 每份文件的分類、狀態與一句話用途。
// 這份資料同時驅動 sidebar、/catalog 頁面、build 產出的 llms.txt，
// 以及 `npm run check:catalog`（docs-site/docs/ 新增或刪除文件而未更新此處時會失敗）。
//
// status 的意義（給 agent 判斷能不能照做）：
//   current    現行契約或操作方式，可依此行動；仍以程式與 schema 為最終依據
//   record     有日期的部署／實測紀錄，是證據，不是現行設定
//   decision   架構決策（ADR），說明為什麼這樣設計
//   historical 已被取代的早期文件，只供追溯，不可照做

export const statuses = {
  current: { label: '現行', description: '現行契約或操作方式，可依此行動' },
  record: { label: '紀錄', description: '有日期的部署／實測證據，不是現行設定' },
  decision: { label: '決策', description: '架構決策與理由' },
  historical: { label: '歷史', description: '已被取代，只供追溯，不可照做' },
};

export const categories = [
  { id: 'install', label: '建置與環境', description: '從零建置、Linux 支援矩陣與重建驗收缺口。' },
  { id: 'architecture', label: '系統架構', description: '多 runtime 與執行沙箱、Slack 互動模型與資料表（分層全貌見系統全貌）。' },
  { id: 'workflow', label: '配置式團隊與工程流程', description: '團隊 JSON、需求追溯、跨專案操作契約、QA 與分流。' },
  { id: 'slack', label: 'Slack 操作與人工閘門', description: '直接對角色提問、核准按鈕、角色 App、進度卡。' },
  { id: 'gates', label: '失敗、審查與額度', description: '失敗回報契約、發布阻擋、額度暫停與自動續跑。' },
  { id: 'runtime', label: 'Runtime 與沙箱契約', description: 'Codex／opencode CLI 參數、雙層沙箱、本機測試網路、模型指定。' },
  { id: 'decisions', label: '架構決策（ADR）', description: '受阻交接、受阻發現連續性、Backend／QA 角色決策。' },
  { id: 'deploy', label: '部署紀錄', description: '既有 VM 的部署版本、雜湊與維運方式。' },
];

export const docs = [
  // 建置與環境
  {
    id: 'INSTALL',
    title: '從零建置唯一入口',
    category: 'install',
    status: 'current',
    summary: '新 VM 從系統前置、工具鏈、CLI 登入、沙箱、Slack 設定到分層驗收的唯一安裝程序；不是一鍵安裝。',
    readWhen: '要在新機安裝、升級既有服務，或確認某個安裝步驟的正確做法。',
    code: ['deploy/bootstrap/', 'deploy/env.sh.template'],
  },
  {
    id: 'rebuild-verification',
    title: '從零重建驗證紀錄',
    category: 'install',
    status: 'current',
    summary: '乾淨 VM 重建演練的證據與「尚待完成的驗收」清單；目前不能宣稱乾淨 VM 重建成功。',
    readWhen: '要回答「專案是否已可從零建置」或更新驗收狀態。',
    code: ['deploy/bootstrap/'],
  },
  {
    id: 'linux-support',
    title: 'Linux 環境與驗證矩陣',
    category: 'install',
    status: 'current',
    summary: '各發行版／架構的實測狀態與 check-linux.sh 能力檢查範圍；未實測者不可標為已支援。',
    readWhen: '判斷某個 Linux 發行版或 ARM64 能不能用。',
    code: ['deploy/bootstrap/check-linux.sh', 'deploy/bootstrap/linux-platform.sh'],
  },

  // 系統架構
  {
    id: 'multi-runtime',
    title: '多 Runtime 設計',
    category: 'architecture',
    status: 'current',
    summary: '安全邊界在 runtime 外層（ExecutionSandbox 的實際機制與網路未隔離等限制）、AgentRuntime 能力宣告與降級對照表。',
    readWhen: '要新增 runtime adapter、理解 runtime 能力差異，或判斷沙箱擋得住什麼。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/runtime/AgentRuntime.java', 'orchestrator/src/main/java/com/zipe/aiteam/runtime/RuntimeRegistry.java', 'orchestrator/src/main/java/com/zipe/aiteam/runtime/ClaudeCodeRuntime.java', 'orchestrator/src/main/java/com/zipe/aiteam/sandbox/'],
  },
  {
    id: 'interaction-model',
    title: '互動模型',
    category: 'architecture',
    status: 'current',
    summary: '每個角色以自己的 Slack 身分發言、交接公開可見但 mention 不觸發、只在真人閘門停下；等待人類時釋放並行槽。',
    readWhen: '理解 Slack 上誰在說話、為什麼主 App 與角色 App 分開。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/slack/'],
  },
  {
    id: 'data-model',
    title: '資料模型',
    category: 'architecture',
    status: 'current',
    summary: 'SQLite 每張表與欄位、Task.State 允許值、四張 JSON 快照表結構；沒有遷移機制。',
    readWhen: '查詢或修改資料庫、判斷任務狀態、改 schema.sql 或 FlowRun／Checkpoint 序列化型別。',
    code: ['orchestrator/src/main/resources/schema.sql', 'orchestrator/src/main/java/com/zipe/aiteam/store/TaskStore.java'],
  },

  // 配置式團隊與工程流程
  {
    id: 'configurable-team',
    title: '配置式專案、角色與工作流程',
    category: 'workflow',
    status: 'current',
    summary: 'AI_TEAM_DEFINITION 團隊 JSON（project／roles／nodes）、scope 與 gate 政策、Agent 依能力自主派工、受阻路由、次數上限、快照相容、承接新任務，以及同一台 VM 多個服務實例（AI_TEAM_INSTANCE）。',
    readWhen: '新增或修改角色、節點、流程，或解釋任務為何停住／能否繼續。',
    code: ['orchestrator/teams/', 'orchestrator/src/main/java/com/zipe/aiteam/flow/', 'orchestrator/src/main/java/com/zipe/aiteam/task/TaskOrchestrator.java', 'orchestrator/src/main/java/com/zipe/aiteam/sandbox/TaskUnits.java'],
  },
  {
    id: 'baseline-verification',
    title: '基準驗證：接入專案與部署配置時先確認驗證指令',
    category: 'workflow',
    status: 'current',
    summary: '服務啟動或 Slack「基準驗證」指令時，在基準分支最新 commit 跑配置的驗證指令；失敗只警告不阻擋，並告訴之後的任務。',
    readWhen: '接入新專案、部署新配置後確認驗證指令能否通過，或任務被告知已知基準失敗時。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/flow/BaselineVerifier.java', 'orchestrator/src/main/java/com/zipe/aiteam/task/TaskOrchestrator.java', 'orchestrator/src/main/java/com/zipe/aiteam/workspace/WorkspaceManager.java'],
  },
  {
    id: 'engineering-workflow',
    title: '需求追溯、獨立驗證與交付邊界',
    category: 'workflow',
    status: 'current',
    summary: 'v2 需求基準與原子驗收項、返工與需求變更、系統獨立驗證收據、PR_CREATED 的意義。',
    readWhen: '處理需求 ID／驗收覆蓋、返工失效傳遞，或判斷 PR 狀態代表什麼。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/flow/Traceability.java', 'orchestrator/src/main/java/com/zipe/aiteam/flow/IssueTracker.java', 'orchestrator/src/main/java/com/zipe/aiteam/flow/IndependentVerifier.java', 'orchestrator/src/main/java/com/zipe/aiteam/flow/VerificationPlan.java', 'orchestrator/src/main/java/com/zipe/aiteam/flow/FileComparison.java', 'orchestrator/src/main/java/com/zipe/aiteam/flow/CommandEvidence.java', 'orchestrator/src/main/java/com/zipe/aiteam/runtime/CommandRuns.java'],
  },
  {
    id: 'cross-project-execution',
    title: '跨專案執行契約',
    category: 'workflow',
    status: 'current',
    summary: 'project.operations 操作契約、各階段沙箱預檢、真人計畫核准與自動測試分離、舊任務配置遷移。',
    readWhen: '讓新專案宣告建置／測試指令，或處理配置更新後的舊任務。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/flow/OperationContract.java', 'orchestrator/src/main/java/com/zipe/aiteam/flow/TeamMigration.java'],
  },
  {
    id: 'direct-ssh-deployment',
    title: 'Agent VM 以 SSH 直連部署',
    category: 'workflow',
    status: 'current',
    summary: 'Agent VM 直連目標、受限公鑰與固定操作；取代 Windows broker 的規格、一次性設定與未完成驗收。',
    readWhen: '設定 SSH 直連部署、管理公鑰與主機指紋，或排查部署通道。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/flow/ServiceSupport.java', 'orchestrator/src/main/java/com/zipe/aiteam/flow/OperationJobs.java', 'orchestrator/src/main/java/com/zipe/aiteam/flow/FlowEngine.java', 'orchestrator/src/main/java/com/zipe/aiteam/runtime/RuntimeTimedOut.java', 'orchestrator/src/main/java/com/zipe/aiteam/sandbox/SandboxUnitCleanup.java', '.agents/skills/service-build/', 'orchestrator/teams/skills/service-build/', 'orchestrator/teams/prompts/service.md', 'orchestrator/teams/prompts/service-triage.md', 'orchestrator/teams/bestpartner.json'],
  },
  {
    id: 'service-delivery',
    title: '服務建置與開發分流',
    category: 'workflow',
    status: 'current',
    summary: 'Infrastructure 建置既有服務、啟動後檢查、自動修復與必要主機資訊；不先進 QA 計畫。',
    readWhen: '建立測試環境、部署服務、設定 serviceTarget 或了解 SERVICE 完成條件。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/flow/ServiceSupport.java', '.agents/skills/service-build/', 'orchestrator/teams/skills/service-build/', 'orchestrator/src/main/java/com/zipe/aiteam/flow/ServiceDelivery.java', 'orchestrator/src/main/java/com/zipe/aiteam/flow/ServiceDeploymentContext.java', 'orchestrator/src/main/java/com/zipe/aiteam/store/TaskStore.java', 'deploy/service-context/', 'orchestrator/teams/prompts/service.md', 'orchestrator/teams/prompts/service-triage.md', 'orchestrator/teams/schemas/service.json'],
  },
  {
    id: 'qa-scenario-workflow',
    title: 'QA 情境導向流程',
    category: 'workflow',
    status: 'current',
    summary: 'QA 在實作前以需求設計情境（含反例與 operation_ids），實作後逐情境執行並附證據。',
    readWhen: '撰寫或審查測試計畫、理解 QA 能寫什麼不能寫什麼。',
    code: ['orchestrator/teams/prompts/plan.md', 'orchestrator/teams/prompts/verify.md'],
  },
  {
    id: 'pm-triage',
    title: 'QA 受阻後的 PM 處理',
    category: 'workflow',
    status: 'current',
    summary: '受阻分流、同討論串指令（處理／補充／中斷／狀態）、需求變更、中斷與中止差異、改用新任務繼續。',
    readWhen: '使用者問「任務停住了怎麼辦」或要在 Slack 下指令操作既有任務。',
    code: ['orchestrator/teams/prompts/triage.md', 'orchestrator/src/main/java/com/zipe/aiteam/task/TriageGate.java'],
  },

  // Slack 操作與人工閘門
  {
    id: 'direct-agent-requests',
    title: '直接對角色提出請求',
    category: 'slack',
    status: 'current',
    summary: '白名單使用者 @角色 唯讀提問，或以「執行」前綴派工；啟用條件、脈絡與權限、真實驗收清單。',
    readWhen: '要直接問某個角色問題或派工，或判斷直接 @角色為何沒反應。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/task/DirectRequest.java'],
  },
  {
    id: 'plan-approval-buttons',
    title: '測試計畫核准按鈕',
    category: 'slack',
    status: 'current',
    summary: '測試計畫核准／要求修改按鈕、收據綁定版本、autoApprovePlanWhenValid、停止訊息的選項按鈕。',
    readWhen: '處理計畫核准、停止訊息上的按鈕，或 human_decision 選項。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/flow/PlanApproval.java', 'orchestrator/src/main/java/com/zipe/aiteam/slack/PlanReview.java'],
  },
  {
    id: 'slack-agent-apps',
    title: '建立 Slack App（主 App 與角色 App）',
    category: 'slack',
    status: 'current',
    summary: '以 manifest 建立主 App 與角色 App、兩種 token 對應的環境變數、邀請進頻道、驗證，以及新增配置式角色的完整步驟。',
    readWhen: '新機設定 Slack、新增角色 App、設定角色獨立身分或 App-Level Token，或 Slack 沒反應時排查。',
    code: ['deploy/slack-app-manifest.yaml', 'deploy/agent-manifests/'],
  },
  {
    id: 'slack-progress',
    title: 'Slack 階段定期回報',
    category: 'slack',
    status: 'current',
    summary: '每個執行中階段的狀態卡（預設 300 秒更新）；卡片只表示呼叫尚未返回，不代表健康或進度。',
    readWhen: '解讀 Slack 上的執行中狀態卡或調整更新間隔。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/slack/StageProgress.java'],
  },

  // 失敗、審查與額度
  {
    id: 'failure-reporting',
    title: '全角色具體失敗回報契約',
    category: 'gates',
    status: 'current',
    summary: 'execution_status 與八欄 failure_report、矛盾結果攔截、系統例外由 agent 白話說明。',
    readWhen: '處理角色回報 BLOCKED、階段失敗，或修改角色 schema。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/task/FailureReport.java', 'orchestrator/src/main/java/com/zipe/aiteam/flow/ErrorExplainer.java', 'orchestrator/src/main/java/com/zipe/aiteam/runtime/ProviderAccessDenied.java', 'orchestrator/src/main/java/com/zipe/aiteam/flow/SystemFailure.java', 'orchestrator/src/main/java/com/zipe/aiteam/sandbox/ArgumentListTooLong.java'],
  },
  {
    id: 'review-gate',
    title: 'Code Review 發布阻擋',
    category: 'gates',
    status: 'current',
    summary: 'Reviewer 報告必須 VERIFIED 且無 blocker／HIGH finding 才能進人工 PR 核准；審查收斂規則；負責人命令開 PR 與放行提醒等級。',
    readWhen: '判斷為何任務沒有進入 PR 核准、調整審查閘門，或負責人要在 Slack 命令開 PR。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/task/ReviewGate.java', 'orchestrator/src/main/java/com/zipe/aiteam/flow/OwnerPublish.java'],
  },
  {
    id: 'quota-continuation',
    title: '額度暫停與自動續跑',
    category: 'gates',
    status: 'current',
    summary: 'Claude／Codex 額度訊息解析、解禁時間推算、自動續跑排程與 Slack 呈現。',
    readWhen: '任務停在 WAITING_QUOTA，或處理額度相關訊息。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/task/QuotaAutoResume.java', 'orchestrator/src/main/java/com/zipe/aiteam/task/QuotaRetryTime.java'],
  },

  // Runtime 與沙箱契約
  {
    id: 'codex-cli-argument-contract',
    title: 'Codex CLI 參數契約',
    category: 'runtime',
    status: 'current',
    summary: 'codex sandbox 與 codex exec／resume 參數不對稱、未呼叫模型的失敗不計次、schema 相容性、提示改由 stdin。',
    readWhen: '修改 CodexRuntime 參數，或升級 Codex CLI 版本後要重驗。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/runtime/CodexRuntime.java', 'orchestrator/src/main/java/com/zipe/aiteam/runtime/CodexLocalTestPolicy.java'],
  },
  {
    id: 'codex-nested-sandbox',
    title: 'Codex 雙層沙箱初始化',
    category: 'runtime',
    status: 'current',
    summary: '外層 systemd 沙箱唯讀時，Codex 內層沙箱需要預先建立 .agents／.codex 掛載點並先跑沙箱預檢。',
    readWhen: 'Codex 階段出現 bwrap／Read-only file system 初始化錯誤。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/runtime/CodexSandboxPreparation.java'],
  },
  {
    id: 'local-test-capabilities',
    title: '本機測試與文件副本建置能力',
    category: 'runtime',
    status: 'current',
    summary: '節點選項 localTestNetwork（只允許本機 TCP、外連被拒）與文件副本建置方式。',
    readWhen: '驗收需要 localhost socket（如 GreenMail），或文件建置在沙箱內失敗。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/runtime/CodexLocalTestPolicy.java'],
  },
  {
    id: 'opencode-runtime-report',
    title: 'opencode Runtime 實測報告',
    category: 'runtime',
    status: 'record',
    summary: 'opencode 1.18.19 旗標、事件流與能力宣告；VM 沙箱下端對端尚未驗證，不可標為已支援。',
    readWhen: '考慮使用第三個 runtime（opencode），或需要實際金額回報。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/runtime/OpenCodeRuntime.java'],
  },
  {
    id: 'per-agent-model',
    title: '每個 Agent 指定模型（legacy）',
    category: 'runtime',
    status: 'current',
    summary: 'legacy 模式以 AI_TEAM_MODEL_* 環境變數指定各角色模型；配置式模式改在團隊 JSON 的 roles 設定。',
    readWhen: '未設定 AI_TEAM_DEFINITION 的 legacy 模式要調整模型。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/config/AiTeamProperties.java'],
  },

  // 架構決策
  {
    id: 'adr-automatic-blocker-routing',
    title: 'ADR：受阻責任自動交接與驗收前系統證據',
    category: 'decisions',
    status: 'decision',
    summary: '受阻事件依配置路由自動交接（每任務 6 個事件、每事件 12 個節點）、operation_ids 閘門、QA 前獨立驗證。',
    readWhen: '理解流程為何能在上限後繼續，或修改自動交接規則。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/flow/AutomaticHandoff.java'],
  },
  {
    id: 'adr-blocked-finding-continuity',
    title: 'ADR：受阻發現的傳遞連續性與被拒工具的可信度',
    category: 'decisions',
    status: 'decision',
    summary: '受阻發現另存 flow.blockedFindings 無條件注入下游；工具被拒卻宣稱完成的報告作廢（ToolTrust）。',
    readWhen: '分流看不到先前受阻原因，或角色工具白名單與報告證據不一致。',
    code: ['orchestrator/src/main/java/com/zipe/aiteam/flow/ToolTrust.java', 'orchestrator/src/main/java/com/zipe/aiteam/flow/FlowContext.java'],
  },
  {
    id: 'backend-qa-decisions',
    title: 'Backend／QA 角色定義：四項決策',
    category: 'decisions',
    status: 'decision',
    summary: '越界即停、Backend 負責測試而 QA 檢查測對東西、失敗誠實回報、QA 權限以沙箱加 commit 路徑稽核。',
    readWhen: '修改實作或驗收角色的職責、權限或 schema。',
    code: ['orchestrator/schemas/impl-schema.json', 'orchestrator/src/main/java/com/zipe/aiteam/task/QaGate.java'],
  },

  // 部署紀錄
  {
    id: 'deploy-2026-09-06-v2',
    title: '2026-09-06 v2 部署',
    category: 'deploy',
    status: 'record',
    summary: '既有 VM 以 team.env 啟用 v2 的版本雜湊、驗證證據、安全修正與維運方式。',
    readWhen: '查既有 VM 的部署方式與 v2 啟用設定。',
  },
];
