# Orchestrator 建置、部署與運維手冊

> 本文保留早期部署紀錄。新機以 [INSTALL.md](INSTALL.md) 與 `deploy/bootstrap/` 為準；目前應跑完整 Maven 測試，不以本文舊測試數作驗收基準。

承接 `vm-setup-manual.md`（環境）與 `deploy/README.md`（Slack App）。本文涵蓋 orchestrator 本身。

疑難排解章節同樣全部來自真實踩到的坑。

---

## 一、專案結構

```
~/ai-team/
├── env.sh                    # 權限 600，token 在這裡，不進版控
├── start.sh                  # 啟動腳本（載入 env 後執行 jar）
├── orchestrator/
│   ├── pom.xml
│   ├── src/main/java/com/zipe/aiteam/
│   │   ├── AiTeamApplication.java
│   │   ├── config/AiTeamProperties.java      # 啟動時驗證設定
│   │   ├── runtime/AgentRuntime.java         # 廠商無關介面
│   │   ├── runtime/ClaudeCodeRuntime.java    # Claude Code adapter
│   │   ├── agent/PmAgent.java
│   │   ├── task/Task.java                    # 狀態機定義
│   │   ├── task/TaskOrchestrator.java        # 佇列、並行槽、審批閘門
│   │   ├── slack/SlackListener.java          # ack-first 進入點
│   │   ├── slack/SlackNotifier.java          # 自訂身分發言
│   │   └── slack/SlackBlocks.java            # Block Kit 版面
│   └── src/main/resources/application.yml
├── prompts/pm.md             # --append-system-prompt-file 的內容
├── schemas/task-schema.json  # --json-schema 的內容
├── logs/<taskId>/            # 每個任務的 Claude stderr
├── workspaces/               # Backend 的隔離工作區（尚未使用）
└── zipe-spring-boot-starters/
```

**角色定義與 schema 刻意放在 jar 之外。** 調整提示詞不需要重新編譯，改完重啟即可。

---

## 二、相依套件版本

查證現行版本，不要照抄舊文件：

```bash
for q in "g:org.springframework.boot+AND+a:spring-boot-starter-parent" "g:com.slack.api+AND+a:bolt-socket-mode" "g:org.glassfish.tyrus.bundles+AND+a:tyrus-standalone-client"; do
  printf '%-45s ' "${q##*a:}"
  curl -sS --max-time 25 "https://search.maven.org/solrsearch/select?q=$q&core=gav&rows=3&wt=json" | jq -r '[.response.docs[].v] | join(", ")'
done
```

本次採用：Spring Boot **3.5.3**、bolt-socket-mode **1.45.3**、tyrus-standalone-client **1.22**。

> 刻意**不用** `spring-boot-starter-web`。Socket Mode 走對外 WebSocket，不需要內建 HTTP 伺服器，
> `application.yml` 中設 `spring.main.web-application-type: none`。

---

## 三、建置

```bash
export JAVA_HOME=$HOME/.local/java
export PATH=$JAVA_HOME/bin:$HOME/.local/maven/bin:$PATH
cd ~/ai-team/orchestrator
mvn -B package
```

成功時輸出 `BUILD SUCCESS` 與 `target/ai-team-orchestrator-0.1.0.jar`。

建置會跑 `WorkspaceManagerTest`（10 項）。這些測試會實際執行 git 指令，
所以 VM 上必須有 `git`（本來就有）。趕時間可以加 `-DskipTests`，
但 `WorkspaceManager` 會刪目錄，跳過測試前先想清楚。

NAT 網路下首次建置（含下載約 100 MB 相依）約 **2 分鐘**；橋接 Wi-Fi 下要一小時以上（見 `vm-setup-manual.md` T10）。

---

## 四、啟動與停止

`~/ai-team/start.sh`：

```bash
#!/usr/bin/env bash
export JAVA_HOME="$HOME/.local/java"
export PATH="$JAVA_HOME/bin:$HOME/.local/node/bin:$HOME/.local/maven/bin:$PATH"
set -a; source "$HOME/ai-team/env.sh"; set +a
exec java -jar "$HOME/ai-team/orchestrator/target/ai-team-orchestrator-0.1.0.jar"
```

`set -a` 讓 `source` 進來的變數自動 export，Spring 的 `@Value` 才讀得到。

**背景常駐啟動**（不要直接在終端機跑，見 B-T5）：

```bash
setsid nohup bash ~/ai-team/start.sh > ~/ai-team/orchestrator.log 2>&1 </dev/null &
```

**停止**：

```bash
pkill -f "[a]i-team-orchestrator-0.1.0.jar"   # 中括號必要：透過 ssh 執行時，沒有它會殺掉自己的 session
```

**確認運作中**：


```bash
PID=$(pgrep -f "[a]i-team-orchestrator-0.1.0.jar" | head -1)

ss -tnp | grep "pid=$PID"
```
**判準：至少有一條 ESTAB 到 `:443`。連線「數量」不是可靠的判準。**

實測四分鐘取樣（每 15 秒一次）：前兩次是 2，之後 14 次連續都是 1。
Socket Mode 會週期性輪替連線，交接期間新舊並存，所以數量在 1 與 2 之間跳動。

先前文件寫死「應為 2」，我一度改成「應為 1」，兩個都不對 ——
**1 是穩態，2 是重連交接中，兩種都健康**。任務執行中還會多一條對 Anthropic API
的連線，那是第三種情況。照固定數字判斷，遲早會把健康的服務誤判成壞掉。

這仍然比看 log 可靠（log 可能是上一次執行留下的），但要看對端而不是數量。
要確定 bot 本身沒問題，用這個唯讀呼叫（不會送出任何訊息）：

```bash
source ~/ai-team/env.sh
curl -s -X POST https://slack.com/api/auth.test \
     -H "Authorization: Bearer $SLACK_BOT_TOKEN"
# 預期 "ok":true
```

---

## 五、健康檢查

```bash
ssh -p 2222 vboxuser@127.0.0.1 'bash -lc "
PID=\$(pgrep -f \"[a]i-team-orchestrator-0.1.0.jar\" | head -1)
echo \"行程        : \${PID:-未執行}\"
[ -n \"\$PID\" ] && echo \"Slack 連線  : \$(ss -tnp 2>/dev/null | grep -c pid=\$PID)（1~2 都正常，0 才是問題）\"
echo \"claude      : \$(claude --version 2>&1 | head -1)\"
grep -aE \"Started AiTeam|沙箱|啟動清理|設定檢查|ERROR\" ~/ai-team/orchestrator.log | tail -5
"'
```

---

## 六、疑難排解

### B-T1. App-Level Token 的 scope 選錯

**症狀**

```
java.lang.IllegalStateException: Failed to connect to the Socket Mode endpoint URL (error: missing_scope)
```

**診斷**（不會印出 token）

```bash
set -a; source ~/ai-team/env.sh; set +a
curl -sS -X POST -H "Authorization: Bearer $SLACK_APP_TOKEN" \
  https://slack.com/api/apps.connections.open | jq -r '"ok=\(.ok) error=\(.error // "none") needed=\(.needed // "-") provided=\(.provided // "-")"'
```

本次輸出：

```
ok=false error=missing_scope needed=connections:write provided=app_configurations:write
```

**原因**

建立 App-Level Token 時選成 `app_configurations:write`。Socket Mode 需要的是 **`connections:write`**。

**解法**

**scope 無法事後修改，必須重新產一個 token。** Basic Information → App-Level Tokens → Generate Token and Scopes → 選 `connections:write`。

順便驗證 Bot Token 與其 scope：

```bash
curl -sS -D - -o /dev/null -H "Authorization: Bearer $SLACK_BOT_TOKEN" \
  https://slack.com/api/auth.test | grep -i '^x-oauth-scopes:'
```

應包含 `app_mentions:read`、`chat:write`、`chat:write.customize`、`reactions:write`。

---

### B-T2. Bot 沒有加入頻道

**症狀**

Slackbot 私下回覆：「你提及 @zipe-ai-team，但他們不在此頻道中。」Orchestrator 的 log 完全沒有動靜。

**原因**

把 App 安裝到 workspace **不等於**把 bot 加進頻道。

**解法**

點 Slackbot 訊息裡的「新增提及的使用者」，或在頻道輸入：

```
/invite @zipe-ai-team
```

---

### B-T3. 頻道 ID 設錯會靜默失敗

**症狀**

Bot 已在頻道中，提及後完全沒反應，Slack 也沒有錯誤。

**原因**

`AI_TEAM_CHANNEL_ID` 與實際頻道不符，訊息被程式的頻道過濾丟棄。

**診斷**

程式在丟棄時會記錄實際收到的頻道 ID：

```bash
grep -a "忽略非指定頻道" ~/ai-team/orchestrator.log
```

log 會直接給出正確的值，把它填回 `env.sh` 即可。

> 因為 scope 刻意最小化（沒給 `channels:read`），無法用 API 查詢頻道資訊 ——
> `conversations.info` 與 `users.conversations` 都會回 `missing_scope`。這是預期的取捨，
> 用 log 診斷即可，不需要為此擴大權限。

**或者直接留空。** 真正的權限控管是 `AI_TEAM_ALLOWED_USERS`，頻道限制只是額外一層，設錯的症狀又特別難查。

---

### B-T4. `dependency:go-offline` 抓不齊，不要接著用 `-o`

**症狀**

```
[ERROR] Cannot access central in offline mode and the artifact
        io.micrometer:micrometer-observation:jar:1.15.1 has not been downloaded from it before.
```

**原因**

`mvn dependency:go-offline` 是**盡力而為**，不保證抓齊所有傳遞相依。本次漏了 micrometer、gson、kotlin-stdlib 等四個。後續用 `-o`（離線）建置就直接失敗。

**解法**

`go-offline` 只當成「預熱快取」，實際建置**不要加 `-o`**：

```bash
mvn -B package      # 缺什麼會自動補下載
```

---

### B-T5. 在終端機直接執行，斷線就結束

**症狀**

`bash ~/ai-team/start.sh` 跑起來一切正常，SSH 斷線後服務消失。而且輸出在終端機，`orchestrator.log` 還停留在上一次的內容，看 log 會誤判。

**解法**

```bash
setsid nohup bash ~/ai-team/start.sh > ~/ai-team/orchestrator.log 2>&1 </dev/null &
```

`setsid` 脫離 session、`</dev/null` 切斷 stdin，兩者缺一不可。

**判斷服務是否真的活著，看連線數而不是 log**：

```bash
PID=$(pgrep -f "[a]i-team-orchestrator-0.1.0.jar" | head -1)
ss -tnp | grep "pid=$PID"       # 至少一條 ESTAB 到 :443（數量 1~2 都正常）
```

---

### B-T6. 在單一 SSH 指令中重啟服務有風險

**症狀**

一條 `pkill … && sleep && nohup 重啟 …` 的複合指令中途 SSH 斷線（exit 255），結果**舊的被殺掉、新的沒起來**，服務整個消失。

**教訓**

重啟這種「先破壞後建設」的操作，不要放在單一遠端指令裡賭它跑完。要嘛分兩步各自確認，要嘛用 `setsid` 讓重啟腳本本身脫離 SSH session。

停掉服務後**一定要驗證新的起來了**，不要假設。

---

### B-T7. `modifyvm` 的 session 鎖

**症狀**

```
VBoxManage.exe: error: The machine 'AI' is already locked for a session (or being unlocked)
```

**原因**

`modifyvm` 需要獨占鎖。剛下過其他 VBoxManage 指令、或 VM 正在啟動／關閉時會拿不到。

**解法**

- VM 執行中要改設定，用 `controlvm` 而非 `modifyvm`（NAT 埠轉發兩者都支援）
- 關機後的操作，等幾秒讓鎖釋放再重試

本次就是 `modifyvm --natpf1` 失敗但 VM 已用 NAT 啟動，導致沒有轉發規則、完全連不進去。補救方式是對執行中的 VM 下 `controlvm "AI" natpf1 …`。

---

## 七、v0 已驗證與尚未實作

### 已驗證可運作

| 機制 | 對應設計文件 |
|---|---|
| ack-first + 佇列（避免 Slack 重送造成四重執行） | `architecture-review.md` B1 |
| `event_id` 去重 | B1 |
| 觸發者白名單 | I5 |
| `--permission-mode dontAsk` + 唯讀工具白名單 | B2 |
| `--append-system-prompt-file` | B4 |
| `--json-schema` 結構化輸出 | I2 |
| stdout / stderr 分離（實測 stderr 為 0 位元組） | B3 |
| 四角色自訂身分發言 | `interaction-model.md` 一 |
| 互動按鈕往返 | `interaction-model.md` 二 |
| 等待審批時釋放並行槽 | `interaction-model.md` 二 |

### 尚未實作

| 缺口 | 嚴重度 |
|---|---|
| **任務狀態不持久**（全在記憶體，重啟即失憶，按鈕變死的） | 🔴 |
| **`event_id` 去重表也在記憶體**（重啟後重送會被當新任務） | 🔴 |
| **`ExecutionSandbox` 完全未做** —— 必須在 Backend Agent 之前完成 | 🔴 |
| 稽核紀錄（階段轉換、成本、session_id、按鈕動作都沒記） | 🟠 |
| 「要求重做」未收集實際回饋，只送罐頭字串 | 🟠 |
| Architecture / Backend / QA 三個角色 | 🟠 |
| 核准後的狀態轉換（目前是死路） | 🟠 |
| worktree 隔離、推分支、開 PR | 🟠 |
| `roles/*.yaml` 中立來源與多 runtime 轉譯 | 🟡 |
| Knowledge Graph MCP | 🟡 |
| 第二個 runtime adapter | 🟡 |

**實作順序上的硬性約束**：`ExecutionSandbox` 必須早於 Backend Agent。Backend 是第一個會修改程式碼並執行 shell 的角色，沒有沙箱就讓它上線，等於作廢整份安全設計。
