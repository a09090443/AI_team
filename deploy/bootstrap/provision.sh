#!/usr/bin/env bash
# 僅安裝使用者工具鏈；不 source env.sh，不啟動服務，不修改系統設定。
set -euo pipefail
umask 077
manifest=${1:?usage: provision.sh /absolute/toolchain.tsv}
prefix=${AI_TEAM_TOOLCHAIN:-$HOME/.local}
bash "$(dirname "$0")/check-linux.sh" tools
for c in curl sha256sum tar mktemp flock; do command -v "$c" >/dev/null || { echo "缺少 $c，請管理員先安裝"; exit 1; }; done
mkdir -p "$prefix"
prefix=$(realpath "$prefix")
exec 9>"$prefix/.ai-team-provision.lock"
flock -n 9 || { echo '另一個佈建正在執行'; exit 1; }
declare -A urls sums
while read -r name url sum extra; do
 [[ -z ${name:-} || $name == \#* ]] && continue
 [[ $name =~ ^(node|java|maven)$ && $url == https://* && $sum =~ ^[a-fA-F0-9]{64}$ && -z ${extra:-} ]] || { echo 'manifest 格式錯誤；需要名稱、HTTPS URL、核實過的 SHA256'; exit 1; }
 [[ -z ${urls[$name]:-} ]] || { echo "重複元件：$name"; exit 1; }
 urls[$name]=$url; sums[$name]=$sum
done < "$manifest"
for name in node java maven; do
 [[ -n ${urls[$name]:-} ]] || { echo "manifest 缺少 $name；尚未進行下載"; exit 1; }
done
for name in node java maven; do
 dest=$prefix/$name
 if [[ -e $dest || -L $dest ]]; then
  [[ -f $dest/.ai-team-sha256 && $(<"$dest/.ai-team-sha256") == "${sums[$name]}" ]] || { echo "$dest 已存在但未受本工具管理，拒絕覆蓋"; exit 1; }
  continue
 fi
 stage=$(mktemp -d "$prefix/.ai-team-$name.XXXXXX")
 curl --fail --location --proto '=https' --proto-redir '=https' --retry 3 --connect-timeout 30 --max-time 1800 --silent --show-error "${urls[$name]}" -o "$stage/archive"
 printf '%s  %s\n' "${sums[$name]}" "$stage/archive" | sha256sum -c -
 mkdir "$stage/unpacked"
 tar -xf "$stage/archive" -C "$stage/unpacked" --strip-components=1 --no-same-owner
 entry=$name; [[ $name == java ]] && entry=javac; [[ $name == maven ]] && entry=mvn
 test -x "$stage/unpacked/bin/$entry"
 printf '%s\n' "${sums[$name]}" > "$stage/unpacked/.ai-team-sha256"
 mv "$stage/unpacked" "$dest"
 echo "$name 已安裝；下載與失敗現場保留於 $stage"
done
export JAVA_HOME="$prefix/java"
export PATH="$prefix/node/bin:$prefix/java/bin:$prefix/maven/bin:$PATH"
node --version
javac -version
mvn --version
echo '工具鏈完成；CLI 安裝、登入與系統前置條件請繼續 INSTALL.md。'
