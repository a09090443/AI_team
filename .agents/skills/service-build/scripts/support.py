#!/usr/bin/env python3
"""Trusted service follow-up: fixed endpoint probes and existing-release recovery."""
from contextlib import closing
import json
from pathlib import Path
import re
import sqlite3
import sys
import time
import urllib.request
import urllib.error
import urllib.parse
import uuid
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
    results=[probe(e) for e in endpoints]
    out=dict(target=target,parent=parent,observed_at=int(time.time()),endpoints=results,operations=[],repair_attempted=False)
    if not all(e["healthy"] for e in results):
        c=ssh_client.config(config["config_file"])
        if c["target"]!=target:raise ValueError("support SSH target mismatch")
        health=remote(home,task,c,"health");out["operations"].append(health)
        if health["exit_code"]!=0 and health["exit_code"]!=75:
            diagnosis=remote(home,task,c,"diagnose");out["operations"].append(diagnosis)
            if config.get("auto_repair_existing_release") is True:
                # No new source, build, credentials or data initialization.
                authorized(home,task,parent,target)
                repair=remote(home,task,c,"repair");out["operations"].append(repair);out["repair_attempted"]=True
                out["endpoints"]=[probe(e) for e in endpoints]
    out["healthy"]=all(e["healthy"] for e in out["endpoints"])
    return out

if __name__=="__main__":
    try:
        if len(sys.argv)!=5:raise ValueError("usage: support.py home task parent target")
        print(json.dumps(run(Path(sys.argv[1]),*sys.argv[2:]),ensure_ascii=False))
    except Exception:
        print(json.dumps(dict(healthy=False,error="SUPPORT_CHECK_UNAVAILABLE",summary="Registered diagnostic could not finish; no deployment success claimed")))
        sys.exit(1)
