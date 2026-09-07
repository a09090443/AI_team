#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")" && pwd)
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/java}
export PATH="$JAVA_HOME/bin:$HOME/.local/node/bin:$HOME/.local/maven/bin:$HOME/.local/bin:$PATH"
bash "$root/preflight.sh"
set -a
source "$root/env.sh"
[[ ! -f $root/team.env ]] || source "$root/team.env"
set +a
exec java -jar "$root/orchestrator/target/ai-team-orchestrator-0.1.0.jar"
