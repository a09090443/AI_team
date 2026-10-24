#!/usr/bin/env bash
# 合併部署用的 teams：來源內附的通用配置＋實例 overlay（team-overlay/），再以候選 JAR 逐一靜態驗證。
#
# 實例自己的團隊配置（專案 json、專屬 prompt、skill 參考）不放在公開 repo，而放在實例目錄的 overlay，
# 結構與 teams/ 相同；部署時疊在來源 teams 之上，所以相對引用的共用 prompts／schemas 照常解析。
# 只寫輸出目錄，不動線上 teams；任何一項不通過就以非零結束，呼叫端在停服務前中止。
#
# 用法：stage-teams.sh <候選 JAR> <來源 teams> <overlay 目錄（可不存在）> <線上 teams> <輸出目錄>
# 環境：AI_TEAM_REPO（驗證用的專案 repo）、AI_TEAM_DEFINITION（啟用中的配置，可空）
set -euo pipefail
jar=${1:?用法：stage-teams.sh <JAR> <來源 teams> <overlay> <線上 teams> <輸出目錄>}
source_teams=${2:?缺少來源 teams}
overlay=${3:?缺少 overlay 目錄}
live_teams=${4:?缺少線上 teams}
out=${5:?缺少輸出目錄}
repo=${AI_TEAM_REPO:?缺少 AI_TEAM_REPO}
definition=${AI_TEAM_DEFINITION:-}

test -d "$source_teams" || { echo "來源 teams 不存在：$source_teams"; exit 1; }
[[ ! -e $out || -z $(ls -A "$out") ]] || { echo "輸出目錄必須是空的：$out"; exit 1; }

# 部署後線上 teams 的 overlay 檔一定等於 overlay；不一致代表有人只改了其中一邊，覆蓋前先停下讓管理者整併。
if [[ -d $overlay ]]; then
  drift=()
  while IFS= read -r -d '' file; do
    rel=${file#"$overlay"/}
    if [[ -e $live_teams/$rel ]] && ! cmp -s "$file" "$live_teams/$rel"; then drift+=("$rel"); fi
  done < <(find "$overlay" -type f -print0)
  if (( ${#drift[@]} )); then
    echo "線上 teams 與 team-overlay 不一致（可能直接改了 teams/）；請先把要保留的版本放進 overlay：${drift[*]}"
    exit 1
  fi
fi

mkdir -p "$out"
cp -a "$source_teams/." "$out/"
[[ ! -d $overlay ]] || cp -a "$overlay/." "$out/"

for team in "$out"/*.json; do
  AI_TEAM_REPO="$repo" AI_TEAM_NODE_REPO="$repo" java -jar "$jar" --validate-team "$team"
done

# 啟用中的配置在線上 teams 時，合併結果裡一定要有它；否則部署後服務會讀到不再更新的舊檔或根本沒有檔案。
if [[ -n $definition ]]; then
  if [[ $(dirname "$(realpath -m "$definition")") == "$(realpath -m "$live_teams")" ]]; then
    name=$(basename "$definition")
    test -f "$out/$name" || { echo "啟用中的配置 $name 不在來源 teams，也不在 team-overlay；請先放進 $overlay 再部署"; exit 1; }
  else
    AI_TEAM_REPO="$repo" AI_TEAM_NODE_REPO="$repo" java -jar "$jar" --validate-team "$definition"
  fi
fi
echo "PASS：teams 已合併並通過靜態驗證（overlay：$( [[ -d $overlay ]] && echo "$overlay" || echo 無)）→ $out"
