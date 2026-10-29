#!/usr/bin/env bash
# Agent VM 端：把管理者建好的沙箱 VM（mode sandbox）登錄給一個 orchestrator 實例，讓該實例的 agent 從 Slack 部署服務。
#
#   sandbox-target.sh keygen
#       產生 Agent VM 專用金鑰（已存在就沿用），只印出公鑰，交給建 VM 的腳本（deploy/sandbox-vm/create-sandbox-vm.sh）。
#   sandbox-target.sh register <實例 home> <target> <沙箱 IP> <known_hosts 檔> [來源路徑,逗號分隔]
#       寫直連設定、安裝並啟動該實例的部署執行器 unit，再以固定入口送唯讀 prepare 確認目標是 sandbox。
#       多個實例共用同一台沙箱時，每個實例各跑一次（target 名稱相同）。
#       來源路徑預設 "."（整個工作樹）；exec 送出的內容上限 128 MiB，repo 含大型文件或二進位檔時列出部署需要的路徑。
#
# known_hosts 檔由 create-sandbox-vm.sh 經 Vagrant 管理通道取得；不使用 ssh-keyscan、不停用指紋核對。
# 不讀出私鑰、不需要 sudo；需要 linger=yes（沿用 INSTALL 的前置條件）。
set -euo pipefail
umask 077
key=${AI_TEAM_SANDBOX_KEY:-$HOME/.ssh/ai-team-sandbox-ed25519}

case ${1:-} in
 keygen)
  if [[ ! -f $key ]]; then
   mkdir -p "$(dirname "$key")"
   ssh-keygen -q -t ed25519 -N '' -C "ai-team-sandbox@$(hostname)" -f "$key"
  fi
  chmod 600 "$key"
  cat "$key.pub";;
 register)
  home=${2:?用法：sandbox-target.sh register <實例 home> <target> <沙箱 IP> <known_hosts 檔>}
  target=${3:?缺少 target 名稱}; address=${4:?缺少沙箱 IP}; hosts=${5:?缺少 known_hosts 檔}
  [[ $target =~ ^[a-z0-9][a-z0-9-]{0,63}$ ]] || { echo "target 名稱只能是小寫英數與連字號：$target"; exit 1; }
  paths=${6:-.}
  [[ $address =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || { echo "沙箱 IP 格式不對：$address"; exit 1; }
  home=$(realpath "$home")
  scripts=$home/teams/skills/service-build/scripts
  test -f "$scripts/ssh_executor.py" || { echo "找不到實例的 service-build 腳本：$scripts"; exit 1; }
  test -f "$key" || { echo "缺少金鑰，先執行 sandbox-target.sh keygen"; exit 1; }
  grep -Eq "^$address ssh-ed25519 [A-Za-z0-9+/=]+" "$hosts" || { echo "known_hosts 檔沒有 $address 的 ed25519 主機公鑰：$hosts"; exit 1; }
  instance=$(basename "$home")
  dir=$HOME/.config/ai-team/ssh-targets; mkdir -p "$dir"
  known=$dir/$target.known_hosts
  install -m 600 "$hosts" "$known"
  config=$dir/$target-$instance.json
  # source_paths 預設 ["."]：sandbox 目標收整個工作樹（exec 仍排除 .git、.ssh、.secrets、.env*）。
  python3 - "$config" "$target" "$address" "$key" "$known" "$paths" <<'PY'
import json, sys
from pathlib import Path
path, target, address, key, known, paths = sys.argv[1:]
Path(path).write_text(json.dumps({"transport": "direct-ssh", "target": target, "mode": "sandbox", "source_paths": paths.split(","),
    "destination": "aiteam@" + address, "port": 22, "identity_file": key, "known_hosts_file": known}, indent=2) + "\n")
PY
  chmod 600 "$config"
  # 先以固定協定查一個不存在的 job：能回 UNKNOWN 就代表網路、金鑰、主機指紋、受限入口與 target 名稱都正確。
  # 失敗就不安裝常駐執行器。
  out=$(cd "$scripts" && python3 - "$config" <<'PY'
import json, secrets, subprocess, sys
import ssh_client
c = ssh_client.config(sys.argv[1])
done = subprocess.run(ssh_client.command(c, "status", secrets.token_hex(16)), capture_output=True, text=True, timeout=60)
print(done.stdout.strip() or done.stderr.strip())
try:
    reply = json.loads(done.stdout.strip().splitlines()[-1])
except (ValueError, IndexError):
    reply = {}
sys.exit(0 if reply.get("target") == c["target"] and reply.get("state") == "UNKNOWN" else 1)
PY
) || { echo "$out"; echo "無法經受限入口連到沙箱，未安裝執行器（檢查網路、金鑰登錄與 known_hosts）"; exit 1; }
  unit=$instance-ssh-deploy.service
  mkdir -p "$HOME/.config/systemd/user"
  cat > "$HOME/.config/systemd/user/$unit" <<UNIT
[Unit]
Description=AI Team fixed SSH deployment executor ($instance → $target)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/bin/python3 $scripts/ssh_executor.py $home $config
Restart=on-failure
RestartSec=10
UMask=0077
KillMode=control-group

[Install]
WantedBy=default.target
UNIT
  systemctl --user daemon-reload
  systemctl --user enable --now "$unit"
  # 執行器啟動後會把固定入口推給 sandbox 目標；restricted 目標會拒絕（SANDBOX_NOT_ENABLED）。
  since=$(date '+%Y-%m-%d %H:%M:%S')
  systemctl --user restart "$unit"
  upgrade=
  for _ in $(seq 1 24); do
   sleep 5
   systemctl --user is-active --quiet "$unit" || { journalctl --user -u "$unit" -n 20 --no-pager; exit 1; }
   upgrade=$(journalctl --user -u "$unit" --since "$since" --no-pager -o cat | grep -m1 'ENTRY_UPGRADE' || true)
   [[ -z $upgrade ]] || break
  done
  echo "${upgrade:-未在 120 秒內看到 ENTRY_UPGRADE}"
  [[ $upgrade == *UPGRADE_UNCHANGED* || $upgrade == *UPGRADE_PASS* ]] \
   || { echo "目標沒有接受入口升級，可能不是 sandbox；請確認建 VM 時的服務配置"; exit 1; }
  echo "PASS：$instance 已登錄沙箱目標 $target（$address），執行器 $unit 運作中。"
  echo "下一步：在該實例的團隊配置設定 project.serviceTarget（name 為 $target），部署後重啟實例。";;
 *)
  sed -n '2,12p' "$0"; exit 1;;
esac
