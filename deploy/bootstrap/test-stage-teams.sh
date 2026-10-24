#!/usr/bin/env bash
# stage-teams.sh 的合併與擋停規則測試；以假的 java 代替候選 JAR，不需建置。
set -euo pipefail
script="$(cd "$(dirname "$0")" && pwd)/stage-teams.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/repo/.git"
# 假 java：配置內容含 INVALID 就驗證失敗，並記下驗過哪些檔案。
cat > "$tmp/bin/java" <<'EOF'
#!/usr/bin/env bash
echo "$4" >> "$STAGE_TEST_LOG"
! grep -q INVALID "$4"
EOF
chmod +x "$tmp/bin/java"
export PATH="$tmp/bin:$PATH" STAGE_TEST_LOG="$tmp/validated.log" AI_TEAM_REPO="$tmp/repo"

fresh() {
  rm -rf "$tmp/case"; mkdir -p "$tmp/case/source/prompts" "$tmp/case/overlay/skills/x/references" "$tmp/case/live"
  echo '{"example":1}' > "$tmp/case/source/example.json"
  echo shared > "$tmp/case/source/prompts/shared.md"
  echo '{"instance":1}' > "$tmp/case/overlay/project.json"
  echo ref > "$tmp/case/overlay/skills/x/references/project.md"
  : > "$STAGE_TEST_LOG"
}
stage() { bash "$script" jar "$tmp/case/source" "$tmp/case/overlay" "$tmp/case/live" "$tmp/case/out" >/dev/null 2>&1; }
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; exit 1; }

fresh
AI_TEAM_DEFINITION="$tmp/case/live/project.json" stage || fail '首次合併應通過'
[[ -f $tmp/case/out/project.json && -f $tmp/case/out/example.json && -f $tmp/case/out/skills/x/references/project.md && -f $tmp/case/out/prompts/shared.md ]] \
  || fail '合併結果缺檔'
grep -q 'out/project.json' "$STAGE_TEST_LOG" && grep -q 'out/example.json' "$STAGE_TEST_LOG" || fail '未驗證全部配置'
pass '來源 teams 與 overlay 合併，所有配置都經過驗證'

fresh; cp "$tmp/case/overlay/project.json" "$tmp/case/live/project.json"
AI_TEAM_DEFINITION="$tmp/case/live/project.json" stage || fail '線上與 overlay 一致時應通過'
pass '線上檔與 overlay 一致時照常部署'

fresh; echo '{"edited":1}' > "$tmp/case/live/project.json"
if AI_TEAM_DEFINITION="$tmp/case/live/project.json" stage; then fail '線上檔被直接修改仍通過'; fi
[[ ! -e $tmp/case/out/project.json ]] || fail '擋停前已寫出合併結果'
pass '線上 teams 被直接修改、與 overlay 不一致時擋停'

fresh; rm "$tmp/case/overlay/project.json"
if AI_TEAM_DEFINITION="$tmp/case/live/project.json" stage; then fail '啟用中的配置缺漏仍通過'; fi
pass '啟用中的配置不在來源也不在 overlay 時擋停'

fresh; echo '{"INVALID":1}' > "$tmp/case/overlay/project.json"
if AI_TEAM_DEFINITION="$tmp/case/live/project.json" stage; then fail '無效的實例配置仍通過'; fi
pass '實例配置未通過靜態驗證時擋停'

fresh; rm -rf "$tmp/case/overlay"
AI_TEAM_DEFINITION="$tmp/case/live/example.json" stage || fail '沒有 overlay、使用內附範例應通過'
pass '沒有 overlay 時只用來源 teams'

fresh; mkdir -p "$tmp/case/elsewhere"; echo '{"outside":1}' > "$tmp/case/elsewhere/team.json"
AI_TEAM_DEFINITION="$tmp/case/elsewhere/team.json" stage || fail 'teams 外的配置應原地驗證'
grep -q 'elsewhere/team.json' "$STAGE_TEST_LOG" || fail 'teams 外的配置未驗證'
pass '啟用中的配置在 teams 以外時原地驗證'

fresh; mkdir -p "$tmp/case/out"; echo old > "$tmp/case/out/leftover"
if AI_TEAM_DEFINITION= stage; then fail '輸出目錄非空仍通過'; fi
pass '輸出目錄必須是空的'
