# VM 環境建置手冊

> 歷史實測與疑難排解，不是目前的逐步安裝程序。新機請從 [INSTALL.md](INSTALL.md) 開始；本文的舊版本、IP 與 provision 階段指令不可直接重跑。

實際在 Ubuntu 26.04 VirtualBox VM 上建置 AI Team 環境的完整記錄，含遇到的每一個問題與解法。

**本手冊的疑難排解章節全部來自真實踩到的坑**，不是預想的情境。

| 項目 | 值 |
|---|---|
| 主機 | Windows 11 |
| VM | VirtualBox，Ubuntu 26.04（Resolute Raccoon），核心 7.0.0-30 |
| 資源 | 8 vCPU / 8 GB RAM / 98 GB 磁碟 |
| 網路 | **NAT + 連接埠轉發**，實測約 **9.5 MB/s**（原為橋接 Wi-Fi，僅 80 KB/s，見 T10） |
| 主機連線方式 | `ssh -p 2222 vboxuser@127.0.0.1`（固定不變） |
| VM 內部 IP | `10.0.2.15`（NAT 固定配發） |
| 帳號 | `vboxuser`，**無免密碼 sudo** |

> **先讀 T10。** 本次建置絕大部分的痛苦來自一個網路設定問題，改完之後下載速度快了 119 倍。
> 如果你正要從頭建置，**第一件事就是把網路改成 NAT**，不要重蹈覆轍。

---

## 建置策略：為什麼全部裝在使用者目錄

`sudo -n true` 回傳 `interactive authentication is required`，代表 sudo 需要密碼。自動化流程無法輸入密碼，所以**完全不使用 `apt`**，改為全部安裝到 `~/.local/`。

這反而帶來好處：不污染系統、可整包刪除重來、不需要提權。

| 元件 | 來源 | 位置 |
|---|---|---|
| Node 22 | nodejs.org 官方 tarball | `~/.local/node` |
| JDK 21 | Adoptium Temurin tarball | `~/.local/java` |
| Maven 3.9.9 | Apache dlcdn tarball | `~/.local/maven` |
| Claude Code | `npm install -g`（prefix 指向 `~/.local/node`） | `~/.local/node/bin/claude` |

已預裝可直接用：`git`、`curl`、`wget`、`jq`。

---

## 步驟

### 0. 建立 SSH 金鑰認證

自動化必須用金鑰，不能用密碼。

在**控制端**（Windows）產生金鑰：

```bash
ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519 -N "" -C "claude-agent-team"
```

把公鑰裝到 VM。**務必在 VM 的主控台執行**，不要透過 Windows 的管線（原因見疑難排解 T1）：

```bash
cd ~ && chmod 755 ~ && mkdir -p .ssh && chmod 700 .ssh && { grep -v 'claude-agent-team' .ssh/authorized_keys 2>/dev/null || true; } > .ssh/ak.tmp && printf '%s\n' '<你的公鑰內容>' >> .ssh/ak.tmp && sed -i 's/\r$//' .ssh/ak.tmp && mv .ssh/ak.tmp .ssh/authorized_keys && chmod 600 .ssh/authorized_keys && cat -A ~/.ssh/authorized_keys | tail -2
```

`cat -A` 的輸出結尾必須是 `$`。若是 `^M$` 表示有 CRLF 污染，見 T1。

驗證：

```bash
ssh -o BatchMode=yes vboxuser@<VM_IP> 'echo CONNECTED'
```

### 1. 環境盤點

```bash
ssh vboxuser@<VM_IP> 'cat /etc/os-release | head -3; nproc; free -h | head -2; df -h / | tail -1; sudo -n true 2>&1 && echo SUDO=passwordless || echo SUDO=needs-password; for c in git curl jq java node npm mvn; do printf "%-6s " $c; command -v $c >/dev/null && echo OK || echo MISSING; done'
```

### 2. 測量實際下載速度

**這一步不要跳過。** 網路速度決定後續每一步的逾時設定與策略。

```bash
ssh vboxuser@<VM_IP> 'curl -sS -o /dev/null --max-time 30 -w "bytes=%{size_download} speed=%{speed_download}B/s\n" https://nodejs.org/dist/v22.21.1/node-v22.21.1-linux-x64.tar.xz'
```

本次實測 **~80 KB/s**，據此推算：

| 檔案 | 大小 | 預計耗時 |
|---|---|---|
| Node 22 | 30 MB | 約 8 分鐘 |
| Maven | 9 MB | 約 2 分鐘 |
| JDK 21 | 190 MB | 約 40 分鐘 |

### 3. 背景執行佈建腳本

因為總耗時遠超過任何工具的逾時上限，必須在 VM 上用 `nohup` 背景執行：

```bash
scp provision.sh vboxuser@<VM_IP>:~/ && ssh vboxuser@<VM_IP> 'chmod +x ~/provision.sh; nohup bash ~/provision.sh > ~/provision-nohup.log 2>&1 & echo PID=$!'
```

腳本必備的三個特性（缺一不可，見 T3）：

- **可續傳**：`curl -C -`
- **會重試**：`--retry 5 --retry-delay 10`
- **偵測停滯**：`--speed-limit 1024 --speed-time 180`（低於 1KB/s 持續 3 分鐘就放棄重來）

### 4. 設定 PATH

寫入 `~/.bashrc`：

```bash
export JAVA_HOME="$HOME/.local/java"
export MAVEN_HOME="$HOME/.local/maven"
export PATH="$HOME/.local/node/bin:$JAVA_HOME/bin:$MAVEN_HOME/bin:$PATH"
```

> 注意：非互動式 SSH（`ssh host 'cmd'`）**不會讀 `~/.bashrc`**。腳本中必須自行 `export PATH`，或用絕對路徑呼叫。

### 5. 目錄骨架

```bash
mkdir -p ~/ai-team/{workspaces,logs,prompts,roles,schemas,orchestrator}
```

### 6. Claude Code 登入（只能人工執行）

```bash
claude
```

走完 OAuth 瀏覽器流程。使用訂閱帳號時**不要設定 `ANTHROPIC_API_KEY`**，且不能使用 `--bare`（`--bare` 不讀 OAuth 憑證）。

---

## 疑難排解

### T1. SSH 金鑰被伺服器拒絕（CRLF 污染）

**症狀**

```
debug1: Offering public key: /c/Users/xxx/.ssh/id_ed25519 ED25519 SHA256:...
debug1: Authentications that can continue: publickey,password
vboxuser@x.x.x.x: Permission denied (publickey,password).
```

客戶端有正確提供金鑰，是伺服器端不認。

**原因**

在 Windows 用 PowerShell 管線把公鑰送過去時，PowerShell 會加上 **CRLF 換行**，`authorized_keys` 該行結尾多一個 `\r`，sshd 比對不到。

**診斷**

```bash
cat -A ~/.ssh/authorized_keys | tail -2
```

結尾是 `^M$` 就是中了；正常應該只有 `$`。

**解法**

在 VM 主控台直接清理重寫（見步驟 0 的指令），關鍵是 `sed -i 's/\r$//'`。

**其他會造成同樣症狀的原因**（一併檢查）

```bash
chmod 755 ~ && chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys
```

家目錄權限過鬆時 sshd 的 `StrictModes` 也會拒絕，且不會給明確錯誤。

---

### T2. 誤判為網路故障（IPv6 與 MTU 的兩個假線索）

**症狀**

`nvm install 22` 無限期卡住，`github.com` 要 20 秒才回應。

**兩條走錯的路**

1. **懷疑 IPv6**：`nodejs.org` 只解析到 IPv6（`2606:4700::...`），看起來像 IPv6 路由壞掉。用 `curl -4` 強制 IPv4 測試 —— **速度完全沒改善**，排除。

2. **懷疑 MTU**：大檔案停滯、小檔案正常，是 MTU 分片問題的典型徵兆。用不分片 ping 探測：

   ```bash
   for sz in 1472 1420 1400 1300; do ping -c 1 -M do -s $sz 8.8.8.8 >/dev/null 2>&1 && echo "$sz OK" || echo "$sz 丟棄"; done
   ```

   結果 1472（即 MTU 1500）完全通過 —— 排除。

**真正原因**

網路只是**慢**，約 80 KB/s。

**關鍵教訓：用 curl 自己的 `--max-time`，不要用外層的 `timeout`**

```bash
# 錯誤：timeout 殺掉 curl，-w 永遠不會印出來，看起來像「零回應」
timeout 60 curl -sS -w '%{speed_download}' URL

# 正確：curl 自行逾時，仍會印出已傳輸的位元組數與速度
curl -sS --max-time 30 -w 'bytes=%{size_download} speed=%{speed_download}B/s\n' URL
```

用錯的寫法讓我一度以為連線完全停滯；換成正確寫法立刻看出「30 秒傳了 2.1MB」，問題性質瞬間清楚。

---

### T3. npm 編譯原生模組導致整台 VM 核心鎖死

**這是本次最嚴重的問題。**

**症狀**

VM 從網路上完全消失。SSH 逾時，ping 回報 `Destination host unreachable`，但 VirtualBox 顯示 VM 仍是 `running`。

**診斷方法：對 VM 截圖**

Guest Additions 沒裝，無法查詢 guest 狀態。改用 VirtualBox 直接截取主控台畫面：

```bash
VBoxManage controlvm "<VM名稱>" screenshotpng /tmp/vm.png
```

**這招是整個排錯過程中最有價值的工具。** 一台網路失聯的 VM 用任何遠端手段都問不出狀態，但截圖直接把核心訊息攤在眼前。

畫面顯示：

```
watchdog: BUG: soft lockup - CPU#5 stuck for 42s! [curl:6615]
watchdog: BUG: soft lockup - CPU#4 stuck for 42s! [cc1plus:50631]
watchdog: BUG: soft lockup - CPU#7 stuck for 42s! [g++:64281]
watchdog: BUG: soft lockup - CPU#0 stuck for 22s! [cc1plus:64789]
... 八個核心全部鎖死
```

**原因**

`npm install -g @anthropic-ai/claude-code` 編譯原生模組時，node-gyp 依 CPU 核心數開了 **8 個平行的 g++**。每個 C++ 編譯行程吃掉大量記憶體，8 GB 的 VM 被壓垮，核心在所有核心上 soft lockup，連 sshd 都排不到 CPU。

**解法：強制編譯平行度為 1**

```bash
export MAKEFLAGS=-j1
export JOBS=1
nice -n 19 npm install -g @anthropic-ai/claude-code
```

`nice -n 19` 確保核心與 sshd 永遠搶得到 CPU，即使編譯很吃資源也能保住遠端連線。

**順序也要改**

把純下載的項目（Maven、JDK、git clone）排在**前面**，會編譯的 Claude Code 放**最後**。這樣即使又鎖死，其他東西已經裝好，不用整包重來。

---

### T4. 硬重置後開機卡在 fsck

**症狀**

```
[***] Job systemd-fsck-root.service/start running (4min 53s / no limit)
dracut-initqueue[334]: Timed out while waiting for udev queue
INFO: task kworker/u32:2:72 blocked for more than 122 seconds
rcu_preempt detected expedited stalls on CPUs/tasks
```

**原因**

T3 的鎖死只能用 `VBoxManage controlvm "AI" reset` 硬重置。ext4 因為不乾淨的關機，下次開機會做完整檢查。98 GB 磁碟的 fsck 在虛擬化的 I/O 上很慢。

**先判斷是「慢」還是「死結」**

用截圖連續觀察，看訊息內容而不只是等：

```bash
VBoxManage controlvm "AI" screenshotpng /tmp/vm.png
```

本例的訊息是死結而非進度：

```
INFO: task kworker/u32:2:72 is blocked on a mutex likely owned by task (udev-worker):332
INFO: task (udev-worker):332 blocked for more than 245 seconds
INFO: task (systemd-fsck):392 blocked for more than 122 seconds
```

三個行程互相等待同一個 mutex，阻塞秒數持續增加卻沒有新進展 —— 這不會自己好。若是 fsck 正常運作，畫面會出現進度百分比而非 `blocked` 訊息。

**解法：冷啟動，不是再次重置**

```bash
VBoxManage controlvm "AI" poweroff
VBoxManage startvm "AI" --type headless
```

`poweroff` 加 `startvm` 會完整重新初始化虛擬硬體，`reset` 不會。本例冷啟動後順利開機完成。

> **關機後可能出現 session 鎖**
>
> ```
> error: The machine 'AI' is already locked by a session
> ```
>
> VirtualBox 釋放鎖需要幾秒。等待後重試即可，本例約 4 秒。

**預防**

- 依 T3 限制編譯平行度，從源頭避免鎖死
- 硬重置是最後手段，先嘗試 `VBoxManage controlvm "AI" acpipowerbutton`（正常關機）

---

### T4b. 重開機後 IP 改變

**症狀**

VM 開機成功，但原本的 IP 連不上。

**原因**

橋接網路取得的是 DHCP 位址，重開機後租約可能換號。本例從 `192.168.68.55` 變成 `192.168.68.53`。

**解法**

Guest Additions 沒裝時無法從主機查詢，只能：

1. 用 `VBoxManage controlvm "<VM>" screenshotpng` 截圖，在主控台看 `ip a` 的輸出
2. 掃描網段：`arp -a | grep 192.168.68`
3. 從 VM 主控台直接看

**根本解法**

裝 Guest Additions 之後就能直接查：

```bash
VBoxManage guestproperty get "<VM>" "/VirtualBox/GuestInfo/Net/0/V4/IP"
```

或請 DHCP 伺服器對該 MAC 保留固定位址。

---

### T5b. Apache CDN 只保留現行版本（404）

**症狀**

Maven 下載連續五次失敗，重試機制無效。

**診斷**

重試不會解決 HTTP 錯誤。直接看回應碼：

```bash
curl -sS -o /dev/null --max-time 25 -w 'code=%{http_code}\n' https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz
# code=404
```

**原因**

`dlcdn.apache.org` **只保留現行版本**。Maven 3.9.9 已被後續版本取代並移除。

**解法**

先查目前有哪些版本：

```bash
curl -sS https://dlcdn.apache.org/maven/maven-3/ | grep -oE "3\.[0-9]+\.[0-9]+" | sort -uV | tail -5
```

本次查到 `3.9.16` 與 `3.10.0`，改用 3.9.16。

要用舊版本則改走封存站（有版本就有檔，但明顯較慢）：

```
https://archive.apache.org/dist/maven/maven-3/<版本>/binaries/...
```

**教訓**：腳本中**不要寫死第三方 CDN 的版本號**，或至少要準備封存站備援。`curl -f` 遇到 404 會回傳失敗，但單純重試永遠救不了 404 —— 重試邏輯應該區分「網路中斷」與「HTTP 4xx」。

---

### T5c. `pgrep -f` 的誤判

**症狀**

VM 剛重開機 2 分鐘，卻回報「佈建執行中」。

**原因**

```bash
pgrep -f provision   # 比對完整命令列
```

我自己那條 SSH 指令裡含有 `~/provision2.log` 字樣，於是 `pgrep -f` 比對到執行該指令的 shell 本身。

**解法**

用更精確的樣式，或改以狀態檔判斷：

```bash
pgrep -f 'bash .*provision3\.sh'     # 精確比對
[ -f ~/provision-done ] && echo 完成  # 或直接看旗標檔
```

腳本結束時寫一個旗標檔，比猜測行程狀態可靠得多。

---

### T5d. 背景程序在斷線或重開機後消失

**症狀**

`nohup ... &` 啟動的佈建，SSH 斷線後就沒了。

**解法**

```bash
ssh host 'setsid nohup bash ~/script.sh > ~/log 2>&1 < /dev/null & echo PID=$!'
```

`setsid` 讓行程脫離 session，`< /dev/null` 切斷 stdin，兩者一起才能真正不受 SSH 連線影響。

注意：**任何方式都無法讓行程在 VM 重開機後存活**。所以腳本必須設計成可重複執行、每一步都先檢查是否已完成再決定要不要做。

---

### T5. 工具逾時 vs 遠端長時間作業

**症狀**

`ssh ... 'long running command'` 在 2 分鐘被砍斷，即使指令內已經有 `timeout 400`。

**原因**

控制端工具本身有預設逾時（本例為 120 秒），會蓋過指令內的設定。

**解法**

長時間作業一律用 `nohup` 丟到 VM 背景，控制端只做輪詢：

```bash
ssh host 'nohup bash ~/script.sh > ~/log 2>&1 & echo PID=$!'
```

輪詢時用「里程碑」而非完整 log：

```bash
ssh host 'grep -a "^\[" ~/provision.log | tail -15; cat ~/provision-status.txt'
```

> 腳本中的 `curl` 一定要加 `--no-progress-meter`。忘記加的話進度條會把 log 灌成幾十 KB 的亂碼，輪詢時完全讀不到有用資訊。

---

### T6. `npm_config_jobs` 讓所有 npm 指令失效（自作自受）

**症狀**

```
npm error `jobs` is not a valid npm option
```

安裝看似完成，但 `claude --version` 回報：

```
Error: claude native binary not installed.
```

**原因**

為了限制編譯平行度（見 T3），我在腳本裡設了：

```bash
export npm_config_jobs=1      # 錯誤
npm config set jobs 1         # 錯誤
```

npm 會把 `npm_config_*` 環境變數當成設定項，而 **`jobs` 不是合法的 npm 選項**。結果是腳本執行期間**每一次 npm 呼叫都直接失敗**，包含下載原生執行檔的 postinstall。

**解法**

限制編譯平行度只能用這兩個，不要碰 npm 設定：

```bash
export MAKEFLAGS=-j1
export JOBS=1               # node-gyp 讀這個
```

若已誤設，檢查並清除：

```bash
grep -n jobs ~/.npmrc && sed -i '/^jobs\s*=/d' ~/.npmrc
unset npm_config_jobs
npm --version               # 應正常輸出版本號
```

**教訓**：在腳本開頭設定環境變數之後，**立刻驗證該工具仍能運作**。一個無效的設定會靜默污染後續每一個步驟，而錯誤訊息出現在離根因很遠的地方。

---

### T7. Claude Code 的原生執行檔是 optional dependency

**症狀**

```
[@anthropic-ai/claude-code postinstall] Native package "@anthropic-ai/claude-code-linux-x64" not found.
  This happens with --omit=optional or when the download failed.
```

**原因**

`@anthropic-ai/claude-code` 本體只是個 wrapper，真正的執行檔在平台專屬的 optional 相依套件裡（Linux x64 是 `@anthropic-ai/claude-code-linux-x64`，約 129 MB 解壓後）。

任何導致 npm 失敗的原因（如 T6）都會讓這個 optional 相依裝不上，而 wrapper 本身安裝成功，於是「看起來裝好了但不能執行」。

**解法**

修好 npm 之後單純重裝即可，**不需要加 `--include=optional`**：

```bash
npm install -g @anthropic-ai/claude-code
```

**不要被空目錄嚇到**：安裝過程中會出現全部 8 個平台的目錄，但只有符合當前平台的那個會真正下載：

```
claude-code-darwin-arm64/      4.0K   ← 佔位空目錄，正常
claude-code-linux-x64/         129M   ← 只有這個真的下載
claude-code-win32-x64/         4.0K   ← 佔位空目錄，正常
```

我一度誤判成「npm 在下載全部 8 個平台」而中斷安裝 —— 那是錯的，白白浪費了 40 分鐘的下載。

---

### T8. 不要在 npm 執行中刪除 node_modules

**症狀**

```
npm warn tar TAR_ENTRY_ERROR ENOENT: no such file or directory, open '.../claude-code-linux-x64/package.json'
npm error code ENOENT
npm error syscall spawn sh
npm error path /home/vboxuser/.local/node/lib/node_modules/@anthropic-ai/claude-code
npm error enoent spawn sh ENOENT
```

**原因**

我在 npm 還在運作時執行了 `rm -rf .../node_modules/@anthropic-ai`。npm 的解壓目標與生命週期腳本的工作目錄被抽走，樹狀狀態損壞。之後每次重裝都在同一個地方失敗。

`spawn sh ENOENT` 特別容易誤導 —— 它看起來像「找不到 sh」，實際意思是「**要執行腳本的那個目錄不存在**」。

**解法**

確認沒有殘留行程之後再清，然後從乾淨狀態重裝：

```bash
pkill -f "npm install"; sleep 3
pgrep -af "npm install" || echo "(乾淨)"
rm -rf ~/.local/node/lib/node_modules/@anthropic-ai
rm -f  ~/.local/node/bin/claude ~/.local/node/bin/.claude-*
npm install -g @anthropic-ai/claude-code
```

**好消息：npm 快取會保留。** 前面下載的內容還在 `~/.npm/_cacache`，所以重裝是**從快取命中，7 秒完成**，不是重新下載 129 MB。

失敗後不要順手 `npm cache clean --force` —— 那會把唯一能救你的東西刪掉。

---

### T9. `~/.bashrc` 的 PATH 對非互動式 SSH 無效

**症狀**

`~/.bashrc` 明明寫了 PATH，但：

```bash
ssh host 'claude --version'
# bash: claude: command not found
```

連 `ssh host 'bash -lc "claude --version"'` 也一樣失敗。

**原因**

Ubuntu 預設的 `~/.bashrc` 開頭就有非互動式的提前返回：

```bash
case $- in
    *i*) ;;
      *) return;;
esac
```

`ssh host 'cmd'` 是非互動式 shell，讀到這裡就 return，後面的 PATH 設定永遠不會執行。

**解法**

兩處都寫，涵蓋所有情境：

```bash
# 1. ~/.profile（login shell 會讀）
cat >> ~/.profile <<'EOF'
export JAVA_HOME="$HOME/.local/java"
export MAVEN_HOME="$HOME/.local/maven"
export PATH="$HOME/.local/node/bin:$JAVA_HOME/bin:$MAVEN_HOME/bin:$PATH"
EOF

# 2. ~/.bashrc 的「最開頭」，在提前 return 之前
sed -i '1i export PATH="$HOME/.local/node/bin:$HOME/.local/java/bin:$HOME/.local/maven/bin:$PATH"' ~/.bashrc
```

自動化腳本內則一律自行 `export PATH` 或使用絕對路徑，不要依賴 profile。

---

### T10. 橋接 Wi-Fi 是本次所有網路問題的根源

**這一條是整份手冊最重要的內容。** 它同時解釋並解決了前面好幾個問題。

**症狀（表面上像三個獨立問題）**

1. VM 的 IP 反覆變動：`.55` → `.53` → `.55` → `.56`，每次重開機都要重找機器
2. 下載速度只有 **80 KB/s**，JDK 要 72 分鐘、Claude Code 裝了 11 小時
3. 大檔案傳輸不斷停滯，`github.com` 單次請求要 20 秒

**真正的原因**

VM 的網路卡是**橋接到主機的 Wi-Fi 網卡**：

```bash
VBoxManage showvminfo "AI" --machinereadable | grep -E '^nic1=|^bridgeadapter1='
# nic1="bridged"
# bridgeadapter1="MediaTek Wi-Fi 6 MT7921 Wireless LAN Card"
```

橋接到無線網卡有兩個後果：

- **IP 由你家路由器的 DHCP 決定**，租約到期就可能換號
- **無線橋接需要 MAC 位址轉譯**，效能極差且不穩定 —— 這是 VirtualBox 在 Wi-Fi 上橋接的已知限制，不是你的網路慢

**解法：改用 NAT + 連接埠轉發**

VM 關機後執行：

```bash
VBoxManage modifyvm "AI" --nic1 nat
VBoxManage modifyvm "AI" --natdnshostresolver1 on
VBoxManage startvm "AI" --type headless
VBoxManage controlvm "AI" natpf1 "ssh,tcp,127.0.0.1,2222,,22"
```

> 若在 VM 執行中設定轉發，用 `controlvm` 而非 `modifyvm`。
> `modifyvm` 需要獨占鎖，在剛下過其他指令時常出現
> `The machine 'AI' is already locked for a session` —— 這時改用 `controlvm` 即可。

之後一律用固定位址連線：

```bash
ssh -p 2222 vboxuser@127.0.0.1
```

**效果**

| 指標 | 橋接 Wi-Fi | NAT | 改善 |
|---|---|---|---|
| 下載速度 | 80 KB/s | **9.5 MB/s** | **119 倍** |
| `github.com` 回應 | 20 秒 | 0.32 秒 | 62 倍 |
| 4.2 MB 檔案 | 30 秒內只傳 2.1 MB（未完成） | 完整下載 | － |
| VM 的 IP | 路由器 DHCP，會變 | `10.0.2.15` 固定 | － |
| 主機連線位址 | 每次都要重找 | `127.0.0.1:2222` 固定 | － |

**為什麼選埠轉發而不是 Host-only 雙網卡**

「NAT + Host-only 雙網卡」也能得到固定 IP，而且 VM 全埠可達。但第二張網卡需要**在 VM 內用 sudo 設定 netplan** 才會啟用。埠轉發完全由 VirtualBox 在主機端處理，**不需要進 VM、不需要 sudo**，對自動化流程友善得多。

之後若要開放其他連接埠（例如 web UI）：

```bash
VBoxManage controlvm "AI" natpf1 "web,tcp,127.0.0.1,8080,,8080"
```

**代價**

NAT 之下 VM 無法被區域網路上的其他機器主動連線。若你之後需要從手機或另一台電腦直接連 VM，才需要改用 Host-only 或橋接（改橋接時記得在路由器對 MAC 設 DHCP 保留）。

---

## 檢查清單

建置完成後逐項確認：

```bash
ssh vboxuser@<VM_IP> 'export PATH="$HOME/.local/node/bin:$HOME/.local/java/bin:$HOME/.local/maven/bin:$PATH"; for c in node npm claude java mvn git jq; do printf "%-8s " $c; command -v $c >/dev/null 2>&1 && ($c --version 2>&1 | head -1) || echo "缺少"; done; echo "--- 目錄 ---"; ls -d ~/ai-team/*/ 2>/dev/null; echo "--- repo ---"; git -C ~/ai-team/zipe-spring-boot-starters log -1 --oneline 2>&1'
```

### 本次實測結果（已通過）

| 項目 | 實際版本 |
|---|---|
| node | v22.21.1 |
| npm | 10.9.4 |
| claude | 2.1.252 (Claude Code) |
| java | openjdk 21.0.12.1 2026-08-18 LTS |
| mvn | Apache Maven 3.9.16 |
| git | 2.53.0（系統預裝） |
| jq | 1.8.1（系統預裝） |
| 目錄骨架 | workspaces / logs / prompts / roles / schemas / orchestrator |
| repo | `4e4858a`，13 個模組 |
| `ANTHROPIC_API_KEY` | 未設定（訂閱模式正確狀態） |

剩下唯一需要人工的步驟：在 VM 上跑一次 `claude` 完成 OAuth 登入。

### 實際耗時參考

| 階段 | 耗時 |
|---|---|
| Node 22（30 MB） | 8.5 分鐘 |
| Maven（9 MB） | 3 分鐘 |
| JDK 21（190 MB） | 72 分鐘 |
| Claude Code（129 MB） | 首次約 30 分鐘；**快取命中重裝 7 秒** |
| 排除故障 | 約 3 小時（含一次當機、一次 fsck、三次 npm 重試） |

以上全部是在**橋接 Wi-Fi（80 KB/s）**下的數字。

改用 NAT（9.5 MB/s）之後，同樣的下載量約 **2 分鐘**就能完成。上表的兩小時下載時間幾乎全部是可以避免的 —— 見 T10。

---

## 給下次的建議

0. **第一件事就是把 VM 網路改成 NAT + 連接埠轉發**（T10）。本次幾乎所有的痛苦 —— 72 分鐘的 JDK、11 小時的 npm、反覆變動的 IP、大檔案停滯 —— 全部源自橋接 Wi-Fi。改完之後下載快了 119 倍。**下次從這裡開始，不要從這裡結束。**
1. **先測速再規劃**，不要假設網路正常 —— 但如果測出來很慢，先檢查是不是橋接 Wi-Fi，而不是接受它。
2. **限制編譯平行度**是低成本的保險，但**只用 `MAKEFLAGS=-j1` 與 `JOBS=1`**，絕對不要碰 `npm_config_jobs`（見 T6）。
3. **`VBoxManage screenshotpng` 應該是診斷失聯 VM 的第一個動作**，不是最後一個。
4. **裝 Guest Additions**。本次因為沒裝，IP 兩次變動都只能靠截圖或人工查詢；有裝就能用 `VBoxManage guestproperty` 直接查。
5. 診斷網路時，**讓工具自己逾時**（`curl --max-time`），不要用外層 `timeout` 包住，否則會丟失最關鍵的統計輸出。
6. **設定環境變數後立刻驗證該工具仍可運作。** 一個無效設定會靜默污染後續每一步，而錯誤會在離根因很遠的地方才浮現。
7. **不要在下載或安裝進行中「順手清理」。** 本次兩個最貴的錯誤都來自誤判狀態後的過早清除：一次浪費 40 分鐘下載，一次弄壞 npm 樹狀態導致連續三輪失敗。看不懂狀態時，先觀察兩次取樣的差異，不要動手。
8. **失敗後不要清 npm 快取。** 快取是重試唯一能省時間的東西 —— 本次讓 30 分鐘的安裝變成 7 秒。
9. **為 VM 設固定 IP 或 DHCP 保留**。本次 IP 在 `.55` 與 `.53` 之間變動兩次，每次都中斷自動化流程。
