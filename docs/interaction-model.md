# 互動模型

三個已定案的決定，以及它們造成的設計後果。

| 決定 | 選擇 |
|---|---|
| Agent 呈現 | 四個角色各自以自己的身分發言 |
| 人類介入 | **每個階段都可以介入** |
| 觸發來源 | v0 只做 Slack `@` 提及 |

---

## 一、四個身分

**每個角色是 workspace 裡獨立的使用者。** 四個 agent 各有自己的 Slack App
（manifest 在 `deploy/agent-manifests/`），因此會出現在成員清單、有自己的個人檔案
與頭像，也可以被個別 @ 提及。

這四個 App 是**唯寫**的：只有 `chat:write`，不訂閱事件、不接收互動、
不讀取頻道訊息。任務排程、審批按鈕與流程控制全部由主 App `zipe-ai-team` 負責 ——
入口只有一個，agent 沒有能力自己觸發或中止任務。

| 角色 | Slack App | token 環境變數 |
|---|---|---|
| PM | PM Agent | `SLACK_BOT_TOKEN_PM` |
| Architecture | Architecture Agent | `SLACK_BOT_TOKEN_ARCHITECTURE` |
| Backend | Backend Agent | `SLACK_BOT_TOKEN_BACKEND` |
| QA | QA Agent | `SLACK_BOT_TOKEN_QA` |
| 系統訊息（佇列、錯誤、閘門） | zipe-ai-team（主 App） | `SLACK_BOT_TOKEN` |

最後一列刻意區隔：**佇列狀態與錯誤不該假裝是某個角色說的**。
哪些訊息來自 Agent、哪些來自基礎設施，要一眼分得出來。

### 沒有專屬 token 時的退路

角色的 token 留空時，`SlackNotifier` 會退回主 token 加上 `chat:write.customize`
的 `username`／`icon_emoji` 覆寫：

| 角色 | username | icon_emoji |
|---|---|---|
| PM | `PM Agent` | `:clipboard:` |
| Architecture | `Architecture Agent` | `:triangular_ruler:` |
| Backend | `Backend Agent` | `:hammer_and_wrench:` |
| QA | `QA Agent` | `:mag:` |

視覺上幾乎一樣，差別是這些訊息屬於同一個 bot，不會出現在成員清單、
也無法被個別 @ 提及。保留這條退路是為了讓「四個 App 還沒建好」的環境仍然完整可用，
而不是安裝到一半就整條管線壞掉。啟動時的 log 會列出哪些角色用獨立帳號、
哪些共用主 token。

有專屬 token 的角色**不會**再覆寫顯示名稱 —— 覆寫會把真實身分蓋掉。

> `chat.update` 只能由訊息的原作者執行。進度訊息由系統身分發出，
> 也由系統身分更新，不要跨身分更新別人的訊息。

---

## 一之二、交接是看得見的

每個階段完成後，**由剛完成的角色以自己的身分 tag 下一棒**，發在同一個討論串裡：

```
PM Agent          @Architecture Agent 需求已經拆解完成，這次需要先定案架構再動手。
                  受影響模組：`base-spring-boot-starter`。我的分析在上一則。

Architecture Agent @Backend Agent 實作邊界與驗收條件已經定好了，請照著做。
                  邊界外的需求不要自行擴大，回報給我們。

Backend Agent     @QA Agent 實作完成（commit `abc1234`），請驗收。
                  我的自陳僅供參考，麻煩你自己到程式碼裡確認。

QA Agent          @你 我這邊完成了，接下來需要你決定要不要開 PR。
```

mention 是真的：對方會收到通知、名字會 highlight。讀起來像四個同事在交接工作。

**但 mention 不是觸發機制。** 實際的執行順序仍由 `TaskOrchestrator` 依序驅動；
agent 的四個 App 是唯寫的，不訂閱任何事件，收到 tag 也不會自己動起來。

這是刻意的取捨。真正做成「mention 觸發下一棒」需要四個 App 各自開 Socket Mode
與 `app_mentions:read`，管線從循序呼叫變成事件驅動 —— 去重、重送、順序保證要在四個
地方各做一次，而且某則 mention 沒送達時，管線會**安靜地停住**而不是報錯。
用同樣的視覺效果換掉一套已經驗證過的去重、佇列、狀態機與稽核，不划算。

角色沒有獨立帳號時，mention 會退成粗體名稱 —— 讀得懂，但 tag 不到人。
啟動時會警告哪些角色處於這個狀態。

---

## 二、每階段閘門

這是三個決定裡影響最大的一個。狀態機從「跑完四個角色再問一次」變成「每個角色跑完都停下來」。

```
                    ┌─────────────┐
   Slack @ 提及  →  │   QUEUED    │
                    └──────┬──────┘
                           ▼
                    ┌─────────────┐
                    │  PM_RUNNING │
                    └──────┬──────┘
                           ▼
                    ┌─────────────────┐
                    │ PM_AWAIT_APPROVAL│ ←── 人：核准 / 重做 / 中止
                    └──────┬──────────┘
                           ▼
                    ┌──────────────────┐
                    │ ARCH_RUNNING     │
                    └──────┬───────────┘
                           ▼
                    ┌──────────────────────┐
                    │ ARCH_AWAIT_APPROVAL  │ ←── 人
                    └──────┬───────────────┘
                           ▼
                    ┌──────────────────┐
                    │ IMPL_RUNNING     │
                    └──────┬───────────┘
                           ▼
                    ┌──────────────────────┐
                    │ IMPL_AWAIT_APPROVAL  │ ←── 人
                    └──────┬───────────────┘
                           ▼
                    ┌──────────────────┐
                    │ QA_RUNNING       │
                    └──────┬───────────┘
                           ▼
                    ┌──────────────────────┐
                    │ QA_AWAIT_APPROVAL    │ ←── 人：核准後才推分支開 PR
                    └──────┬───────────────┘
                           ▼
                    ┌─────────────┐
                    │  COMPLETED  │
                    └─────────────┘
```

### 關鍵設計點：等待人類時要釋放並行槽

訂閱帳號下全域並行上限是 1。如果任務在 `AWAIT_APPROVAL` 期間仍佔著那個名額，**你去吃個午餐回來，整個團隊停擺兩小時**。

所以：

- 並行槽只在 `*_RUNNING` 狀態持有
- 進入 `*_AWAIT_APPROVAL` 立刻釋放
- 人類核准後重新排隊取得名額

這讓多個任務可以同時處在「等人看」的狀態，只有實際在燒額度的階段才互斥。

### 每則階段結果附三個按鈕

```
🔧 Backend Agent

任務 #1024 實作完成

變更：5 個檔案
測試：132 passed, 0 failed
Commit: abc1234

殘餘風險：
・連線池設定尚未針對 PostgreSQL 調整
・未測試 MySQL 與 PostgreSQL 混用的情境

[ 核准，繼續 ]  [ 要求重做 ]  [ 中止任務 ]
```

**按鈕權限與觸發權限用同一份白名單。** 否則任何人都能核准 Agent 的產出，前面所有權限設計都白費。

### 「要求重做」要能帶回饋

這是每階段閘門真正的價值所在，也是 `--resume` 派上用場的地方：

1. 人按「要求重做」→ 彈出 modal 輸入原因（或要求在 thread 中回覆）
2. Orchestrator 用該階段的 `session_id` 加 `--resume` 續接
3. 把人的回饋當成新的 prompt 送進去

Agent 保有前一輪的完整 context，不需要從頭再來一次。**沒有 session 續接的話，每次重做都是全額重跑** —— 這就是 I3 在這個互動模型下變得關鍵的原因。

重做次數要有上限（建議 3 次），超過就強制轉為 `NEEDS_HUMAN`，避免人與 Agent 在迴圈裡互相消耗額度。

### 一個誠實的提醒

四道閘門對「把 README 的錯字改掉」這種任務會很煩人。

v0 先照你的選擇做滿四道，實際用過再決定要不要優化。之後可以考慮：**依 PM 判定的風險等級決定要不要跳過中間閘門** —— `backward_compatibility_risk` 為 `NONE` 且 `task_type` 為 `DOCS` 時只保留最後一道。

但這是用過之後的調整，不是現在就該加的複雜度。

---

## 三、v0 只做 Slack 觸發

`InboundRequest` 保持單一來源，不預先抽象。

理由：多來源在架構上只是「多一個進佇列的入口」，`TaskQueue.submit()` 的簽章不會因為之後加 Gitea webhook 而改變。現在多抽一層只是增加沒有第二個實作來驗證的介面。

之後要加事件源時，改動範圍就是新增一個 adapter 呼叫同一個 `submit()`。

---

## 對 v0 範圍的影響

原本規劃的 v0 是「PM 一個角色、唯讀、回一段分析」。加上每階段閘門後，v0 應該調整為：

> Slack 提出需求 → PM 分析 → **以 PM Agent 身分回報並附按鈕** → 人按核准 → 顯示「已核准，下一階段尚未實作」

這樣 v0 就跑通了完整骨架：Socket Mode、ack-first、去重、白名單、佇列與並行槽的取得釋放、`AgentRuntime` 介面、JSON Schema、自訂身分發言、**互動按鈕的往返**、狀態機持久化。

按鈕往返這一段必須在 v0 就做，因為它牽涉 Socket Mode 的 interactivity payload 處理，跟事件處理是兩條不同的路徑。留到後面才做等於 v0 沒有驗證到一半的通訊路徑。
