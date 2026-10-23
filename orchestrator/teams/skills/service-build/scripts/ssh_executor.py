#!/usr/bin/env python3
"""Trusted Agent VM operation journal and SSH executor."""
import argparse
from contextlib import closing
import json
import hashlib
from pathlib import Path
import re
import sqlite3
import time
import client
import ssh_client


def save(home, job):
    result = job.get("result")
    if job.get("state") == "SUCCEEDED" and (not job.get("verified") or not result or result.get("exit_code") != 0):
        raise ValueError("success journal invariant")
    if job.get("state") == "FAILED" and (not result or type(result.get("exit_code")) is not int or result["exit_code"] in {0,75}):
        raise ValueError("failure journal invariant")
    job["updated"] = int(time.time())
    client.atomic_json(home / "operation-jobs" / job["task"] / (job["id"] + ".json"), job)


def cancellation_requested(home, job):
    return client.cancellation_requested(home,job)


def poll(home, config):
    c = ssh_client.config(config)
    (home / "operation-executor-heartbeat").touch(mode=0o600)
    with closing(sqlite3.connect((home / "ai-team.db").as_uri() + "?mode=ro", uri=True)) as db:
        rows = db.execute("select task.id,task.state,flow_run.data from task join flow_run on task.id=flow_run.task_id").fetchall()
    for task, task_state, raw in rows:
        task = str(task)
        if not re.fullmatch(r"[0-9]+", task):
            continue
        root = home / "workspaces" / ("task-" + task)
        if not root.is_dir() or root.resolve() != root.absolute():
            continue
        try:
            flow = json.loads(raw)
        except (ValueError,TypeError):
            continue
        jobs_dir = home / "operation-jobs" / task
        if not isinstance(flow,dict):
            continue
        definition,node,service_target = client.service_parts(flow)
        target = service_target.get("name")
        service = node.get("gate")=="SERVICE" and target==c["target"]
        if not service and not any(jobs_dir.glob("*.json")):
            continue
        queue = root / client.RELATIVE
        if queue.is_symlink() or queue.resolve()!=queue.absolute():
            continue
        pending = queue / "request.json"
        if task_state == "STAGE_RUNNING" and pending.exists():
            try:
                if pending.is_symlink() or pending.stat().st_size > 2048:
                    raise ValueError("unsafe request")
                req = json.loads(pending.read_text())
                valid = (isinstance(req,dict) and req.get("task")==task
                         and req.get("action") in client.ACTIONS
                         and isinstance(req.get("id"),str) and re.fullmatch(r"[0-9a-f]{32}",req["id"]))
            except (ValueError,OSError):
                req=None;valid=False
            path = jobs_dir / (req["id"]+".json") if valid else None
            if path is not None and not path.exists() and client.authorized(root, c["target"]):
                if (queue / (req["id"]+".accepted")).exists() and not (queue / (req["id"]+".ssh-accepted")).exists():
                    continue
                head = client.source_head(root)
                if req.get("source_head", head) != head:
                    continue
                job = dict(version=1,id=req["id"],task=task,node=flow.get("activeNode"),
                           action=req["action"],target=c["target"],source_head=head,source_sha256=None,
                           state="QUEUED",updated=int(time.time()),deadline=int(time.time())+7200,
                           result=None,error_kind=None,verified=False)
                save(home, job)
                queue = client.directory(root)
                (queue / (req["id"] + ".accepted")).touch()
                (queue / (req["id"] + ".ssh-accepted")).touch()
        for path in jobs_dir.glob("*.json"):
            try:
                job = json.loads(path.read_text())
            except (ValueError,OSError):
                continue
            if not isinstance(job,dict):
                continue
            if job.get("task") != task or job.get("target") != c["target"]:
                continue
            if job["state"] in {"SUCCEEDED", "FAILED"}:
                continue
            cancel = (task_state in {"ABORTED","FAILED"} or job.get("cancel_requested",False)
                      or cancellation_requested(home,job))
            # Paused tasks only collect work already sent; they never launch queued work.
            allow_launch = (task_state == "STAGE_RUNNING" and job.get("node")==flow.get("activeNode")
                            and client.authorized(root,c["target"]))
            if not job.get("launched") and (cancel or not allow_launch):
                if cancel:
                    job.update(state="FAILED",error_kind="OPERATION_CANCELLED",verified=False,
                               cancel_requested=True,result=dict(id=job["id"],action=job["action"],target=job["target"],
                               source_sha256=job.get("source_sha256"),exit_code=130,completed=int(time.time()),
                               evidence=["Cancelled before remote launch; no remote operation created"]))
                    save(home,job)
                    client.atomic_json(queue/(job["id"]+".json"),job["result"])
                elif job.get("error_kind")!="TASK_NOT_ACTIVE":
                    job.update(state="UNCERTAIN",error_kind="TASK_NOT_ACTIVE",result=None)
                    save(home,job)
                continue
            now = time.time()
            if now < job.get("next_poll_at",0) and not (cancel and not job.get("cancel_requested")):
                continue
            expired = now > job["deadline"]
            job["next_poll_at"] = int(now) + (300 if expired else 5)
            if cancel:job["cancel_requested"]=True
            try:
                job["state"] = "UNCERTAIN" if expired or cancel or task_state!="STAGE_RUNNING" else "RUNNING"
                save(home, job)
                result = ssh_client.execute_job(c, root, job,allow_launch=allow_launch and not expired and not cancel,
                                                cancel=cancel)
                if result is not None:
                    job["result"] = result
                    job["verified"] = True
                    job["state"] = "SUCCEEDED" if result["exit_code"] == 0 else "FAILED"
                    job["error_kind"] = None if result["exit_code"] == 0 else result.get("error_kind","REMOTE_FAILED")
                    client.atomic_json(queue / (job["id"] + ".json"), result)
                elif expired or cancel or task_state!="STAGE_RUNNING":
                    job.update(state="UNCERTAIN",result=None,
                               error_kind="CANCEL_UNCONFIRMED" if cancel else ("OPERATION_DEADLINE" if expired else "TASK_PAUSED"))
            except ssh_client.SourcePreflightError as exc:
                kind = str(exc).split(":",1)[0]
                job.update(state="FAILED",error_kind=kind,result=dict(id=job["id"],action=job["action"],
                           target=job["target"],source_sha256=None,exit_code=1,completed=int(time.time()),evidence=[str(exc)]))
                client.atomic_json(queue / (job["id"]+".json"),job["result"])
            except (ValueError, OSError, ssh_client.subprocess.SubprocessError):
                job.update(state="UNCERTAIN", error_kind="RECONCILIATION_REQUIRED")
            save(home, job)


def reconcile(home, config, task, job_id, head, digest):
    c = ssh_client.config(config)
    if not re.fullmatch(r"[0-9]+", task) or not re.fullmatch(r"[0-9a-f]{32}", job_id):
        raise ValueError("invalid reconciliation identity")
    root = home / "workspaces" / ("task-" + task)
    if client.source_head(root) != head:
        raise ValueError("source HEAD mismatch")
    if hashlib.sha256(ssh_client.archive_source(root)).hexdigest() != digest:
        raise ValueError("source archive mismatch")
    result = ssh_client.remote_result(c, job_id)
    if (not result or result.get("id") != job_id or result.get("action") not in {"prepare","start"}
            or result.get("target") != c["target"]):
        raise ValueError("historical remote receipt mismatch")
    action = result["action"]
    source_digest = digest if action=="start" else None
    ssh_client.validate_receipt(result, c, action, job_id, source_digest)
    if result["exit_code"] != 0:
        raise ValueError("historical operation did not succeed")
    existing = home/"operation-jobs"/task/(job_id+".json")
    if existing.exists():
        previous=json.loads(existing.read_text())
        if previous.get("state") in {"SUCCEEDED","FAILED"}:
            if (previous.get("state")=="SUCCEEDED" and previous.get("verified") is True
                    and previous.get("source_head")==head and previous.get("action")==action
                    and previous.get("source_sha256")==source_digest and previous.get("result")==result):
                return
            raise ValueError("terminal journal is immutable")
    with closing(sqlite3.connect((home / "ai-team.db").as_uri()+"?mode=ro",uri=True)) as db:
        row = db.execute("select data from flow_run where task_id=?", (task,)).fetchone()
    if not row:
        raise ValueError("unknown task")
    flow = json.loads(row[0])
    save(home, dict(version=1,id=job_id,task=task,node=flow.get("activeNode"),action=action,
                    target=c["target"],source_head=head,source_sha256=source_digest,state="SUCCEEDED",
                    updated=int(time.time()),deadline=int(time.time()),result=result,error_kind=None,verified=True))


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("home",type=Path);parser.add_argument("config",type=Path)
    parser.add_argument("--once",action="store_true")
    parser.add_argument("--reconcile",nargs=4,metavar=("TASK","ID","HEAD","SHA256"))
    args=parser.parse_args()
    if args.home.resolve()!=args.home.absolute() or not args.home.is_dir():
        raise ValueError("invalid instance home")
    import fcntl
    with args.config.with_suffix(".executor.lock").open("a") as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        if args.reconcile:
            reconcile(args.home,args.config,*args.reconcile)
            return
        import threading
        def heartbeat():
            while True:
                (args.home / "operation-executor-heartbeat").touch(mode=0o600)
                time.sleep(5)
        threading.Thread(target=heartbeat,daemon=True).start()
        while True:
            poll(args.home,args.config)
            if args.once:break
            time.sleep(2)

if __name__=="__main__":main()
