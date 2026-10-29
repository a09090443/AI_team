#!/usr/bin/env bash
# sandbox-overlay.py 的 fixture 測試：補上沙箱區塊、可重複執行、保留既有服務路線並提示內建操作。
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
tool=$root/deploy/bootstrap/sandbox-overlay.py
template=$root/orchestrator/teams/maven-library.json
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# 沒有服務路線的配置：拿範本拿掉 service 相關設定當成舊專案。
python3 - "$template" "$work/plain.json" <<'PY'
import json, sys
t = json.load(open(sys.argv[1], encoding="utf-8"))
for op in ("service-prepare", "service-start", "service-health", "service-diagnose"):
    t["project"]["operations"].pop(op)
t["project"]["requiredTools"].remove("python3")
t["project"].pop("serviceTarget")
t["roles"].pop("infrastructure")
t["nodes"].pop("service"); t["nodes"].pop("service_triage")
t["nodes"]["intake"]["routes"].pop("service")
json.dump(t, open(sys.argv[2], "w", encoding="utf-8"), ensure_ascii=False, indent=2)
PY
out=$(python3 "$tool" "$work/plain.json" "$template" shared-sandbox)
grep -q '^ADDED:' <<<"$out" && grep -q '^WRITTEN:' <<<"$out"
python3 - "$work/plain.json" <<'PY'
import json, sys
t = json.load(open(sys.argv[1], encoding="utf-8"))
assert t["project"]["serviceTarget"] == {"name": "shared-sandbox", "checks": {"prepare": "service-prepare", "start": "service-start", "health": "service-health"}}
assert t["nodes"]["intake"]["routes"]["service"] == "service" and "service_triage" in t["nodes"]
assert "python3" in t["project"]["requiredTools"] and "infrastructure" in t["roles"]
assert t["project"]["operations"]["service-start"]["argv"][2:] == ["exec", "ai-team-sandbox/start.sh"]
PY
echo "PASS: 沒有服務路線的配置補上沙箱區塊與服務目標"

out=$(python3 "$tool" "$work/plain.json" "$template" shared-sandbox)
grep -q '^UNCHANGED:' <<<"$out" && ! grep -q '^ADDED:' <<<"$out"
echo "PASS: 重複執行不重複加入、不改檔"

# 既有服務路線：只改目標名稱，內建 start 列為 WARN。
python3 - "$work/plain.json" "$work/existing.json" <<'PY'
import json, sys
t = json.load(open(sys.argv[1], encoding="utf-8"))
t["project"]["operations"]["service-start"]["argv"] = ["python3", "/x/client.py", "start"]
t["project"]["serviceTarget"]["name"] = "old-host"
json.dump(t, open(sys.argv[2], "w", encoding="utf-8"), ensure_ascii=False, indent=2)
PY
out=$(python3 "$tool" "$work/existing.json" "$template" shared-sandbox)
grep -q '^KEPT:' <<<"$out" && grep -q '^WARN: 操作 service-start 使用內建 start' <<<"$out"
python3 -c 'import json,sys; t=json.load(open(sys.argv[1])); assert t["project"]["serviceTarget"]["name"]=="shared-sandbox"; assert t["project"]["operations"]["service-start"]["argv"][2]=="start"' "$work/existing.json"
echo "PASS: 既有服務路線只改目標、保留操作並提示內建操作"
