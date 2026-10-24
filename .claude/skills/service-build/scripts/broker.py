#!/usr/bin/env python3
"""Host-owned SSH/VirtualBox broker for the service-build skill."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
import time
import uuid
import shlex

HERE = Path(__file__).resolve().parent
ACTIONS = {"prepare", "start", "health", "diagnose"}


def checked_config(path):
    config = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    mode = config.get("transport", "virtualbox")
    if mode not in {"ssh", "virtualbox"}:
        raise ValueError("unsupported host transport")
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", config["target"]):
        raise ValueError("invalid target name")
    from guest import source_paths
    config["source_paths"] = source_paths(config.get("source_paths"))
    if mode == "virtualbox":
        if str(uuid.UUID(config["vm_uuid"])) != config["vm_uuid"]:
            raise ValueError("VM UUID must be explicitly enrolled")
        if not re.fullmatch(r"[a-z_][a-z0-9_-]*", config["guest_user"]):
            raise ValueError("invalid guest account")
    else:
        if not re.fullmatch(r"[a-z_][a-z0-9_-]*@[a-zA-Z0-9.-]+", config["target_ssh"]):
            raise ValueError("invalid target SSH destination")
        if not isinstance(config["target_port"], int) or not 1 <= config["target_port"] <= 65535:
            raise ValueError("invalid target SSH port")
        for name in ["target_identity_file", "target_known_hosts_file"]:
            if not Path(config[name]).is_file():
                raise ValueError("missing enrolled SSH file: " + name)
    if not re.fullmatch(r"/[A-Za-z0-9_./-]+", config["client_path"]) or ".." in config["client_path"].split("/"):
        raise ValueError("invalid installed skill client path")
    if not config.get("dedicated_sit_vm"):
        raise ValueError("system installation requires a dedicated SIT VM enrollment")
    if any(not re.fullmatch(r"[0-9]+", str(x)) for x in config.get("tasks", [])):
        raise ValueError("invalid optional task allowlist")
    if not re.fullmatch(r"/[A-Za-z0-9_./-]+", config["agent_home"]) or ".." in config["agent_home"].split("/"):
        raise ValueError("invalid agent home")
    if not re.fullmatch(r"[a-z_][a-z0-9_-]*@[a-zA-Z0-9.-]+", config["agent_ssh"]):
        raise ValueError("invalid agent SSH destination")
    if not isinstance(config["agent_port"], int) or not 1 <= config["agent_port"] <= 65535:
        raise ValueError("invalid agent SSH port")
    return config


class Broker:
    def __init__(self, config):
        self.c = config
        self.state = Path(config["state_directory"]).resolve()
        self.state.mkdir(parents=True, exist_ok=True)

    def ssh(self, args, data=None):
        # All arguments are administrator paths or validated numeric task IDs.
        command = ["ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
                   "-o", "ConnectTimeout=10", "-p", str(self.c["agent_port"]), self.c["agent_ssh"], *args]
        result = subprocess.run(command, input=data, capture_output=True, timeout=120)
        if result.returncode:
            raise RuntimeError("agent SSH transport failed (exit " + str(result.returncode) + ")")
        return result.stdout

    def remote(self, mode, task, data=None):
        home = self.c["agent_home"]
        paths = self.c["source_paths"] if mode == "broker-source" else []
        return self.ssh(["python3", self.c["client_path"], mode,
                         home + "/workspaces/task-" + str(task), self.c["target"], *paths], data)

    def vbox(self, verb, args, timeout=1900):
        password_file = Path(self.c["password_file"])
        if not password_file.is_file() or password_file.is_symlink():
            raise ValueError("DEV_LOGIN_REQUIRED: configure the administrator-owned password file once; the agent will install SSH")
        argv = [self.c["vboxmanage"], "guestcontrol", self.c["vm_uuid"], verb,
                "--username", self.c["guest_user"], "--passwordfile", str(password_file), *args]
        result = subprocess.run(argv, capture_output=True, timeout=timeout)
        if result.returncode:
            # VBox errors may echo arguments; no credentials or raw guest output to the model.
            markers = [x for x in (result.stdout + result.stderr).decode(errors="replace").splitlines()
                       if x.startswith("SERVICE_FAILED:")]
            raise RuntimeError("VirtualBox " + verb + " failed (exit " + str(result.returncode) + ")"
                               + ("; " + markers[-1][:1000] if markers else ""))
        return result.stdout

    def run_guest(self, exe, args, timeout=1200):
        return self.vbox("run", ["--exe", exe, "--timeout", str(timeout * 1000),
                                 "--wait-stdout", "--wait-stderr", "--", *args], timeout + 10)

    def verify_source(self, data):
        from guest import validate_archive
        archive = validate_archive(data, self.c["source_paths"])
        actual = {x.name: hashlib.sha256(archive.extractfile(x).read()).hexdigest()
                  for x in archive.getmembers() if x.isfile()}
        expected = json.loads(Path(self.c["source_manifest"]).read_text())
        if not expected or actual != expected:
            raise ValueError("deployment source differs from administrator-reviewed manifest; no root execution")

    def target_ssh(self, argv, data=None, timeout=1200):
        command = ["ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
                   "-o", "IdentitiesOnly=yes", "-o", "ConnectTimeout=10",
                   "-o", "UserKnownHostsFile=" + self.c["target_known_hosts_file"],
                   "-i", self.c["target_identity_file"], "-p", str(self.c["target_port"]),
                   self.c["target_ssh"], shlex.join(argv)]
        result = subprocess.run(command, input=data, capture_output=True, timeout=timeout)
        if result.returncode:
            markers = [x for x in (result.stdout + result.stderr).decode(errors="replace").splitlines()
                       if x.startswith("SERVICE_FAILED:")]
            raise RuntimeError("target SSH operation failed (exit " + str(result.returncode) + ")"
                               + ("; " + markers[-1][:1000] if markers else ""))
        return result.stdout

    @staticmethod
    def receipt(action, output, digest=None):
        lines = [x for x in output.decode(errors="replace").splitlines()
                 if x.startswith(("PREPARED:", "HEALTH_PASS:", "START_PASS:", "DIAGNOSIS:"))]
        expected = {"prepare": "PREPARED:", "start": "START_PASS:", "health": "HEALTH_PASS:", "diagnose": "DIAGNOSIS:"}
        if not any(x.startswith(expected[action]) for x in lines):
            raise RuntimeError("guest did not return a completion marker")
        return {"exit_code": 0, "evidence": lines, "source_sha256": digest}

    def execute_ssh(self, request, task):
        # Host-owned commands only; model input never supplies a shell command.
        guest_dir = self.target_ssh(["mktemp", "-d", "/tmp/ai-team-service-XXXXXXXX"], timeout=30).decode().strip()
        if not re.fullmatch(r"/tmp/ai-team-service-[A-Za-z0-9]{8}", guest_dir):
            raise ValueError("invalid target temporary directory")
        digest = None
        try:
            self.target_ssh(["sh", "-c", "cat > " + guest_dir + "/guest.py"], (HERE / "guest.py").read_bytes(), 60)
            args = ["sudo", "-n", "python3", guest_dir + "/guest.py", request["action"]]
            if request["action"] == "start":
                data = self.remote("broker-source", task)
                self.verify_source(data)
                digest = hashlib.sha256(data).hexdigest()
                self.target_ssh(["sh", "-c", "cat > " + guest_dir + "/source.tar"], data, 120)
                args.append(guest_dir + "/source.tar")
            return self.receipt(request["action"], self.target_ssh(args), digest)
        finally:
            # Known files only. Preserve uncertain execution and database contents.
            try:
                self.target_ssh(["rm", "-f", guest_dir + "/guest.py", guest_dir + "/source.tar"], timeout=30)
                self.target_ssh(["rmdir", guest_dir], timeout=30)
            except Exception:
                pass

    def execute(self, request, task):
        if request.get("action") not in ACTIONS or request.get("task") != str(task):
            raise ValueError("request is outside enrolled scope")
        if not re.fullmatch(r"[0-9a-f]{32}", request.get("id", "")):
            raise ValueError("invalid request ID")
        action = request["action"]
        if self.c.get("transport") == "ssh":
            return self.execute_ssh(request, task)
        # Verify guest account via Guest Additions, independently of SSH/NAT address.
        self.run_guest("/usr/bin/id", ["-u"], timeout=30)
        guest_dir = "/home/" + self.c["guest_user"] + "/.ai-team-service-" + request["id"]
        self.run_guest("/usr/bin/mkdir", ["-m", "0700", guest_dir], timeout=30)
        with tempfile.TemporaryDirectory(prefix="service-job-", dir=self.state) as tmp:
            tmp = Path(tmp)
            self.vbox("copyto", [str(HERE / "guest.py"), guest_dir + "/guest.py"], timeout=60)
            args = action
            source_digest = None
            if action == "start":
                data = self.remote("broker-source", task)
                self.verify_source(data)
                source_digest = hashlib.sha256(data).hexdigest()
                source = tmp / "source.tar"
                source.write_bytes(data)
                self.vbox("copyto", [str(source), guest_dir + "/source.tar"], timeout=120)
                args += " " + guest_dir + "/source.tar"
            # Password is transferred only to its intended VM, within a private transient directory.
            # Guest launcher removes it on exit. It is never in argv, request JSON, logs, or source archives.
            launcher = tmp / "run.sh"
            launcher.write_text("#!/bin/bash\nset -eu\ntrap 'rm -f " + guest_dir + "/login' EXIT\n"
                                + "chmod 600 " + guest_dir + "/login\n"
                                + "sudo -S -p '' /usr/bin/python3 " + guest_dir + "/guest.py " + args
                                + " < " + guest_dir + "/login\n", encoding="utf-8", newline="\n")
            self.vbox("copyto", [str(launcher), guest_dir + "/run.sh"], timeout=60)
            try:
                self.vbox("copyto", [str(Path(self.c["password_file"]).resolve()), guest_dir + "/login"], timeout=60)
                output = self.run_guest("/bin/bash", [guest_dir + "/run.sh"])
                return self.receipt(action, output, source_digest)
            finally:
                # Never recurse/delete a computed directory. Remove only this request's known files.
                for name in ["login", "run.sh", "guest.py", "source.tar"]:
                    try:
                        self.vbox("rm", ["--force", guest_dir + "/" + name], timeout=30)
                    except Exception:
                        pass

    def poll(self):
        tasks = self.c.get("tasks")
        if not tasks:
            home = self.c["agent_home"]
            tasks = json.loads(self.ssh(["python3", self.c["client_path"], "broker-tasks", home, self.c["target"]]))
        for task in tasks:
            if not re.fullmatch(r"[0-9]+", str(task)):
                raise ValueError("invalid discovered task")
            request = json.loads(self.remote("broker-read", task))
            if request is None:
                continue
            job_id = request.get("id", "")
            if not re.fullmatch(r"[0-9a-f]{32}", job_id):
                raise ValueError("invalid job ID")
            record = self.state / (job_id + ".json")
            if record.exists():
                result = json.loads(record.read_text())
            else:
                lock = self.state / (job_id + ".running")
                if lock.exists():
                    # A crashed broker must not silently re-execute an uncertain mutation.
                    result = {"exit_code": 75, "evidence": ["previous execution interrupted; inspect target before retry"]}
                else:
                    with lock.open("x") as out:
                        out.write(str(task))
                    try:
                        self.remote("broker-accepted", task)
                        result = self.execute(request, task)
                    except Exception as exc:
                        result = {"exit_code": 1, "evidence": [str(exc)]}
                result.update(id=job_id, action=request["action"], target=self.c["target"], task=str(task),
                              completed=int(time.time()))
                record.write_text(json.dumps(result), encoding="utf-8")
                lock.unlink(missing_ok=True)
            self.remote("broker-result", task, json.dumps(result).encode())
            print(json.dumps(result), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("config")
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()
    broker = Broker(checked_config(args.config))
    run_loop(broker, args.once)


def run_loop(broker, once=False):
    delay = 3
    while True:
        try:
            broker.poll()
        except (RuntimeError, ValueError, OSError, subprocess.SubprocessError):
            if once:
                raise
            # A transient transport failure must not silently kill the host worker.
            # Receipts and interrupted-operation locks remain authoritative.
            print("BROKER_POLL_RETRY: transport or queue unavailable; retained state", flush=True)
            time.sleep(delay)
            delay = min(delay * 2, 60)
            continue
        if once:
            return
        delay = 3
        time.sleep(delay)


if __name__ == "__main__":
    main()
