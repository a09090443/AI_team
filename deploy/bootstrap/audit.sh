#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")" && pwd)
export PATH="$HOME/.local/bin:$PATH"
db=$root/ai-team.db
test -f "$db"
case ${1:-list} in
 list) sqlite3 -readonly -header -column "$db" 'select id,state,created_at,updated_at from task order by cast(id as integer) desc;';;
 cost) sqlite3 -readonly -header -column "$db" 'select task_id,sum(cost_usd) as reported_usd from audit_log group by task_id;';;
 *) [[ $1 =~ ^[0-9]+$ ]] || { echo 'usage: audit.sh list|cost|task-id'; exit 2; }
    sqlite3 -readonly -header -column "$db" "select at,actor,action,detail,session_id from audit_log where task_id='$1' order by id;";;
esac
