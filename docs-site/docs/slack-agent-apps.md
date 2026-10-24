# 建立 Slack App（主 App 與角色 App）

系統需要一個主 App 與五個角色 App：

| App | manifest | 用途 |
|---|---|---|
| 主 App `zipe-ai-team` | `deploy/slack-app-manifest.yaml` | 接收需求、排程、按鈕與流程控制、佇列狀態與錯誤訊息 |
| PM／Architecture／Backend／QA／Code Reviewer | `deploy/agent-manifests/*.yaml` | 以角色身分發言、交接時真正 @ 到對方、接收對該角色的直接提問 |

角色 App 不做也能運作——角色會退回「主 App 換名字」的模式，差別是不會出現在成員清單，
**交接訊息裡的 mention 會變成粗體文字而 tag 不到人**，也不能直接 @ 角色提問。

上表是現行預設團隊，不是配置式模式的角色上限。要在 `orchestrator/teams/*.json` 新增第六個以上的角色，
依下方「新增配置式角色」辦理；不要把新角色硬加進 legacy role switch。

---

## 1. 以 manifest 建立 App（六個都一樣）

1. 開 <https://api.slack.com/apps> → **Create New App** → **From a manifest**（不是 From scratch）
2. 選擇 workspace，**Next**
3. 把對應 manifest 的**全部內容**貼進去；頁面預設可能是 JSON 分頁，記得切到 **YAML**
4. **Next** → 檢查摘要 → **Create**
5. **OAuth & Permissions → Install to Workspace → Allow**，複製 **Bot User OAuth Token**（`xoxb-`）
6. **Basic Information → App-Level Tokens → Generate Token and Scopes**：名稱隨意，
   scope 選 **`connections:write`**，複製 `xapp-` token。scope 無法事後修改，選錯只能重新產生
7. （選用）**Basic Information → Display Information → App icon** 換頭像；只能上傳圖片

manifest 已包含：Socket Mode、訂閱 `app_mention`、`chat:write`、`app_mentions:read`；
主 App 另有 `chat:write.customize`、`reactions:write` 與互動元件（按鈕）。
刻意不給 `channels:history`：只處理被明確 @ 的訊息，不讀頻道歷史。

## 2. 填進設定

在 VM 用編輯器改 `~/ai-team/env.sh`（權限 600），**不要把 token 貼進聊天或 shell 指令歷史**：

| App | Bot Token（`xoxb-`） | App-Level Token（`xapp-`） |
|---|---|---|
| 主 App | `SLACK_BOT_TOKEN` | `SLACK_APP_TOKEN` |
| PM Agent | `SLACK_BOT_TOKEN_PM` | `SLACK_APP_TOKEN_PM` |
| Architecture Agent | `SLACK_BOT_TOKEN_ARCHITECTURE` | `SLACK_APP_TOKEN_ARCHITECTURE` |
| Backend Agent | `SLACK_BOT_TOKEN_BACKEND` | `SLACK_APP_TOKEN_BACKEND` |
| QA Agent | `SLACK_BOT_TOKEN_QA` | `SLACK_APP_TOKEN_QA` |
| Code Reviewer | `SLACK_BOT_TOKEN_CODE_REVIEW` | `SLACK_APP_TOKEN_CODE_REVIEW` |

角色缺 Bot Token 時發言退回主 App 名稱覆寫；缺 App-Level Token 時直接 @ 該角色不會有反應，其餘功能不受影響。
每個角色要用自己的 token，不可共用主 App 的。

`AI_TEAM_ALLOWED_USERS` 填 Slack **member ID**（`U` 開頭），不是顯示名稱：
點自己的頭像 → 檢視完整個人檔案 → 右側 `⋯` → 複製成員 ID。

## 3. 六個都邀請進頻道

**安裝到 workspace ≠ 加入頻道。** 沒邀請的話主 App 收不到 @，角色 App 發言會得到 `not_in_channel`：

```
/invite @zipe-ai-team
/invite @PM Agent
/invite @Architecture Agent
/invite @Backend Agent
/invite @QA Agent
/invite @Code Reviewer
```

## 4. 重啟並驗證

確認沒有執行中／排隊任務後重啟（stop 被拒絕時不要強制殺服務）：

```bash
bash ~/ai-team/service.sh stop && bash ~/ai-team/service.sh start
bash ~/ai-team/service.sh status
grep -aE "Slack 身分|tag 不到" ~/ai-team/orchestrator.log | tail -2
```

成功時是 `Slack 身分：獨立帳號 [PM ARCHITECTURE BACKEND QA CODE_REVIEW]　共用主 token [無]`。

逐個確認 token 對應到哪個 bot（不會印出 token 本身），五行應對應五個不同的 bot 名稱：

```bash
source ~/ai-team/env.sh
for r in PM ARCHITECTURE BACKEND QA CODE_REVIEW; do
  v="SLACK_BOT_TOKEN_$r"; t="${!v}"
  [ -z "$t" ] && { echo "$r: 未設定"; continue; }
  echo "$r: $(curl -s -X POST https://slack.com/api/auth.test -H "Authorization: Bearer $t" | grep -oE "\"user\":\"[^\"]*\"|\"error\":\"[^\"]*\"")"
done
```

直接 @ 角色的最終驗收見 [直接對角色提出請求](direct-agent-requests.md)。

---

## 第二個以上實例的 App 組

每個服務實例都要有自己的一組 App（不可共用 App-Level Token，原因見
[同一台 VM 多個服務實例](configurable-team.md#同一台-vm-多個服務實例)）。做法是為該實例複製一組 manifest（主 App 取自 `deploy/slack-app-manifest.yaml`、角色取自 `deploy/agent-manifests/*.yaml`），
存放在該實例自己的位置（例如實例目錄或私有 repo，不放進公開 repo），把 App 名稱與顯示名稱加上實例前綴（避免在 Slack 裡與其他實例同名），再依第 1 節逐一建立：

| manifest | Bot Token | App-Level Token |
|---|---|---|
| `main.yaml`（主 App） | `SLACK_BOT_TOKEN` | `SLACK_APP_TOKEN` |
| `pm.yaml` | `SLACK_BOT_TOKEN_PM` | `SLACK_APP_TOKEN_PM` |
| `architecture.yaml` | `SLACK_BOT_TOKEN_ARCHITECTURE` | `SLACK_APP_TOKEN_ARCHITECTURE` |
| `backend.yaml` | `SLACK_BOT_TOKEN_BACKEND` | `SLACK_APP_TOKEN_BACKEND` |
| `qa.yaml` | `SLACK_BOT_TOKEN_QA` | `SLACK_APP_TOKEN_QA` |
| `infrastructure.yaml`（配置有此角色時） | `SLACK_BOT_TOKEN_INFRASTRUCTURE` | `SLACK_APP_TOKEN_INFRASTRUCTURE` |
| `code-review.yaml` | `SLACK_BOT_TOKEN_CODE_REVIEW` | `SLACK_APP_TOKEN_CODE_REVIEW` |

App ID 可直接開 `https://api.slack.com/apps/<App ID>`：Bot Token 在 **OAuth & Permissions**，App-Level Token 在
**Basic Information → App-Level Tokens**（名稱 `socket`、scope `connections:write`）。

- 變數名稱與其他實例相同，但填進**該實例自己的** `env.sh`（例如 `~/ai-team-<實例>/env.sh`），值是這組 App 的 token。
- 分段改派段的 Codex 角色（例如 `builder_codex`）通常不另建 App，Bot Token 留空時由主 App 以原角色名稱代發。
- 這組 App 都要 `/invite` 進該實例的頻道，`AI_TEAM_CHANNEL_ID` 填該頻道 ID；不要把其他實例的 App 留在這個頻道。
- 建立後以 auth.test 確認身分，服務啟動 log 應顯示各角色為獨立帳號、Socket Mode 皆啟動；直接 @ 與流程身分仍要以真實訊息驗證。
- 經驗：從瀏覽器自動化帶入 manifest 時，對 CodeMirror 呼叫 `setValue` 不會更新表單狀態，建立出來的是預設 Demo App
  （Slack 會提示 app 沒有 scope）；要以貼上方式（`replaceSelection(..., 'paste')`）帶入，並在 Review 頁核對 scope 數。
  `?new_app=1&manifest_yaml=` 預填網址曾實測無法開啟建立視窗。

---

## 疑難排解

| 症狀 | 原因與處理 |
|---|---|
| manifest 被拒絕：`long_description` | 選填，但填了就必須 > 173 且 ≤ 4000 字元。現有 manifest 都已符合；自行修改時用 PowerShell `$t.Length` 算字數，不要用 Git Bash `wc -m`（非 UTF-8 locale 下會算成位元組） |
| manifest 其他欄位 | `description` ≤ 140 字元；`display_information.name` ≤ 35；`bot_user.display_name` ≤ 80 且不可含空白或句點 |
| 啟動時 `Failed to connect to the Socket Mode endpoint URL (error: missing_scope)` | App-Level Token 的 scope 不是 `connections:write`，要重新產生 |
| `invalid_auth` | 多半是把 `xapp-` 貼到 Bot Token 欄位，或反過來 |
| 五個角色顯示同一個 bot 名稱 | 同一個 token 貼了多次，用上面的驗證指令逐個確認 |
| @主 App 後完全沒反應、log 也沒動靜 | 主 App 沒被 `/invite` 進頻道，Slackbot 會私下提示「他們不在此頻道中」 |
| Bot 在頻道內、@ 後仍沒反應 | `AI_TEAM_CHANNEL_ID` 設錯，訊息被過濾。`grep -a "忽略非指定頻道" ~/ai-team/orchestrator.log` 會印出實際收到的頻道 ID |
| 重啟後仍顯示「共用主 token」 | 改到的是 `deploy/env.sh.template`（範本），服務讀的是 VM 上的 `~/ai-team/env.sh`；或檔案沒存到 |
| 新增 scope 後沒生效 | 需要 **Reinstall to Workspace**；若 Bot Token 因此更換，也要更新 env.sh |

---

## 角色 App 拿不到什麼權限

- **不能讀頻道內容**：沒有 `channels:history`，只收得到明確 @ 它的那則訊息
- **被 @ 時只做唯讀對話**：不能因此改動任務或核准 PR；明確派工要用「執行」前綴，見 [直接對角色提出請求](direct-agent-requests.md)
- **不能觸發或中止任務**：沒有互動元件，按鈕與流程控制全部在主 App
- **只能發到被邀請進去的頻道**

交接訊息中的 mention 是**通知**，不是**觸發**；實際執行順序由 orchestrator 驅動，
理由見 [互動模型](interaction-model.md)。

---

## 新增配置式角色

以下流程適用於 `AI_TEAM_DEFINITION` 啟用的配置式團隊，例如新增 Infrastructure Agent。
角色能否執行，與是否有獨立 Slack App 是兩件事：沒有角色 App 時仍可執行節點，但由主 App 代發、無法直接 @ 該角色。

**交付要求**：每次新增 Agent 的完成回報都必須附上本節實際套用後的 Slack 操作方式，包括 manifest 路徑、App 建立／安裝、
兩個 token 的環境變數名稱、`/invite`、服務重啟，以及直接 `@` 與正式流程身分的驗證結果或待辦。不可只交付團隊 JSON；
若決定不建立獨立角色 App，也必須明示主 App 代發與不能直接 `@` 的限制。

### 1. 先定義角色與流程責任

在目標團隊 JSON 的 `roles` 新增穩定且不重複的角色 ID，並設定 `name`、`runtime`、`model`、`prompt`、`tools`，以及兩個
**環境變數名稱**（不是 token 值）：

```json
"infrastructure": {
  "name": "Infrastructure Agent",
  "runtime": "codex",
  "model": "gpt-5.6-sol",
  "reasoningEffort": "medium",
  "prompt": "prompts/infrastructure.md",
  "botTokenEnv": "SLACK_BOT_TOKEN_INFRASTRUCTURE",
  "appTokenEnv": "SLACK_APP_TOKEN_INFRASTRUCTURE",
  "tools": ["Read"]
}
```

再新增或調整 `nodes`，讓節點的 `role` 指向新角色，或以 `candidateRoles` 納入自主派工候選（須提供 `capabilities`），
並補齊 prompt、schema、轉移、前置契約與權限政策。上面的唯讀範例不足以承接實作；候選角色須另外具備相符工具。
只增加 Slack App、不在團隊 JSON 加角色與節點，不會讓新角色進入正式流程。完整配置規則見
[配置式專案、角色與工作流程](configurable-team.md)。

### 2. 建立角色 manifest

從 `deploy/agent-manifests/` 複製權限最接近的角色 manifest 為新檔，例如
`deploy/agent-manifests/infrastructure.yaml`，至少改掉：

- `display_information.name` 與 `bot_user.display_name`；名稱須符合 Slack 長度限制。
- `display_information.description`／`long_description`，如實描述角色用途。
- 保留 Socket Mode、`app_mention`、`chat:write`、`app_mentions:read`；角色 App 不加 interactivity。

新增受版控檔案時，同步把檔名與用途加入 `docs-site/agent/project-tree.md`。若這是 repo 內正式支援的角色，也要把 manifest、
token 變數占位與角色名稱加入 `deploy/bootstrap/check-source.sh` 的來源一致性檢查；否則 build 不會替你驗證新角色的資產是否齊全。

依本頁第 1 節用 manifest 建立／安裝 App，取得該 App 自己的 `xoxb-` 與 `xapp-`；不同服務實例不可共用同一個
App-Level Token。

### 3. 在目標實例設定 token

把團隊 JSON 引用的變數加入該實例的 `~/ai-team/env.sh`（權限 600）；token 不可放進 JSON、Git、聊天或 shell 歷史：

```bash
export SLACK_BOT_TOKEN_INFRASTRUCTURE=""
export SLACK_APP_TOKEN_INFRASTRUCTURE=""
```

實際值由使用者本人以編輯器填入。若此角色是 repo 隨附的通用角色，`deploy/env.sh.template` 也要加入空白占位；
只屬於單一部署實例的角色，可只放在該實例的 `env.sh`。`team.env` 原則上放非機密的團隊切換設定，不放 token。

### 4. 邀請、靜態驗證、部署與重啟

1. 在目標頻道執行 `/invite @Infrastructure Agent`。
2. 以 `--validate-team` 驗證團隊 JSON、prompt、schema、節點引用與 repo；它不會驗證 Slack token 或 scope。
3. 執行 `bash deploy/bootstrap/check-source.sh`；若新增了檔案，文件目錄樹與 catalog 也必須一致。
4. 確認沒有執行中／排隊任務，部署 `teams/`、prompt/schema 與必要程式變更後重啟服務。配置修改只影響重啟後建立的新任務；舊任務沿用快照。

### 5. 驗收

- 啟動 log 顯示新角色使用獨立 Slack 帳號，而非共用主 token。
- 在目標頻道直接 `@Infrastructure Agent`，確認唯讀角色對話由正確實例接收。
- 建立隔離測試任務，確認流程真的進入新節點、以新角色身分貼出報告，受阻與續跑路由正常。
- 不把「App 能被 @」當成部署、寫入或發布權限已正確；角色工具、沙箱、外部系統憑證與人工閘門仍須分別驗證。
