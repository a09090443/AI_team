#!/usr/bin/python3
"""Root-owned forced SSH entry; accepts only reviewed service actions."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

LIB = Path("/usr/local/lib/ai-team-service")
CONFIG = Path("/etc/ai-team-service/target.json")
STATE = Path("/var/lib/ai-team-service")
MAX_ARCHIVE = 128 * 1024 * 1024

def request(text, expected_target):
    parts = text.split()
    if (len(parts) not in (4,5) or parts[0] != "ai-team-service" or parts[1] != expected_target
            or parts[2] not in {"prepare","start","health","diagnose"}
            or not re.fullmatch(r"[0-9a-f]{32}", parts[3])):
        raise ValueError("unapproved SSH command")
    digest = parts[4] if len(parts)==5 else None
    if (parts[2] == "start") != (digest is not None) or (digest and not re.fullmatch(r"[0-9a-f]{64}", digest)):
        raise ValueError("invalid archive digest")
    return parts[2], parts[3], digest

def verify_source(data, manifest):
    sys.path.insert(0, str(LIB))
    from guest import validate_archive
    archive = validate_archive(data)
    actual = {m.name:hashlib.sha256(archive.extractfile(m).read()).hexdigest() for m in archive.getmembers() if m.isfile()}
    if not manifest or actual != manifest:
        raise ValueError("source differs from administrator approval")

def execute(text):
    import fcntl
    if os.geteuid() != 0:
        raise ValueError("administrator execution required")
    c = json.loads(CONFIG.read_text())
    action, job, digest = request(text, c["target"])
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    with (STATE / "execution.lock").open("a") as guard:
        fcntl.flock(guard, fcntl.LOCK_EX)
        record = STATE / (job+".json")
        if record.exists():
            cached = json.loads(record.read_text())
            if (cached["target"],cached["action"],cached.get("source_sha256")) != (c["target"],action,digest):
                raise ValueError("request ID reused for different operation")
            # Consume bounded input on replay so SSH stdin cannot hide a broken pipe.
            if action == "start":
                sys.stdin.buffer.read(MAX_ARCHIVE+1)
            return cached
        running = STATE / (job+".running")
        result = dict(id=job,action=action,target=c["target"],source_sha256=digest,completed=int(time.time()))
        if running.exists():
            result.update(exit_code=75,evidence=["Previous execution interrupted; inspect target before retry; data retained"])
            return result
        with running.open("x") as marker:
            marker.write(action)
        try:
            with tempfile.TemporaryDirectory(prefix="ssh-service-",dir=STATE) as tmp:
                argv=["/usr/bin/python3", str(LIB/"guest.py"),action]
                if action == "start":
                    data=sys.stdin.buffer.read(MAX_ARCHIVE+1)
                    if len(data)>MAX_ARCHIVE or hashlib.sha256(data).hexdigest()!=digest:
                        raise ValueError("archive size or digest mismatch")
                    verify_source(data,json.loads(Path(c["source_manifest"]).read_text()))
                    archive=Path(tmp)/"source.tar";archive.write_bytes(data);argv.append(str(archive))
                log=STATE/(job+".log")
                with log.open("w") as out:
                    os.chmod(log,0o600)
                    proc=subprocess.run(argv,stdout=out,stderr=subprocess.STDOUT,timeout=1200)
                expected={"prepare":"PREPARED:","start":"START_PASS:","health":"HEALTH_PASS:","diagnose":"DIAGNOSIS:"}[action]
                lines=[line.strip()[:1000] for line in log.read_text(errors="replace").splitlines()
                       if line.startswith(("PREPARED:","START_PASS:","HEALTH_PASS:","DIAGNOSIS:"))][:20]
                if proc.returncode or not any(line.startswith(expected) for line in lines):
                    result.update(exit_code=1,evidence=["Target operation failed; protected target log: "+str(log)])
                else:
                    result.update(exit_code=0,evidence=lines)
        except subprocess.TimeoutExpired:
            result.update(exit_code=75,evidence=["Target operation timed out; inspect target; data retained"])
            # Preserve uncertain-operation marker; no automatic replay.
            return result
        except (ValueError,OSError,KeyError):
            result.update(exit_code=1,evidence=["Source approval or target setup check failed; data retained"])
        result["completed"]=int(time.time())
        temp=record.with_suffix(".tmp");temp.write_text(json.dumps(result));os.chmod(temp,0o600);os.replace(temp,record)
        running.unlink()
        return result

if __name__ == "__main__":
    try:
        if len(sys.argv)!=2:
            raise ValueError("one forced SSH request required")
        print(json.dumps(execute(sys.argv[1])))
    except Exception:
        print("RESTRICTED_SSH_ENTRY_REJECTED",file=sys.stderr)
        sys.exit(1)
