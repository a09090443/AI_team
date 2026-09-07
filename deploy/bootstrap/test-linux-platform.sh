#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/linux-platform.sh"
[[ $(linux_family ubuntu '') == debian ]]
[[ $(linux_family debian '') == debian ]]
[[ $(linux_family rocky 'rhel centos fedora') == rhel ]]
[[ $(linux_family almalinux 'rhel centos fedora') == rhel ]]
[[ $(linux_family derivative 'debian') == debian ]]
[[ $(linux_family unknown 'notdebian') == unknown ]]
[[ -z $(platform_block Linux x86_64 glibc) ]]
[[ $(platform_block Linux aarch64 glibc) == *架構* ]]
[[ $(platform_block Linux x86_64 musl) == *glibc* ]]
[[ $(platform_block MINGW64_NT x86_64 glibc) == *Linux* ]]
fixture=$(mktemp -d)
printf 'ID="rocky"\nVERSION_ID="9.4"\nID_LIKE="rhel centos fedora"\nUNUSED=$(touch /DO_NOT_EXECUTE)\n' > "$fixture/os-release"
[[ $(os_value "$fixture/os-release" ID) == rocky ]]
[[ $(os_value "$fixture/os-release" VERSION_ID) == 9.4 ]]
[[ $(os_value "$fixture/os-release" ID_LIKE) == 'rhel centos fedora' ]]
[[ $(os_value "$fixture/os-release" UNUSED) == '$(touch /DO_NOT_EXECUTE)' ]]
[[ -z $(os_value "$fixture/os-release" MISSING) ]]
[[ -z $(os_value "$fixture/nonexistent" ID) ]]
echo 'PASS：16 項平台分類／架構與 libc 拒絕／安全文字解析斷言；這是 fixture 測試，不是 Linux 實機驗收。'
