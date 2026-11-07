---
title: Slack 與團隊設定
---

# Slack 與團隊設定

設定分成私人的 `env.sh` 與不含憑證的團隊 JSON；選填 `team.env` 在 env.sh 後載入，同名值以 `team.env` 為準。
JSON 只填 token 的環境變數名稱，不填 token 值。以下為設定索引，完整欄位見 repo 的 `docs-site/agent/configuration.md`。

## 主 App 與角色 App

1. 在 [Slack App 管理介面](https://api.slack.com/apps)按「Create New App」，選「Paste a manifest」，貼上 `deploy/slack-app-manifest.yaml` 的內容建立主 App，再安裝至 workspace。

   ![Create new app 對話框：選 From scratch 下的 Paste a manifest](./img/slack-create-app.png)

2. 取得兩種 token，分別設定 `SLACK_BOT_TOKEN`、`SLACK_APP_TOKEN`：
   - Bot Token：左側選「OAuth & Permissions」，複製 `xoxb-` 開頭的「Bot User OAuth Token」。

     ![OAuth & Permissions 頁的 Bot User OAuth Token（值已遮蔽）](./img/slack-bot-token.png)

   - App-Level Token：左側選「Basic Information」，在「App-Level Tokens」按「Generate Token and Scopes」，加入 `connections:write` 後產生 `xapp-` 開頭的 token。

     ![Basic Information 頁的 App-Level Tokens 區塊，scope 為 connections:write](./img/slack-app-level-token.png)

   token 只放進 `env.sh`，不要截圖、貼到聊天或提交到 repo。
3. 每個角色使用 `deploy/agent-manifests/` 對應 manifest 建立並安裝獨立 App，使用自己的兩種 token。
4. 邀請主 App 與已建立的角色 App 進目標頻道（在頻道輸入 `/invite @App名稱`）；安裝到 workspace 不等於已加入頻道。
5. 確認沒有執行中或排隊任務後重啟服務，逐一實際 @角色並驗證流程發言身分。

預設角色的環境變數後綴為 `PM`、`ARCHITECTURE`、`BACKEND`、`QA`、`CODE_REVIEW`。
例如 QA 使用 `SLACK_BOT_TOKEN_QA` 與 `SLACK_APP_TOKEN_QA`。
Bot Token 控制發言身分；App-Level Token 用於 Socket Mode 與直接 mention。缺 Bot Token 退回主 App 代發，缺 App-Level Token 無法直接 @角色。
不要共用主 App 的 App-Level Token。只設角色 App-Level Token 卻缺專屬 Bot Token 時，服務會拒絕啟用角色連線。

新增配置式角色時，在角色設定指定 `botTokenEnv`／`appTokenEnv`，建立對應 manifest，完成安裝、token、邀請、重啟與實際驗證；
也可刻意不建獨立 App，但需讓使用者知道由主 App 代發、不能直接 @。

## 必要設定與用途

| 設定 | 說明 |
|---|---|
| `AI_TEAM_ALLOWED_USERS` | 逗號分隔的 Slack member ID，不是顯示名稱 |
| `AI_TEAM_CHANNEL_ID` | 唯一允許操作的頻道 ID，不支援 DM |
| `AI_TEAM_HOME` | 實際安裝目錄 |
| `AI_TEAM_REPO` | 本機目標 repo；配置式專案另由 `repositoryEnv` 指定來源變數 |
| `AI_TEAM_DEFINITION` | 團隊 JSON 的絕對路徑；留空啟用 legacy 模式 |
| `CLAUDE_BIN`／`CODEX_BIN`／`OPENCODE_BIN` | CLI 執行檔絕對路徑；Java 固定要求 Claude，其餘依角色 runtime 檢查；bootstrap preflight 固定要求 Claude 與 Codex，opencode 路徑非空時也檢查 |
| `AI_TEAM_GITHUB_TOKEN` | 發布時推分支與建立 PR；未設定會在發布階段失敗 |
| `AI_TEAM_INSTANCE` | 多服務實例使用不同名稱，避免 systemd unit 衝突 |
| `AI_TEAM_REPORT_DETAIL` | `brief` 或 `full`，預設 brief |

`env.sh` 的完整範本為 `deploy/env.sh.template`。不同團隊的 repo 變數名稱以 `project.repositoryEnv` 為準。

## 配置式團隊的結構

從內附的 `orchestrator/teams/node-web.json` 範例開始，連同其相對引用的 prompts／schemas 一起維護。

| 區塊 | 負責內容 |
|---|---|
| `project` | repo、基準分支、工具鏈、快取、固定操作與獨立驗證指令 |
| `roles` | 角色名稱、runtime、模型、提示、工具權限與 Slack token 變數名稱 |
| `entry`／`nodes` | 起始節點、依賴、路由、角色與輸出契約 |
| `maxTransitions` | 流程轉移上限；與節點重跑、受控交接上限分開 |

`project.operations` 宣告允許的 argv、節點、目錄及寫入範圍；宣告本身不會增加沙箱權限。
`project.verification.commands` 是系統獨立驗證使用的固定指令；agent 不能以自己宣稱測試成功取代收據。
角色可用模型名依 runtime 而異，不可直接互換。

儲存後先驗證：

```bash
java -jar "$HOME/ai-team/orchestrator/target/ai-team-orchestrator-0.1.0.jar" --validate-team "$HOME/ai-team/teams/node-web.json"
```

這只驗證配置，不代表目標 repo、工具或真實流程已驗收。確認後設定 AI_TEAM_DEFINITION 並重啟，新任務才載入新快照。
舊任務使用受控遷移或承接，詳見[故障排查](troubleshooting.md)。

## 每個角色各自選 AI CLI 與模型

每個角色在 `roles` 中各自指定使用哪個 AI CLI（`runtime`）與模型，同一團隊可以混用。
例如 PM 用 Claude Code 的 opus 拆解需求、Backend 用 sonnet 實作、QA 改用 Codex 驗收：

```json
"roles": {
  "coordinator": { "name": "PM Agent",      "runtime": "claude-code", "model": "opus" },
  "builder":     { "name": "Backend Agent", "runtime": "claude-code", "model": "sonnet" },
  "tester":      { "name": "QA Agent",      "runtime": "codex",       "model": "<codex 模型名>", "reasoningEffort": "medium" }
}
```

範例只列相關欄位；完整角色還需要 `prompt`、`tools` 與 token 變數名稱，可從 `orchestrator/teams/node-web.json` 複製後修改。

| 欄位 | 必填 | 說明 |
|---|---|---|
| `runtime` | 是 | `claude-code`、`codex` 或 `opencode`；對應的 `CLAUDE_BIN`／`CODEX_BIN`／`OPENCODE_BIN` 必須可執行 |
| `model` | 否 | 該 CLI 認得的模型名；留空交給 CLI 預設。三家格式不通用，opencode 為 `provider/model` |
| `reasoningEffort` | 否 | 推理強度 `low`／`medium`／`high`／`xhigh`，目前只支援 `codex`；其他 runtime 填寫會驗證失敗 |

- 選擇依據：拆解需求與審查適合推理較強的模型；照計畫實作與重複驗證可用較快、較省額度的模型；
  驗收與實作用不同 runtime，可降低同一模型自己驗自己的盲點。
- 各 CLI 的額度分開計算；某個 runtime 用盡時，輪到使用它的角色才會讓任務停在 `WAITING_QUOTA`。
- opencode 目前只實測過唯讀角色對話，寫入工作區的正式階段尚未驗證，正式流程建議先用 Claude Code 或 Codex。
- 名稱打錯時，Claude Code 會直接失敗；Codex 可能退回預設模型而不報錯，改完要看實際回報確認。
- 討論串的階段進度訊息會顯示實際使用的角色、runtime、模型與推理強度，例如
  `QA Agent／codex／模型：…／推理：medium`；未指定模型時顯示「CLI 預設」。
- 修改後先用上方 `--validate-team` 驗證，再重啟服務；只有新任務會套用，既有任務沿用建立時的快照。
