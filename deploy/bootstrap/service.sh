#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")" && pwd)
export PATH="$HOME/.local/bin:$PATH"
exec 9>"$root/service.lock"
flock -n 9 || { echo '另一個管理操作正在執行'; exit 1; }
pids() {
 local p
 for p in /proc/[0-9]*/cmdline; do
  [[ -r $p ]] || continue
  if tr '\0' '\n' < "$p" 2>/dev/null | grep -Fxq "$root/orchestrator/target/ai-team-orchestrator-0.1.0.jar"; then
   basename "$(dirname "$p")"
  fi
 done
 return 0
}
case ${1:-status} in
 start)
  [[ -z $(pids) ]] || { echo '服務已執行'; exit 0; }
  setsid nohup bash "$root/start.sh" > "$root/orchestrator.log" 2>&1 </dev/null 9>&- &
  echo '啟動已送出，請確認 status 與本次啟動 log；此訊息不代表健康檢查成功。';;
 stop)
  if [[ -f $root/ai-team.db ]]; then
   active=$(sqlite3 -readonly "$root/ai-team.db" "select count(*) from task where state like '%RUNNING' or state in ('QUEUED','RESUME_QUEUED','TRIAGE_QUEUED');")
   [[ $active == 0 ]] || { echo '有執行中或排隊任務，拒絕停止'; exit 1; }
  fi
  for pid in $(pids); do kill -TERM "$pid"; done
  for attempt in {1..20}; do [[ -z $(pids) ]] && exit 0; sleep 1; done
  echo '服務未停止，請查核；未強制殺除'; exit 1;;
 status)
  current=$(pids)
  [[ -n $current ]] || { echo 'STOPPED'; exit 1; }
  echo "RUNNING PID=$current"
  # 只列本實例的連線；同一台 VM 有其他實例時，不過濾會把別人的 443 也算進來。
  ss -tnp state established | grep -E ':443' | grep -E "pid=($(echo $current | tr ' ' '|'))," || true
  echo '請確認 443 連線屬於上述 PID；啟動 log 必須有沙箱自檢通過。';;
 *) echo 'usage: service.sh start|stop|status'; exit 2;;
esac
