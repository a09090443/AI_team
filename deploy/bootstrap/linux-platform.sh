#!/usr/bin/env bash
# 純函式供偵測與 fixture 測試使用；不 source 外部 os-release。
os_value() {
 local file=$1 key=$2 line value
 [[ -r $file ]] || return 0
 while IFS= read -r line || [[ -n $line ]]; do
  [[ $line == "$key="* ]] || continue
  value=${line#*=}
  value=${value%$'\r'}
  if [[ $value == \"*\" || $value == \'*\' ]]; then value=${value:1:${#value}-2}; fi
  printf '%s\n' "$value"
  return 0
 done < "$file"
}
linux_family() {
 case $1 in
  debian|ubuntu) echo debian; return;;
  rocky|almalinux) echo rhel; return;;
 esac
 # 衍生系統只辨識家族，不因此取得「實測支援」資格。
 case " $2 " in
  *' debian '*|*' ubuntu '*) echo debian;;
  *' rhel '*|*' centos '*|*' fedora '*) echo rhel;;
  *) echo unknown;;
 esac
}
platform_block() {
 [[ $1 == Linux ]] || { echo '需要 Linux kernel；不接受 Windows Git Bash 等環境'; return; }
 [[ $2 == x86_64 ]] || { echo "架構 $2 尚無已驗證的工具鏈 manifest；目前只開放 x86_64"; return; }
 [[ $3 == glibc ]] || { echo '需要 glibc；其他 libc 尚未支援目前工具鏈'; return; }
}
