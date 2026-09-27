# Slack 方案評估

`architecture-review.md` 直接沿用了原文的 Socket Mode 假設，沒有評估方案選擇本身。本文補上。

「Slack 方案」其實是**四個獨立的問題**，其中第一個最重要，而且原文與初版評估都沒問。

---

## A. 要不要自建？官方已經有 Claude Code in Slack

這是應該最先回答的問題。

Anthropic 已有官方的 Slack 整合：在 Slack `@Claude` 提出程式任務，它會自動判斷意圖、在 Claude Code on the web 開一個 session、把進度回報到原 thread，完成後給你「View Session」與「Create PR」按鈕。

**而且 Pro 與 Max 方案就能用** —— 正是你目前的方案。（Team 與 Enterprise 正被 Claude Tag 取代，但 Pro/Max 保留這條路。）

### 與你的情境對照

| 項目 | 官方版 | 影響 |
|---|---|---|
| Repository | **僅支援 GitHub** | `zipe-spring-boot-starters` 在 GitHub 上，**可用** |
| Gitea / Jira | 不支援 | 原文規劃的 Phase 6/7 做不到 |
| 執行位置 | Claude Code on the web 雲端環境 | **碰不到你的 Linux VM、內網、內部資料庫** |
| 角色分工 | 單一 session | 沒有 PM / Architecture / Backend / QA 的階段閘門 |
| Knowledge Graph | 無法掛自訂 MCP | 你最大的資產用不上 |
| PR | 一次一個 | 對 PoC 無影響 |
| 使用場所 | 僅頻道，不支援 DM | 符合團隊協作情境 |
| 額度 | **每人用自己的帳號與方案額度** | 評估報告 I6 的「共用你個人額度」問題直接消失 |
| 建置成本 | 裝一個 app，約 30 分鐘 | 對比自建的 8 個 Phase |

最後一列是關鍵。官方版的額度模型比自建版好：每個 Slack 使用者用自己的 Claude 帳號跑，不會吃掉你的訂閱額度。**但反過來說，沒有 Claude 訂閱的同事就用不了。**

### 官方版也承認 prompt injection 問題

官方文件的警告值得引用：Claude 會取得對話 context 以理解請求，「可能會遵循 context 中其他訊息的指示」，因此只應在可信的 Slack 對話中使用。

這正是評估報告 I5 指出的同一個問題。連官方產品都只能用「請在可信頻道使用」來處理，說明這是架構性限制而非實作瑕疵 —— 你的自建版本必須靠觸發者白名單與人工審批閘門來補，不能寄望有更漂亮的解法。

### 建議：不是二選一，是先裝來當基準線

**先花 30 分鐘把官方版裝起來用一週**，理由有三：

1. 它會直接告訴你「單一 agent + 雲端環境」能滿足多少比例的實際需求。可能是 70%，那自建的價值就要重新評估。
2. 它是你自建版本的**品質與速度基準線**。自建版如果做出來比它慢又比它笨，那就是白做。
3. 如果它就夠用，你省下整個專案。

自建版真正無可取代的只有四件事：

- **多角色分工與階段閘門**（Architecture 先出 ADR、QA 獨立驗證）
- **自訂 Knowledge Graph**
- **Gitea / Jira / 內網資源**
- **完整的流程控制與稽核紀錄**

如果這四項裡你真正需要的只有一兩項，先想清楚能不能用更輕的方式達成。

長期建議是**並行**：官方版做日常小修小補，自建版專注在需要多角色審查、KG 與內網資源的任務。

---

## B. Slack 付費方案

| 方案 | 價格 | App 數量 | 訊息保留 |
|---|---|---|---|
| Free | $0 | **10 個** | **90 天** |
| Pro | 約 US$8.75/人/月（月繳） | 無限 | 無限 |
| Business+ | 約 US$18/人/月 | 無限 | 無限 |

### 先澄清一個容易誤讀的限制

Slack 定價頁寫 Free 方案「僅限 1:1 的外部訊息與會議」，這裡的「會議」指的是 **huddles（語音／視訊通話）**，Free 只能 1:1、30 分鐘。

**這跟組建 agent team 無關。** Free 方案：

- 頻道數量沒有限制
- 頻道成員數沒有限制
- 文字訊息、thread、Block Kit 互動元件（審批按鈕）全部可用

而且 **bot 根本不能加入 huddle** —— Slack app 透過 Events API 收發文字訊息，本架構沒有任何一步需要通話。

### Free 方案真正會擋到本專案的

| 限制 | 影響 |
|---|---|
| **90 天訊息保留，超過一年的資料會被刪除** | **真正的問題**，見下 |
| 10 個 app 安裝上限 | 綽綽有餘（官方 Claude app + 自建 bot = 2 個） |
| 不能建立 User Groups | 小影響，用不了 `@backend-team` 這類群組提及 |
| Canvas 與 List 變唯讀 | 無法在 Slack 內做任務看板；但任務狀態本來就存在 Orchestrator 的資料庫 |
| Guest 帳號停用 | 外部協作者無法觸發，本情境用不到 |
| Workflow Builder 停用 | 不影響 —— 審批按鈕走 Block Kit 互動元件，不是 Workflow Builder |

**Free 方案的真正限制是 90 天訊息保留，不是 app 數量，更不是會議。**

10 個 app 對這個專案綽綽有餘（官方 Claude app 佔 1 個、自建 bot 佔 1 個）。但 90 天保留對「AI 團隊長期維護一個專案」是硬傷：

- 任務討論的歷史脈絡會消失
- Agent 讀取 thread context 時會讀不到被清掉的訊息
- 稽核追溯會斷（雖然你的 Orchestrator 有自己的 log，但 Slack 端的人類討論會不見）

Business+ 的進階 AI 功能與本專案無關，不需要。

**建議**：

- **PoC 階段用 Free 完全足夠** —— 90 天內做完實驗綽綽有餘。
- 決定當成長期工作流程後再升 Pro，主因是訊息保留。
- 若只是你一個人實驗，**Slack Developer Program 的免費 sandbox 是更好的選擇**：完整功能，不用動到正式 workspace，也不佔 app 名額。

---

## C. 接入方式：Socket Mode vs HTTP Events API

| | Socket Mode | HTTP Events API |
|---|---|---|
| 對外暴露 | 不需要 | 需要公開 HTTPS URL |
| 憑證 | 不需要 | 需要 TLS 憑證管理 |
| 防火牆 | 只需對外連線 | 需開孔或反向代理 |
| Token | `xapp-`（`connections:write`）+ `xoxb-` | 僅 `xoxb-` + signing secret |
| 水平擴展 | 較麻煩 | 無狀態，容易 |
| Marketplace 上架 | **不允許** | 允許 |
| 連線上限 | 同時最多 10 條 WebSocket | 不適用 |

**結論：Socket Mode，原文的選擇正確。**

這是原架構裡少數我完全沒有異議的技術選型。理由：

- 你的執行環境是一台自用 Linux VM。HTTP 方案要處理 DDNS、TLS 憑證、防火牆、反向代理，全部是為了一個內部工具而付出的成本。
- Socket Mode 不能上架 Marketplace 的限制對你零影響 —— 這個 bot 只服務自己的 workspace。
- 10 條連線上限對單實例綽綽有餘。

未來若把 orchestrator 搬上雲端並且要跑多實例，再改 HTTP。那時候改的成本也不高，因為 Bolt 兩種模式共用同一套 handler。

---

## D. Bot 型態：app_mention vs Agents/AI apps 容器

Slack 有一個專門的 Agent 容器：分割視窗、頂部導覽入口、文字串流、建議提示。誘人之處是**串流與狀態更新**，正好對應「任務跑好幾分鐘，使用者不知道進度」這個痛點。

但有三個否決點：

1. **狀態與串流功能僅限 DM 中的 agent 容器**，一般頻道 bot 用不到。
2. 需要付費方案（或 Developer Program sandbox）。
3. Workspace guest 無法使用啟用 Agents 功能的 app。

而你的情境是**團隊在頻道裡協作維護一個專案**，需要其他人看得見討論與結果。DM 容器在這裡反而是錯的形狀。

**結論：用 `app_mention`，維持在頻道。**

進度回報用 `chat.postMessage` 先發一則「已收到，任務 #1024 排入佇列」，再用 `chat.update` 反覆編輯同一則訊息更新階段（分析中 → 實作中 → 測試中 → 等待審批）。效果對使用者來說跟串流差不多，實作簡單得多，也不佔用額外的 thread 空間。

若日後要做 `assistant:write` 的 agent 容器，需要訂閱 `message.im`、`app_home_opened`、`app_context_changed` 等事件，是另一套整合，不是加個旗標就好。

---

## 建議組合

| 項目 | 選擇 | 理由 |
|---|---|---|
| **第 0 步** | 先裝官方 Claude in Slack | 建立基準線，可能直接省下整個專案 |
| Slack 方案 | PoC 用 Free 或 Developer sandbox；長期升 Pro | 90 天訊息保留是升級主因 |
| 接入方式 | Socket Mode | 自用 VM 環境的唯一合理選擇 |
| Bot 型態 | `app_mention`，頻道內 | 團隊協作需要可見性 |
| 進度回報 | `chat.postMessage` + `chat.update` | 不需要 agent 容器就能有好體驗 |

這個組合把「第 0 步」放在最前面是刻意的。評估報告的 Phase 0 是「驗證 CLI 契約」，這裡再往前一步：**先驗證你到底需不需要自建**。

---

## 對評估報告的影響

新增這一節後，`architecture-review.md` 第六節的落地順序前面要再插一個階段：

| Phase | 內容 | 驗收標準 |
|---|---|---|
| **-1** | 裝官方 Claude in Slack，實際用一週 | 明確回答：官方版滿足了多少比例的需求？自建版真正要補的是哪幾項？ |
| **0** | 驗證 CLI 契約 | （原有內容） |

如果 Phase -1 的結論是「官方版滿足 80%，剩下的 20% 不值得投入八個 Phase」，那這就是這份評估最有價值的產出。
