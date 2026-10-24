# 每個 Agent 指定模型（legacy）

本文只適用 `AI_TEAM_DEFINITION` 留空的 legacy 五角色模式。配置式模式改在團隊 JSON 的
`roles[].model`／`runtime`／`reasoningEffort` 設定（見 [配置式團隊](configurable-team.md)），下列環境變數不生效。

legacy 五個角色可以各自使用不同的模型。留空表示交給該角色 runtime 的 CLI 決定。

---

## 怎麼設定

`~/ai-team/env.sh`：

```bash
export AI_TEAM_MODEL_PM=""
export AI_TEAM_MODEL_ARCHITECTURE=""
export AI_TEAM_MODEL_BACKEND=""
export AI_TEAM_MODEL_QA=""
export AI_TEAM_MODEL_CODE_REVIEW=""   # 預設 runtime 是 codex，要填 codex 認得的模型名
```

改完重啟。啟動 log 會回報實際生效的值：

```
階段模型：PM Agent = haiku
階段模型：Architecture Agent = (CLI 預設)
階段模型：Backend Agent = (CLI 預設)
階段模型：QA Agent = (CLI 預設)
```

**這行的用途是驗證設定有沒有被讀到。** 設了值卻顯示「CLI 預設」，代表填錯位置
（例如改到 `deploy/env.sh.template` 而不是 VM 上的 `~/ai-team/env.sh`）或沒重啟。
不必等到跑完一個任務、花掉一塊美金才發現。

決定順序：`AI_TEAM_MODEL_<角色>` > `StageSpec` 的預設值 > 不傳 `--model`。
讓環境變數優先，是因為調模型多半是當下的成本／品質取捨，不該每次都要改程式碼重編譯。

---

## 可用的值（claude-code runtime，本機實測）

| 填入 | 實際模型 |
|---|---|
| `sonnet` | `claude-sonnet-5` |
| `opus` | `claude-opus-5` |
| `haiku` | `claude-haiku-4-5` |
| （留空） | `claude-sonnet-5`（CLI 預設） |

完整模型 ID 也可以填。訂閱帳號下三個層級都可用。
換成 codex 或 opencode runtime 時模型名互不通用：codex 收到不認識的名稱**不會拒絕**而是退回 fallback，
opencode 格式是 `provider/model`（見 [多 Runtime 設計](multi-runtime.md)）。

**打錯名稱不會安靜退回預設**（claude-code）—— `claude` 會以結束碼 1 失敗，該階段直接中止。
這是刻意保留的行為：這個專案已經被靜默失敗坑過好幾次，寧可大聲壞掉。

---

## 選擇建議

依實測觀察到的階段特性：

| 角色 | 特性 | 考量 |
|---|---|---|
| PM | 每次約 1 分鐘、US$0.20 | 它決定要不要啟動下游、要不要架構決策。**判斷不穩會讓整條管線走偏**（實測同一個需求，兩次分別判定需要與不需要 Architecture）。成本影響最小，是最值得升級的一段 |
| Architecture | 約 3 分鐘、US$0.48 | 要讀大量既有程式碼並推理相容性，最吃推理品質。實測它能抓出「`HexUtil` 輸出大寫但 `Md5Util` 預設小寫」這種跨檔案的慣例衝突 |
| Backend | 約 5 分鐘、US$0.58–0.88 | 執行最久、成本最高。換強模型時**成本放大最明顯**，但它有 `mvn verify` 這個客觀的正確性檢查兜底 |
| QA | 約 7 分鐘、US$0.60 | 要看出「測試通過但沒測到對的東西」。實測它能發現上游報告與事實不符（Backend 宣稱 `docs_synced: true` 但沒有任何文件變更） |

一個合理的起手式是 **PM 與 Architecture 用 opus、Backend 與 QA 維持 sonnet**：
把預算放在「決策品質影響最深、但執行時間最短」的兩段。

反過來，若目標是壓成本，PM 用 haiku 風險最大 —— 它的判斷錯誤不會當場失敗，
而是讓後面每一段都做錯事。

---

## 稽核會記錄實際用的模型

```
[稽核] task=1004 actor=backend-agent RUNTIME_CALL : Backend Agent 完成，耗時 200 秒，模型 claude-haiku-4-5-20251001, claude-sonnet-5
```

記錄的是**實際使用**而非我們要求的值，兩者不一定相同 ——
`claude` 內部會用小模型做一些輔助工作，所以常常會看到兩個以上的模型。
比較不同輪次的成本與品質時，要看這個而不是設定值。

查詢：

```bash
sqlite3 ~/ai-team/ai-team.db "select task_id, actor, detail, cost_usd from audit_log where action='RUNTIME_CALL' order by id desc limit 10;"
```
