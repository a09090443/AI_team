# Codex 雙層沙箱初始化

## 症狀與原因

Codex 階段在執行任何工具指令前就失敗：

```
bwrap: Can't mkdir .../task-<id>/.agents: Read-only file system
```

這不是測試失敗或 token 不足，而是沙箱初始化失敗。配置式 LIMITED 節點的外層 systemd 沙箱讓工作區根目錄唯讀，
只開放測試與建置目錄；Codex `workspace-write` 的內層沙箱（bwrap）需要為 `.agents`、`.codex` 建立唯讀掛載點，
而這兩個目錄原本不存在，內層又沒有權限建立。

## 系統怎麼處理

`CodexSandboxPreparation` 在呼叫模型前：

1. 在工作區準備 `.agents`、`.codex` 兩個空目錄，既有內容不變。
   拒絕根目錄別名、metadata 是 symlink 或檔案、缺少 `.git` 的假工作區；不建立假的 `.git`、不安裝 skill、不改權限設定。
2. 以相同外層 `SandboxPolicy` 執行 `codex sandbox -P :workspace -- /bin/pwd` 預檢。
   錯誤或 30 秒逾時會保留 `<角色>.codex.preflight.log` 並停止，**不呼叫模型、不退回無沙箱執行**。

正式的 `codex exec -s workspace-write` 設定沒有放寬。實機雙層測試確認測試與 `target` 可寫，
產品目錄、根目錄、metadata 與 `.git` 不可寫。

## 注意

- 預檢成功只證明指令能在沙箱內啟動，不代表 Maven、文件建置、本機網路、Git 寫入或 QA 會通過；
  本機 socket 與文件副本建置見 [本機測試能力](local-test-capabilities.md)。
- 若在新機又看到上述 bwrap 錯誤，代表跑的是舊版或掛載條件不同，必須記錄為阻擋並查明，**不可用放寬沙箱的方式繞過**。
- `codex sandbox` 與 `codex exec` 的參數不對稱，升級 Codex CLI 後要依 [Codex CLI 參數契約](codex-cli-argument-contract.md) 重驗。
  此流程於 0.153.4 首次實測，0.156.1 升級時重驗。
- 既有 Git worktree 的 `.git` 檔案可通過 metadata 檢查，但端對端只驗證過獨立 clone。

官方背景：[沙箱](https://learn.chatgpt.com/docs/sandboxing)、[權限設定](https://learn.chatgpt.com/docs/permissions)。
