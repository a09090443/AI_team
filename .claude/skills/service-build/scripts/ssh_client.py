#!/usr/bin/env python3
"""Synchronous Agent VM -> target VM SSH deployment. No Windows worker."""
import hashlib
import json
import re
import subprocess
import sys
import time
import uuid
from pathlib import Path
import client

ACTIONS = {"prepare", "start", "health", "diagnose"}
MAX_ARCHIVE = 128 * 1024 * 1024

def config(path):
    p = Path(path)
    if p.is_symlink() or not p.is_file() or p.stat().st_size > 16384:
        raise ValueError("SSH_CONFIG_REQUIRED: administrator target configuration is missing or invalid")
    c = json.loads(p.read_text(encoding="utf-8"))
    if c.get("transport") != "direct-ssh" or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", c.get("target", "")):
        raise ValueError("direct-ssh target required; Windows broker fallback is not allowed")
    if not re.fullmatch(r"[a-z_][a-z0-9_-]*@[A-Za-z0-9][A-Za-z0-9.-]*", c.get("destination", "")):
        raise ValueError("invalid SSH destination")
    if type(c.get("port")) is not int or not 1 <= c["port"] <= 65535:
        raise ValueError("invalid SSH port")
    for key in ("identity_file", "known_hosts_file"):
        f = Path(c[key])
        if not f.is_absolute() or f.is_symlink() or not f.is_file():
            raise ValueError("SSH_ENROLLMENT_REQUIRED: missing enrolled key or host fingerprint")
    return c

def command(c, action, request_id, digest=None):
    if action not in ACTIONS | {"result", "status", "cancel"} or not re.fullmatch(r"[0-9a-f]{32}", request_id):
        raise ValueError("invalid fixed operation")
    remote = "ai-team-service " + c["target"] + " " + action + " " + request_id
    if action == "start":
        if not digest or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError("archive digest required")
        remote += " " + digest
    return ["ssh", "-T", "-o", "BatchMode=yes", "-o", "PasswordAuthentication=no", "-o", "KbdInteractiveAuthentication=no",
            "-o", "StrictHostKeyChecking=yes", "-o", "IdentitiesOnly=yes", "-o", "ConnectTimeout=10",
            "-o", "UserKnownHostsFile=" + c["known_hosts_file"], "-i", c["identity_file"],
            "-p", str(c["port"]), c["destination"], remote]

class SourcePreflightError(ValueError):
    pass

def archive_source(root):
    if subprocess.check_output(["git", "-C", str(root), "status", "--porcelain"]).strip():
        raise SourcePreflightError("SOURCE_WORKSPACE_DIRTY: commit the approved deployment changes before start; SSH was not attempted")
    paths = ["bestpartner-service", "docs/docker/sit", "docs/sql"]
    existing = subprocess.check_output(["git", "-C", str(root), "ls-tree", "-z", "--name-only", "HEAD", "--", *paths]).decode("utf-8").split("\0")
    missing = [path for path in paths if path not in existing]
    if missing:
        raise SourcePreflightError("SOURCE_MISSING_PATHS: committed deployment source lacks " + ", ".join(missing) + "; add the missing deployment files and commit them; SSH was not attempted")
    data = subprocess.check_output(["git", "-C", str(root), "archive", "HEAD", *paths])
    if len(data) > MAX_ARCHIVE:
        raise SourcePreflightError("SOURCE_TOO_LARGE: deployment archive exceeds approved size; SSH was not attempted")
    return data

def run(config_file, action):
    if action not in ACTIONS:
        raise ValueError("unsupported action")
    c = config(config_file)
    root = client.workspace()
    existing_queue = root / client.RELATIVE
    existing = existing_queue / "request.json"
    if existing.exists():
        req = json.loads(existing.read_text())
        cached = existing_queue / (req["id"] + ".json")
        if req.get("action") == action and cached.exists():
            value = json.loads(cached.read_text())
            if value.get("exit_code") != 75:
                print(json.dumps(value))
                return value["exit_code"]
    if not client.authorized(root, c["target"]):
        raise ValueError("service stage is not active for this target")
    queue = client.directory(root)
    pending = queue / "request.json"
    if pending.exists():
        # Preserve an old broker request ID; it is never rewritten to fake an active stage.
        if pending.is_symlink() or pending.stat().st_size > 2048:
            raise ValueError("unsafe pending request")
        request = json.loads(pending.read_text())
        if (not {"id","action","task","created"} <= set(request) or request["task"] != root.name[5:]
                or not re.fullmatch(r"[0-9a-f]{32}",request["id"]) or type(request["created"]) is not int):
            raise ValueError("invalid pending request")
        if (queue / (request["id"] + ".accepted")).exists() and not (queue / (request["id"] + ".json")).exists() and not (queue / (request["id"] + ".ssh-accepted")).exists():
            raise ValueError("legacy worker accepted this request; reconcile its receipt before switching transport")
        if request["action"] != action:
            raise ValueError("previous action pending; retrieve its result first")
    else:
        request = {"id": uuid.uuid4().hex, "action": action, "task": root.name[5:], "created": int(time.time())}
        with pending.open("x", encoding="utf-8") as out:
            json.dump(request, out)
    data = None
    digest = None
    if action == "start":
        try:
            data = archive_source(root)
        except SourcePreflightError as error:
            receipt = dict(id=request["id"],action=action,target=c["target"],source_sha256=None,
                           exit_code=1,completed=int(time.time()),evidence=[str(error)])
            (queue / (request["id"] + ".json")).write_text(json.dumps(receipt), encoding="utf-8")
            pending.unlink()
            print(json.dumps(receipt, ensure_ascii=False))
            return 1
        digest = hashlib.sha256(data).hexdigest()
    if not client.authorized(root, c["target"]) and not (queue / (request["id"]+".ssh-accepted")).exists():
        raise ValueError("service stage stopped before SSH execution")
    try:
        result = subprocess.run(command(c, action, request["id"], digest), input=data, capture_output=True, timeout=1250)
    except subprocess.TimeoutExpired:
        print("SSH_OPERATION_UNCERTAIN: request retained; retry same action to retrieve receipt")
        return 75
    if not result.stdout or len(result.stdout) > 65536:
        print("SSH_CONNECTION_FAILED: no valid receipt; verify network, pinned fingerprint, public key and restricted target entry; request retained")
        return 75
    try:
        receipt = json.loads(result.stdout)
    except (ValueError, UnicodeError):
        raise ValueError("invalid target receipt; raw SSH output withheld")
    if receipt.get("id") == request["id"] and receipt.get("target") == c["target"] and receipt.get("exit_code") == 75:
        print(json.dumps({"status":"OPERATION_PENDING","id":request["id"]}))
        return 75
    if (receipt.get("id") != request["id"] or receipt.get("action") != action or receipt.get("target") != c["target"]
            or type(receipt.get("exit_code")) is not int or receipt.get("source_sha256") != digest):
        raise ValueError("SSH receipt mismatch; request retained")
    if receipt["exit_code"] == 0 and result.returncode != 0:
        raise ValueError("SSH transport failed despite success claim; request retained")
    marker={"prepare":"PREPARED:","start":"START_PASS:","health":"HEALTH_PASS:","diagnose":"DIAGNOSIS:"}[action]
    evidence=receipt.get("evidence")
    if (not isinstance(evidence,list) or not evidence or any(not isinstance(x,str) or len(x)>1500 for x in evidence)
            or type(receipt.get("completed")) is not int
            or (receipt["exit_code"] == 0 and not any(x.startswith(marker) for x in evidence))):
        raise ValueError("incomplete target receipt; request retained")
    # Project only the protocol fields, never relay arbitrary logs or credentials.
    safe = {k: receipt.get(k) for k in ("id", "action", "target", "exit_code", "source_sha256", "completed", "evidence")}
    (queue / (request["id"] + ".json")).write_text(json.dumps(safe), encoding="utf-8")
    print(json.dumps(safe, ensure_ascii=False))
    if receipt["exit_code"] != 75:
        pending.unlink()
    return receipt["exit_code"]



def validate_receipt(receipt, c, action, job_id, digest):
    marker = {"prepare":"PREPARED:", "start":"START_PASS:", "health":"HEALTH_PASS:", "diagnose":"DIAGNOSIS:"}[action]
    if (receipt.get("id") != job_id or receipt.get("target") != c["target"]
            or receipt.get("action") != action or receipt.get("source_sha256") != digest
            or type(receipt.get("exit_code")) is not int or type(receipt.get("completed")) is not int
            or not isinstance(receipt.get("evidence"), list)
            or any(not isinstance(x,str) or len(x)>1500 for x in receipt["evidence"])
            or (receipt["exit_code"] == 0 and not any(x.startswith(marker) for x in receipt["evidence"]))):
        raise ValueError("remote receipt mismatch")


def remote_query(c, action, job_id):
    proc = subprocess.run(command(c, action, job_id), capture_output=True, timeout=100 if action=="cancel" else 20)
    if proc.returncode or not proc.stdout or len(proc.stdout)>65536:
        return None
    data = json.loads(proc.stdout)
    if (not isinstance(data,dict) or data.get("id") != job_id or data.get("target") != c["target"]
            or type(data.get("exit_code")) is not int):
        raise ValueError("invalid remote status identity")
    return data


def remote_result(c, job_id):
    return remote_query(c,"result",job_id)


def execute_job(c, root, job, allow_launch=True, cancel=False):
    # UNKNOWN from a verified target is different from an unconfirmed SSH exchange.
    receipt = remote_result(c, job["id"])
    digest = job.get("source_sha256")
    if receipt is None:
        if job.get("launched") or not allow_launch:
            return None
    elif receipt["exit_code"] == 75:
        if cancel:
            receipt = remote_query(c,"cancel",job["id"])
            if not receipt or receipt["exit_code"] == 75:
                return None
        elif receipt.get("state") != "UNKNOWN" or not allow_launch:
            return None
        else:
            receipt = None
    if receipt is None:
        if cancel or not allow_launch or time.time() > job.get("deadline",float("inf")):
            return None
        if (not client.authorized(root,c["target"])
                or client.cancellation_requested(root.parent.parent,job)):
            return None
        if client.source_head(root) != job["source_head"]:
            raise ValueError("source HEAD changed")
        data = archive_source(root) if job["action"] == "start" else None
        new_digest = hashlib.sha256(data).hexdigest() if data else None
        if digest is not None and new_digest != digest:
            raise ValueError("source archive changed during same-ID retry")
        digest = new_digest
        job["source_sha256"] = digest
        job["launched"] = True
        client.atomic_json(root.parent.parent / "operation-jobs" / job["task"] / (job["id"]+".json"), job)
        # Recheck immediately before the only operation that may launch a remote job.
        if (not client.authorized(root,c["target"])
                or client.cancellation_requested(root.parent.parent,job)):
            return None
        proc = subprocess.run(command(c, job["action"], job["id"], digest),
                              input=data, capture_output=True, timeout=30)
        if proc.returncode or not proc.stdout or len(proc.stdout)>65536:
            return None
        receipt = json.loads(proc.stdout)
        if receipt.get("exit_code") == 75:
            return None
    validate_receipt(receipt, c, job["action"], job["id"], digest)
    if job["action"] == "health" and receipt["exit_code"] == 0:
        jobs = root.parent.parent / "operation-jobs" / job["task"]
        starts = [json.loads(p.read_text()) for p in jobs.glob("*.json")]
        starts = [j for j in starts if j.get("action")=="start" and j.get("verified") is True
                  and j.get("state")=="SUCCEEDED" and j.get("source_head")==job["source_head"]
                  and j.get("target")==job["target"] and j.get("task")==job["task"]
                  and j.get("node")==job.get("node")
                  and isinstance(j.get("result"),dict) and j["result"].get("exit_code")==0]
        if not starts:
            raise ValueError("health has no verified current start")
        start = max(starts, key=lambda j:j["updated"])
        releases = [x for x in start["result"]["evidence"] if x.startswith("START_PASS: release=")]
        if not releases or not any(x.startswith("HEALTH_PASS: release="+releases[0].split("release=",1)[1]+";") for x in receipt["evidence"]):
            raise ValueError("health release does not match current start")
        receipt["start_id"] = start["id"]
        receipt["start_source_sha256"] = start["source_sha256"]
    return receipt

if __name__ == "__main__":
    try:
        if len(sys.argv) != 3:
            raise ValueError("usage: ssh_client.py administrator-config prepare|start|health|diagnose")
        sys.exit(run(sys.argv[1], sys.argv[2]))
    except (ValueError, KeyError, OSError, subprocess.SubprocessError):
        print("DIRECT_SSH_STOPPED: configuration, authorization or receipt validation failed; no Windows broker fallback", file=sys.stderr)
        sys.exit(1)
