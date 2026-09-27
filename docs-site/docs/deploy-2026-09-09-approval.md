# 2026-09-09 核准閘門與 #1014 受控恢復

## 已部署

台北時間 19:49，既有 VM 啟動 PID 16368；沙箱隔離、工具鏈與五角色 Slack
Socket Mode 啟動通過。這不是乾淨 VM 建置驗收，也不代表真人核准／QA 端到端通過。

- 建置：`/home/vboxuser/ai-team/approval-build.xcQbUc`
- jar SHA256：`d619f6ef1d0c264ff83b6ce56107f1a8a92c2f01f66d3b59655ec5c585a5d036`
- 新任務配置：`b9430f9df516356beee1127860cea68b978fc3f954996d69b30d6199e5eb956a`
- 備份：`/home/vboxuser/ai-team/approval-backup.gfAnl9`
- 測試：208 項，205 通過、3 既有 opt-in 略過，包含 3 項真實沙箱預檢。
- `check-source.sh` 通過；無 push，仍有尚未提交的先前修改。

上線內容包含跨專案操作契約／預檢、真人測試計畫核准、配置遷移、Slack 報告可讀性。
新增完整逐情境計畫呈現與「檢視測試計畫」；QA 輸入包含系統核准收據，不能以
建置證據代替核准，也不能以補核准直接把舊驗收改成 PASS。

## #1014 的特定授權恢復

這次是使用者在 Codex 明確授權的管理操作，不是讓模型自動遷移或放寬上限。
一般 Slack「配置更新」仍拒絕模型、角色、流程或核准政策變更。

- 原三個 commit `814c9b1`、`c0f1617`、`3bcf2b7` 保留，HEAD 為 `3bcf2b7`，工作樹乾淨。
- 原需求、27 條計畫、實作報告、工作區、分支、所有 visits/transitions 不變。
- `triage` 已用 3/3 次，不重設；受控定位到 `verification`，仍 `AWAIT_HUMAN`。
- QA 改為 `codex / gpt-5.6-sol / medium`；`activeNode=null`，下次使用新 session。
- `requirePlanApproval=true`；核准收據為空，沒有排隊、執行 QA 或代為核准。
- 此舊任務 `operations={}`：啟用工具／工作目錄基本預檢，但未宣稱完整逐操作寫入預檢。
- 舊 QA／PM 待用報告失效，歷史 stage/checkpoint/稽核及原快照留存。
- 新快照：`5195a86a99295510839a901c5edb508743f28d59a6ccdf0f2000fa2b6a86e670`。

使用候選 Java 實際反序列化恢復快照並驗證 `Traceability.planReason`、前置契約
與 `PlanApproval.blockingReason`，確認沒有核准就不能進驗收。
SQL 前後比對所有 task、其他 flow、checkpoint、stage_result 雜湊一致。

## 使用者下一步

完整計畫已由 PM 身分送至原討論串：狀態說明、概覽、27 條情境與核准指令，
共 30 筆 API 成功收據，存於 `approval-build.xcQbUc/plan-delivery.jsonl`。
這證明 Slack 接受訊息，不代表使用者已審閱或核准。

在原討論串審閱完整計畫，若同意，實際 @PM Agent 後輸入：

```text
補充 #1014 核准測試計畫 plan 473251b736eb240a7e11fe9040e1a8f3512bad214e525f1a57d890b3b066d5cb
```

收到「已保存測試計畫核准」後，另發 `補充 #1014 請依已核准計畫重新執行 QA 驗收`。
核准／重送皆不代表核准 PR。需要修改計畫時先提出意見，不可先核准再修改原計畫。
真人 Slack 核准事件到達及 Codex 真實驗收仍待使用者操作；測試不代替這項實測。

## 回復條件

啟動或沙箱自檢失敗時，部署腳本停止服務並回復 jar、配置與 #1014 快照。
若已有任務執行，腳本拒絕強制停止；需人工協調，不能直接覆蓋整份 DB。
後續回復須保留新事件、核准與工作成果，不可盲目套用部署時整份 DB。
此次未設定開機自動啟動。
