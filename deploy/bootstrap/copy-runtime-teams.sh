#!/usr/bin/env bash
# 複製來源 teams 的執行資產。測試保留在來源，實例 overlay 由呼叫端另行套用。
set -euo pipefail
source_teams=${1:?缺少來源 teams}
destination=${2:?缺少目的地 teams}
test -d "$source_teams"
mkdir -p "$destination"
tar -C "$source_teams" --anchored --no-wildcards-match-slash \
  --exclude='./skills/service-build/scripts/test_*.py' \
  --exclude='./skills/service-build/scripts/service_fixture.py' \
  --exclude='./scripts/test_verify_gradle_jars.py' \
  --no-anchored --exclude='__pycache__' --exclude='*.pyc' \
  -cf - . | tar -C "$destination" -xf -
