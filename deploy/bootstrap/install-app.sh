#!/usr/bin/env bash
# 來源根目錄 → 新安裝目錄。不覆蓋既有環境，不自動啟動。
set -euo pipefail
umask 077
source_root=$(cd "${1:?usage: install-app.sh source-root NEW-destination}" && pwd)
destination=${2:?missing new destination}
destination=$(realpath -m "$destination")
[[ ! -e $destination && ! -L $destination ]] || { echo '目的地已存在，拒絕覆蓋；升級請使用獨立目錄'; exit 1; }
bash "$source_root/deploy/bootstrap/check-source.sh"
bash "$source_root/deploy/bootstrap/check-linux.sh" runtime
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/java}
export PATH="$JAVA_HOME/bin:$HOME/.local/maven/bin:$PATH"
build=$(mktemp -d)
cp -a "$source_root/orchestrator/src" "$source_root/orchestrator/pom.xml" "$source_root/orchestrator/prompts" "$source_root/orchestrator/schemas" "$source_root/orchestrator/teams" "$build/"
(cd "$build" && mvn -B package > build.log 2>&1) || { echo "建置失敗，見 $build/build.log；未安裝"; exit 1; }
mkdir -p "$destination/orchestrator/target" "$destination/workspaces" "$destination/logs"
cp "$build/target/ai-team-orchestrator-0.1.0.jar" "$destination/orchestrator/target/"
cp -a "$build/prompts" "$build/schemas" "$build/teams" "$destination/"
cp "$source_root/deploy/env.sh.template" "$destination/env.sh"
cp "$source_root/deploy/bootstrap/"{start,service,preflight,audit,backup,restore,check-linux,linux-platform}.sh "$destination/"
cp "$build/build.log" "$destination/build.log"
sha256sum "$destination/orchestrator/target/ai-team-orchestrator-0.1.0.jar" > "$destination/jar.sha256"
echo "已安裝但未啟動：$destination；請填 env.sh（AI_TEAM_HOME 須指向此目錄）。建置證據：$build"
