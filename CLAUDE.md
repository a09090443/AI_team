@AGENTS.md

# Claude Code 專屬規範

## 每個 session 開場

先讀 `.ai/context.md`（當前工作）、`.ai/progress.md`（進度），再動手。
不要重問使用者已經寫在裡面的脈絡。

## 收尾

- 完成一個有意義的工作單位 → 更新 `.ai/progress.md`
- 做出架構或技術選型決策 → 追加 `.ai/decisions.md`（不覆蓋舊條目）
- 工作中斷、卡關、或換手給 Codex → 更新 `.ai/context.md`

## 其他

- 大範圍修改先用 plan mode。
- 改動 runtime／schema／teams 設定後，跑 `bash deploy/bootstrap/check-source.sh` 與 Maven 測試。
- `.ai/` 是給人與 agent 看的交接記錄，不是 orchestrator 執行時讀的設定；
  orchestrator 給下游 agent 的規範仍在 `orchestrator/prompts/`。
