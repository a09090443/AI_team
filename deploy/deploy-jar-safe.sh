#!/usr/bin/env bash
# 只替換 JAR 的受控部署：備份 → 替換 → 重啟 → 健康檢查；啟動失敗自動回復備份並重啟。
# 用法（在本機）：ssh -p 2223 agent@127.0.0.1 'bash -s <VM 上的候選 JAR> <預期 SHA256> [安裝目錄，預設 ~/ai-team]' < deploy/deploy-jar-safe.sh
# 候選 JAR 必須在隔離建置目錄產生，不在服務的 target/ 建置。只換 JAR；配置（teams/）變更要另外處理。
set -uo pipefail
build=$1
expected=$2
root=${3:-$HOME/ai-team}
jar=$root/orchestrator/target/ai-team-orchestrator-0.1.0.jar
cd "$root" || exit 1
[[ $(sha256sum "$build" | cut -d' ' -f1) == "$expected" ]] || { echo '候選 JAR 雜湊不符，停止'; exit 1; }
# 只看本實例的 unit：同一台 VM 另一個實例有工作時，不該擋住這裡的部署。
instance=$(set +u; { source ./env.sh; [[ ! -f team.env ]] || source ./team.env; } >/dev/null 2>&1; printf %s "${AI_TEAM_INSTANCE:-}")
if [[ -n $instance ]]; then patterns=("ai-team-$instance-*")
else patterns=('ai-team-[0-9]*' 'ai-team-check-*' 'ai-team-selftest-*' 'ai-team-preflight-*' 'ai-team-codex-preflight-*'); fi
if [[ -n $(systemctl --user list-units "${patterns[@]}" --no-legend) ]]; then echo "仍有本實例的 unit 在跑（${patterns[*]}），停止"; exit 1; fi
snapshot() { sqlite3 -readonly ai-team.db "select group_concat(id||':'||state||':'||updated_at, ' ') from task where state not in ('COMPLETED','PR_CREATED','ABORTED','FAILED');"; }
before=$(snapshot); echo "before=$before"
backup=$(mktemp -d "$root/orchestrator-backup.XXXXXX")
cp -p "$jar" "$backup/" || exit 1
sqlite3 ai-team.db ".backup '$backup/ai-team.db'" || exit 1
echo "backup=$backup"
bash service.sh stop || { echo '服務無法停止，未替換'; exit 1; }

# 啟動並等待：成功＝最後一個角色連線完成；失敗＝Spring 啟動失敗或行程消失。
start_and_check() {
  bash service.sh start >/dev/null
  for _ in $(seq 1 90); do
    grep -q "INSPECTOR 直接對話 Socket Mode 已啟動" orchestrator.log 2>/dev/null && return 0
    grep -q "Application run failed" orchestrator.log 2>/dev/null && return 1
    sleep 2
  done
  return 1
}

cp "$build" "$jar"
if start_and_check && bash service.sh status >/dev/null 2>&1; then
  echo "部署成功 $(sha256sum "$jar" | cut -c1-16)"
  result=0
else
  echo '啟動失敗，保留失敗 log 並自動回復備份'
  cp orchestrator.log "$backup/failed-start.log"
  grep -E "Caused by|ERROR" orchestrator.log | grep -v "^\s*at " | tail -3 | cut -c1-250
  bash service.sh stop >/dev/null 2>&1 || true
  cp "$backup/ai-team-orchestrator-0.1.0.jar" "$jar"
  if start_and_check; then echo "已回復舊版 $(sha256sum "$jar" | cut -c1-16)"; else echo '！！回復後仍無法啟動，需要人工處理'; fi
  result=1
fi
(bash service.sh status || true) | head -1
echo "ERROR 行數=$(grep -c ' ERROR ' orchestrator.log || true)"
grep -E "沙箱自檢通過|沙箱工具鏈自檢通過|builder：" orchestrator.log | cut -c1-100
after=$(snapshot)
[[ $before == "$after" ]] && echo '任務資料前後一致' || echo "任務資料有變動：$after"
exit $result
