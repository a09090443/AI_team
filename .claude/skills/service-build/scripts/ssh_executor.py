#!/usr/bin/env python3
"""Agent-VM-local fixed operation service; never connects through Windows."""
import argparse
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import time
import client
import ssh_client

def poll(home, config):
    c=ssh_client.config(config)
    with sqlite3.connect((home/"ai-team.db").as_uri()+"?mode=ro",uri=True) as db:
        tasks=[str(row[0]) for row in db.execute("select id from task where state='STAGE_RUNNING'")]
    for task in tasks:
        if not re.fullmatch(r"[0-9]+",task):continue
        root=home/"workspaces"/("task-"+task)
        if root.resolve()!=root.absolute() or not client.authorized(root,c["target"]):continue
        queue=client.directory(root);(queue/"broker-heartbeat").touch()
        path=queue/"request.json"
        if not path.exists():continue
        if path.is_symlink() or path.stat().st_size>2048:raise ValueError("unsafe request")
        request=json.loads(path.read_text())
        if (request.get("task")!=task or request.get("action") not in ssh_client.ACTIONS
                or not re.fullmatch(r"[0-9a-f]{32}",request.get("id",""))):raise ValueError("invalid request")
        receipt=queue/(request["id"]+".json")
        if receipt.exists():continue
        accepted=queue/(request["id"]+".accepted")
        if accepted.exists() and not (queue/(request["id"]+".ssh-accepted")).exists():
            result=dict(id=request["id"],action=request["action"],target=c["target"],exit_code=75,
                        evidence=["Legacy worker accepted the request; reconcile previous receipt before direct SSH"],completed=int(time.time()))
        else:
            (queue/(request["id"]+".ssh-accepted")).touch();accepted.touch()
            proc=subprocess.run(["python3",str(Path(__file__).with_name("ssh_client.py")),str(config),request["action"]],
                                cwd=root,capture_output=True,timeout=1300)
            if receipt.exists():continue
            result=dict(id=request["id"],action=request["action"],target=c["target"],exit_code=75 if proc.returncode==75 else 1,
                        evidence=["Direct SSH operation could not obtain a verified receipt; check enrolled network, key, host fingerprint and restricted target entry"],completed=int(time.time()))
        tmp=receipt.with_suffix(".tmp");tmp.write_text(json.dumps(result));tmp.replace(receipt)

def main():
    parser=argparse.ArgumentParser();parser.add_argument("home",type=Path);parser.add_argument("config",type=Path);parser.add_argument("--once",action="store_true")
    args=parser.parse_args()
    if args.home.resolve()!=args.home.absolute() or not args.home.is_dir():raise ValueError("invalid instance home")
    import fcntl
    args.config.parent.mkdir(parents=True,exist_ok=True)
    with args.config.with_suffix(".executor.lock").open("a") as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        while True:
            poll(args.home,args.config)
            if args.once:break
            time.sleep(2)

if __name__=="__main__":main()
