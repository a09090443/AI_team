# 互動模型

三個設計決定與它們的後果：

| 決定 | 選擇 |
|---|---|
| Agent 呈現 | 每個角色以自己的 Slack 身分發言，系統訊息由主 App 發出 |
| 交接 | 交接在同一討論串公開可見，但 mention 只是通知、不是觸發 |
| 人類介入 | 只在需要真人判斷的閘門停下，等待時釋放並行槽 |

設定步驟見 [建立 Slack App](slack-agent-apps.md)；流程與閘門全貌見 [系統全貌](/agent/system-map)。

---

## 一、角色身分與系統身分

五個角色（PM、Architecture、Backend、QA、Code Reviewer）各有自己的 Slack App
（manifest 在 `deploy/agent-manifests/`），因此會出現在成員清單、有自己的頭像，也可以被個別 @。
角色 App 只能發言與接收對自己的 @ 提問（唯讀對話，見 [直接對角色提出請求](direct-agent-requests.md)）。
角色 App 的 manifest 沒有啟用 interactivity，所以**按鈕只能放在系統身分的訊息上**；角色本人貼的階段報告
最後的「完整報告：補充 #N 檢視完整報告」仍是文字（停止訊息另有「看完整報告」按鈕）；
任務排程、按鈕與流程控制全部由該實例的主 App 負責——
每個實例入口只有一個，角色 App 沒有能力觸發或中止任務。

**佇列狀態、錯誤與閘門訊息一律由主 App 發出**，不假裝是某個角色說的；
哪些訊息來自 agent、哪些來自基礎設施，要一眼分得出來。

### 沒有專屬 token 時的退路

角色的 Bot Token 留空時，`SlackNotifier` 退回主 token 加上 `chat:write.customize` 的名稱與圖示覆寫：

| 角色 | username | icon_emoji |
|---|---|---|
| PM | `PM Agent` | `:clipboard:` |
| Architecture | `Architecture Agent` | `:triangular_ruler:` |
| Backend | `Backend Agent` | `:hammer_and_wrench:` |
| QA | `QA Agent` | `:mag:` |
| Code Review | `Code Reviewer` | `:eyes:` |

視覺上幾乎一樣，但這些訊息屬於同一個 bot，不在成員清單，也無法被個別 @。
保留這條退路是為了讓角色 App 還沒建好的環境仍能完整運作。啟動 log 會列出哪些角色用獨立帳號、哪些共用主 token。
有專屬 token 的角色**不會**再覆寫顯示名稱。

**系統身分從不覆寫名稱**（`SlackNotifier.overridesName`）：系統訊息以該實例主 App 本身的名稱與頭像顯示。
2026-10-03 前系統身分被寫死覆寫成預設主 App 名稱，同一台 VM 的第二個實例在自己頻道的狀態卡、
「開成新需求」等系統訊息因此顯示成第一個實例的名字（實際仍是自己的 token 發出）。已修正；
`Identity.SYSTEM` 的名稱改為中性的 `AI Team`，只在取不到 user ID、mention 退成粗體文字時使用。

> `chat.update` 只能由訊息的原作者執行。進度卡由系統身分發出，也由系統身分更新。

---

## 二、交接是看得見的

每個階段完成後，由剛完成的角色以自己的身分在同一討論串 tag 下一棒。
mention 是真的：對方會收到通知、名字會 highlight。角色沒有獨立帳號時，mention 退成粗體名稱。

**但 mention 不是觸發機制。** 執行順序由 orchestrator（配置式流程為 `FlowEngine`）驅動；
bot 互相 tag 會被事件入口排除，不會自行派工。

這是刻意的取捨：做成「mention 觸發下一棒」會把循序流程變成事件驅動，去重、重送、順序保證要在每個 App 各做一次，
而某則 mention 沒送達時，管線會**安靜地停住**而不是報錯。

---

## 三、只在需要真人時停下

真人閘門只設在需要人判斷的地方：測試計畫核准、需求變更核准、PR 核准，以及受阻或用完次數後的決定
（詳見 [系統全貌](/agent/system-map) 的「人工閘門」與 [測試計畫核准按鈕](plan-approval-buttons.md)）。
只有任務提出者（驗收負責人）能核准；按鈕與文字指令同樣受白名單與負責人檢查。

### 等待人類時釋放並行槽

訂閱帳號下全域並行上限建議是 1（`AI_TEAM_MAX_CONCURRENCY`）。如果任務在等人期間仍佔著名額，
使用者離開一下，整個團隊就停擺。所以：

- 並行槽只在實際執行 agent 時持有，不論成敗結束時都會釋放
- 進入等待狀態（`AWAIT_HUMAN`、`AWAIT_PR_APPROVAL`、`WAITING_QUOTA` 等）不佔名額
- 人類回覆後重新排隊取得名額

多個任務可以同時處在「等人看」的狀態，只有實際在燒額度的階段才互斥。

---

## 四、只有 Slack 一個入口

需求只從 Slack `@` 提及進入，不預先抽象多來源。之後要加事件來源（例如 webhook），
只需新增一個 adapter 呼叫同一個提交入口。
