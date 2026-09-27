# 部署前置：你要做的三件事

> 本文是早期 Slack 設定紀錄。新機唯一入口為 [INSTALL.md](../docs-site/docs/INSTALL.md)，五角色 App 見 [slack-agent-apps.md](../docs-site/docs/slack-agent-apps.md)。`deploy-*.sh` 是歷史單機升級，不能當通用安裝腳本。

這三件事都需要你的身分登入，我做不到。做完之後其餘的我可以接手。

順序無所謂，可以並行。

---

## 1. Slack App（約 10 分鐘）

不要照原文一步步點設定，用 manifest 一次建好。

1. 前往 <https://api.slack.com/apps> → **Create New App** → **From an app manifest**
2. 選擇 workspace
3. 貼上 `slack-app-manifest.yaml` 的全部內容
4. 建立完成後，做兩件事拿 token：

   **App-Level Token**（給 Socket Mode 用）
   - Basic Information → App-Level Tokens → Generate Token and Scopes
   - Name 隨意（例如 `socket`），Scope 選 `connections:write`
   - 產生後複製 `xapp-` 開頭的字串

   **Bot Token**
   - OAuth & Permissions → Install to Workspace → 允許
   - 複製 `xoxb-` 開頭的字串

5. 在要使用的頻道輸入 `/invite @zipe-ai-team`

manifest 已包含的設定：Socket Mode 開啟、訂閱 `app_mention`、互動元件開啟（審批按鈕用）、
scopes 給 `app_mentions:read`、`chat:write`、`chat:write.customize`、`reactions:write`。

> `chat:write.customize` 是讓四個 Agent 各自以自己的名稱與圖示發言用的。
> 若你已經用**沒有這個 scope 的舊版 manifest** 建好 App，到 OAuth & Permissions
> 補上後需要重新安裝到 workspace。詳見 `docs-site/docs/interaction-model.md`。

> 刻意不給 `channels:history`。v0 只處理被明確 @ 提及的訊息，
> 不讀取整個頻道歷史。之後若需要 thread context 再加。

### Manifest 常見錯誤

**`long_description` — Must be more than 173 characters**

這個欄位是**選填**的，但一旦填了就必須**超過 173 個字元**（上限 4000）。初版的中文說明只有約 100 字元，會被拒絕。

兩種解法：

1. 寫長一點（目前 manifest 已是 332 字元，通過）
2. 整段刪掉 —— 選填欄位不填不會有問題

驗證字元數（PowerShell）：

```powershell
$t = "你的 long_description 內容"; $t.Length
```

> 注意不要用 Git Bash 的 `wc -m` 計算中文 —— 在非 UTF-8 locale 下它算的是**位元組**，中文每字 3 位元組，會得到約 3 倍的虛高數字。

**其他欄位限制**

| 欄位 | 限制 |
|---|---|
| `description` | 最多 140 字元 |
| `long_description` | 須 > 173 且 ≤ 4000 字元，選填 |
| `display_information.name` | 最多 35 字元 |
| `bot_user.display_name` | 最多 80 字元，不可含空白或句點 |

---

## 2. VM 上的 Claude Code 登入（約 5 分鐘）

```bash
npm install -g @anthropic-ai/claude-code    # 不要用 sudo
claude                                       # 走完 OAuth 流程，然後離開
claude doctor                                # 確認狀態正常
```

登入用你現有的訂閱帳號。**不要設定 `ANTHROPIC_API_KEY`。**

---

## 3. 環境變數檔（約 5 分鐘）

```bash
mkdir -p ~/ai-team
cp env.sh.template ~/ai-team/env.sh
nano ~/ai-team/env.sh        # 填入 token 與你的 Slack user ID
chmod 600 ~/ai-team/env.sh
```

**token 由你自己填，不要貼給我。** 我只需要知道「填好了」。

`AI_TEAM_ALLOWED_USERS` 要填 Slack **member ID**（`U` 開頭），不是顯示名稱。
取得方式：Slack 中點自己的頭像 → 檢視完整個人檔案 → 右側 `⋯` → 複製成員 ID。

---

# 做完之後：Phase 0

在 VM 上跑一次：

```bash
sudo apt install -y jq
source ~/ai-team/env.sh
bash phase0-verify.sh
```

它會產出 `phase0-report.md`。**把那份報告的內容貼給我。**

這支腳本把 `architecture-review.md` 裡所有關於 CLI 行為的主張變成可觀察的測試 ——
`--permission-mode dontAsk` 的唯讀限制是否真的擋得住寫檔、`--json-schema` 是否真的回傳
`structured_output`、`--resume` 是否真的保有前文、`--bare` 是否真的在訂閱帳號下失敗。

那些主張目前全部來自官方文件，我沒有實測過。報告與文件不符時，要修正的是我的文件。

成本約 9 次短 prompt 的 claude 呼叫，全部在 `/tmp` 的臨時 repo 進行，不碰你的真實專案。

---

# 我需要你告訴我的資訊

| 項目 | 說明 |
|---|---|
| SSH 連線方式 | host / IP、port、username |
| 金鑰是否已設好 | 從這台 Windows 執行 `ssh <user>@<host>` 能否直接登入。**我不能輸入密碼**，必須是金鑰認證 |
| sudo 權限 | 有沒有（裝 apt 套件需要） |
| 目標 repo | 確認就是 `zipe-spring-boot-starters`，以及要不要我 clone |
| Slack workspace | 名稱與要使用的頻道名稱 |
| 前置三件事 | 哪幾件做完了 |

你提到的「Slack 連結」如果是 workspace 邀請連結，那我用不到 —— 我不能建立帳號或登入 Slack。
我與 Slack 的互動全部透過你建立的 App 的 token，在 VM 上進行。
