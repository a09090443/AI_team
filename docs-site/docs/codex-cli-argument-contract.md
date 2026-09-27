# Codex CLI 參數契約與「未呼叫模型」的失敗處理

本文記錄 `CodexRuntime`／`CodexLocalTestPolicy` 依賴的 CLI 行為（皆為實機測試，非推測）。
首次於 codex-cli 0.153.4 實測，2026-09-25 升級到 0.156.1 時重驗。**換 CLI 版本時要重跑下方的契約測試再上線。**

## 1. `codex sandbox` 與 `codex exec` 的參數不對稱

| | `codex sandbox` | `codex exec` |
|---|---|---|
| `-P` / `--permission-profile` | **必填** | **不接受**，結束碼 2（`unexpected argument '-P' found`） |
| `-c permissions.<名稱>={...}` | 接受 | 接受，但單獨給會被拒絕 |
| 指定要套用哪個 profile | 由 `-P` 指定 | `-c default_permissions="<名稱>"` |

```
$ codex exec --strict-config -c 'default_permissions="no_such_profile"' -C <dir> x
Error: default_permissions refers to undefined profile `no_such_profile`

$ codex exec --strict-config -c 'permissions.x={...}' -C <dir> x
Error: config defines `[permissions]` profiles but does not set `default_permissions`

$ codex exec --strict-config -c active_permission_profile='"x"' -C <dir> x
Error loading config.toml: unknown configuration field `active_permission_profile`
```

預檢走 `codex sandbox`、正式派工走 `codex exec`，所以同一組參數會讓預檢通過、派工才失敗。
`CodexLocalTestPolicy` 因此拆成 `sandboxArguments()` 與 `execArguments()`，共用同一份 `permissions.<名稱>` 定義，只有指定方式不同。

## 2. `codex exec resume` 的選項必須放在子指令

權限 `-c` 選項若放在父層 `codex exec` 與 `resume` 之間，CLI 接受語法，但 resume **不會套用**，
靜默退回 `:read-only`（`turn_context` 顯示 `active_permission_profile=:read-only`，Maven 無法寫入 `target` 與 `/tmp`）。

- resume 形式：`codex exec resume <子指令選項> <session-id> -`，所有選項放在 session id 之前。
- permission profile 定義、`default_permissions`、schema、model 與 reasoning 都放在 `resume` 之後。
- 一般 workspace-write 續跑使用 resume 接受的 `-c sandbox_mode="workspace-write"`，不用只屬於父層 exec 的 `-s`。

## 3. 提示一律由 stdin 傳入

長任務累積的需求基準、測試計畫、交接紀錄與受阻發現會讓提示超過 Linux 單一參數上限
`MAX_ARG_STRLEN`（131,072 位元組），`systemd-run` 以 `Argument list too long` 失敗且重試無效。因此提示不放 argv：

- Claude Code：`claude -p ...`（不帶提示引數），啟動後把提示寫入 stdin 並關閉。
- Codex：`codex exec ... -` 與 `codex exec resume ... <SESSION_ID> -`，啟動後寫入 stdin 並**必須關閉**，否則 codex 會等 EOF 直到逾時。
- 外層 `systemd-run --user --wait --pipe` 會轉接 stdin。`systemd-run` 不帶入呼叫端的 `PATH`，codex 啟動腳本需要 `node`，由 `ExecutionSandbox` 設定環境。
- 實測：Claude Code 441,104 位元組、Codex 151,331 位元組的提示皆由 stdin 正確送達。
- **opencode 仍以 argv 傳遞提示**，其 stdin 行為未實測。

## 4. Structured Outputs 只支援 JSON Schema 子集合

Codex API 會在建立 thread 前以 `invalid_json_schema` 拒絕 `uniqueItems`。
團隊快照保留原始 schema，`CodexRuntime` 只在送出的副本遞迴移除 `uniqueItems`，舊任務不必遷移；
需求／驗收 ID 的唯一性仍由 `Traceability` 閘門檢查，驗收不因此放寬。新版 `plan.json` 已不產生 `uniqueItems`。

## 5. 未呼叫模型的失敗不計次

`RuntimeUsageError` 只在三項條件全中時成立，寧可少判不可誤判：

1. 事件流為空（有任何一行 JSONL 就代表模型端已開始）
2. 結束碼為 clap 的用法錯誤碼 2
3. stderr 有明確的用法錯誤字樣

明確的 `invalid_json_schema` 且尚未出現 `thread.started`／`turn.started` 時，同樣歸類為啟動前的請求契約錯誤。

`FlowEngine` 收到這類例外時，把本輪的節點次數、`transitions` 與一次性受控恢復授權
（`recoveryGrants`／`recoveryRevisions`）原樣還原，清空 `activeNode`，並在 Slack 明說「本次未呼叫任何模型」。
理由：系統自己的參數缺陷不該吃掉真人核准。額度（`QuotaExceeded`）走等待與自動續跑，兩者不共用。

## 契約測試（升級 CLI 必跑）

opt-in `AI_TEAM_CODEX_SANDBOX_SMOKE=true` 的 `CodexSandboxVmTest`，**零模型呼叫**：

- 在非 git 目錄跑 `codex exec --strict-config`，參數全被接受時會停在 `Not inside a trusted directory`；
  並反向驗證把 sandbox 專用旗標交給 exec 仍會出現 `unexpected argument`。
- 以不存在的 session id 驗證 resume 的選項可被解析，在本機查找 session 時就停止。
- 驗證本機 TCP 與可寫路徑（見 [本機測試能力](local-test-capabilities.md)）。

fresh 與 resume 都要通過。另有 `OperationPreflightVmTest`、`LinuxVerificationSmokeTest` 等 opt-in 實機測試。
