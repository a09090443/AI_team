# 跨專案執行契約

## 目標與範圍

不同專案用配置宣告建置、測試、文件操作；引擎不以專案名稱、npm 或特定角色名稱
決定權限。本版涵蓋配置式流程；legacy 管線維持舊行為，不假稱已套用新契約。
採單一中央工作佇列、每任務固定配置快照，沿用 Java／SQLite／外層 systemd 沙箱。

```text
專案操作契約 → 靜態驗證 → 各階段沙箱預檢 → Agent → 版本化交接
                                          ↓
                          真人計畫核准 → QA → 獨立驗證 → 真人 PR 核准
```

工作區與权限是不同維度：已有任務工作區時，唯讀 PM 仍查該副本，但不能寫入。
工作區失蹤、路徑不符、symlink 或舊 session 指向不同目錄時停止，不退回主 repo。
交接包含工作區、目前 commit、配置版本；不是以 agent 自陳判定產物有無。

## 操作契約

`project.operations` 是選填物件；各操作包括 `nodes`、`directory`、`argv`、`writes`。
目錄與寫入需求均相對於 repo 根目錄。以下只是配置片段，節點及工具須另有宣告：

```json
{
  "operations": {
    "docs-build": {
      "nodes": ["implementation"],
      "directory": "doc-site",
      "argv": ["npm", "run", "build"],
      "writes": ["doc-site/build", "doc-site/.docusaurus", "llms.txt", "llms-full.txt"]
    }
  },
  "requirePlanApproval": true
}
```

同一形式可宣告 Maven、Node 或其他工具；宣告**不會自動增加角色工具權限**。
`requiredTools` 仍列必要執行檔，`cachePaths` 列已核准的快取。
Java＋文件網站與 Node 範例均已加入契約。新專案仍需管理者盤點其必要操作，
本版不會從 README 或模型輸出自動推導、安裝工具、開放權限。

靜態檢查拒絕 shell 表達式、越界路徑、未知節點、唯讀寫入及未宣告工具。
目前 argv 為簡單、無空白／shell 特殊字元的參數；複雜命令不自行降級成 shell。
`writes` 是操作需求，不是新的權限來源，也不代表列出所有執行中副作用。

## 各階段預檢與證據

`OperationPreflight` 在呼叫模型前：

1. 核對角色 CLI 白名單是否包含已宣告指令（未知 runtime 預設拒絕）。
2. 核對操作目錄、可寫路徑、唯讀 pins、快取目錄及 symlink。
3. 在與該角色相同的 systemd 沙箱政策中執行固定唯讀 probe：Git HEAD、工具存在、
   目錄存在，以及 `test -w`。不執行建置、不呼叫模型、不安裝依賴。
4. 保存工作區、HEAD、配置版本、時間、紀錄檔及 SHA256 至 `flow_run.executionContexts`。

probe 有 20 秒 unit 上限與 25 秒用戶端等待上限；失敗傳回具體操作或證據路徑，
流程停止待管理者處理，未自動放寬权限。缺失快取也明確停止，不默默略過。
模型開始前的資訊不等於建置成功、依賴可下載、磁碟空間充足或 CLI 真實操作必然成功。
Claude 全域 deny 規則或版本行為仍可能造成執行時拒絕，必須保留原始失敗證據。
Codex 沒有細粒度 CLI 工具白名單，不能把前置宣告當成命令限制；外層沙箱仍是安全邊界。

未宣告 operations 的歷史快照維持相容，**不宣稱已通過新預檢**。
實際執行與依賴安裝仍由 agent 按既有契約進行，獨立驗證仍使用最終 commit 的新 clone。

## 真人核准與自動測試分離

`project.requirePlanApproval=true` 時，在進入 VERIFICATION 前以及發布驗收檢查時，
要求 `planApprovals` 收據。收據包含計畫節點、完整 plan revision、需求基準、配置版本、
原需求負責人與時間。不是 agent 報告中的 approved 欄位，也不是 independentEvidence。
計畫內容、需求、配置或負責人改變時，既有收據不再適用。
此閘門不禁止 implementation 執行開發所需的單元測試，但禁止未核准就進正式 QA 驗收。

在原 Slack 討論串 @主機器人或角色，使用：

```text
補充 #任務編號 核准測試計畫 計畫節點 plan_revision
```

系統會提示實際節點與完整 digest，必須先審閱該計畫再送出。
只有白名單內的原需求負責人能核准，而且任務必須停止等待人工。
核准只存收據、不排工作；再以一般補充續跑。PR 核准維持另一道獨立閘門。
歷史快照缺少 flag 時維持 false；不能把啟用新服務視為核准舊任務。

## 舊任務配置更新

不使用寫死任務 ID 的維修方式作為日常機制。原負責人在 AWAIT_HUMAN、
無待核准需求變更時，可於原討論串：

```text
補充 #任務編號 檢視配置更新
補充 #任務編號 核准配置更新 預覽所列token
```

預覽比較任務快照與目前服務載入的配置，token 綁定來源、目標與完整目標內容。
只允許工具、快取、建置／測試指引、operations 的能力設定差異，以及同一 runtime 內的角色模型
（2026-09-25 起，例如 `roles.builder.model："sonnet" → "opus"`）；
角色集合、runtime、路由、基準分支、repo、提示／schema、保護路徑與核准政策若改變，
拒絕原地遷移，需另建任務或另行設計受控遷移。大於 5000 字元的差異不截斷求核准。
換模型後下一步一律開新對話，不以新模型接續舊模型的 session。

核准後保留原快照於 migrationHistory、原工作區、需求與計畫；不使用舊 session，
失效舊 QA／review 報告及獨立驗證／核准證據，仍停在等待人工，不自動續跑或發布。
不重設節點重試上限，也不猜測返工路由；若目前節點的前置證據已失效，
續跑會明確停止，不能繞過前置契約。管理者需決定返工或另建任務。

## 設計取捨與後續

採「明確宣告＋失敗停止」而非自動猜測，以較高的專案導入成本換取可追查與不擴權。
目前一個服務載入一個活動專案配置；不同專案可各自配置，不代表單一 Slack 服務已具
多租戶專案選擇器。未實作自動套件管理、Docker/Kubernetes 沙箱或任意命令的語意分析。
當專案量增加時，可再評估多專案路由、共用契約模板、預檢快取（須綁定環境版本），
以及配置遷移的專用管理介面；不提前引入分散式佇列。

## 驗證範圍

程式測試涵蓋兩種技術棧的同一引擎、CLI 白名單、缺工具、快取缺失、越界／symlink、
唯讀拒絕、預檢失敗不呼叫模型、真人核准版本與不自動排隊、配置預覽及遷移歷史。
VM 上以 opt-in `AI_TEAM_PREFLIGHT_SMOKE=true` 實測工具存在、缺工具及唯讀 pin 拒絕；
測試目錄位於隔離建置的 `target/preflight-smoke`，避免 `/tmp` 被 PrivateTmp 隱藏。
這不等於真實 Slack 多專案端到端驗收。
