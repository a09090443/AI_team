# 當前工作（Codex 與 Claude Code 共用）

> 換手前務必更新這份檔案。內容只寫「現在正在做什麼、卡在哪」，
> 長期結論寫 `decisions.md`，已完成的寫 `progress.md`。

最後更新：2026-09-07（Claude Code）

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
