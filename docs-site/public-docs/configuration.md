---
title: Slack 與團隊設定
---

# Slack 與團隊設定

設定分成私人的 `env.sh` 與不含憑證的團隊 JSON；選填 `team.env` 在 env.sh 後載入，同名值以前者為準。
JSON 只填 token 的環境變數名稱，不填 token 值。以下為設定索引，完整欄位見 repo 的 `docs-site/agent/configuration.md`。

## 主 App 與角色 App

1. 在 Slack App 管理介面使用 `deploy/slack-app-manifest.yaml` 建立主 App，安裝至 workspace。
2. 取得 Bot Token 與具 `connections:write` 的 App-Level Token，分別設定 `SLACK_BOT_TOKEN`、`SLACK_APP_TOKEN`。
3. 每個角色使用 `deploy/agent-manifests/` 對應 manifest 建立並安裝獨立 App，使用自己的兩種 token。
4. 邀請主 App 與已建立的角色 App 進目標頻道；安裝到 workspace 不等於已加入頻道。
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
| `CLAUDE_BIN`／`CODEX_BIN` | CLI 執行檔絕對路徑 |
| `AI_TEAM_GITHUB_TOKEN` | 發布時推分支與建立 PR；未設定會在發布階段失敗 |
| `AI_TEAM_INSTANCE` | 多服務實例使用不同名稱，避免 systemd unit 衝突 |
| `AI_TEAM_REPORT_DETAIL` | `brief` 或 `full`，預設 brief |

`env.sh` 的完整範本為 `deploy/env.sh.template`。不同團隊的 repo 變數名稱以 `project.repositoryEnv` 為準。

## 配置式團隊的結構

從 `orchestrator/teams/spring-starters.json` 或 `node-web.json` 開始，連同其相對引用的 prompts／schemas 一起維護。

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

