#!/usr/bin/env python3
"""Service request entry; fixed execution runs on the Agent VM and uses SSH to the target."""
import json
import os
from pathlib import Path
import re
import subprocess
import sqlite3
from contextlib import closing
import sys
import time
import uuid

ACTIONS = {"prepare", "start", "health", "diagnose"}
RELATIVE = Path("docs/docker/sit/.agent-service")


def workspace():
    root = Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip())
    if not re.fullmatch(r"task-[0-9]+", root.name) or root.resolve() != root.absolute():
        raise ValueError("service tool requires a real task workspace")
    return root


def directory(root):
    path = root / RELATIVE
    for ancestor in [path, *path.parents]:
        if ancestor == root:
            break
        if ancestor.is_symlink():
            raise ValueError("service queue cannot contain symlinks")
    path.mkdir(parents=True, exist_ok=True)
    (path / ".gitignore").write_text("*\n", encoding="utf-8")
    return path


def source_head(root):
    return subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()


def atomic_json(path, data):
    import tempfile
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.is_symlink() or path.parent.resolve() != path.parent.absolute():
        raise ValueError("unsafe operation path")
    fd, name = tempfile.mkstemp(dir=path.parent, prefix=".operation-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as out:
            json.dump(data, out)
            out.flush()
            os.fsync(out.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def cancellation_requested(home, job):
    path = home / "operation-cancellations" / job["task"] / (job["id"]+".json")
    if not path.exists():
        return False
    if path.is_symlink() or path.resolve()!=path.absolute() or path.stat().st_size>4096:
        return False
    try:
        marker=json.loads(path.read_text())
        return (type(marker.get("version")) is int and marker["version"]==1
                and marker.get("id")==job["id"] and marker.get("task")==job["task"]
                and marker.get("node")==job.get("node") and marker.get("reason")=="user_interrupt"
                and type(marker.get("requested_at")) is int)
    except (ValueError,OSError,AttributeError):
        return False


def submit(action, timeout=30):
    if action not in ACTIONS:
        raise ValueError("unsupported service action")
    root = workspace()
    queue = directory(root)
    pending = queue / "request.json"
    head = None
    if pending.exists():
        request = json.loads(pending.read_text())
        if request.get("action") != action:
            old = queue / (request["id"] + ".json")
            if not old.exists() or json.loads(old.read_text()).get("exit_code") == 75:
                print(json.dumps(dict(status="OPERATION_PENDING",execution_state="RUNNING",
                                      id=request["id"],action=request["action"],task=request["task"])))
                return 0
            pending.unlink()
    if not pending.exists():
        head = source_head(root)
        # Only trusted executor receipts can be reused as checkpoints.
        jobs = root.parent.parent / "operation-jobs" / root.name[5:]
        if action in {"prepare", "start"}:
            for path in sorted(jobs.glob("*.json"), key=lambda p: p.stat().st_mtime, reverse=True):
                job = json.loads(path.read_text())
                if (job.get("source_head") == head and job.get("action") == action
                        and job.get("verified") is True and job.get("state") == "SUCCEEDED" and job.get("result")
                        and job.get("task") == root.name[5:] and job["result"].get("exit_code") == 0):
                    with closing(sqlite3.connect((root.parent.parent / "ai-team.db").as_uri()+"?mode=ro",uri=True)) as db:
                        row = db.execute("select data from flow_run where task_id=?",(root.name[5:],)).fetchone()
                    current = json.loads(row[0]) if row else {}
                    target = current.get("snapshot",{}).get("definition",{}).get("project",{}).get("serviceTarget",{}).get("name")
                    if job.get("target") != target or job.get("node") != current.get("activeNode"):
                        continue
                    print(json.dumps(job["result"], ensure_ascii=False))
                    return 0
        request = {"id": uuid.uuid4().hex, "action": action, "task": root.name[5:],
                   "created": int(time.time()), "source_head": head}
        atomic_json(pending, request)
    deadline = time.monotonic() + timeout
    while True:
        result = queue / (request["id"] + ".json")
        if result.exists():
            data = json.loads(result.read_text())
            if data.get("id") != request["id"] or data.get("action") != action:
                raise ValueError("service receipt mismatch")
            if data["exit_code"] != 75:
                print(json.dumps(data, ensure_ascii=False))
                pending.unlink(missing_ok=True)
                return int(data["exit_code"])
        if result.exists() and data["exit_code"] == 75:
            print(json.dumps({"status":"OPERATION_PENDING","execution_state":"RUNNING","id":request["id"],"action":action,"task":request["task"]}))
            return 0
        accepted = queue / (request["id"] + ".accepted")
        trusted_job = root.parent.parent / "operation-jobs" / request["task"] / (request["id"]+".json")
        if not accepted.exists() and not trusted_job.exists():
            if not authorized(root):
                print("TASK_NOT_ACTIVE: request retained")
                return 75
            heartbeat = root.parent.parent / "operation-executor-heartbeat"
            if not heartbeat.exists() or time.time() - heartbeat.stat().st_mtime > 30:
                print("SERVICE_EXECUTOR_OFFLINE: request retained")
                return 75
        if time.monotonic() >= deadline:
            print(json.dumps({"status": "OPERATION_PENDING", "execution_state": "RUNNING", "id": request["id"],
                              "action": action, "task": request["task"]}))
            return 0
        time.sleep(min(2, max(0, deadline - time.monotonic())))


def broker_read(root):
    queue = root / RELATIVE
    if queue.is_symlink() or queue.resolve() != queue.absolute():
        raise ValueError("unsafe queue")
    pending = queue / "request.json"
    if not pending.exists():
        return None
    if pending.is_symlink() or pending.stat().st_size > 2048:
        raise ValueError("unsafe request")
    request = json.loads(pending.read_text())
    if (not {"id", "action", "task", "created"} <= set(request)
            or not re.fullmatch(r"[0-9a-f]{32}", request["id"])
            or request["action"] not in ACTIONS or request["task"] != root.name[5:]
            or not isinstance(request["created"], int)
            or not 0 <= time.time() - request["created"] <= 7200):
        raise ValueError("invalid or expired request")
    return request


def authorized(root, target_name="bestpartner-dev"):
    database = root.parent.parent / "ai-team.db"
    with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as conn:
        row = conn.execute("select task.state,flow_run.data from task join flow_run on task.id=flow_run.task_id where task.id=?",
                           (root.name[5:],)).fetchone()
    if row is None or row[0] != "STAGE_RUNNING":
        return False
    flow = json.loads(row[1])
    node = flow.get("snapshot", {}).get("definition", {}).get("nodes", {}).get(flow.get("node"), {})
    target = flow.get("snapshot", {}).get("definition", {}).get("project", {}).get("serviceTarget")
    return (node.get("gate") == "SERVICE" and flow.get("activeNode") == flow.get("node")
            and target and target.get("name") == target_name)


def main():
    if len(sys.argv) == 2:
        return submit(sys.argv[1])
    # Host-side transport entry: root is chosen from the administrator's task allowlist.
    if len(sys.argv) not in {3, 4}:
        raise ValueError("usage: client.py prepare|start|health|diagnose")
    mode, path = sys.argv[1:3]
    target_name = sys.argv[3] if len(sys.argv) == 4 else "bestpartner-dev"
    root = Path(path)
    if mode == "broker-tasks":
        if root.resolve() != root.absolute() or not root.is_dir():
            raise ValueError("invalid agent home")
        with closing(sqlite3.connect((root / "ai-team.db").as_uri() + "?mode=ro", uri=True)) as conn:
            tasks = [r[0] for r in conn.execute("select id from task where state='STAGE_RUNNING'")]
        print(json.dumps([t for t in tasks if re.fullmatch(r"[0-9]+", t)
                          and authorized(root / "workspaces" / ("task-" + t), target_name)]))
        return 0
    if not root.is_dir() or root.resolve() != root.absolute() or not re.fullmatch(r"task-[0-9]+", root.name):
        raise ValueError("invalid broker workspace")
    if mode == "broker-read":
        queue = directory(root)
        (queue / "broker-heartbeat").touch()
        print(json.dumps(broker_read(root) if authorized(root, target_name) else None))
    elif mode == "broker-accepted":
        request = broker_read(root)
        if not request or not authorized(root, target_name):
            raise ValueError("service stage is no longer active")
        with (root / RELATIVE / (request["id"] + ".accepted")).open("x") as out:
            out.write(str(int(time.time())))
    elif mode == "broker-result":
        data = json.load(sys.stdin)
        request = broker_read(root)
        if not request or data.get("id") != request["id"] or data.get("action") != request["action"]:
            raise ValueError("stale result")
        queue = root / RELATIVE
        destination = queue / (request["id"] + ".json")
        temp = queue / (request["id"] + ".tmp")
        with temp.open("x", encoding="utf-8") as out:
            json.dump(data, out)
        os.replace(temp, destination)
    elif mode == "broker-source":
        if not authorized(root, target_name):
            raise ValueError("service stage is no longer active")
        request = broker_read(root)
        if not request or request["action"] != "start":
            raise ValueError("no authorized start request")
        # Only committed, reviewed product files. Agent changes are delivered after system commit.
        result = subprocess.run(["git", "-C", str(root), "archive", "HEAD", "bestpartner-service",
                                 "docs/docker/sit", "docs/sql"], stdout=sys.stdout.buffer)
        return result.returncode
    else:
        raise ValueError("unknown broker mode")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.SubprocessError, KeyError) as exc:
        print("Service tool stopped: " + str(exc), file=sys.stderr)
        sys.exit(1)
