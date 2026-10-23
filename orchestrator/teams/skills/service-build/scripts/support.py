#!/usr/bin/env python3
"""Trusted service follow-up: fixed endpoint probes and existing-release recovery."""
from contextlib import closing
import json
import hashlib
from pathlib import Path
import re
import sqlite3
import sys
import time
import urllib.request
import urllib.error
import urllib.parse
import uuid

DEADLINE = None
import client
import ssh_client

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None

def url(value):
    if not isinstance(value,str) or len(value)>2048:
        raise ValueError("invalid registered URL")
    u=urllib.parse.urlsplit(value)
    if u.scheme not in {"http","https"} or not u.hostname or u.username or u.password or u.query or u.fragment:
        raise ValueError("invalid registered URL")
    _=u.port
    return value

def probe(endpoint):
    result=dict(audience=endpoint["audience"],url=url(endpoint["url"]),probe_url=url(endpoint["probe_url"]),
                probe_origin="Agent VM",user_route_verified=False,observed_at=int(time.time()))
    try:
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
        with opener.open(urllib.request.Request(result["probe_url"],method="GET",headers={"Range":"bytes=0-0"}),timeout=5) as response:
            result.update(http_status=response.status,healthy=200<=response.status<300)
    except urllib.error.HTTPError as exc:
        result.update(http_status=exc.code,healthy=False)
    except (OSError,ValueError):
        result.update(http_status=None,healthy=False,error="ENDPOINT_UNREACHABLE")
    return result

def remote(home,task,c,action):
    path=home/"service-support"/task/(action+".json")
    job=json.loads(path.read_text()) if path.exists() else {"id":uuid.uuid4().hex,"action":action,"target":c["target"]}
    if job.get("target")!=c["target"] or job.get("action")!=action:
        raise ValueError("support job mismatch")
    # Completed evidence is historical; each new support invocation performs a fresh check.
    if job.get("result",{}).get("exit_code") not in {None,75}:
        job={"id":uuid.uuid4().hex,"action":action,"target":c["target"]}
    if action=="repair" and not path.exists():
        job["attempts"]=1
    elif action=="repair" and path.exists():
        previous=json.loads(path.read_text())
        attempts=previous.get("attempts",1)
        if previous.get("result",{}).get("exit_code") not in {None,75}:
            if attempts>=2:
                return dict(id=previous["id"],action=action,exit_code=1,evidence=["Two existing-release recovery attempts exhausted; original diagnostics retained"])
            attempts+=1
        job["attempts"]=attempts
    client.atomic_json(path,job)
    end=time.monotonic()+180
    while True:
        if DEADLINE is not None and time.time()>=DEADLINE:
            return dict(id=job["id"],action=action,exit_code=75,state="UNCERTAIN",evidence=["Diagnostic deadline reached; original job retained"])
        receipt=ssh_client.remote_result(c,job["id"])
        if receipt is not None and receipt.get("state")=="UNKNOWN":
            receipt=ssh_client.remote_query(c,action,job["id"])
        if receipt and receipt.get("exit_code")!=75:
            ssh_client.validate_receipt(receipt,c,action,job["id"],None)
            job["result"]=receipt;client.atomic_json(path,job)
            return receipt
        if time.monotonic()>=end:
            return dict(id=job["id"],action=action,exit_code=75,state="UNCERTAIN",evidence=["Original support job retained; do not submit a new repair"])
        time.sleep(2)

def authorized(home,task,parent,target):
    with closing(sqlite3.connect((home/"ai-team.db").as_uri()+"?mode=ro",uri=True)) as db:
        row=db.execute("select task.state,flow_run.data from task join flow_run on task.id=flow_run.task_id where task.id=?",(task,)).fetchone()
    if not row or row[0]!="STAGE_RUNNING":raise ValueError("support task not active")
    flow=json.loads(row[1]);definition,node,service=client.service_parts(flow)
    if service.get("name")!=target:raise ValueError("support target mismatch")
    if flow.get("dialogue"):
        roles={n.get("role") for n in (definition.get("nodes") or {}).values() if isinstance(n,dict) and n.get("gate")=="SERVICE"}
        if flow.get("parentId")!=parent or flow.get("dialogueRole") not in roles:
            raise ValueError("support role or parent mismatch")
    elif parent!=task or node.get("gate")!="SERVICE" or flow.get("node")!=flow.get("activeNode"):
        raise ValueError("support service node mismatch")

def run(home,task,parent,target):
    if not all(re.fullmatch(r"[0-9]+",v) for v in [task,parent]) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}",target):
        raise ValueError("invalid support identity")
    if home.resolve()!=home.absolute():raise ValueError("invalid support home")
    authorized(home,task,parent,target)
    path=home/"service-context"/(target+".json")
    if path.is_symlink() or path.stat().st_size>32768:raise ValueError("invalid support registration")
    config=json.loads(path.read_text())
    if config.get("target")!=target:raise ValueError("support registration mismatch")
    endpoints=config.get("access_endpoints",[])
    if not isinstance(endpoints,list) or not 1<=len(endpoints)<=4:raise ValueError("registered access endpoints required")
    if any(not isinstance(e,dict) or not isinstance(e.get("audience"),str) or len(e["audience"])>200 for e in endpoints):raise ValueError("invalid endpoint audience")
    # Reconcile an accepted repair before any new recovery decision, including after a worker restart.
    pending=home/"service-support"/task/"repair.json"
    recovered=[]
    if pending.exists() and json.loads(pending.read_text()).get("result",{}).get("exit_code") in {None,75}:
        c=ssh_client.config(config["config_file"])
        if c["target"]!=target:raise ValueError("support SSH target mismatch")
        receipt=remote(home,task,c,"repair");recovered.append(receipt)
        if receipt["exit_code"]==75:
            return dict(target=target,parent=parent,healthy=False,endpoints=[],operations=recovered,
                        repair_attempted=True,diagnosis=dict(category="OPERATION_PENDING",next_action="RECONCILE_ORIGINAL_JOB"))
    results=[probe(e) for e in endpoints]
    out=dict(target=target,parent=parent,observed_at=int(time.time()),endpoints=results,operations=recovered,repair_attempted=bool(recovered))
    category="HEALTHY"
    if not all(e["healthy"] for e in results):
        c=ssh_client.config(config["config_file"])
        if c["target"]!=target:raise ValueError("support SSH target mismatch")
        health=remote(home,task,c,"health");out["operations"].append(health)
        category="ACCESS_ROUTE" if health["exit_code"]==0 else ("OPERATION_PENDING" if health["exit_code"]==75 else "APPLICATION_HEALTH")
        if health["exit_code"]!=0 and health["exit_code"]!=75:
            diagnosis=remote(home,task,c,"diagnose");out["operations"].append(diagnosis)
            details="\n".join(diagnosis.get("evidence",[]))
            if re.search(r'"oom_killed"\s*:\s*true|BUILD_OOM',details):category="RESOURCE_MEMORY"
            elif "BUILD_TIMEOUT" in details:category="BUILD_TIMEOUT"
            if diagnosis["exit_code"]==75:category="OPERATION_PENDING"
            retry=home/"service-support"/task/"repair-policy.json"
            fingerprint=hashlib.sha256(json.dumps(diagnosis.get("evidence",[]),sort_keys=True).encode()).hexdigest()
            previous=json.loads(retry.read_text()) if retry.exists() else {}
            repair_file=home/"service-support"/task/"repair.json"
            last=json.loads(repair_file.read_text()).get("result",{}) if repair_file.exists() else {}
            unchanged_failure=last.get("exit_code") not in {None,0,75} and previous.get("fingerprint")==fingerprint
            if unchanged_failure:
                out["retry_suppressed"]="SAME_DIAGNOSTIC_AFTER_FAILED_REPAIR"
            if config.get("auto_repair_existing_release") is True and not unchanged_failure and diagnosis["exit_code"]==0:
                client.atomic_json(retry,dict(fingerprint=fingerprint))
                # No new source, build, credentials or data initialization.
                authorized(home,task,parent,target)
                repair=remote(home,task,c,"repair");out["operations"].append(repair);out["repair_attempted"]=True
                out["endpoints"]=[probe(e) for e in endpoints]
    out["healthy"]=all(e["healthy"] for e in out["endpoints"])
    out["diagnosis"]=dict(category="HEALTHY" if out["healthy"] else category,
                          next_action="ANSWER_REGISTERED_URL" if out["healthy"] else "INSPECT_SAVED_EVIDENCE")
    return out

def job(home,task,parent,target,generation):
    # The lock spans every probe and remote action. Duplicate/recovered collectors cannot repair twice.
    import fcntl
    global DEADLINE
    if not re.fullmatch(r"[0-9]+",task):raise ValueError("invalid support identity")
    directory=home/"service-support"/task
    directory.mkdir(parents=True,exist_ok=True,mode=0o700)
    if directory.resolve()!=directory.absolute():raise ValueError("invalid support directory")
    lock=directory/"job.lock"
    if lock.is_symlink():raise ValueError("invalid support lock")
    with lock.open("a+") as stream:
        try:fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:return 75
        path=directory/"job.json"
        previous=json.loads(path.read_text()) if path.exists() else {}
        same=all(previous.get(k)==v for k,v in dict(task=task,parent=parent,target=target,generation=generation).items())
        if same and previous.get("state") in {"COMPLETED","FAILED"}:return 0
        if previous and previous.get("state")=="RUNNING" and not same:
            if any(previous.get(k)!=v for k,v in dict(task=task,parent=parent,target=target).items()):
                raise ValueError("original diagnostic identity mismatch")
            # Lock ownership proves the previous worker exited. Reconcile its saved generation first.
            generation=previous["generation"];same=True
        now=int(time.time())
        if same and previous.get("deadline",0)<=now:
            previous.update(state="FAILED",phase="DEADLINE_EXCEEDED",updated_at=now)
            client.atomic_json(directory/"current.json",dict(healthy=False,error="SUPPORT_TIMEOUT"))
            client.atomic_json(path,previous);return 0
        authorized(home,task,parent,target)
        record=previous if same else dict(version=1,task=task,parent=parent,target=target,generation=generation,
                                         started_at=now,deadline=now+600)
        DEADLINE=record["deadline"]
        record.update(state="RUNNING",phase="PROBE_OR_RECONCILE",updated_at=now)
        client.atomic_json(path,record)
        client.atomic_json(directory/"current.json",dict(healthy=False,error="SUPPORT_PENDING"))
        try:
            result=run(home,task,parent,target)
            result.update(task=task,generation=generation)
            client.atomic_json(directory/"current.json",result)
            uncertain=any(o.get("exit_code")==75 for o in result.get("operations",[]))
            record.update(state="RUNNING" if uncertain else "COMPLETED",
                          phase="RECONCILE_ORIGINAL_JOB" if uncertain else "RESULT_SAVED",updated_at=int(time.time()))
        except Exception:
            client.atomic_json(directory/"current.json",dict(healthy=False,error="SUPPORT_CHECK_UNAVAILABLE"))
            record.update(state="FAILED",phase="DIAGNOSTIC_FAILED",updated_at=int(time.time()))
        client.atomic_json(path,record)
        return 0

if __name__=="__main__":
    try:
        if len(sys.argv)==7 and sys.argv[5]=="--job":
            sys.exit(job(Path(sys.argv[1]),*sys.argv[2:5],sys.argv[6]))
        if len(sys.argv)!=5:raise ValueError("usage: support.py home task parent target [--job generation]")
        print(json.dumps(run(Path(sys.argv[1]),*sys.argv[2:]),ensure_ascii=False))
    except Exception:
        print(json.dumps(dict(healthy=False,error="SUPPORT_CHECK_UNAVAILABLE",summary="Registered diagnostic could not finish; no deployment success claimed")))
        sys.exit(1)
