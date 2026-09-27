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
| `docs-sync` | 依「文件優先」規則開工前找文件、發現缺漏或落差時修正、功能異動後核對文件並驗證 |

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
