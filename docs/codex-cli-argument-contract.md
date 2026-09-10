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

## 驗證

隔離目錄 `usage-fix-build.Sa7L5Y`，未動線上 `target/`：

- `mvn -B package`：231 項，0 失敗、0 錯誤，10 項為 opt-in 略過
- opt-in 全部另行實跑通過：`CodexSandboxVmTest` 4/4（含新 CLI 契約與本機 TCP）、
  `OperationPreflightVmTest` 3/3、`LinuxVerificationSmokeTest` 3/3
- `deploy/bootstrap/check-source.sh` 通過
- 候選 jar SHA256 `9d54bb4b18c4efacb7ac0e7dcce63191d97ff1963a9c3bf33a5037cceb52a3d7`

尚未部署，也尚未有任何真實 QA 端到端通過的證據。
