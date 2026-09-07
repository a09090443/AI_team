#!/usr/bin/env bash
# 只接受自己建立且可信任的備份目錄；不接受外來 tar，不覆蓋現場。
set -euo pipefail
umask 077
backup=$(cd "${1:?usage: restore.sh trusted-backup NEW-destination}" && pwd)
dest=$(realpath -m "${2:?missing new destination}")
[[ ! -e $dest && ! -L $dest && $dest != "$backup"/* ]] || { echo '目的地須為備份目錄外的新目錄'; exit 1; }
[[ -z $(find "$backup" -type l -print -quit) ]] || { echo '備份含符號連結，請人工檢查；未還原'; exit 1; }
(cd "$backup" && sha256sum --check SHA256SUMS)
mkdir -m 700 "$dest"
cp -a "$backup/." "$dest/"
echo "已還原到 $dest，未啟動。請補 env.sh、repo、工具鏈與重新登入，先執行 preflight。"
