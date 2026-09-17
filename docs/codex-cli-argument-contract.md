# Codex CLI 參數契約與「未呼叫模型」的失敗處理

對應事故：#1014 於 2026-09-09 15:20 派工 QA 時，Codex 以結束碼 2 立即結束，
`logs/1014/verification.codex.jsonl` 為 0 bytes，
`verification.codex.stderr.log` 內容為 `error: unexpected argument '-P' found`。

## 根因

`CodexLocalTestPolicy` 的同一組參數同時餵給兩個子指令，但 codex-cli 0.153.4
的參數契約並不對稱（本機實測，非推測）：

| | `codex sandbox` | `codex exec` |
|---|---|---|
| `-P` / `--permission-profile` | **必填** | **不接受**，回結束碼 2 |
| `-c permissions.<名稱>={...}` | 接受 | 接受，但單獨給會被拒絕 |
| 指定要套用哪個 profile | 由 `-P` 指定 | `-c default_permissions="<名稱>"` |

實測依據：

```
$ codex exec --strict-config -c 'default_permissions="no_such_profile"' -C <dir> x
Error: default_permissions refers to undefined profile `no_such_profile`

$ codex exec --strict-config -c 'permissions.x={...}' -C <dir> x
Error: config defines `[permissions]` profiles but does not set `default_permissions`

$ codex exec --strict-config -c active_permission_profile='"x"' -C <dir> x
Error loading config.toml: unknown configuration field `active_permission_profile`
```

因為預檢走 `codex sandbox`，它會通過（preflight log 最後一行是 `local-tcp-ok`），
真正派工走 `codex exec` 才失敗——預檢反而遮蔽了缺陷。

## 修正

- `CodexLocalTestPolicy` 拆成 `sandboxArguments()` 與 `execArguments()`，
  共用同一份 `permissions.<名稱>` profile 定義，只有「指定方式」不同。
- 新增真實 CLI 契約測試（opt-in `AI_TEAM_CODEX_SANDBOX_SMOKE`，**零模型呼叫**）：
  在非 git 目錄跑 `codex exec --strict-config`，參數全被接受時會停在
  `Not inside a trusted directory`；同時反向驗證把 sandbox 專用旗標交給 exec
  仍必須出現 `unexpected argument`，避免再次混用。

## 未呼叫模型的失敗不計次

新增 `RuntimeUsageError`。判定條件三項全中才成立，寧可少判不可誤判：

1. 事件流為空（有任何一行 JSONL 就代表模型端已開始）
2. 結束碼為 clap 的用法錯誤碼 2
3. stderr 有明確用法錯誤字樣

`FlowEngine` 收到這個例外時，把本輪的節點次數、`transitions` 與
一次性受控恢復授權（`recoveryGrants` / `recoveryRevisions`）原樣還原，
清空 `activeNode`，並在 Slack 明說「本次未呼叫任何模型」。
理由：我們自己的參數缺陷不該吃掉真人核准，逼使用者重走整套核准流程。
額度（`QuotaExceeded`）仍走原本的等待與自動續跑路徑，兩者不共用。

## 2026-09-13：resume 選項必須放在子指令

#1014 再次續跑時，無模型的 `codex sandbox` 預檢通過，但實際
`codex exec resume` 的 `turn_context` 顯示 `active_permission_profile=:read-only`，
隨後 Maven 無法寫入 `base-spring-boot-starter/target`，`/tmp` 也是唯讀。

對比同一個 session 的證據：首次非 resume 回合正確使用
`ai_team_local_tests`；之後每次 resume 都退回 `:read-only`。根因是舊指令將
權限 `-c` 選項放在父層 `codex exec` 與 `resume` 之間；CLI 接受語法，
但 resume 子指令沒有套用這些設定。

修正後：

- resume 指令改成 `codex exec resume <子指令選項> <session-id> <prompt>`。
- 本機測試 profile 的定義與 `default_permissions` 都放在 `resume` 後。
- 一般 workspace-write 續跑使用 resume 可接受的
  `-c sandbox_mode="workspace-write"`，不再使用只屬於父層 exec 的 `-s`。
- 所有選項放在 session id 前，避免位置曖昧。
- 實機 CLI 回歸以不存在的 session id 驗證設定可被 resume 解析，
  在本機查找就停止，不呼叫模型。

## 驗證

隔離目錄 `usage-fix-build.Sa7L5Y`，未動線上 `target/`：

- `mvn -B package`：231 項，0 失敗、0 錯誤，10 項為 opt-in 略過
- opt-in 全部另行實跑通過：`CodexSandboxVmTest` 4/4（含新 CLI 契約與本機 TCP）、
  `OperationPreflightVmTest` 3/3、`LinuxVerificationSmokeTest` 3/3
- `deploy/bootstrap/check-source.sh` 通過
- 候選 jar SHA256 `9d54bb4b18c4efacb7ac0e7dcce63191d97ff1963a9c3bf33a5037cceb52a3d7`

尚未部署，也尚未有任何真實 QA 端到端通過的證據。

## 2026-09-14：Structured Outputs schema 相容性

#1014 恢復到測試計畫節點後，Codex API 在建立 thread 前以
`invalid_json_schema` 拒絕舊快照的 `uniqueItems`。這不是 agent 回報失敗，
也不是使用者未完成恢復；Codex Structured Outputs 只支援 JSON Schema 子集合。

修正方式：

- 團隊快照仍保留原始 schema；`CodexRuntime` 只在送出的副本遞迴移除
  `uniqueItems`，因此舊任務不必遷移快照。
- `Traceability` 原有的 requirement／acceptance ID 唯一性閘門仍會執行，
  runtime 相容處理不會放寬產品驗收。
- 明確的 `invalid_json_schema` 且尚未出現 `thread.started`／`turn.started` 時，
  歸類為 agent 啟動前的請求契約錯誤；流程會還原本輪次數與一次性恢復授權。
- 新版 `plan.json` 不再產生已知不相容的 `uniqueItems`。

隔離建置：275 項測試、0 失敗、10 項 opt-in 略過；Codex runtime 針對性
測試 25/25，兩份 team 靜態驗證與 `check-source.sh` 均通過。

## 2026-09-17 提示一律由 stdin 傳入（Claude Code 與 Codex）

**症狀**：#1016 分流節點啟動即失敗，`IOException: Cannot run program "systemd-run": error: 7 (Argument list too long)`，
任何重試都一樣。提示 131,772 位元組，超過 Linux 單一參數上限 `MAX_ARG_STRLEN` 131,072。
長任務累積的需求基準、測試計畫、交接紀錄與受阻發現都在提示裡，任何專案跑得夠久都會碰到。

**修正**：提示不再放 argv。
- Claude Code：`claude -p ...`（不帶提示引數），啟動後把提示寫入 stdin 並關閉。
- Codex：`codex exec ... -` 與 `codex exec resume ... <SESSION_ID> -`，啟動後寫入 stdin 並關閉。
  stdin 必須關閉，否則 codex 會等待 EOF 直到逾時（既有陷阱）。
- 外層 `systemd-run --user --wait --pipe` 會轉接 stdin，不需額外設定。

**實測**（VM，經 `systemd-run --user --wait --pipe`）：
- Claude Code 2.1.252：441,104 位元組提示由 stdin 送入，正確回覆指定字串（exit 0，費用 US$0.48）。
- codex-cli 0.153.4：151,331 位元組提示以 `-` 由 stdin 送入，正確回覆指定字串（exit 0）。
  注意 `systemd-run` 不會帶入呼叫端的 `PATH`，codex 的啟動腳本需要 `node`；正式執行路徑由 `ExecutionSandbox` 設定環境。

**未涵蓋**：opencode runtime 仍以 argv 傳遞提示；其 stdin 行為未在 VM 實測，維持「未驗證」。
換 CLI 版本時須重驗 stdin 提示行為。
