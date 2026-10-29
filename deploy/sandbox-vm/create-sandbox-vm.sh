#!/usr/bin/env bash
# 在 VirtualBox 主機（Windows 用 Git Bash）一次建立 AI Team 共用沙箱 VM，產生給 Agent VM 的 known_hosts。
#
#   create-sandbox-vm.sh <管理目錄> <Agent 公鑰檔> <target 名稱>
#
# 公鑰由 Agent VM 執行 deploy/bootstrap/sandbox-target.sh keygen 取得。可用環境變數調整（預設值）：
#   AI_TEAM_SANDBOX_NAME（ai-team-sandbox）、AI_TEAM_SANDBOX_CPUS（4）、AI_TEAM_SANDBOX_MEMORY（4096，MB）、
#   AI_TEAM_SANDBOX_IP（192.168.56.20）、AI_TEAM_SANDBOX_ADAPTER（VirtualBox Host-Only Ethernet Adapter）
#
# 管理目錄已有 VM 時沿用（不重建、不 destroy）；Vagrantfile 與本版不同時停止，請先人工比對。
# 完成後把 <管理目錄>/agent-known_hosts 傳到 Agent VM，執行 sandbox-target.sh register。
set -euo pipefail
dir=${1:?用法：create-sandbox-vm.sh <管理目錄> <Agent 公鑰檔> <target 名稱>}
pubfile=${2:?缺少 Agent 公鑰檔}; target=${3:?缺少 target 名稱}
name=${AI_TEAM_SANDBOX_NAME:-ai-team-sandbox}; cpus=${AI_TEAM_SANDBOX_CPUS:-4}; memory=${AI_TEAM_SANDBOX_MEMORY:-4096}
ip=${AI_TEAM_SANDBOX_IP:-192.168.56.20}; adapter=${AI_TEAM_SANDBOX_ADAPTER:-VirtualBox Host-Only Ethernet Adapter}
here=$(cd "$(dirname "$0")" && pwd)
skill=$here/../../orchestrator/teams/skills/service-build/scripts

[[ $target =~ ^[a-z0-9][a-z0-9-]{0,63}$ && $name =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$ ]] || { echo "target／VM 名稱格式不對"; exit 1; }
[[ $cpus =~ ^[0-9]+$ && $memory =~ ^[0-9]+$ && $ip =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || { echo "CPU、記憶體或 IP 格式不對"; exit 1; }
grep -Eq '^ssh-ed25519 [A-Za-z0-9+/=]+( [^ ]+)?$' "$pubfile" || { echo "不是單行 ed25519 公鑰：$pubfile"; exit 1; }
command -v vagrant >/dev/null || { echo "找不到 vagrant"; exit 1; }

mkdir -p "$dir"
if [[ -f $dir/Vagrantfile ]] && ! cmp -s "$here/Vagrantfile" "$dir/Vagrantfile"; then
 echo "$dir/Vagrantfile 與 deploy/sandbox-vm/Vagrantfile 不同；請先比對，不覆寫"; exit 1
fi
cp "$here/Vagrantfile" "$dir/Vagrantfile"
# install/ 只放安裝受限入口需要的檔案，經 Vagrant 單向複製到 guest。
mkdir -p "$dir/install"
for file in install-ssh-target.sh ssh_target.py guest.py; do cp "$skill/$file" "$dir/install/$file"; done
cp "$pubfile" "$dir/install/agent.pub"
write_settings() {
 printf '{"name":"%s","cpus":%s,"memory":%s,"ip":"%s","host_only_adapter":"%s","target":"%s","verify_host_key":"%s"}\n' \
  "$name" "$cpus" "$memory" "$ip" "$adapter" "$target" "$1" > "$dir/sandbox.json"
}
[[ -f $dir/sandbox.json ]] && grep -q '"verify_host_key":"always"' "$dir/sandbox.json" && write_settings always || write_settings accept_new

cd "$dir"
vagrant validate
vagrant up --provider=virtualbox
# 主機公鑰經 Vagrant 管理的 SSH 通道讀取（不使用 ssh-keyscan），之後 Vagrant 也改成嚴格核對。
hostkey=$(vagrant ssh -c 'cat /etc/ssh/ssh_host_ed25519_key.pub' -- -q 2>/dev/null | tr -d '\r' | awk '/^ssh-ed25519 /{print $1" "$2; exit}')
[[ -n $hostkey ]] || { echo "讀不到 guest 的 ed25519 主機公鑰"; exit 1; }
printf '%s %s\n' "$ip" "$hostkey" > agent-known_hosts
write_settings always
vagrant ssh -c 'id aiteam && sudo test -f /etc/ai-team-service/target.json && sudo cat /etc/ai-team-service/target.json' -- -q
echo
echo "主機公鑰指紋（請在 VM console 以 ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub 核對）："
ssh-keygen -lf agent-known_hosts
echo "PASS：沙箱 VM $name（$ip）已建立；把 $dir/agent-known_hosts 傳到 Agent VM，對每個實例執行"
echo "  deploy/bootstrap/sandbox-target.sh register <實例 home> $target $ip <known_hosts 檔>"
