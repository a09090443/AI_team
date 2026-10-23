---

sidebar_position: 5
title: 設定參考
description: env.sh 環境變數與團隊配置 JSON 的欄位、可選值與限制。
---

# 設定參考

新 v2 配置載入時，系統將 `contracts/issue-tracking-v1` 共用問題追蹤契約納入快照與版本雜湊，
不需逐專案新增欄位，也不以專案名稱分支；既有任務不因重啟自動啟用。
此為系統生成的快照資產，不是要在 teams 下建立同名檔案。細節見[問題完整性](/docs/engineering-workflow#跨專案問題完整性2026-10-11)。

設定分兩處：**`~/ai-team/env.sh`**（權限 600，含憑證，不進版控）與 **團隊配置 JSON**
（`orchestrator/teams/*.json`，不含憑證，只引用環境變數名稱）。
範本是 `deploy/env.sh.template`；欄位驗證在 `config/AiTeamProperties.java` 與 `flow/TeamDefinition.java`。

## 環境變數

選填的 `~/ai-team/team.env`（同樣 600）會在 `env.sh` 之後載入，兩邊都有時以 `team.env` 為準；
用途是把「切換團隊配置」和含憑證的 `env.sh` 分開。

| 變數 | 用途 |
|---|---|
| `SLACK_APP_TOKEN`、`SLACK_BOT_TOKEN` | 主 App 的 `xapp-`（Socket Mode）與 `xoxb-` token |
| `SLACK_BOT_TOKEN_<角色>` | 角色 App 的 Bot Token；留空則以主 token 改名代發，無法被 @ |
| `SLACK_APP_TOKEN_<角色>` | 角色 App 自己的 App-Level Token，啟用直接對角色提問；不可共用主 App 的 |
| `AI_TEAM_ALLOWED_USERS` | 允許觸發的 Slack user ID，逗號分隔（不是顯示名稱） |
| `AI_TEAM_CHANNEL_ID` | 運作頻道 ID；preflight 視為必填 |
| `AI_TEAM_DEFINITION` | 團隊配置 JSON 的絕對路徑；**留空＝legacy 五角色模式**。修改後重啟，只影響新任務 |
| `GRADLE_USER_HOME` | 只有 Gradle 專案需要：指向 `$HOME/.cache/gradle`，配置 `cachePaths` 宣告 `.cache/gradle`；沙箱白名單會帶入，系統獨立驗證不受影響 |
| `AI_TEAM_INSTANCE` | 服務實例名稱（建議放 `team.env`）。單一服務留空；同一台 VM 多個專案服務時**必填且不可重複**，systemd unit 改名為 `ai-team-<實例>-…`。格式見 [多個服務實例](/docs/configurable-team#同一台-vm-多個服務實例) |
| `AI_TEAM_HOME`、`AI_TEAM_REPO`、`AI_TEAM_WORKSPACES`、`AI_TEAM_LOGS` | 服務根目錄、目標 repo 鏡像、任務工作區、階段 log |
| `CLAUDE_BIN`、`CODEX_BIN`、`OPENCODE_BIN` | 各 CLI 絕對路徑；只有用到該 runtime 才需要 |
| `AI_TEAM_GITHUB_TOKEN` | 推分支與開 PR；留空時管線能跑完但核准後推不出去（啟動 log 會出 WARN）。`PullRequestPublisher` 以它當 HTTPS git 推送密碼並呼叫 GitHub REST API 開 PR。建議用只限目標 repo 的 Fine-grained token：<https://github.com/settings/personal-access-tokens/new> → Repository access 只選該 repo → Repository permissions 設 **Contents: Read and write**、**Pull requests: Read and write**（Metadata 唯讀自動帶入）。每個實例填自己 repo 的 token，由使用者本人填入 `env.sh`；到期要換新並重啟 |
| `AI_TEAM_BASE_BRANCH`、`AI_TEAM_BRANCH_PREFIX` | 基準分支（留空自動偏好 `develop`）與任務分支前綴 |
| `AI_TEAM_GIT_USER_NAME`、`AI_TEAM_GIT_USER_EMAIL` | agent commit 署名，寫在工作區 local config |
| `AI_TEAM_MAX_CONCURRENCY` | 實例內 legacy 階段的並行槽上限；中央工作迴圈仍依序執行，單獨調高不會讓 v2 任務平行；訂閱帳號建議 1 |
| `AI_TEAM_RESUME_OPERATION_TASK` | 2026-10-20 已部署。只在一次啟動指定一個數字 task ID，將符合條件的舊 AWAIT_HUMAN 排回原長作業；須同 activeNode、乾淨工作區與有效同 HEAD journal。不是批次掃描或核准開關，用後移除，勿常駐 env.sh／team.env |
| `AI_TEAM_TASK_TIMEOUT_SECONDS`、`AI_TEAM_GIT_TIMEOUT_SECONDS` | 全域逾時覆寫（0＝用各階段設定）與 git 逾時 |
| `AI_TEAM_PROGRESS_INTERVAL_SECONDS` | 進度卡更新秒數，預設 300、最小 30；**不能設成空字串** |
| `AI_TEAM_REPORT_DETAIL` | `brief`（預設）或 `full`；完整報告可用 `補充 #<id> 檢視完整報告 [節點]` 叫出 |
| `AI_TEAM_BASELINE_VERIFY` | `on`（預設）或 `off`。on 時服務啟動會在「目前驗證指令／工具鏈＋基準分支最新 commit」還沒有結果時自動跑一次（只改提示詞、角色或流程不會觸發）[基準驗證](/docs/baseline-verification)；off 只關掉自動觸發，Slack「基準驗證」指令照樣可用 |
| `AI_TEAM_EXPLAINER_MODEL`、`AI_TEAM_EXPLAINER_ENABLED` | 系統錯誤白話說明所用模型（預設 haiku）與開關；布林值不能設成空字串 |
| `AI_TEAM_MODEL_<角色>`、`AI_TEAM_RUNTIME_<角色>`、`AI_TEAM_CODE_REVIEW` | **僅 legacy 模式**；配置式模式改在 JSON 的 `roles` 設定 |

`<角色>` 為 `PM`、`ARCHITECTURE`、`BACKEND`、`QA`、`CODE_REVIEW`。
使用 Claude 訂閱帳號時不可設定 `ANTHROPIC_API_KEY`，也不能用 `--bare`。

## 團隊配置 JSON

結構對所有專案相同；`orchestrator/teams/` 內附的配置可當範例，各自啟用的功能見[實例一覽](/docs/configurable-team#各專案實例的功能啟用狀態)。
所有 prompt／schema 路徑必須在配置檔所在目錄內，跳脫或 symlink 指向外部會被拒絕。

```json
{
  "version": 2,
  "id": "<團隊 ID>",
  "project": { "...": "專案層設定" },
  "roles": { "<角色 ID>": { "...": "角色設定" } },
  "entry": "intake",
  "maxTransitions": 24,
  "sharedInstructions": "prompts/shared.md",
  "collaboration": { "consultPerVisit": 2, "consultPerTask": 12, "questionsPerRound": 2, "boardMaxEntries": 100 },
  "nodes": { "<節點 ID>": { "...": "節點設定" } }
}
```

`sharedInstructions` 是選填欄位：所有角色共用的工作規則檔（配置目錄內相對路徑），建立快照時一併凍結，
正式階段附在角色提示之後、技能與本次階段指示之前（「【團隊共用工作規則】」區塊）；唯讀對話與諮詢回答不附。
沒設定時不序列化，revision 不變。四份內附配置都指向 `prompts/shared.md`。

`collaboration` 是選填欄位，用來啟用角色諮詢與共享看板，各欄位的上限如下：

| 欄位 | 範圍 | 說明 |
|---|---|---|
| `consultPerVisit` | 0～2 | 每次節點執行最多幾輪諮詢 |
| `consultPerTask` | 0～16 | 每個任務最多幾題 |
| `questionsPerRound` | 1～3 | 每輪最多幾題 |
| `boardMaxEntries` | 1～200 | 看板最多幾筆 |

沒設定時，整個功能關閉，舊配置的 revision 也不會變動。行為說明見[角色諮詢與共享看板](/docs/configurable-team#角色諮詢與共享看板2026-10-17)。

### `project`

| 欄位 | 意義 |
|---|---|
| `name`、`repositoryEnv`、`baseBranch` | 專案名稱、存放 repo 路徑的環境變數名、基準分支 |
| `buildInstructions`、`testInstructions` | 注入角色的建置／測試規範；`testInstructions` 的每一步都要有對應 `operations` |
| `operations` | 操作契約：`{ "<id>": { nodes, directory, argv, writes } }`。`argv` 不支援管線，多步驟請拆開。執行檔不可為 `rm`（Codex 會拒絕，載入配置即失敗；清空目錄用 `find <目錄> -mindepth 1 -delete`）。`writes` 中沒有 git 追蹤檔的目錄會被系統自動寫進工作區 `.git/info/exclude`，不進寫入稽核或代為提交（見 [configurable-team](/docs/configurable-team)） |
| `verification` | 系統獨立驗證：管理者固定的 `commands` 與 `timeoutSeconds`（最多 1800；超時不算測試失敗，交給真人；啟用 `project.autonomy.verificationContinuations` 時先在同一個 clone 自動續驗剩下的指令），在 QA 前於全新 clone 執行，依賴下載快取放在 `<安裝目錄>/verification-cache`；選填 `docOnlyPaths`（路徑 glob）：系統依 git diff 判定變更全部落在其中、且不含強制全跑檔時略過建置測試；同一份清單也會寫進實作節點的指示，要求純文件變更不要自行編譯（見 engineering-workflow）；選填 `commandScopes`（與 `commands` 一一對應的負責路徑，只跑範圍內有變更的指令）與 `qaOnlyPaths`（由 QA 驗證、系統不跑指令的路徑，須與 commandScopes 一起設定且不得重疊）；有不屬於任何範圍的變更就全跑。相同輸入指紋已通過的指令會重用（2026-10-17 起） |
| `nodes.<實作>.protectedPathFallback` | 選填 `{"role", "paths"}`：需求提到原實作角色 runtime 寫不了的路徑、且其中有落在 `paths`（目錄 `/**`）內的，就分段改派這些檔案（範圍外的不交出去，原角色要改時被拒而停下問人；2026-10-08 前要「全部」在範圍內才改派）：該角色只改 `paths` 內的檔案（系統核對全部在範圍內後代為提交），其餘工作仍由原角色完成；行為設定類路徑寫死禁止（見 multi-runtime） |
| `requiredTools` | 啟動時沙箱工具鏈自檢逐一檢查，缺任一項服務不啟動 |
| `protectedPaths`、`cachePaths` | 任何角色不可寫的路徑；允許的快取目錄（只限 `.m2/repository`、`.npm`、`.gradle/caches`、`.cache/<名稱>`） |
| `requirePlanApproval`、`autoApprovePlanWhenValid` | 測試計畫是否需真人核准；通過機械檢查時是否自動核准（預設 false） |

### `roles.<角色 ID>`

| 欄位 | 意義 |
|---|---|
| `name` | Slack 顯示名稱，例如 `QA Agent` |
| `runtime` | `claude-code`、`codex` 或 `opencode`（opencode 在 VM 沙箱下尚未驗證） |
| `model` | 該 runtime 認得的模型名；三家格式不通用（opencode 為 `provider/model`） |
| `reasoningEffort` | 選填，目前僅 Codex 支援 `low`／`medium`／`high`／`xhigh` |
| `prompt` | 角色一般職責的提示檔 |
| `capabilities` | 自主派工候選角色必填的能力與限制描述，最多 2000 字；提供模型判斷，不會授予權限 |
| `botTokenEnv`、`appTokenEnv` | Slack token 的**環境變數名稱**（值不進 JSON、不進快照） |
| `tools` | 工具白名單，例如 `Read`、`Bash(mvn *)`。要角色核實產物就必須給對應唯讀工具，否則被拒後報告作廢。Claude Code 以 `--permission-mode dontAsk` 執行，**沒列在這裡的工具一律拒絕**，包含 `WebSearch`、`WebFetch`：角色要查網路（例如查套件最新版）必須明列，可寫成 `WebFetch(domain:repo1.maven.org)` 限定網域。Codex runtime 目前未開啟網頁搜尋 |

### `nodes.<節點 ID>`

| 欄位 | 意義 |
|---|---|
| `kind` | `AGENT` 或 `APPROVAL` |
| `role`、`schema`、`instructions` | 執行角色、輸出 schema、階段指示（優先於角色 prompt） |
| `candidateRoles` | 選填 1～16 個不重複的合格角色 ID（須含原 role）；非入口 agent 節點可用。上游以 handoff 選擇下一階段負責人，缺漏或越權會受阻；未設定時維持原 role |
| `consultRoles` | 選填 1～8 個可諮詢的角色 ID，須先設定頂層 `collaboration`。不可含自己（含 candidateRoles）；REVIEW 節點不可諮詢實作角色；被諮詢角色須有 `capabilities`。見 [角色諮詢與共享看板](/docs/configurable-team#角色諮詢與共享看板2026-10-17) |
| `scope` | 寫入權限，見下表 |
| `gate` | 工作契約，見下表 |
| `workspace` | 是否建立任務工作區；已有工作區的 READ_ONLY 節點仍唯讀查看該副本 |
| `writablePaths` | `LIMITED` 可寫路徑，支援 `/`、`*`、`**` |
| `localTestNetwork` | 只允許隔離 namespace 內本機 TCP；限 Codex、`LIMITED`、`VERIFICATION` 節點 |
| `plan` | 驗收節點引用的計畫節點 |
| `next`、`blocked`、`routes` | 成功、受阻時的下一站，以及 agent 可回傳的 route 鍵；保留目標 `human`、`end` |
| `requires` | 必須先有有效收據的前置節點；`APPROVAL` 節點至少要涵蓋實作、計畫、驗收、審查 |
| `maxVisits`、`timeoutSeconds`、`maxTurns` | 相同輸入連續重跑上限（最多 3）、逾時（秒，最多 3600；2026-10-22 前為 1800）、回合上限（最多 80）。`timeoutSeconds` 可用配置遷移套用到舊任務，另兩項不行 |

| `scope` | 權限 |
|---|---|
| `READ_ONLY` | 只允許 runtime 自身狀態寫入 |
| `WORKSPACE` | 獨立 clone 可寫；必須配 `IMPLEMENTATION` 契約 |
| `LIMITED` | 只有 `writablePaths` 匹配的目錄與該 clone 的 Git 狀態（明確目錄不存在時先建立空目錄；萬用字元只匹配既有目錄，全無匹配即停止）；沙箱外逐筆核對 commit 與未提交路徑 |

| `gate` | 契約 |
|---|---|
| `REPORT` | 共用完成／失敗報告 |
| `REQUIREMENTS` | 需求基準：需求 ID、原子驗收項目與維度、範圍、待確認問題 |
| `IMPLEMENTATION` | 成功狀態、建置品質、無範圍衝突 |
| `TEST_PLAN` | 情境覆蓋所有原子驗收 ID，每條有反例與可執行的 `operation_ids` |
| `VERIFICATION` | 逐情境實際結果與證據，引用計畫節點 |
| `REVIEW` | 獨立核實、檢查紀錄、無 blocker 或 HIGH finding |

### 驗證與生效

```bash
java -jar orchestrator/target/ai-team-orchestrator-0.1.0.jar --validate-team <配置檔>
bash deploy/bootstrap/check-source.sh
```

`--validate-team` 只驗證結構、引用、資產與 repo 目錄，不驗證 CLI 登入、Slack scope 或沙箱。
配置在服務啟動時讀取；改完要重啟，且**只影響新任務**。舊任務沿用建立時的快照。

完整說明見 [configurable-team](/docs/configurable-team) 與 [cross-project-execution](/docs/cross-project-execution)。


## 自主恢復（project.autonomy）

選填，null＝停用。欄位 `modelTimeoutRecoveries`（0～3）、`maxTimeoutSeconds`（0～3600）、`verificationContinuations`（0～3）、
`requirementChangeOptions`、`channelCommandRouting`、`deferUnverifiable`（2026-10-23，缺欄位為 false）、
`pullRequestSyncRounds`（0～3，缺欄位為 0）、`pullRequestSyncAutoPush`（缺欄位為 false）。各實例的啟用狀態見[實例一覽](/docs/configurable-team#各專案實例的功能啟用狀態)：
- bestpartner 為 2／3600／2／true／true／true／2／true；
- dynamic-api 與 spring-starters 為 0／0／0／false／false／true／0／false，只開延後驗收。

可原地配置遷移。PR 衝突同步的兩個欄位讀**目前**配置（舊任務快照沒有），見 [已開 PR 的衝突同步](/docs/engineering-workflow#已開-pr-的衝突同步2026-10-23)。
行為與邊界見 [失敗回報](/docs/failure-reporting)「管理者授權的自主恢復」一節。

## 服務建置與指定腳本

`project.serviceTarget` 指定管理者核准的服務目標名稱及檢查操作 ID（至少 start、health）；null 時不能啟動 service runtime。
`Gate.SERVICE` 限 LIMITED 工作區，成功直接 end，不套用開發 QA／PR 閘門；完整欄位與證據限制見 [服務建置](/docs/service-delivery)。
`project.operations.*.scriptSha256` 僅可配固定的 bash 相對腳本，內容必須吻合且執行時唯讀；見 [跨專案契約](/docs/cross-project-execution)。


## 服務執行工具（2026-10-10）

服務建置以 `project.operations` 的四個 `service-*` 操作與 `serviceTarget` 指定目標；Agent VM 的 `~/.config/ai-team/ssh-targets/<目標名稱>.json` 指定 destination、port、identity_file、known_hosts_file，固定執行器依即時 SERVICE 狀態授權。詳見 [服務建置](/docs/service-delivery)。


### 節點 skill

`nodes.<id>.skills` 選填，最多 8 份不重複的 `skills/<name>/SKILL.md` 或 `skills/<name>/references/<name>.md` 相對路徑。檔案須位於 teams 目錄內，每份不超過 256 KB。
舊配置缺欄位照舊；skill 與參考內容納入 revision／snapshot，角色改派保留 skills，唯讀對話不注入。變更 skill 須新任務承接，不能以能力遷移替換提示。

直連設定僅存私鑰與 known_hosts 的路徑；私鑰留在 Agent VM，目標的 forced-command 入口由管理者一次性安裝；已授權測試部署不需逐版來源manifest。

## 文件驗收配置（2026-10-12 來源更新）

`project.verification.docOnlyPaths` 配合系統 Git 差異與文字副檔名限制略過不相關產品建置，內附配置均已提供；SQL／腳本／設定不適用。
必要文件站建置另由 verification 操作處理。可配置 qa-docs-dir／clean／snapshot／extract／install／build 等操作，僅寫 target/qa-docs，原 docs-site 唯讀，安裝離線使用實作準備的 npm 快取。
plan 的 protected_path_changes 明列需跨 runtime 改派的檔案；不新增權限，仍須符合節點 protectedPathFallback.paths。詳見[配置流程](/docs/configurable-team)。尚未部署。


### 2026-10-19 部署脈絡（已部署）

實例可放 service-context/&lt;target&gt;.json，提供不含機密的宿主程式、設定路徑、啟動指令與歷史收據索引；詳見 [服務建置](/docs/service-delivery)。此檔僅供說明，不增加操作權限。


### 2026-10-19 SSH 直連規格（已啟用）

Agent VM 的 systemd 固定部署執行器已啟用，以 SSH 公鑰直連 dev VM；網路、公鑰、主機指紋、受限入口與 health 已實測，不需要 Windows broker。#1046 的舊 skill 快照須新任務承接；dev VM 曾 heartbeat 停止，經使用者核准重啟後已重新驗證健康與8088入口，當機根因待查。本輪未重部署產品或續跑 Slack。詳見 [直連部署](/docs/direct-ssh-deployment)。

## 長作業恢復的設定邊界（2026-10-20 已部署）

AI_TEAM_RESUME_OPERATION_TASK 在一般 recoverOnStartup 之後讀取，只接受單一數字任務編號；限定配置流程、非角色對話、AWAIT_HUMAN、node=activeNode、乾淨工作區與有效同 HEAD journal。由中央佇列排 RESUME_QUEUED；不是套用新快照、重新核准計畫或核准 PR。管理者應先以固定 executor --reconcile 驗證原成功收據，用後清除變數；程式不會自動修改你的 env 檔。

沒有新增團隊 JSON 的 operation timeout 設定。client 等待預設 30 秒後交回 OPERATION_PENDING；executor journal deadline 建立時為 7200 秒；遠端 root job 的 RuntimeMaxSec 為 7000 秒、TimeoutStopSec 為 30 秒；模型 sandbox 的 TimeoutStopSec 為 3 秒。這些是不同層的來源常數，不能用調高節點 timeoutSeconds 取代結果對帳。

標準佈局下，Java 依 AI_TEAM_HOME、Python 依 workspace 的 home 定位 operation-jobs、operation-cancellations、operation-executor-heartbeat。journal 不在模型工作區；自訂 workspaces 位置時須核對兩者是否指向同一實例 home，不可假稱任意佈局已實測。可信 prepare/start 只在 task／activeNode／target／HEAD 相符時重用，health 仍須核對 start release。詳見[直連部署](/docs/direct-ssh-deployment)。

此契約已部署；未修改 env.sh／team.env，正式恢復使用一次性啟動變數。實測範圍與任務結果見直連部署紀錄。


## 服務追問支援（已部署）

home/service-context/&lt;target&gt;.json可設定access_endpoints陣列（1～4筆），每筆audience、url、probe_url；url為使用者完整入口，probe_url為Agent VM受控實測位置。config_file沿用既有SSH設定引用；auto_repair_existing_release=true允許固定工具只修復已部署active應用，不build或動資料。沒有設定不自動推定URL或新增主機。HTTP探測不跟隨redirect，不接受憑證/query/fragment URL。

AI_TEAM_RESUME_DIALOGUE_TASK只在一次啟動指定舊AWAIT_HUMAN、dialogue=true且有parent的單一對話ID；已COMPLETED服務對話僅在保存答案含不可執行的執行前綴建議時允許校正，走原中央佇列重新回答同一問題；不更換快照、不重開部署、用後移除。service-support/&lt;task&gt;保存current.json與health/diagnose/repair原job；dialogue-evidence/&lt;task&gt;是已清理來源之結案對話的證據資料夾，不是產品clone。


### 持久診斷期限（已部署）

沒有新增環境變數或team.json欄位。Linux support worker的job.json記錄600秒deadline，Java等待上限610秒；remote每次操作另有180秒查詢界線，期限不因worker重啟延長。重啟恢復限定有效診斷服務角色對話，或原activeNode且乾淨同HEAD的SERVICE階段；不會改env.sh或既有快照。中央槽維持循序執行。
