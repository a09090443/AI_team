# 當前工作（Codex 與 Claude Code 共用）

> 換手前務必更新這份檔案。內容只寫「現在正在做什麼、卡在哪」，
> 長期結論寫 `decisions.md`，已完成的寫 `progress.md`。

最後更新：2026-09-08（Codex）

## 最新交接

Slack 定期回報已於 2026-09-08 22:23 台北時間部署，PID 80214，啟動檢查通過。
備份 progress-backup.oFdYpg；詳見 progress.md 與 docs/slack-progress.md。
尚未執行真實 Slack 五分鐘更新驗收，等待使用者續跑任務。
#1014 為 implementation 的 Claude session limit，但辨識漏掉措辭而停在 AWAIT_HUMAN；
checkpoint 與未提交實作仍保留，額度回報修正尚未實作。下方保留先前交接脈絡。

## 目標

建立 Codex 與 Claude Code 的跨工具交接機制（本檔案所屬的 `.ai/` 架構）。

## 現況

- `AGENTS.md` 為專案規範單一事實來源；`CLAUDE.md` 以 `@AGENTS.md` import 之。
- `.ai/context.md`／`decisions.md`／`progress.md` 建立完成，兩邊 agent 皆需讀寫。
- 專案已 git init 並推上 GitHub（https://github.com/a09090443/AI_team ，private，分支 `main`）。

## 已知阻礙

- 從零重建的乾淨 VM 驗收未完成，詳見 [docs/rebuild-verification.md](../docs/rebuild-verification.md)
  的「尚待完成的驗收」七項。
- source tar 傳至測試 VM 被權限審核拒絕，尚待使用者明確同意。

## 下一步

由使用者決定：是否要把 `.ai/` 的更新義務也寫進 `orchestrator/prompts/`，
讓 orchestrator 派出的下游 agent 也維護目標 repo 的 `.ai/`。
