#!/usr/bin/env bash
# 只檢查與提供診斷，不安裝套件、不改 AppArmor/SELinux/sysctl。
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/linux-platform.sh"
mode=${1:-runtime}
[[ $mode == tools || $mode == runtime ]] || { echo 'usage: check-linux.sh tools|runtime'; exit 2; }
libc=unknown
if command -v getconf >/dev/null && getconf GNU_LIBC_VERSION >/dev/null 2>&1; then libc=glibc; fi
reason=$(platform_block "$(uname -s)" "$(uname -m)" "$libc")
[[ -z $reason ]] || { echo "BLOCKED：$reason"; exit 1; }
release=/etc/os-release
[[ -r $release ]] || release=/usr/lib/os-release
id=$(os_value "$release" ID)
version=$(os_value "$release" VERSION_ID)
family=$(linux_family "$id" "$(os_value "$release" ID_LIKE)")
printf '環境：ID=%s VERSION_ID=%s family=%s arch=%s\n' "${id:-unknown}" "${version:-unknown}" "$family" "$(uname -m)"
echo '辨識發行版不等於已通過重建驗收，請查閱 linux-support.md。'
case $family in
 debian) echo '套件處理：由管理員依此版本的 apt 套件目錄處理缺項。';;
 rhel) echo '套件處理：由管理員依此版本的 dnf 套件目錄處理缺項。';;
 *) echo '未知家族：只做能力檢查，套件與安全政策須人工確認。';;
esac
failed=0
required=(bash curl sha256sum tar xz mktemp realpath flock)
if [[ $mode == runtime ]]; then required+=(git jq sqlite3 systemctl systemd-run unshare ss pgrep); fi
for tool in "${required[@]}"; do
 command -v "$tool" >/dev/null || { echo "BLOCKED：缺少指令 $tool；請管理員安裝對應套件後重試"; failed=1; }
done
if [[ $mode == runtime ]]; then
 if [[ ! -r /proc/1/comm || $(< /proc/1/comm) != systemd ]]; then
  echo 'BLOCKED：PID 1 不是 systemd，現有 ExecutionSandbox 不支援此 init 配置'; failed=1
 fi
 if [[ ! -r /sys/fs/cgroup/cgroup.controllers ]]; then
  echo 'BLOCKED：未偵測到 cgroup v2 controllers；此建置配置不放行'; failed=1
 else
  controllers=" $(< /sys/fs/cgroup/cgroup.controllers) "
  for controller in cpu memory pids; do
   [[ $controllers == *" $controller "* ]] || { echo "BLOCKED：cgroup 缺少 $controller controller"; failed=1; }
  done
 fi
 if command -v systemctl >/dev/null && ! systemctl --user show-environment >/dev/null 2>&1; then
  echo 'BLOCKED：user manager／user bus 不可用；請管理員檢查登入 session、PAM 與 user service'; failed=1
 fi
 if command -v unshare >/dev/null && ! unshare -Ur true; then
  echo 'BLOCKED：user namespace 探測失敗；請查核核心設定與安全稽核紀錄，不自行推定是 AppArmor 或 SELinux'; failed=1
 fi
 command -v getenforce >/dev/null && { printf 'SELinux 狀態：'; getenforce; } || true
 if [[ -r /sys/module/apparmor/parameters/enabled ]]; then printf 'AppArmor enabled：'; head -n 1 /sys/module/apparmor/parameters/enabled; fi
fi
[[ $failed == 0 ]] || exit 1
echo "PASS：$mode 前置能力檢查；尚未證實隔離有效、cgroup user delegation 或 CLI 可執行。啟動時仍須通過沙箱實測。"
