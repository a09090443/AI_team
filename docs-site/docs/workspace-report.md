# 工作區隔離驗證報告

- 執行時間：2026-09-02
- 主機：Ubuntu 26.04（VirtualBox「AI」）
- 對象 repo：`zipe-spring-boot-starters`（`.git` 2.3 MB，全部 7.1 MB，預設分支 `master`）

驗證 Backend／QA 的隔離工作區該怎麼做。結論推翻了原設計的 git worktree 方案。

---

## 1. 原設計：git worktree —— 與沙箱衝突

`corrected-architecture.md` 與 `HANDOFF.md` 都寫「Backend 需 worktree 隔離」。
實測發現它與 `ExecutionSandbox` 的寫入邊界直接衝突。

worktree 的 `.git` 不是目錄，而是一個指標檔：

```
gitdir: /home/vboxuser/ai-team/zipe-spring-boot-starters/.git/worktrees/probe-wt
```

index、ref、新物件全部寫在**主 repo** 底下。沙箱只開放工作區可寫時：

| 結果 | 操作 | 觀察 |
|---|---|---|
| PASS | `git status` | 正常（唯讀操作） |
| PASS | 修改工作區內的檔案 | `WROTE` |
| **FAIL** | `git add` | `fatal: Unable to create '.../.git/worktrees/probe-wt/index.lock': Read-only file system` |
| **FAIL** | `git commit` | 同上 |
| PASS | 對照組：額外開放主 repo 的 `.git` 可寫 | git 操作全部正常 |

**要讓 worktree 能用，就得把主 repo 的 `.git` 開放給 agent 寫。**
那等於讓它有能力改寫 `master` 的 ref、刪除分支、或直接破壞物件庫 ——
沙箱擋住了工作區之外的檔案，卻在版本控制上開了一個更大的洞。

---

## 2. 改用每任務獨立 clone

| 結果 | 主張 | 觀察 |
|---|---|---|
| PASS | clone 成本可接受 | **0.32 秒**，工作區 9.3 MB |
| PASS | clone 的 `.git` 是真目錄，自給自足 | 是 |
| PASS | 沙箱內可建立分支 | `ai-team/task-probe` |
| PASS | 沙箱內可 `add` 與 `commit` | commit `2fcad74` 成功 |
| PASS | 主 repo 的工作樹仍為唯讀 | `README.md: Read-only file system` |
| PASS | 主 repo 的 `.git` 仍為唯讀 | 寫入被擋 |
| PASS | 主 repo 的 `master` 未被動到 | HEAD 不變、工作樹乾淨 |

一個 7 MB 的 repo 花 0.3 秒換到完整隔離，這個交換非常划算。
若日後要維護的 repo 大到讓 clone 變成負擔，再回頭考慮 worktree 加上
「只開放 `.git/worktrees/<name>` 與 `.git/objects`」的細粒度政策。

---

## 3. 決定下來的行為

| 問題 | 決定 | 理由 |
|---|---|---|
| 從哪個 branch 開 | 主 repo 的 `refs/remotes/origin/<基準分支>`，取之前先 `fetch` | 本機分支可能落後；長期維護若以過期基準開分支，PR 會充滿無關的衝突 |
| 基準分支怎麼判斷 | `AI_TEAM_BASE_BRANCH` > `develop`（若遠端有）> `origin/HEAD` > 目前分支，並記錄選了哪個與原因 | 見下方第 4 節：對 gitflow 專案用 `origin/HEAD`（= master）開分支，會做出一個永遠合不進去的 PR |
| 分支前綴 | `AI_TEAM_BRANCH_PREFIX`，預設 `feature/ai-team-task-` | 符合該 repo「feature → develop → master」的流程 |
| 主 repo 會被改動嗎 | 只 `fetch`，不碰本機分支與工作樹 | 主 repo 是唯讀基準，不是工作場所 |
| 重做時沿用工作區嗎 | 不沿用，砍掉重來 | clone 只要 0.3 秒，沿用會讓 agent 在上一次失敗的殘骸上工作 |
| commit 署名 | 寫在工作區的 local config | 不改動使用者的 `~/.gitconfig` |
| `origin` 指向 | 改回真正的 GitHub URL | clone 出來會指向本機路徑，不改的話之後推分支會推進主 repo |
| 何時刪除 | `COMPLETED`／`ABORTED`／孤兒 → 啟動時刪除 | 每個 9 MB，不清會無限累積 |
| 何時保留 | 等待中（可能還要推 PR）、`FAILED`（診斷現場） | 失敗現場砍掉就查不出當時發生什麼事 |

---

## 4. 主 repo 鏡像本身是壞的

決定好基準分支的規則之後，實測發現規則在真實環境下**選不到 develop** ——
因為主 repo 的鏡像有兩個問題，兩個都會安靜地降低整個系統的品質：

| 問題 | 觀察 | 後果 |
|---|---|---|
| single-branch clone | `remote.origin.fetch` 只有 `+refs/heads/master:refs/remotes/origin/master` | `git fetch` 永遠拿不到 develop，基準判斷會退回 master，做出合不進去的 PR |
| shallow clone | `is-shallow-repository` 為 true，本機只有 220 個 commit | Architecture Agent 靠 `git log` 理解設計意圖，淺歷史會讓它看不到變更的來由 |

修正（只增加歷史與遠端追蹤 ref，不動工作樹與本機分支）：

```bash
git config remote.origin.fetch "+refs/heads/*:refs/remotes/origin/*"
git fetch --unshallow origin
```

修正後 commit 數從 **220 補到 368**（少了 148 筆歷史），六條遠端分支全部可見。

但 `.git` 也從 2.3 MB 膨脹到 **67 MB**，工作區 clone 隨之變成 74 MB ——
因為 `mvn-repo` 這條分支存的是建置產物。它永遠不會是程式變更的基準，所以從鏡像排除：

```bash
git config --unset-all remote.origin.fetch
git config --add remote.origin.fetch "+refs/heads/master:refs/remotes/origin/master"
git config --add remote.origin.fetch "+refs/heads/develop:refs/remotes/origin/develop"
# 刪掉不需要的遠端追蹤 ref 後 git gc --prune=now
```

最終：`.git` **2.5 MB**、完整 368 筆歷史、master 與 develop 都在，
工作區 clone **9.6 MB / 0.09 秒**。

`develop`（`03c0075`）與 `master`（`4e4858a`）確實指向不同 commit，
基準選錯不是理論問題。

---

## 5. 驗證方式

`WorkspaceManagerTest` 共 12 項，全部通過，**用真的 git repository 而非 mock** ——
這個類別的價值全在於它與 git 的互動是否正確，換成 mock 等於把要驗證的東西換掉。

涵蓋：獨立 clone 而非指標檔、`origin` 指向真正的遠端、以遠端最新基準開分支
（測試會先在 origin 推進一個 commit，確認工作區拿到的是新的而不是落後的本機 master）、
commit 署名、重做時清掉殘骸、刪除唯讀的 git 物件檔、拒絕逃逸路徑、只認得工作區目錄，
以及基準分支的三條路徑（明確設定、自動選 develop、退回 origin 預設分支）。

啟動清理另外在實機驗證過：放一個資料庫查無此任務的 `task-9001` 與一個
非工作區的目錄，重啟後前者被刪、後者完好。

---

## 6. 破壞性操作的防護

`discard()` 是唯一會刪東西的方法，刪除前要通過兩道檢查：

1. 正規化後的路徑必須真的位於 `AI_TEAM_WORKSPACES` 之下，且不等於根目錄本身
2. 目錄名必須以 `task-` 開頭

少了它們，一個設錯或設空的 `AI_TEAM_WORKSPACES` 就足以刪掉不該刪的東西。
逃逸路徑（如 `../../etc`）已納入測試。
