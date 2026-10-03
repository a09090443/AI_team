#!/usr/bin/env bash
# 僅本機檢查；不輸出憑證，不呼叫模型或 Slack。
set -euo pipefail
root=$(cd "$(dirname "$0")" && pwd)
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/java}
export PATH="$JAVA_HOME/bin:$HOME/.local/node/bin:$HOME/.local/maven/bin:$HOME/.local/bin:$PATH"
bash "$root/check-linux.sh" runtime
test -f "$root/env.sh"
[[ $(stat -c %a "$root/env.sh") == 600 ]] || { echo 'env.sh 權限須為 600'; exit 1; }
set -a
source "$root/env.sh"
[[ ! -f $root/team.env ]] || source "$root/team.env"
set +a
[[ $(realpath "$AI_TEAM_HOME") == "$root" ]] || { echo 'AI_TEAM_HOME 與安裝位置不一致'; exit 1; }
# 同一台 VM 多個服務實例：unit 名稱靠 AI_TEAM_INSTANCE 區分（見 configurable-team.md）。
instance=${AI_TEAM_INSTANCE:-}
if [[ -n $instance ]]; then
 [[ $instance =~ ^[a-z][a-z0-9]{0,15}$ && ! $instance =~ ^(check|preflight|selftest|codex)$ ]] || {
  echo 'AI_TEAM_INSTANCE 無效：須為小寫英文字母開頭、只含小寫英數、最多 16 字，且不可為 check／preflight／selftest／codex'; exit 1; }
fi
own_jar="$root/orchestrator/target/ai-team-orchestrator-0.1.0.jar"
others=0
for p in /proc/[0-9]*/cmdline; do
 [[ -r $p ]] || continue
 jar=$(tr '\0' '\n' < "$p" 2>/dev/null | grep -E '/orchestrator/target/ai-team-orchestrator-0\.1\.0\.jar$' | head -1) || true
 [[ -n $jar && $jar != "$own_jar" ]] && others=$((others + 1))
done
if (( others > 0 )) && [[ -z $instance ]]; then
 echo "偵測到其他安裝目錄的服務正在執行（$others 個）；同一台 VM 多個實例時 AI_TEAM_INSTANCE 必填且各實例不可重複"; exit 1
fi
for name in SLACK_APP_TOKEN SLACK_BOT_TOKEN AI_TEAM_ALLOWED_USERS AI_TEAM_CHANNEL_ID CLAUDE_BIN CODEX_BIN; do
 [[ -n ${!name:-} ]] || { echo "缺少設定：$name"; exit 1; }
done
for tool in java mvn git jq sqlite3 systemd-run systemctl unshare ss flock; do
 command -v "$tool" >/dev/null || { echo "缺少系統工具：$tool"; exit 1; }
done
test -x "$CLAUDE_BIN"; test -x "$CODEX_BIN"
# opencode 是選用的第三個 runtime：沒設就跳過，設了就必須真的可執行。
[[ -z ${OPENCODE_BIN:-} ]] || test -x "$OPENCODE_BIN"
if [[ -n ${AI_TEAM_DEFINITION:-} ]]; then
 java -jar "$root/orchestrator/target/ai-team-orchestrator-0.1.0.jar" --validate-team "$AI_TEAM_DEFINITION"
else
 test -d "$AI_TEAM_REPO/.git"
 [[ $(git -C "$AI_TEAM_REPO" rev-parse --is-shallow-repository) == false ]]
 git -C "$AI_TEAM_REPO" rev-parse --verify "origin/${AI_TEAM_BASE_BRANCH:-develop}" >/dev/null
fi
systemctl --user show-environment >/dev/null
unshare -Ur true
for file in pm pm_triage architecture backend qa qa_plan code_review direct; do test -s "$root/prompts/$file.md"; done
for file in task triage adr impl qa qa-plan review direct; do jq -e . "$root/schemas/$file-schema.json" >/dev/null; done
echo 'PASS：本機前置檢查。尚未驗證 CLI 登入、Slack、沙箱實際隔離或 PR。'
