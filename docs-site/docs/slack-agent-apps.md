# 建立五個 Agent 的 Slack App

讓 PM、Architecture、Backend、QA、Code Reviewer 成為 workspace 裡獨立的使用者，
交接時才能真正 @ 到對方。

不做也能運作 —— 角色會退回「一個 bot 換名字」的模式，
差別是不會出現在成員清單、**交接訊息裡的 mention 會變成粗體文字而 tag 不到人**。

這五個 App 除了發言，還要能接收 `app_mention` —— 使用者可以在 Slack 直接 @ 某個角色
問問題（唯讀對話）。所以 manifest 含 `chat:write` 與 `app_mentions:read`、訂閱
`app_mention`、開啟 Socket Mode，每個 App 另外需要一組 App-Level Token（`xapp-`）。
設定步驟見 [直接對角色提出請求](direct-agent-requests.md)。

它們仍然沒有互動元件，也沒有能力觸發或中止任務 —— 任務排程、審批按鈕與流程控制
全部在主 App `zipe-ai-team`。

---

## 你需要準備

- 五份 manifest：`deploy/agent-manifests/` 底下的 `pm.yaml`、`architecture.yaml`、
  `backend.yaml`、`qa.yaml`、`code-review.yaml`
- 對該 workspace 安裝 App 的權限
- 大約 12 分鐘（每個 App 步驟相同）

---

## 步驟：以 PM Agent 為例

其餘四個完全一樣，只是換一份 manifest。

### 1. 建立 App

1. 開 <https://api.slack.com/apps>
2. 右上角 **Create New App**
3. 選 **From a manifest**（不是 From scratch）
4. 選擇你的 workspace（`AI-team`），**Next**
5. 把 `deploy/agent-manifests/pm.yaml` 的**全部內容**貼進去

   > 頁面預設可能是 JSON 分頁，記得切到 **YAML**。

6. **Next** → 檢查摘要 → **Create**

### 2. 安裝到 workspace

1. 左側選 **OAuth & Permissions**
2. 最上方 **Install to Workspace** → **Allow**

### 3. 複製 Bot Token

同一頁上方會出現 **Bot User OAuth Token**，格式是 `xoxb-...`。

> ⚠️ **只要 `xoxb-` 開頭這一個。**
> 另一種 `xapp-` 開頭的 App-Level Token 只有主 App `zipe-ai-team` 需要，
> 這幾個角色 App 不需要，也不要填進去。

### 4. （選用）換頭像

**Basic Information** → 往下找 **Display Information** → **App icon**。

emoji 在這裡沒用，要上傳圖片。不換也可以，Slack 會用預設圖示。

### 5. 其餘四個重複一次

| manifest | App 名稱 | 填進哪個環境變數 |
|---|---|---|
| `pm.yaml` | PM Agent | `SLACK_BOT_TOKEN_PM` |
| `architecture.yaml` | Architecture Agent | `SLACK_BOT_TOKEN_ARCHITECTURE` |
| `backend.yaml` | Backend Agent | `SLACK_BOT_TOKEN_BACKEND` |
| `qa.yaml` | QA Agent | `SLACK_BOT_TOKEN_QA` |
| `code-review.yaml` | Code Reviewer | `SLACK_BOT_TOKEN_CODE_REVIEW` |

---

## 步驟：填進設定並邀請進頻道

### 6. 填 token

```bash
ssh -t -p 2222 vboxuser@127.0.0.1 "nano ~/ai-team/env.sh"
```

檔案最後面：

```bash
export SLACK_BOT_TOKEN_PM="xoxb-..."
export SLACK_BOT_TOKEN_ARCHITECTURE="xoxb-..."
export SLACK_BOT_TOKEN_BACKEND="xoxb-..."
export SLACK_BOT_TOKEN_QA="xoxb-..."
export SLACK_BOT_TOKEN_CODE_REVIEW="xoxb-..."
```

`Ctrl+O` 存檔、`Ctrl+X` 離開。

### 7. 五個都邀請進頻道 ⚠️

**安裝到 workspace ≠ 加入頻道。** 沒邀請的話 token 正確也貼不進去，
會得到 `not_in_channel`。在 AI 團隊所在的頻道輸入：

```
/invite @PM Agent
/invite @Architecture Agent
/invite @Backend Agent
/invite @QA Agent
/invite @Code Reviewer
```

### 8. 重啟

```bash
ssh -p 2222 vboxuser@127.0.0.1 'pkill -f "[a]i-team-orchestrator-0.1.0.jar"; sleep 2; (setsid nohup bash ~/ai-team/start.sh > ~/ai-team/orchestrator.log 2>&1 </dev/null &)'
```

---

## 驗證

```bash
ssh -p 2222 vboxuser@127.0.0.1 'grep -aE "Slack 身分|tag 不到" ~/ai-team/orchestrator.log | tail -2'
```

成功：

```
Slack 身分：獨立帳號 [PM ARCHITECTURE BACKEND QA CODE_REVIEW]　共用主 token [無]
```

還沒成功的話會是：

```
Slack 身分：獨立帳號 [無]　共用主 token [PM ARCHITECTURE BACKEND QA CODE_REVIEW]
WARN 共用主 token 的角色 tag 不到 ——⋯
```

要逐個確認 token 對應到哪個 bot（不會印出 token 本身）：

```bash
ssh -p 2222 vboxuser@127.0.0.1 'source ~/ai-team/env.sh
for r in PM ARCHITECTURE BACKEND QA CODE_REVIEW; do
  v="SLACK_BOT_TOKEN_$r"; t="${!v}"
  [ -z "$t" ] && { echo "$r: 未設定"; continue; }
  echo "$r: $(curl -s -X POST https://slack.com/api/auth.test -H "Authorization: Bearer $t" | grep -oE "\"user\":\"[^\"]*\"|\"error\":\"[^\"]*\"")"
done'
```

五行應該對應到五個不同的 bot 名稱。若出現重複，代表有 token 貼錯。

---

## 疑難排解

| 症狀 | 原因與處理 |
|---|---|
| 建立 App 時 manifest 被拒絕 | `long_description` 必須超過 173 字元。五份 manifest 都已符合，若你自己改過內容要注意 |
| `invalid_auth` | 多半是貼到 `xapp-` 開頭的 App-Level Token。要 `xoxb-` 開頭那個 |
| 五個角色顯示同一個 bot 名稱 | 同一個 token 貼了多次。用上面的驗證指令逐個確認 |
| 訊息貼不出來、log 出現 `not_in_channel` | 該 App 沒被 `/invite` 進頻道。安裝到 workspace 不等於加入頻道 |
| 重啟後仍顯示「共用主 token」 | env.sh 沒存到，或改到了 `deploy/env.sh.template`（那是範本，不是服務讀的檔案）。服務讀的是 VM 上的 `~/ai-team/env.sh` |
| 交接訊息裡是粗體名稱而不是藍色 mention | 該角色沒有獨立 token。mention 需要真正的 bot user ID，名稱覆寫出來的假身分沒有 |

---

## 這五個 App 拿不到什麼權限

值得知道邊界在哪：

- **不能讀頻道內容** —— 沒有 `channels:history`，只收得到明確 @ 它的那則訊息
- **只接收 `app_mention`** —— 被 @ 到會以唯讀對話回答，不能因此改動任務或核准 PR
- **不能觸發或中止任務** —— 沒有互動元件，按鈕與流程控制全部在主 App
- **發訊息只能發到被邀請進去的頻道**

交接訊息中的 mention 是**通知**，不是**觸發**。實際的執行順序由 orchestrator 驅動，
理由見 `interaction-model.md` 的「交接是看得見的」一節。
# 2026-09-06 更新

以下保留原唯寫 App 的建置紀錄。直接 @角色的新版設定請改看
[直接對角色提出請求](direct-agent-requests.md)；目前 manifest 已包含接收事件所需的 scope 與 Socket Mode。
只更新 manifest 不代表功能已啟用，仍需重新授權、各角色 App Token 與新版服務部署。
