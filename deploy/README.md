# deploy/

新機從零建置的唯一入口是 [INSTALL.md](../docs-site/docs/INSTALL.md)，本目錄只放它用到的檔案與既有 VM 的受控部署腳本。

| 路徑 | 用途 |
|---|---|
| `bootstrap/` | 從零建置工具（工具鏈、安裝、前置檢查、啟停、稽核、備份還原）。新機只用這裡 |
| `env.sh.template` | 環境變數範本，複製為 VM 上的 `~/ai-team/env.sh`（權限 600）；token 由使用者本人填 |
| `slack-app-manifest.yaml` | 主 App `zipe-ai-team` 的 manifest |
| `agent-manifests/` | 五個角色 App 的 manifest；建立步驟見 [建立 Slack App](../docs-site/docs/slack-agent-apps.md) |
| `deploy-orchestrator.sh` | 既有 VM 的通用受控部署：換上隔離目錄建好的 JAR、`src/main`、`teams`，前後比對任務資料，失敗自動回復 |
| `deploy-jar-safe.sh` | 既有 VM 只換 JAR 的受控部署，啟動失敗自動回復 |
| `engineering-v2-team.env` | 既有 VM 的 `team.env`（只指定 `AI_TEAM_DEFINITION`，不含 token） |
| `test-maven-settings.xml` | 測試用空白 Maven settings |

兩支部署腳本都要求沒有執行中或排隊的任務，候選 JAR 必須在隔離目錄建置，不可在服務的 `target/` 建置。
不要再新增只為單次部署寫死任務編號或日期的腳本。
