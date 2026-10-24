---
name: docs-sync
description: 在 AI Team 專案中開始任何工作、回答專案問題、或完成任何程式／配置／部署修改時使用。依 AGENTS.md「文件優先」規則找出並閱讀相關文件，發現文件缺漏或與程式不符時補寫或修正文件，最後以 docs-site build 驗證。
---

# 文件優先核對流程

規則本文在 repo 根目錄 `AGENTS.md` 的「文件優先（強制）」；本 skill 是執行步驟，兩者衝突時以 AGENTS.md 為準。

## 1. 開工：先讀文件

1. 讀本機 `.ai/context.md`、`.ai/progress.md`（不進版控；不存在時依 AGENTS.md 建立）。
2. 讀 `docs-site/agent/index.md` 的「依任務找文件」，找出本次任務對應的文件。
3. 需要找檔案時查 `docs-site/agent/project-tree.md`。
4. 查 `docs-site/catalog.mjs` 確認文件狀態：`current` 可據以行動、`record`／`decision` 是證據與理由、`historical` 不可照做。
5. 讀完文件再讀程式；讀程式時同時核對文件說法是否仍然成立。

## 2. 發現缺漏或落差：當次修正

- 程式有、文件查不到：補寫到主題最接近的 `current` 文件；沒有合適文件就新增 `docs-site/docs/<主題>.md`，
  並在 `docs-site/catalog.mjs` 登錄（分類、狀態、summary、readWhen、code）。
- 文件與程式不符：以程式實際行為修正文件。若判斷是程式錯誤，先向使用者回報並確認，文件標明目前行為與已知問題。

## 3. 功能異動後：找出受影響文件

以被修改的檔案、類別、設定鍵、環境變數、Slack 指令、按鈕文字、狀態值為關鍵字：

```bash
# catalog 中 code 欄位對應到被改檔案的文件
grep -n "<被改的路徑或類別>" docs-site/catalog.mjs
# 文件內文引用
grep -rn "<關鍵字>" docs-site/docs docs-site/agent
```

- 使用者操作改變 → 檢查 `docs-site/agent/operations.md`
- 設定欄位或環境變數改變 → 檢查 `docs-site/agent/configuration.md`
- 流程、狀態或程式碼位置改變 → 檢查 `docs-site/agent/system-map.md`
- 新增、搬移、刪除檔案 → 更新 `docs-site/agent/project-tree.md`（附一句用途）

`current` 文件直接改正；`record`／`decision`／`historical` 文件不改寫證據，改為追加帶日期段落，必要時調整 catalog 狀態。

## 4. 驗證與回報

```bash
cd docs-site && npm run build
```

build 前會檢查文件目錄、專案目錄樹與 AI 工具設定一致性，並檢查斷鏈。通過後在回報與 `.ai/progress.md`
列出：核對過的文件、補寫的文件、修正的文件（無需修改時也寫明核對過哪些）。
