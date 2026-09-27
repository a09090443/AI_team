# 執行沙箱驗證報告

- 執行時間：2026-09-01
- 主機：Ubuntu 26.04，Linux 7.0.0-30-generic x86_64（VirtualBox「AI」，8 vCPU / 8 GB）
- systemd user manager：running，cgroup v2 已委派 `cpu memory pids`

驗證 `ExecutionSandbox` 賴以成立的每一項機制。與 Phase 0 相同，全部為實機對照測試 ——
**每一項「有防護」的主張都配一組「沒防護時會怎樣」的對照組**，否則無法分辨
「擋下來了」和「根本沒跑到」。

---

## 1. 可用的隔離機制：先刪掉不能用的

| 結果 | 主張 | 觀察 |
|---|---|---|
| FAIL | bubblewrap 可用 | 未安裝，且無免密碼 sudo 不能裝 |
| FAIL | firejail / nsjail / podman / docker 可用 | 全部未安裝 |
| PASS | setpriv / prlimit 存在 | `/usr/bin/setpriv`、`/usr/bin/prlimit` |
| PASS | systemd user manager 可用 | `systemctl --user is-system-running` → running |
| PASS | cgroup v2 控制器已委派給使用者 | `cpu memory pids` |

**結論**：容器與獨立沙箱工具這條路全斷，唯一可用的是 systemd user unit。

---

## 2. 關鍵阻礙：AppArmor 擋掉 unprivileged user namespace

| 結果 | 主張 | 觀察 |
|---|---|---|
| — | `kernel.unprivileged_userns_clone` | 1（允許） |
| FAIL | userns 實際可用 | `kernel.apparmor_restrict_unprivileged_userns = 1`，`unshare -Ur` → `write failed /proc/self/uid_map: Operation not permitted` |

**這是整份報告最重要的一項。** 檔案系統隔離（`ProtectSystem`、`ProtectHome`、
`PrivateTmp`）需要 mount namespace，而 mount namespace 需要 user namespace。
Ubuntu 24.04 起 AppArmor 預設擋掉後者。

### 危險之處：systemd 靜默略過

修正前的實測，`ReadOnlyPaths` / `ProtectHome` / `PrivateTmp` 三項**全部被寫穿，
且 systemd 完全不報錯**：

| 結果 | 測試 | 觀察 |
|---|---|---|
| FAIL | `ReadOnlyPaths` 阻擋越界寫入 | `WROTE` —— 沙箱無效 |
| FAIL | `ProtectHome=read-only` 阻擋家目錄寫入 | `WROTE` —— 沙箱無效 |
| FAIL | `PrivateTmp=yes` 隔離 /tmp | `SEES` —— 沙箱無效 |

設定檔看起來完全正確，實際零防護。這就是 `ExecutionSandbox.selfTest()` 存在的
唯一理由：**把靜默失敗變成大聲失敗**。

### 修正

以一次 sudo 永久解除限制：

```bash
echo 'kernel.apparmor_restrict_unprivileged_userns = 0' | sudo tee /etc/sysctl.d/99-ai-team-userns.conf
sudo sysctl --system
```

代價是略微放寬這台隔離開發 VM 的核心限制，換到的是「安全邊界在 runtime 之外」
這項設計決策真的能成立（見 `multi-runtime.md`）。

---

## 3. 修正後：檔案系統隔離

| 結果 | 主張 | 觀察 |
|---|---|---|
| PASS | 越界寫入被擋 | `BLOCKED` + `Read-only file system`，canary 內容未變 |
| PASS | 對照組：白名單內仍可寫 | `WROTE` |
| PASS | 家目錄不可寫 | `BLOCKED` |
| PASS | `~/.claude` 列入白名單後可寫 | `WROTE` |
| PASS | 巢狀覆蓋：可寫父目錄內可再釘唯讀 | `~/.claude` 可寫，但 `~/.claude/settings.json` → `Read-only file system` |
| PASS | 憑證仍可讀（認證不被打斷） | `READABLE` |
| PASS | 沙箱內 git 可操作 | `GIT_OK` |

巢狀覆蓋這項直接決定了一個提權管道能不能堵住：`~/.claude` 必須可寫（session、
憑證更新），但 `settings.json` 能定義 hook、`plugins` 能塞入會被載入的程式碼，
兩者都會在**下一次**執行時生效，等於讓 agent 有辦法改寫自己的執行條件。
釘回唯讀有效，這個管道就堵住了。

---

## 4. 資源上限（不需 userns，修正前後皆有效）

| 結果 | 主張 | 觀察 |
|---|---|---|
| PASS | `MemoryMax` 觸發 cgroup OOM | 上限 64M、吃 300M → 進程被 `Killed` |
| PASS | `TasksMax` 限制行程數 | 上限 5、開 10 個 → `fork: Resource temporarily unavailable`，exit=254 |
| PASS | `CPUQuota` 被接受 | unit 正常執行 |
| PASS | 資源上限與檔案系統隔離可同時生效 | 兩者並用時 `TasksMax` 仍然攔截 |
| PASS | 網路保留（claude 必須連得出去） | `DNS OK` |

這組直接對應 `vm-setup-manual.md` 記載的前科：npm 原生模組編譯開滿八核，
把 VM 壓到 soft lockup 需硬重置。

---

## 5. 三個會靜默失敗的實作陷阱

實測中踩到、已在程式碼中修正並納入自檢：

| 結果 | 陷阱 | 觀察 |
|---|---|---|
| PASS | systemd unit **不繼承呼叫端的 cwd** | `ProcessBuilder.directory()` 只設得到 `systemd-run` 自己身上，必須明確給 `--working-directory`，否則 agent 會安靜地跑在家目錄而非工作區 |
| PASS | 路徑不存在時 unit **直接起不來且無任何輸出** | `ReadOnlyPaths=$HOME/.claude/NO_SUCH_FILE` → 無輸出；加上 `-` 前綴 → `STARTED`。乾淨環境下 `~/.claude/settings.json` 常不存在 |
| PASS | unit **不繼承呼叫端的環境變數** | 沙箱內 `PATH` 是系統預設、`JAVA_HOME` 未設定、`command -v mvn` 找不到 —— Backend 每次建置都會失敗，且看起來像環境壞掉而不是沙箱設定漏了 |

自檢已涵蓋這三項：驗證 `pwd` 落在指定工作目錄、刻意帶一個不存在的釘選路徑、
以及在沙箱內執行 `command -v mvn` 確認建置工具真的找得到。

### 環境變數用白名單而不是全部帶進去

`--setenv` 只傳遞 `PATH`、`JAVA_HOME`、`MAVEN_OPTS`。

用白名單的理由是 orchestrator 自己的環境裡有 **Slack token**
（`start.sh` 以 `set -a` source `env.sh`）。整包倒進沙箱，等於把「以 bot 身分發言」
的能力交給一個會改程式碼的 agent。`HOME` 與 `LANG` 由 systemd user manager 提供，
不需要也不應該手動傳。

工具鏈與檔案系統隔離分開判斷：前者是能力問題，後者是安全問題，
混在同一個旗標裡會讓「為什麼 Backend 被拒絕執行」變得難以診斷。

---

## 6. 端對端：PM 在唯讀沙箱內執行

以完全等同 `SandboxPolicy.readOnly()` 產生的指令，對真實 repo 跑一次 PM 分析。

| 結果 | 主張 | 觀察 |
|---|---|---|
| PASS | 認證未被 `ProtectHome=read-only` 打斷 | `is_error:false`、`subtype:success` |
| PASS | 可讀取唯讀的 repo 並正確分析 | 正確指出 `base-spring-boot-starter` 與 `com.zipe.util.crypto` |
| PASS | structured_output 正常產生 | `task_type:FEATURE`、`confidence:HIGH`、`required_agents:["backend","qa"]` |
| PASS | stderr 乾淨 | 0 bytes |
| — | 成本 | US$0.1496 |

**沙箱沒有破壞任何既有功能。**

---

## 總計

- PASS：22
- FAIL：5（皆為「這條路不能走」的排除性結論，非未解決的缺陷）

## 結論

設計決策 6（安全邊界必須在 runtime 之外）在這台 VM 上**成立**，但**有前提**：
`kernel.apparmor_restrict_unprivileged_userns` 必須為 0。

這個前提無法從程式碼內部保證，只能在啟動時檢出 —— 所以 `selfTest()` 不是
可有可無的健全性檢查，而是這項設計決策的執行條件本身。自檢未通過時，
`ExecutionSandbox.requireFsConfinement()` 會拒絕執行 Backend／QA，
PM 與 Architecture 仍可運作（它們是唯讀的）。
