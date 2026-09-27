# 歷史安裝程序（2026-09-04，勿直接執行）

新機請改讀 [INSTALL.md](INSTALL.md)。以下保留當時紀錄，其中流程與設定已過時。

這是**重新安裝時該照著走的唯一入口**。步驟已依實際踩過的坑重新排序 —— 尤其是第 1 步，它決定了後面每一步的耗時。

各步驟的詳細說明與疑難排解在對應的文件中，遇到問題再翻。

| 文件 | 涵蓋範圍 |
|---|---|
| `vm-setup-manual.md` | VM 環境安裝，T1–T10 疑難排解 |
| `../deploy/README.md` | Slack App 建立與 manifest |
| `build-and-run.md` | Orchestrator 建置、運維，B-T1–B-T7 疑難排解 |

**NAT 網路下全程約 1 小時**，其中大半是等下載與你手動操作 Slack。

---

## 步驟 1：先把 VM 網路改成 NAT ⚠️

**這一步放在最前面是刻意的。** 上次因為用橋接 Wi-Fi，下載速度只有 80 KB/s，JDK 花了 72 分鐘、Claude Code 裝了 11 小時、VM 的 IP 還變動了三次。改成 NAT 之後速度是 9.5 MB/s，**快了 119 倍**。

VM 關機狀態下：

```bash
VBoxManage modifyvm "AI" --nic1 nat && VBoxManage modifyvm "AI" --natdnshostresolver1 on && VBoxManage startvm "AI" --type headless && sleep 5 && VBoxManage controlvm "AI" natpf1 "ssh,tcp,127.0.0.1,2222,,22"
```

之後一律用這個固定位址連線，不會再變：

```bash
ssh -p 2222 vboxuser@127.0.0.1
```

細節與取捨見 `vm-setup-manual.md` T10。

---

## 步驟 2：SSH 金鑰認證

自動化不能用密碼。在控制端產生金鑰：

```bash
ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519 -N "" -C "claude-agent-team"
```

**在 VM 的主控台**貼上以下指令安裝公鑰（不要透過 Windows 的管線，會帶 CRLF，見 T1）：

```bash
cd ~ && chmod 755 ~ && mkdir -p .ssh && chmod 700 .ssh && printf '%s\n' '<公鑰內容>' >> .ssh/authorized_keys && sed -i 's/\r$//' .ssh/authorized_keys && chmod 600 .ssh/authorized_keys && cat -A ~/.ssh/authorized_keys | tail -1
```

最後一行輸出結尾必須是 `$`。若是 `^M$` 表示 CRLF 沒清乾淨。

---

## 步驟 3：安裝環境（全使用者層級，不需 sudo）

`sudo` 需要密碼因此不使用 `apt`，全部裝到 `~/.local/`。

```bash
ssh -p 2222 vboxuser@127.0.0.1 'sudo -n true 2>/dev/null && echo "有免密碼 sudo" || echo "無免密碼 sudo（照本流程走）"'
```

| 元件 | 版本 | 位置 |
|---|---|---|
| Node | v22.21.1 | `~/.local/node` |
| JDK | Temurin 21 | `~/.local/java` |
| Maven | 3.9.16 | `~/.local/maven` |
| Claude Code | 2.1.252 | `~/.local/node/bin/claude` |

安裝腳本在 `../deploy/`。**限制編譯平行度**（見 T3、T6）：

```bash
export MAKEFLAGS=-j1
export JOBS=1
# 絕對不要設 npm_config_jobs 或 npm config set jobs —— jobs 不是合法 npm 選項，會弄壞所有 npm 指令
```

PATH 要同時寫進 `~/.profile` **與** `~/.bashrc` 的最開頭（Ubuntu 的 `.bashrc` 對非互動式 shell 會提前 return，見 T9）。

---

## 步驟 4：Claude Code 登入（只能人工）

```bash
claude          # 完成 OAuth 流程
claude doctor
```

使用訂閱帳號時**不要設定 `ANTHROPIC_API_KEY`**，也不能用 `--bare`。

---

## 步驟 5：建立 Slack App

照 `../deploy/README.md`，用 `slack-app-manifest.yaml` 一次建好。

**兩個最容易錯的地方**：

| 陷阱 | 正確做法 |
|---|---|
| App-Level Token 的 scope | 必須選 **`connections:write`**，不是 `app_configurations:write`（B-T1）。選錯只能重新產 token |
| Bot 沒加入頻道 | 安裝到 workspace ≠ 加入頻道，要 `/invite @zipe-ai-team`（B-T2） |

manifest 的 `long_description` 必須超過 173 字元，否則會被拒絕。
manifest 的 `long_description` 必須超過 173 字元，否則會被拒絕。

### 再建四個角色 App

**逐步教學見 `slack-agent-apps.md`。** 摘要：四個 agent 各自是 workspace 裡獨立的使用者，
manifest 在 `../deploy/agent-manifests/`（`pm.yaml`、`architecture.yaml`、`backend.yaml`、`qa.yaml`）。
每個都用「Create New App → From a manifest」建立，安裝後取得各自的
Bot User OAuth Token（`xoxb-...`，不是 `xapp-`）。

這四個是**唯寫** App：只有 `chat:write`，沒有 Socket Mode、不訂閱事件、不接收互動。
它們只負責把該角色的結果貼進主 App 建立的討論串。

**四個都要 `/invite` 進頻道**，否則貼不進去：

```
/invite @PM Agent
/invite @Architecture Agent
/invite @Backend Agent
/invite @QA Agent
```

不建這四個 App 也能運作 —— 角色會退回主 App 加名稱覆寫的模式，
差別只在不會出現於成員清單、無法被個別 @ 提及。啟動時的 log 會列出
哪些角色用獨立帳號、哪些共用主 token。

---

## 步驟 6：填寫 env.sh

```bash
cp ~/ai-team/env.sh.template ~/ai-team/env.sh
nano ~/ai-team/env.sh
chmod 600 ~/ai-team/env.sh
```

必填：`SLACK_APP_TOKEN`、`SLACK_BOT_TOKEN`、`AI_TEAM_ALLOWED_USERS`（`U` 開頭的 member ID）。
選填：`AI_TEAM_CHANNEL_ID`（`C` 開頭，留空表示不限制頻道）、
四個角色的 `SLACK_BOT_TOKEN_*`、以及要推 PR 才需要的 `AI_TEAM_GITHUB_TOKEN`。

驗證（不會印出完整 token）：

```bash
source ~/ai-team/env.sh && echo "APP:${SLACK_APP_TOKEN:0:8} BOT:${SLACK_BOT_TOKEN:0:8} PM:${SLACK_BOT_TOKEN_PM:0:8} USERS:${AI_TEAM_ALLOWED_USERS}"
```


## 步驟 7：準備目標 repository 鏡像 ⚠️

`AI_TEAM_REPO` 指向的是 AI 團隊的**唯讀基準**，每個任務會從它 clone 出獨立工作區。
這個鏡像的品質直接決定 agent 的品質，兩個預設值會安靜地害到你：

```bash
git clone https://github.com/<owner>/<repo>.git ~/ai-team/<repo>
cd ~/ai-team/<repo>

# 1. 不要 shallow —— Architecture Agent 靠 git log 理解設計意圖
git rev-parse --is-shallow-repository        # 應為 false，是 true 就 git fetch --unshallow origin

# 2. 不要 single-branch —— 少了 develop，開分支會以 master 為基準，
#    對 gitflow 專案等於做出一個永遠合不進去的 PR
git config --unset-all remote.origin.fetch
git config --add remote.origin.fetch "+refs/heads/master:refs/remotes/origin/master"
git config --add remote.origin.fetch "+refs/heads/develop:refs/remotes/origin/develop"
git fetch origin
git branch -r                                 # 應同時看到 origin/master 與 origin/develop
```

只追蹤真正會當基準的分支。把存建置產物的分支（例如 `mvn-repo`）也拉進來，
`.git` 會從 2.5 MB 膨脹到 67 MB，每個工作區跟著變成 74 MB。

**預暖 Maven 快取**（不做的話第一個 Backend 任務會花掉大半逾時在下載相依）：

```bash
cd ~/ai-team/<repo> && mvn -B verify
```

實測（8 vCPU / 8 GB）：冷快取 **217 秒**，暖快取 **118 秒**，單模組 `-pl <module> -am` **16 秒**。
`~/.m2` 約 369 MB。Backend 階段的逾時設為 1800 秒就是依據這組數字。

---

## 步驟 8：驗證 CLI 契約

```bash
bash ~/phase0-verify.sh
```

產出 `phase0-report.md`，把 `architecture-review.md` 中所有關於 CLI 行為的主張變成可觀察的測試。

上次結果：**13 項通過、0 失敗**（見 `phase0-report.md`）。若有 FAIL，要修的是設計文件而不是現實。

---

## 步驟 9：開啟 user namespace（唯一需要 sudo 的步驟）⚠️

執行沙箱的檔案系統隔離靠 systemd 的 `ProtectSystem` / `ProtectHome` / `PrivateTmp`，
這三項需要 unprivileged user namespace。Ubuntu 24.04 起 AppArmor 預設擋掉它，
而且 **systemd 不會報錯，只會靜默略過** —— 設定看起來有加，實際零防護。

```bash
ssh -t -p 2222 vboxuser@127.0.0.1 "echo 'kernel.apparmor_restrict_unprivileged_userns = 0' | sudo tee /etc/sysctl.d/99-ai-team-userns.conf && sudo sysctl --system >/dev/null && unshare -Ur echo 'userns 已啟用'"
```

`-t` 是為了讓 sudo 能跳出密碼提示。看到 `userns 已啟用` 才算成功；
寫進 `/etc/sysctl.d/` 所以重開機後仍然有效。

跳過這步不會讓服務起不來，但 Backend／QA 會被沙箱自檢擋下拒絕執行
（PM 與 Architecture 是唯讀的，仍可運作）。詳見 `sandbox-report.md`。

---

## 步驟 10：建置並啟動 Orchestrator

```bash
cd ~/ai-team/orchestrator && mvn -B package
setsid nohup bash ~/ai-team/start.sh > ~/ai-team/orchestrator.log 2>&1 </dev/null &
```

**不要在終端機直接跑 `start.sh`** —— 斷線就結束（B-T5）。

確認沙箱自檢通過（沒有這行就代表 Backend／QA 會被拒絕執行）：

```bash
grep 沙箱 ~/ai-team/orchestrator.log
# 預期：沙箱自檢通過：越界寫入被擋、白名單內可寫、工作目錄正確。會修改程式碼的角色已解鎖。
```

確認運作中（看連線數，不要只看 log）：

```bash
PID=$(pgrep -f "[a]i-team-orchestrator-0.1.0.jar" | head -1)
ss -tnp | grep "pid=$PID"      # 至少一條 ESTAB 到 :443（數量會在 1~2 間跳動，都正常）
```

---

## 步驟 11：端對端測試

在 Slack 頻道：

```
@zipe-ai-team 分析 db-spring-boot-starter
```

預期流程：排入佇列 → PM Agent 分析中 → PM Agent 以自己的身分回報結構化結果 → 三個按鈕（核准／重做／中止）。

沒反應時的診斷順序：

```bash
grep -aE "已排入佇列|忽略非指定頻道|拒絕未授權" ~/ai-team/orchestrator.log | tail -5
```

| log 內容 | 問題 |
|---|---|
| 完全空白 | Bot 不在頻道，或 Socket Mode 沒連上 |
| `忽略非指定頻道 Cxxxx` | `AI_TEAM_CHANNEL_ID` 設錯，log 會給出正確值 |
| `拒絕未授權使用者 Uxxxx` | `AI_TEAM_ALLOWED_USERS` 設錯 |

---

## 前次安裝的實際耗時

| 階段 | 橋接 Wi-Fi（實際發生） | NAT（改善後預估） |
|---|---|---|
| 環境安裝 | 約 2 小時純下載 | 約 5 分鐘 |
| 排除故障 | 約 3 小時 | 大部分可避免 |
| Orchestrator 建置 | 相依 99 MB 下載 30 分鐘以上 | 約 2 分鐘 |

**多數的痛苦來自步驟 1 沒有先做。**
