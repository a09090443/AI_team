# 僅適用已核實的 #1014 舊快照；不可換成新團隊整份配置。
if .snapshot.revision != "1e66c07b26c5e2264dcdf965ae83d7f0ba80910432d65befd9649492a8c6692c"
   or .node != "triage" or .activeNode != null
   or .workspacePath != "/home/vboxuser/ai-team/workspaces/task-1014"
then error("#1014 原狀態不符，拒絕遷移") else . end
| .snapshot.definition.roles.builder.tools += [
    "Bash(npm --version)", "Bash(npm -v)", "Bash(node --version)",
    "Bash(npm ci)", "Bash(npm install)", "Bash(npm run build)"
  ]
| .snapshot.definition.project.cachePaths += [".npm"]
| .snapshot.definition.project.requiredTools += ["node", "npm"]
| .snapshot.definition.project.buildInstructions += " 文件建置請在 doc-site 執行 npm ci（有 lockfile）或 npm install（無 lockfile），再 npm run build；使用 PATH 上的 npm，不使用 npx 或絕對路徑替代。"
| .context += "\n2026-09-09 管理者修正紀錄（不是測試計畫或 PR 核准）：原 triage 在主 repository 查核而錯誤認定產物遺失；經 Git 確認任務工作區 /home/vboxuser/ai-team/workspaces/task-1014 存在 814c9b1，24 個檔案變更且工作樹乾淨。不得依原 triage 報告重做全部實作或合併回主 repo。請在原任務工作區重新核實，既有實作不代表獨立 QA 通過。已補文件建置 npm ci/install/run build 權限及 .npm 快取。原 SC-23 真人核准要求保留，independentEvidence 是建置收據而非真人核准紀錄，不能以 null 推論無人核准。"
