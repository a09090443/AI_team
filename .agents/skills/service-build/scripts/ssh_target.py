#!/usr/bin/python3
"""Root-owned forced SSH entry; accepts authorized fixed service actions."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import tarfile
import time

LIB = Path("/usr/local/lib/ai-team-service")
CONFIG = Path("/etc/ai-team-service/target.json")
STATE = Path("/var/lib/ai-team-service")
MAX_ARCHIVE = 128 * 1024 * 1024

def request(text, expected_target):
    parts = text.split()
    if (len(parts) not in (4,5) or parts[0] != "ai-team-service" or parts[1] != expected_target
            or parts[2] not in {"prepare","start","health","diagnose","result","status","cancel","repair"}
            or not re.fullmatch(r"[0-9a-f]{32}", parts[3])):
        raise ValueError("unapproved SSH command")
    digest = parts[4] if len(parts)==5 else None
    if (parts[2] == "start") != (digest is not None) or (digest and not re.fullmatch(r"[0-9a-f]{64}", digest)):
        raise ValueError("invalid archive digest")
    return parts[2], parts[3], digest

def verify_source(data, config):
    # Deployment authorization comes from the enrolled SSH key and active SERVICE
    # request. Validate archive structure against the configured product source
    # paths, not a per-version administrator manifest.
    sys.path.insert(0, str(LIB))
    from guest import service_settings, validate_archive
    with validate_archive(data, service_settings(config)["source_paths"]):
        pass

def atomic_record(path, value):
    import tempfile
    fd, name = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(fd,"w") as out:
            json.dump(value,out)
            out.flush()
            os.fsync(out.fileno())
        os.replace(name,path)
    finally:
        if os.path.exists(name):os.unlink(name)


def read_source(job):
    return (STATE / (job + ".source")).read_bytes()


def unit_name(job):
    if not re.fullmatch(r"[0-9a-f]{32}",job):
        raise ValueError("invalid job ID")
    return "ai-team-service-job-" + job + ".service"


def unit_status(job):
    proc = subprocess.run(["systemctl","show",unit_name(job),
                           "--property=LoadState","--property=ActiveState","--property=Result"],
                          capture_output=True,text=True,timeout=15)
    values = dict(line.split("=",1) for line in proc.stdout.splitlines() if "=" in line)
    if proc.returncode != 0 and values.get("LoadState") != "not-found":
        return None
    return values


def spawn_worker(text, job):
    # Test seam: mock this function; production always creates a root system unit.
    proc = subprocess.run(["systemd-run","--quiet","--unit="+unit_name(job),
            "--property=KillMode=control-group","--property=TimeoutStopSec=30s",
            "--property=RuntimeMaxSec=7000s",
            "--property=ExecStopPost=/usr/bin/python3 "+str(LIB/"guest.py")+" cancel-build "+job,
            "/usr/bin/python3",str(Path(__file__).resolve()),"--worker",text],
            stdin=subprocess.DEVNULL,capture_output=True,timeout=20)
    if proc.returncode:
        raise OSError("systemd job launch not confirmed")


def cleanup_build(job):
    proc = subprocess.run(["/usr/bin/python3",str(LIB/"guest.py"),"cancel-build",job],
                          capture_output=True,timeout=35)
    return proc.returncode == 0


def cancel_job(job, target):
    record = STATE / (job+".json")
    if record.exists():
        result = json.loads(record.read_text())
        if result.get("exit_code") != 75:
            return result
    accepted = STATE / (job+".request")
    (STATE/(job+".cancel")).touch(mode=0o600)
    if not accepted.exists():
        # Fence a delayed same-ID SSH submission even when cancel arrived first.
        return dict(id=job,target=target,state="UNCERTAIN",exit_code=75,error_kind="CANCELLATION_REQUESTED")
    action, _, digest = request(accepted.read_text(),target)
    status = None
    cleaned = False
    stopped_code = None
    try:
        proc = subprocess.run(["systemctl","stop",unit_name(job)],capture_output=True,timeout=45)
        stopped_code = proc.returncode
        status = unit_status(job)
    except (OSError,subprocess.SubprocessError):
        pass
    try:
        cleaned = cleanup_build(job)
    except (OSError,subprocess.SubprocessError):
        pass
    # The worker may have completed successfully before stop won the race.
    if record.exists():
        finished = json.loads(record.read_text())
        if finished.get("exit_code") != 75:
            return finished
    stopped = status is not None and (status.get("LoadState")=="not-found" or
                  (stopped_code == 0 and status.get("ActiveState") in {"inactive","failed"}))
    result = dict(id=job,target=target,action=action,source_sha256=digest,completed=int(time.time()),
                  exit_code=130 if stopped and cleaned else 75,state="FAILED" if stopped and cleaned else "UNCERTAIN",
                  error_kind="OPERATION_CANCELLED" if stopped and cleaned else "CANCEL_UNCONFIRMED",
                  evidence=["Cancellation requested for the exact job unit; service data retained"])
    atomic_record(record,result)
    return result


def execute(text):
    import fcntl
    if os.geteuid() != 0:
        raise ValueError("administrator execution required")
    c = json.loads(CONFIG.read_text())
    action, job, digest = request(text, c["target"])
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    # Do not block unrelated status/cancel calls while SSH is uploading an archive.
    data = sys.stdin.buffer.read(MAX_ARCHIVE+1) if action == "start" else None
    with (STATE / "submission.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        record = STATE / (job + ".json")
        accepted = STATE / (job + ".request")
        if action == "cancel":
            return cancel_job(job,c["target"])
        if record.exists():
            result = json.loads(record.read_text())
            if action not in {"result", "status"} and (
                    result.get("action") != action or result.get("source_sha256") != digest):
                raise ValueError("request ID reused")
            return result
        if action in {"result","status"}:
            if not accepted.exists() and (STATE/(job+".cancel")).exists():
                return dict(id=job,target=c["target"],state="UNCERTAIN",exit_code=75,error_kind="CANCELLATION_REQUESTED")
            if not accepted.exists() and not (STATE/(job+".running")).exists():
                return dict(id=job,target=c["target"],state="UNKNOWN",exit_code=75)
            status = unit_status(job)
            state = "RUNNING" if status and status.get("ActiveState") in {"active","activating","deactivating"} else "UNCERTAIN"
            if (status and status.get("LoadState")=="not-found" and not (STATE/(job+".running")).exists()
                    and not (STATE/(job+".cancel")).exists()):
                state="UNKNOWN"
            return dict(id=job,target=c["target"],state=state,exit_code=75,
                        error_kind=None if state=="RUNNING" else "WORKER_STATE_UNCERTAIN")
        if accepted.exists():
            if accepted.read_text() != text:
                raise ValueError("request ID reused")
            status = unit_status(job)
            if not status or status.get("LoadState") != "not-found" or (STATE/(job+".running")).exists():
                return dict(id=job,target=c["target"],state="UNCERTAIN" if not status or status.get("ActiveState") not in {"active","activating"} else "RUNNING",exit_code=75)
            # A confirmed absent unit with no worker marker permits same-ID launch retry.
        elif (STATE / (job + ".running")).exists():
            return dict(id=job,target=c["target"],state="UNCERTAIN",exit_code=75)
        if (STATE/(job+".cancel")).exists():
            return dict(id=job,target=c["target"],state="UNCERTAIN",exit_code=75,error_kind="CANCELLATION_REQUESTED")
        if action == "start":
            try:
                if len(data)>MAX_ARCHIVE or hashlib.sha256(data).hexdigest()!=digest:
                    raise ValueError("archive digest mismatch")
                verify_source(data, c)
            except (ValueError, tarfile.TarError) as exc:
                reason = str(exc) if str(exc).startswith("SERVICE_CONFIG_REQUIRED:") else "Source integrity or format check failed"
                result = dict(id=job,action=action,target=c["target"],source_sha256=digest,
                              completed=int(time.time()),exit_code=1,evidence=[reason[:1400]])
                atomic_record(record,result)
                return result
            (STATE / (job+".source")).write_bytes(data)
            os.chmod(STATE / (job+".source"),0o600)
        accepted.write_text(text);os.chmod(accepted,0o600)
        try:
            spawn_worker(text,job)
        except (OSError,subprocess.SubprocessError):
            return dict(id=job,target=c["target"],state="UNCERTAIN",exit_code=75,error_kind="WORKER_LAUNCH_UNCONFIRMED")
        return dict(id=job,target=c["target"],state="RUNNING",exit_code=75)


def worker(text):
    import fcntl
    if os.geteuid() != 0:
        raise ValueError("administrator execution required")
    c = json.loads(CONFIG.read_text())
    action, job, digest = request(text, c["target"])
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    lockfile = STATE / ("execution.lock" if action in {"prepare","start","repair"} else job+".execution.lock")
    with lockfile.open("a") as guard:
        fcntl.flock(guard, fcntl.LOCK_EX)
        record = STATE / (job+".json")
        if (STATE/(job+".cancel")).exists():
            return dict(id=job,target=c["target"],state="UNCERTAIN",exit_code=75)
        if record.exists():
            cached = json.loads(record.read_text())
            if (cached["target"],cached["action"],cached.get("source_sha256")) != (c["target"],action,digest):
                raise ValueError("request ID reused for different operation")
            # Consume bounded input on replay so SSH stdin cannot hide a broken pipe.
            if action == "start":
                read_source(job)
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
                    data=read_source(job)
                    if len(data)>MAX_ARCHIVE or hashlib.sha256(data).hexdigest()!=digest:
                        raise ValueError("archive size or digest mismatch")
                    verify_source(data, c)
                    archive=Path(tmp)/"source.tar";archive.write_bytes(data);argv.append(str(archive))
                log=STATE/(job+".log")
                with log.open("w") as out:
                    os.chmod(log,0o600)
                    proc=subprocess.run(argv,stdout=out,stderr=subprocess.STDOUT,timeout=6900,
                                        env=dict(os.environ,AI_TEAM_OPERATION_ID=job))
                expected={"prepare":"PREPARED:","start":"START_PASS:","health":"HEALTH_PASS:","diagnose":"DIAGNOSIS:","repair":"REPAIR_PASS:"}[action]
                with log.open("rb") as source:
                    source.seek(0,2);source.seek(max(0,source.tell()-65536))
                    tail=source.read(65536).decode("utf-8",errors="replace")
                lines=[line.strip()[:1450] for line in tail.splitlines()
                       if line.startswith(("PREPARED:","START_PASS:","HEALTH_PASS:","DIAGNOSIS:","REPAIR_PASS:"))][-20:]
                if proc.returncode or not any(line.startswith(expected) for line in lines):
                    result.update(exit_code=1,evidence=["Target operation failed; protected target log: "+str(log)]
                                  + [line for line in lines if line.startswith("DIAGNOSIS:")])
                else:
                    result.update(exit_code=0,evidence=lines)
        except subprocess.TimeoutExpired:
            result.update(exit_code=75,evidence=["Target operation timed out; inspect target; data retained"])
            result["state"] = "UNCERTAIN"
            result["error_kind"] = "REMOTE_TIMEOUT"
            atomic_record(record,result)
            try:
                result["build_cleanup"] = "CONFIRMED" if cleanup_build(job) else "UNCERTAIN"
            except (OSError,subprocess.SubprocessError):
                result["build_cleanup"] = "UNCERTAIN"
            atomic_record(record,result)
            return result
        except (ValueError,OSError,KeyError,tarfile.TarError):
            result.update(exit_code=1,evidence=["Source archive integrity, format or target setup check failed; data retained"])
        result["completed"]=int(time.time())
        atomic_record(record,result)
        running.unlink()
        return result

if __name__ == "__main__":
    try:
        if len(sys.argv)==3 and sys.argv[1]=="--worker":
            worker(sys.argv[2])
            sys.exit(0)
        if len(sys.argv)!=2:
            raise ValueError("one forced SSH request required")
        print(json.dumps(execute(sys.argv[1])))
    except Exception:
        print("RESTRICTED_SSH_ENTRY_REJECTED",file=sys.stderr)
        sys.exit(1)
