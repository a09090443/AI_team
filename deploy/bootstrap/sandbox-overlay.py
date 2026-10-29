#!/usr/bin/env python3
"""讓一份團隊配置接上沙箱目標（sandbox-onboard.sh 呼叫）。

用法：sandbox-overlay.py <要修改的團隊配置 json> <內附範本 json> <target 名稱>

- 還沒有 service 節點：從範本複製沙箱區塊（四個 service 操作、infrastructure 角色、service／service_triage 節點、
  需求節點的 service 路線、python3 工具），再設定 serviceTarget。
- 已有 service 節點：保留原本的操作與路徑，只改 serviceTarget 的名稱；仍使用內建 start／health／diagnose 的操作
  在沙箱目標不能用，列為 WARN 讓管理者改成 exec。
可重複執行；結果相同時不改檔。只改傳入的檔案，備份與驗證由呼叫端負責。
"""
import json
import sys
from pathlib import Path

SERVICE_OPS = ("service-prepare", "service-start", "service-health", "service-diagnose")
DEFAULT_CHECKS = {"prepare": "service-prepare", "start": "service-start", "health": "service-health"}
BUILTIN_NEEDS_SERVICE = {"start", "health", "diagnose"}


def builtin_action(argv):
    """client.py 後面接的內建動作；exec 或非 client.py 操作回 None。"""
    for index, value in enumerate(argv[:-1]):
        if value.endswith("/client.py"):
            return argv[index + 1]
    return None


def apply(team, template, target):
    notes = []
    project, nodes, roles = team["project"], team["nodes"], team["roles"]
    if "service" not in nodes:
        operations = project.setdefault("operations", {})
        for op in SERVICE_OPS:
            operations[op] = template["project"]["operations"][op]
        tools = project.setdefault("requiredTools", [])
        if "python3" not in tools:
            tools.append("python3")
        client_tool = next(t for t in template["roles"]["infrastructure"]["tools"] if "client.py" in t)
        if "infrastructure" not in roles:
            roles["infrastructure"] = template["roles"]["infrastructure"]
        elif client_tool not in roles["infrastructure"]["tools"]:
            roles["infrastructure"]["tools"].append(client_tool)
        nodes["service"] = template["nodes"]["service"]
        nodes["service_triage"] = template["nodes"]["service_triage"]
        intake = next(id for id, node in nodes.items() if node.get("gate") == "REQUIREMENTS")
        nodes[intake].setdefault("routes", {})["service"] = "service"
        notes.append("ADDED: 沙箱服務路線（service、service_triage、四個 service 操作、infrastructure 角色、" + intake + " 的 service 路線）")
        checks = dict(DEFAULT_CHECKS)
    else:
        checks = (project.get("serviceTarget") or {}).get("checks") or dict(DEFAULT_CHECKS)
        for op_id, op in project.get("operations", {}).items():
            if "service" in op.get("nodes", []) and builtin_action(op.get("argv", [])) in BUILTIN_NEEDS_SERVICE:
                notes.append("WARN: 操作 " + op_id + " 使用內建 " + builtin_action(op["argv"])
                             + "，沙箱目標不支援；請改成 client.py exec <腳本>")
        notes.append("KEPT: 既有服務路線與操作，只更新服務目標")
    project["serviceTarget"] = {"name": target, "checks": checks}
    return notes


def main():
    if len(sys.argv) != 4:
        sys.exit("用法：sandbox-overlay.py <團隊配置 json> <內附範本 json> <target 名稱>")
    path, template_path, target = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
    before = path.read_text(encoding="utf-8")
    team = json.loads(before)
    notes = apply(team, json.loads(template_path.read_text(encoding="utf-8")), target)
    after = json.dumps(team, ensure_ascii=False, indent=2) + "\n"
    if json.loads(after) != json.loads(before):
        path.write_text(after, encoding="utf-8")
        notes.append("WRITTEN: " + str(path))
    else:
        notes.append("UNCHANGED: " + str(path))
    print("\n".join(notes))


if __name__ == "__main__":
    main()
