---
sidebar_position: 7
title: AI 工具設定
description: 各 AI 開發工具如何讀取本專案的規則、skills 與 MCP，以及單一來源的同步方式。
---

# AI 工具設定

本專案讓所有 AI 開發工具（Codex、Claude Code、Cursor、opencode、VS Code Copilot、Gemini CLI）
讀到同一套規則、skills 與 MCP 設定。**只改單一來源**，其他檔案由同步腳本產生。

## 單一來源與產生檔

| 項目 | 單一來源（只改這裡） | 產生／入口檔（不要手改） |
|---|---|---|
| 規則 | `AGENTS.md` | `CLAUDE.md`（只有 `@AGENTS.md` 一行）、`.gemini/settings.json` 的 `context.fileName`、`.vscode/settings.json` 的 `chat.useAgentsMdFile` |
| Skills | `.agents/skills/<名稱>/SKILL.md` | `.claude/skills/<名稱>/`（鏡像） |
| MCP | `.agents/mcp.json` | `.mcp.json`、`.cursor/mcp.json`、`.vscode/mcp.json`、`.gemini/settings.json`、`opencode.json`、`.codex/config.toml` 標記區塊 |

```bash
# 修改 .agents/ 後產生各工具設定（產生檔要一起提交）
node .agents/sync-agent-config.mjs

# 只檢查一致性；docs-site 的 npm run build 前會自動執行
node .agents/sync-agent-config.mjs --check
```

`--check` 會在以下情況失敗：產生檔與來源不一致、`.claude/skills/` 有不在 `.agents/skills/` 的 skill、
`CLAUDE.md` 多了 `@AGENTS.md` 以外的內容、VS Code 未開啟 `chat.useAgentsMdFile`、
SKILL.md 缺少 `name`（須等於目錄名）或 `description`、MCP 項目同時或都沒設定 `command` 與 `url`。

## 各工具讀取位置

「狀態」一欄依本專案原則區分：**已使用**表示本專案實際以該工具工作並讀到規則；
**文件記載**表示依官方文件設定，尚未在本專案實測。

| 工具 | 規則 | Skills | MCP | 狀態 |
|---|---|---|---|---|
| Claude Code | `CLAUDE.md` → `@AGENTS.md` | `.claude/skills/`（鏡像） | `.mcp.json` | 規則：已使用；skills、MCP：文件記載 |
| Codex | `AGENTS.md`（原生） | `.agents/skills/`（原生） | `.codex/config.toml`（僅受信任專案） | 規則：已使用；skills、MCP：文件記載 |
| Cursor | `AGENTS.md` | `.agents/skills/`，另會讀 `.claude/skills/` | `.cursor/mcp.json` | 文件記載 |
| opencode | `AGENTS.md`（與 CLAUDE.md 並存時只讀 AGENTS.md） | `.agents/skills/`，另會讀 `.claude/skills/` | `opencode.json` 的 `mcp` | 文件記載 |
| VS Code Copilot | `AGENTS.md`（需 `chat.useAgentsMdFile`） | 依 VS Code Agent Skills 支援 | `.vscode/mcp.json` | 文件記載 |
| Gemini CLI | `AGENTS.md`（`.gemini/settings.json` 的 `context.fileName`） | 依 Gemini CLI skills 支援 | `.gemini/settings.json` 的 `mcpServers` | 文件記載 |

Cursor 與 opencode 會同時讀到 `.agents/skills/` 與 `.claude/skills/` 的同名 skill；兩者內容由腳本保證一致。

## 新增 skill

1. 建立 `.agents/skills/<名稱>/SKILL.md`，開頭 YAML front matter 必須有 `name`（等於目錄名）與 `description`
   （寫清楚何時使用，工具依此決定是否自動套用）。
2. 需要的腳本或參考檔放在同一目錄。
3. 執行 `node .agents/sync-agent-config.mjs`，提交 `.agents/skills/` 與 `.claude/skills/`。
4. 更新[專案目錄樹](project-tree.md)。

skill 是執行步驟，規則本文仍只寫在 `AGENTS.md`；兩者衝突時以 `AGENTS.md` 為準。

現有 skill：

| 名稱 | 用途 |
|---|---|
| `service-build` | Slack agent 可重用的環境準備、建置、啟動、檢查與修復流程，主機連線獨立配置 |
| `docs-sync` | 依「文件優先」規則開工前找文件、發現缺漏或落差時修正、功能異動後核對文件並驗證 |
| `context-compression` | 長任務的結構化摘要與交接，保留目標、決策、檔案異動、風險及下一步；附評估參考與工具 |
| `sandbox-setup` | 建立／重建共用沙箱 VM、以 `sandbox-onboard.sh` 讓專案接上沙箱、驗證與排錯（本專案自建，見 [共用沙箱環境](/docs/sandbox-environment)） |
| `virtualbox-control` | 以 VBoxManage 管理本機 VirtualBox VM（電源、快照、資源、複製／匯入匯出），以 Vagrant 建立 Ubuntu 開發／測試 VM，新 VM 預設 SSH 金鑰登入 |

2026-10-29 安裝 [a09090443/agent-skills](https://github.com/a09090443/agent-skills/tree/main/virtualbox-control) 的
`virtualbox-control` 原版（上游 commit `3e24062`，只含文件、無腳本），單一來源為 `.agents/skills/virtualbox-control/`。
它是給開發者在本機管理 VM 的工具 skill，不是 orchestrator 部署通道；Agent VM 日常部署仍依 `service-build` 走 SSH 直連。

### 長任務的上下文整理

2026-10-15 安裝社群專案 [Agent Skills for Context Engineering](https://github.com/muratcankoylan/Agent-Skills-for-Context-Engineering/tree/main/skills/context-compression)
的 `context-compression` 原版（含 references、scripts、tests），單一來源為 `.agents/skills/context-compression/`，
Claude Code 鏡像由同步腳本產生。其餘工具沿用上表的原生 skills 讀取方式；安裝與檔案一致性驗證不代表各工具已實際觸發 skill。
可要求 agent「使用 context-compression 整理目前任務並建立交接摘要」。下次載入專案時可發現新 skill；未更新清單的工具請重新開啟工作階段。

`AGENTS.md`「長任務自動整理上下文」要求 agent 自行判斷觸發：長任務階段完成且探索內容累積、
交接／中斷前、重複查找或新舊結論混淆、工具明確提示容量不足時使用；短任務不反覆摘要。
其中**任務交接為強制使用**，涵蓋換 agent、工具、session 與承接新任務；短任務也須提供簡短摘要。
交出方先更新摘要與交接檔，接手方先讀取並核對現場；突發中斷未及整理時，由接手方先重建並核實摘要。
這是共用提示規則，並非計時器或強制程式閘門；各工具是否遵循仍需實際觀察，無 token 遙測時不設定假定百分比。

摘要須依 `AGENTS.md` 使用台灣繁體中文，沿用 `.ai/context.md`（覆寫）、`.ai/progress.md`（超過上限歸檔）、
`.ai/decisions.md`（只追加）的交接分工與保存上限（見 [貢獻流程](contributing.md#開工與收尾)），
保留使用者限制、核准範圍、原始目標、目前版本、未解問題與證據位置，並區分程式測試與真實端對端驗證。
詳細 log 按需讀取；不要把摘要再附上完整歷史而增加負擔。skill 提供整理方法，不會自行刪除 CLI 對話歷史、
切換 session 或啟用 runtime 原生 compaction；也未自動注入 Slack agent 的 `node.skills` 或改動已保存的任務快照。

## 新增 MCP server

在 `.agents/mcp.json` 的 `mcpServers` 加入項目後執行同步腳本：

```json
{
  "mcpServers": {
    "<名稱>": { "command": "npx", "args": ["-y", "<套件>"], "env": { "KEY": "value" } },
    "<遠端名稱>": { "url": "https://example.com/mcp", "headers": { "X-Header": "value" } }
  }
}
```

- 名稱只能用英數、`-`、`_`。stdio 用 `command`／`args`／`env`；http 用 `url`／`headers`。
- 不要寫入憑證值；需要金鑰時使用該工具支援的環境變數讀取方式。
- `.codex/` 不進版控：腳本只替換 `.codex/config.toml` 中 `# >>> agents-sync` 標記區塊，保留個人設定；
  若區塊外已有同名 server，腳本拒絕寫入並回報。其他開發者需各自執行一次同步。
- 目前 `.agents/mcp.json` 沒有共用 server。本機 `.codex/config.toml` 的 `dbx` 是個人設定，未納入共用來源。

## 加入其他工具

先查證該工具的官方文件（規則、skills、MCP 各自讀哪裡、格式為何），擴充 `.agents/sync-agent-config.mjs`
與本頁表格，並標明「文件記載」；未實測不可標成已使用。


## Slack agent 使用 skills

`service-build` 是可攜式系統建置 skill，單一來源 `.agents/skills/service-build/`；同步脚本同時產生 `.claude/skills/service-build/` 與 `orchestrator/teams/skills/service-build/`，不得手改產生檔。
`node.skills` 指定要載入的 SKILL.md／references 相對路徑，TeamCatalog 固定內容到 snapshot.assets，ConfiguredExecutor 在非對話派工時注入提示。
這不依賴 CLI 自動發現 skill，也不擴張工具與主機權限。換機只改獨立主機設定及必要的團隊目標，不重寫共用流程。
