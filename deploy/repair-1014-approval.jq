if .node != "triage" or .activeNode != null or .visits.triage != 3
  or .snapshot.revision != "8eb98b02910c72909f7592e4a9926f242e4856665cb0e5cf990f473844c3d02a"
  or (.receipts.plan.scenarios | length) != 27
then error("#1014 現場已變更，拒絕遷移") else . end
| .migrationHistory = ((.migrationHistory // []) + [.snapshot])
| .snapshot.definition.project.requirePlanApproval = true
| .snapshot.definition.project.operations = {}
| .snapshot.definition.roles.tester.runtime = "codex"
| .snapshot.definition.roles.tester.model = "gpt-5.6-sol"
| .snapshot.definition.roles.tester.reasoningEffort = "medium"
| .node = "verification"
| .activeNode = null
| .approvalHead = null
| .independentEvidence = null
| .planApprovals = {}
| .executionContexts = {}
| del(.receipts.verification, .receipts.review, .receipts.triage, .heads.verification, .heads.review)
| .context += "\n2026-09-09 管理者依使用者授權受控恢復：保留 814c9b1/c0f1617/3bcf2b7 原工作區及需求、計畫，不重做實作。原 triage 已用滿 3 次，不重設次數；改定位 verification 並啟用真人測試計畫核准前置閘門。QA 改為 Codex gpt-5.6-sol medium，重新開始 session。尚未核准測試計畫、未續跑、未核准 PR。舊 QA 結果保留於歷史紀錄，不得在補核准後直接改成 PASS；核准後重新驗收目前 HEAD。"
