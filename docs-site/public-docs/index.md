---
title: AI Team
slug: /intro
---

# AI Team

AI Team 是以 Slack 為介面的 AI 軟體團隊協調器。使用者提出需求，系統依團隊配置安排不同 AI CLI 角色完成需求整理、設計、實作、驗收與審查，再由真人核准建立 GitHub PR。

## 依你的角色開始

:::info[色塊代表什麼]

手冊中的重點用色塊標示：**藍色**是名詞與補充說明，**綠色**是建議做法，**黃色**是容易誤會或需要注意的地方，**紅色**是不可逆或與安全有關的操作。

:::

| 讀者 | 建議路線 |
|---|---|
| 使用者 | [第一個 Slack 任務](quickstart.md) → [操作與角色對話](usage.md) → [故障排查](troubleshooting.md) |
| 管理者 | [安裝與驗證](installation.md) → [Slack 與團隊設定](configuration.md) |
| 開發者 | [架構概要](architecture.md) → [工作流程](workflow.md) → [開發與維護](development.md) |
| AI agent | [AI 閱讀入口](ai-guide.md) → 依任務找到權威文件與程式 |

## 系統解決什麼問題

- 讓任務經過可追溯的需求、驗收、審查與真人閘門。
- 以團隊 JSON 配置角色、節點、權限與驗證方式；每個角色可各自指定 AI CLI（Claude Code、Codex、opencode）與模型。
- 保存任務快照、問題清單、工作區與驗證收據，協助返工與接續。
- 以 runtime 外層沙箱限制執行範圍，並如實回報額度、權限與環境限制。

## 能力與交付邊界

Java 21／Spring Boot 實作協調器，透過 Claude Code、Codex 等 CLI 執行工作。
使用者需由管理者先設定服務、Slack App、帳號登入與目標 repo；不是只安裝 Slack App 就能運作。

:::warning[目前的驗證邊界]

- 尚未完成乾淨 VM 的完整重建驗收。
- opencode 僅唯讀角色對話有 VM 沙箱與真實 Slack 端對端證據，正式寫入階段等用途仍待驗證。
- 程式測試、服務啟動、真實端對端與產品驗收分別判斷；**建立 PR 不代表已合併或部署**。

:::

本網站提供一般使用、管理與架構手冊及公開 AI 索引，排除私人主機識別、憑證與實際部署／交接紀錄。
詳細 repo 文件仍保留在原始碼內，實際工作須核對現行程式與任務快照。
