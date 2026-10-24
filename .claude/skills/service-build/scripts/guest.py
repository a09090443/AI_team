#!/usr/bin/env python3
"""Reviewed operations inside an explicitly dedicated Ubuntu SIT VM. No arbitrary commands."""
import base64
import hashlib
import io
import json
import os
import re
from pathlib import Path, PurePosixPath
import secrets
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.parse
import urllib.request

CONFIG = Path("/etc/ai-team-service/target.json")
# Sandbox targets: the administrator declared the whole VM disposable, so agent scripts run as root here.
SANDBOX_BASE = Path("/opt/ai-team-sandbox")
MODES = {"restricted", "sandbox"}
# Product layout comes only from the administrator's target configuration (see configure()).
ROOT = COMPOSE = PROJECT = APP_SERVICE = DB_SERVICE = APP_DIR = HEALTH_URL = ENV_LINK = PASSWORD_LINK = None
SOURCE_PATHS = ()
SAFE_PART = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]*")


def config_error(detail):
    return ValueError("SERVICE_CONFIG_REQUIRED: " + detail
                      + "; update the administrator service configuration (assets/target-service.example.json)")


def relative_path(value, name, hidden=False):
    parts = value.split("/") if isinstance(value, str) and len(value) <= 200 else []
    if not parts or any(not (SAFE_PART.fullmatch(p) or hidden and p.startswith(".") and SAFE_PART.fullmatch(p[1:]))
                        or p in {".git", ".ssh"} for p in parts):
        raise config_error("invalid " + name)
    return "/".join(parts)


def source_paths(value):
    if not isinstance(value, list) or not 1 <= len(value) <= 16:
        raise config_error("source_paths must list 1-16 committed repository paths")
    paths = [relative_path(p, "source_paths entry") for p in value]
    if len(set(paths)) != len(paths):
        raise config_error("duplicate source_paths entry")
    return paths


def under(path, sources):
    return any(path == s or path.startswith(s + "/") for s in sources)


def service_settings(config):
    """Validate the product section of /etc/ai-team-service/target.json; never guess missing values."""
    s = config.get("service") if isinstance(config, dict) else None
    if not isinstance(s, dict):
        raise config_error("target configuration has no service section")
    missing = [k for k in ("root", "source_paths", "compose_file", "compose_project", "app_service",
                           "database_service", "app_directory", "health_url", "env_file", "db_password_file")
               if k not in s]
    if missing:
        raise config_error("missing service settings: " + ", ".join(missing))
    root = s["root"]
    if (not isinstance(root, str) or not re.fullmatch(r"(/[A-Za-z0-9_][A-Za-z0-9_.-]*){2,8}", root)
            or ".." in root.split("/")):
        raise config_error("root must be a dedicated absolute directory such as /opt/ai-team-<name>")
    paths = source_paths(s["source_paths"])
    compose = relative_path(s["compose_file"], "compose_file")
    app = relative_path(s["app_directory"], "app_directory")
    if not under(compose, paths) or not under(app, paths):
        raise config_error("compose_file and app_directory must be inside source_paths")
    if not isinstance(s["compose_project"], str) or not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", s["compose_project"]):
        raise config_error("invalid compose_project")
    services = [s["app_service"], s["database_service"]]
    if any(not isinstance(x, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,62}", x) for x in services) \
            or services[0] == services[1]:
        raise config_error("app_service and database_service must be two different Compose service names")
    url = urllib.parse.urlsplit(s["health_url"]) if isinstance(s["health_url"], str) else None
    if not url or url.scheme not in {"http", "https"} or not url.hostname or url.username or url.password:
        raise config_error("health_url must be an http(s) URL reachable inside the target VM")
    env = relative_path(s["env_file"], "env_file", hidden=True)
    password = relative_path(s["db_password_file"], "db_password_file", hidden=True)
    if env == password or under(env, paths) or under(password, paths):
        raise config_error("env_file and db_password_file must be distinct release links outside source_paths")
    return dict(root=root, source_paths=paths, compose_file=compose, compose_project=s["compose_project"],
                app_service=services[0], database_service=services[1], app_directory=app,
                health_url=s["health_url"], env_file=env, db_password_file=password)


def configure(settings):
    global ROOT, COMPOSE, PROJECT, APP_SERVICE, DB_SERVICE, APP_DIR, HEALTH_URL, ENV_LINK, PASSWORD_LINK
    global SOURCE_PATHS, BUILD_NAME, BUILD_LABEL
    ROOT = Path(settings["root"])
    COMPOSE = settings["compose_file"]
    PROJECT = settings["compose_project"]
    APP_SERVICE, DB_SERVICE = settings["app_service"], settings["database_service"]
    APP_DIR = settings["app_directory"]
    HEALTH_URL = settings["health_url"]
    ENV_LINK, PASSWORD_LINK = settings["env_file"], settings["db_password_file"]
    SOURCE_PATHS = tuple(settings["source_paths"])
    if not OPERATION_ID:
        BUILD_NAME = "ai-team-build-" + PROJECT
    BUILD_LABEL = "ai-team.service-build=" + PROJECT


def target_mode(config):
    mode = config.get("mode", "restricted") if isinstance(config, dict) else None
    if mode not in MODES:
        raise config_error("mode must be restricted or sandbox")
    return mode


def install_config(raw):
    """Turn the administrator's service file into target.json fields; service settings are optional only in sandbox mode."""
    if not isinstance(raw, dict):
        raise config_error("service configuration must be a JSON object")
    raw = dict(raw)
    mode = target_mode({"mode": raw.pop("mode", "restricted")})
    if not raw and mode == "sandbox":
        return {"mode": mode}
    return {"mode": mode, "service": service_settings({"service": raw})}


def load_target(path=None):
    path = path or CONFIG
    try:
        config = json.loads(path.read_text())
    except (OSError, ValueError):
        raise config_error("cannot read " + str(path))
    if not isinstance(config, dict) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", str(config.get("target", ""))):
        raise config_error("target configuration has no valid target name")
    target_mode(config)
    return config


def load_settings(path=None):
    return service_settings(load_target(path))


def run(argv, **kwargs):
    subprocess.run(argv, check=True, timeout=1800, **kwargs)


def prepare(mode="restricted", target=None):
    release = dict(line.split("=", 1) for line in Path("/etc/os-release").read_text().splitlines() if "=" in line)
    if release.get("ID", "").strip('"') != "ubuntu":
        raise ValueError("only dedicated Ubuntu SIT guests are supported")
    codename = release.get("VERSION_CODENAME", "").strip('"')
    if codename not in {"jammy", "noble", "resolute"}:
        raise ValueError("unsupported Ubuntu release")
    os.environ["DEBIAN_FRONTEND"] = "noninteractive"
    run(["apt-get", "update"])
    run(["apt-get", "install", "-y", "ca-certificates", "curl", "openssh-server", "python3-cryptography"])
    if not Path("/usr/bin/docker").exists():
        # Do not remove or replace another container runtime without a separate decision.
        for package in ["docker.io", "podman-docker", "containerd", "runc"]:
            p = subprocess.run(["dpkg-query", "-W", "-f=${Status}", package], capture_output=True, text=True)
            if p.returncode == 0 and "install ok installed" in p.stdout:
                raise ValueError("existing runtime requires explicit migration: " + package)
        Path("/etc/apt/keyrings").mkdir(mode=0o755, exist_ok=True)
        run(["curl", "--fail", "--silent", "--show-error", "--location",
             "https://download.docker.com/linux/ubuntu/gpg", "-o", "/etc/apt/keyrings/docker.asc"])
        os.chmod("/etc/apt/keyrings/docker.asc", 0o644)
        arch = subprocess.check_output(["dpkg", "--print-architecture"], text=True).strip()
        Path("/etc/apt/sources.list.d/docker.sources").write_text(
            "Types: deb\nURIs: https://download.docker.com/linux/ubuntu\nSuites: " + codename
            + "\nComponents: stable\nArchitectures: " + arch + "\nSigned-By: /etc/apt/keyrings/docker.asc\n")
        run(["apt-get", "update"])
        run(["apt-get", "install", "-y", "docker-ce", "docker-ce-cli", "containerd.io",
             "docker-buildx-plugin", "docker-compose-plugin"])
    run(["systemctl", "enable", "--now", "ssh", "docker"])
    version = subprocess.check_output(["docker", "compose", "version", "--short"], text=True).strip().lstrip("v")
    if tuple(int(x) for x in version.split(".")[:2]) < (2, 30):
        raise ValueError("existing Docker Compose needs a reviewed upgrade to >= 2.30")
    if ROOT is not None:
        ROOT.mkdir(mode=0o700, exist_ok=True)
    if mode == "sandbox":
        (SANDBOX_BASE / target / "home").mkdir(mode=0o700, parents=True, exist_ok=True)
    print("PREPARED: SSH, Docker and Compose; mode=" + mode + "; no database volumes removed")


def validate_exec_archive(data):
    archive = tarfile.open(fileobj=io.BytesIO(data))
    total, script = 0, False
    for member in archive.getmembers():
        p = PurePosixPath(member.name)
        if (p.is_absolute() or ".." in p.parts or not p.parts
                or not (p.parts == ("run.sh",) and member.isfile() or p.parts[0] == "source")
                or any(x in {".git", ".ssh", ".secrets"} or x.startswith(".env") for x in p.parts)
                or not (member.isfile() or member.isdir())):
            raise ValueError("unsafe sandbox payload entry")
        script = script or p.parts == ("run.sh",)
        total += member.size
        if total > 200_000_000:
            raise ValueError("sandbox payload is too large")
    if not script:
        raise ValueError("sandbox payload has no run.sh")
    return archive


def output_tail(path, lines=30):
    with path.open("rb") as log:
        log.seek(0, 2); size = log.tell(); log.seek(max(0, size - 65536))
        raw = log.read().decode("utf-8", errors="replace")
    raw = re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", raw)
    picked = [redact_diagnostic(x.rstrip()) for x in raw.splitlines() if x.strip()]
    return ["".join(c for c in x if c.isprintable())[:300] for x in picked[-lines:]]


def sandbox_exec(archive_file, target):
    """Run an agent-authored script as root on a sandbox target; output is returned only as a redacted tail."""
    data = Path(archive_file).read_bytes()
    archive = validate_exec_archive(data)
    base = SANDBOX_BASE / target
    home, runs = base / "home", base / "runs"
    for directory in (home, runs):
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    run_dir = runs / (OPERATION_ID or secrets.token_hex(16))
    run_dir.mkdir(mode=0o700)
    with archive:
        extract_source(archive, run_dir)
    (run_dir / "source").mkdir(exist_ok=True)
    for old in sorted((p for p in runs.iterdir() if p != run_dir and p.is_dir() and not p.is_symlink()),
                      key=lambda p: p.stat().st_mtime)[:-4]:
        shutil.rmtree(old, ignore_errors=True)
    env = dict(os.environ, AI_TEAM_SANDBOX_HOME=str(home), AI_TEAM_OPERATION_ID=OPERATION_ID,
               DEBIAN_FRONTEND="noninteractive")
    log = run_dir / "run.log"
    with log.open("wb") as out:
        try:
            code = subprocess.run(["bash", str(run_dir / "run.sh")], cwd=run_dir / "source", env=env,
                                  stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT, timeout=6600).returncode
        except subprocess.TimeoutExpired:
            code = None
    for line in output_tail(log):
        print("EXEC_OUTPUT: " + line)
    if code is not None and (code < 0 or code == 137):
        try:
            total = int(next(x.split()[1] for x in Path("/proc/meminfo").read_text().splitlines()
                             if x.startswith("MemTotal:"))) // 1024
        except (OSError, StopIteration, ValueError):
            total = None
        print("EXEC_OUTPUT: script or a child was killed by a signal (exit " + str(code) + "); likely out of memory"
              + ("" if total is None else ", the target has " + str(total) + " MiB")
              + "; cap build memory (JVM -Xmx, Gradle --max-workers, container --memory) or add swap")
    if code != 0:
        print("EXEC_FAILED: exit=" + ("timeout" if code is None else str(code)), flush=True)
        raise ValueError("sandbox script failed")
    print("EXEC_PASS: exit=0; run=" + run_dir.name, flush=True)


def archive_entry_allowed(parts, member, sources):
    for source in sources:
        s = tuple(source.split("/"))
        # Inside a configured source path, or a parent directory entry that git archive emits for it.
        if parts[:len(s)] == s or (member.isdir() and s[:len(parts)] == parts):
            return True
    return False


def validate_archive(data, sources):
    sources = source_paths(list(sources))
    archive = tarfile.open(fileobj=io.BytesIO(data))
    total = 0
    for member in archive.getmembers():
        p = PurePosixPath(member.name)
        if (p.is_absolute() or ".." in p.parts or not p.parts
                or not archive_entry_allowed(p.parts, member, sources)
                or any(x in {".git", ".ssh", ".secrets"} or x.startswith(".env") for x in p.parts)
                or not (member.isfile() or member.isdir())):
            raise ValueError("unsafe deployment archive entry")
        total += member.size
        if total > 200_000_000:
            raise ValueError("deployment archive is too large")
    return archive


def extract_source(archive, destination):
    # validate_archive already rejects links/special files. Explicit extraction also
    # supports Ubuntu 22.04's Python 3.10 without tarfile's newer filter argument.
    for member in archive.getmembers():
        path = destination / member.name
        if member.isdir():
            path.mkdir(parents=True, exist_ok=True)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("xb") as out, archive.extractfile(member) as source:
                shutil.copyfileobj(source, out)
            path.chmod(0o755 if member.mode & 0o111 else 0o644)


def provision_secrets(release):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    secret_dir = ROOT / "secrets"
    secret_dir.mkdir(mode=0o700, exist_ok=True)
    env = secret_dir / "env.sit"
    password_file = secret_dir / "db-password"
    if env.exists() != password_file.exists():
        raise ValueError("partial secrets detected; preserve and repair manually, do not rotate database password")
    if not env.exists():
        existing = subprocess.check_output(["docker", "volume", "ls", "--format", "{{.Name}}"], text=True)
        if any(x.startswith(PROJECT + "_") for x in existing.splitlines()):
            raise ValueError("existing SIT volumes without secrets; automatic reset is forbidden")
        password, encryption, crypto = [secrets.token_urlsafe(32) for _ in range(3)]
        iv = secrets.token_bytes(12)
        value = AESGCM(hashlib.sha256(encryption.encode()).digest()).encrypt(iv, password.encode(), None)
        ciphertext = base64.urlsafe_b64encode(bytes([12]) + iv + value).decode().rstrip("=")
        # Validate compatibility before persisting. Never print any generated value.
        assert AESGCM(hashlib.sha256(encryption.encode()).digest()).decrypt(iv, value, None).decode() == password
        old = os.umask(0o077)
        try:
            with password_file.open("x") as out:
                out.write(password)
            with env.open("x") as out:
                out.write("DB_USERNAME=pguser\nDB_PASSWORD=${enc::" + ciphertext + "}\n"
                          + "CONFIG_ENCRYPTION_KEY=" + encryption + "\nCRYPTO_SECRET_KEY=" + crypto
                          + "\nOPENROUTER_API_KEY=\n")
        finally:
            os.umask(old)
    for link, source in ((PASSWORD_LINK, password_file), (ENV_LINK, env)):
        (release / link).parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        (release / link).symlink_to(source)


def compose(release, *args):
    return ["docker", "compose", "--project-name", PROJECT, "-f", str(release / COMPOSE), *args]


def health(release):
    # Inspect containers directly: Compose versions differ in JSON array/JSON Lines output.
    ids = subprocess.check_output(compose(release, "ps", "-q", DB_SERVICE, APP_SERVICE), text=True).split()
    if len(ids) != 2:
        raise ValueError("database and application must both be running")
    details = json.loads(subprocess.check_output(["docker", "inspect", *ids], text=True))
    if any(not d["State"]["Running"] for d in details):
        raise ValueError("a required container is not running")
    if any(d["Config"].get("Labels", {}).get("com.docker.compose.service") == DB_SERVICE
           and d["State"].get("Health", {}).get("Status") != "healthy" for d in details):
        raise ValueError("database health check failed")
    with urllib.request.urlopen(HEALTH_URL, timeout=15) as response:
        if response.status != 200:
            raise ValueError("application health page failed")
    if any(str(release / COMPOSE) not in d["Config"].get("Labels", {}).get("com.docker.compose.project.config_files", "").split(",")
           for d in details if d["Config"].get("Labels",{}).get("com.docker.compose.service") == APP_SERVICE):
        raise ValueError("container release does not match active start")
    print("HEALTH_PASS: release=" + release.name + "; database and application running; "
          + (urllib.parse.urlsplit(HEALTH_URL).path or "/") + " HTTP 200")


def build_budget(meminfo=None):
    # Reserve memory for the guest OS and Docker; never use a container limit
    # larger than the VM as a substitute for sizing the JVM.
    text = Path("/proc/meminfo").read_text() if meminfo is None else meminfo
    total = int(next(line.split()[1] for line in text.splitlines() if line.startswith("MemTotal:"))) // 1024
    if total < 2500:
        raise ValueError("build requires at least 2500 MiB guest memory")
    limit = min(4096, total - 1024)
    heap = min(2048, max(768, limit - 1024))
    return limit, heap


OPERATION_ID = os.environ.get("AI_TEAM_OPERATION_ID", "")
if OPERATION_ID and not re.fullmatch(r"[0-9a-f]{32}", OPERATION_ID):
    raise ValueError("invalid operation identity")
# Without an operation identity configure() names the build after the Compose project.
BUILD_NAME = "ai-team-build-" + OPERATION_ID if OPERATION_ID else None
BUILD_LABEL = None


def remove_owned_build(timeout=30):
    found = subprocess.run(["docker", "inspect", BUILD_NAME], capture_output=True, text=True, timeout=timeout)
    if found.returncode:
        if any(message in getattr(found,"stderr","") for message in ("No such object","No such container")):
            return
        raise ValueError("cannot verify build container absence")
    item = json.loads(found.stdout)[0]
    if item.get("Config", {}).get("Labels", {}).get("ai-team.service-build") != PROJECT:
        raise ValueError("build container name belongs to another owner")
    if OPERATION_ID and item.get("Config",{}).get("Labels",{}).get("ai-team.operation-id") != OPERATION_ID:
        raise ValueError("build belongs to a different operation")
    subprocess.run(["docker", "rm", "-f", BUILD_NAME], check=True, capture_output=True, timeout=timeout)


def redact_diagnostic(text):
    # Read secrets locally only; never evaluate env syntax or include its raw contents.
    values = []
    env = ROOT/"secrets"/"env.sit" if ROOT is not None else Path("/nonexistent")
    password = ROOT/"secrets"/"db-password" if ROOT is not None else Path("/nonexistent")
    if password.is_file():
        value=password.read_text(errors="replace").strip()
        if value:values.append(value)
    if env.is_file():
        for line in env.read_text(errors="replace").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                value=line.split("=",1)[1].strip().strip("\"'")
                if value:values.append(value)
    for value in sorted(set(values),key=len,reverse=True):
        text=text.replace(value,"[REDACTED]")
    text=re.sub(r"(?i)(?:https?://)?[^\s/:@]+:[^\s/@]+@", "[REDACTED]@",text)
    text=re.sub(r"(?i)(authorization\s*[:=]\s*(?:bearer\s+|basic\s+)?)[^\s,;]+",r"\1[REDACTED]",text)
    text=re.sub(r"""(?ix)((?:[a-z0-9_]*(?:password|passwd|token|secret|api[_-]?key|credential)[a-z0-9_]*)
                   ["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,;]+)""",
                r"\1[REDACTED]",text)
    text=re.sub(r"(?i)\b(?:sk-[a-z0-9_-]{12,}|gh[pousr]_[a-z0-9_]{12,}|xox[baprs]-[a-z0-9-]{10,})", "[REDACTED]",text)
    return text


def safe_errors(path, phase="build"):
    if not path.is_file() or path.is_symlink():
        return []
    with path.open("rb") as log:
        log.seek(0,2);size=log.tell();log.seek(max(0,size-32768))
        raw=log.read(32768).decode("utf-8",errors="replace")
    raw=re.sub(r"\x1b\[[0-9;]*[A-Za-z]","",raw)
    # Select before redaction, but truncate only AFTER complete-value masking.
    lines=raw.splitlines()
    if size>32768 and lines:lines=lines[1:]
    picked=[]
    context=0
    for line in lines:
        stripped=line.strip()
        match=(bool(re.search(r"(?:^e:|^error:|^FAILURE:|^\* What went wrong:|^Execution failed for task|^> Task .*FAILED|^Caused by:)",stripped))
               if phase=="build" else bool(re.search(r"(?i)(error|failed|unhealthy|denied|not found|invalid|timeout|timed out)",stripped)))
        if match or context:
            safe=redact_diagnostic(stripped)
            safe="".join(c for c in safe if c.isprintable())[:120]
            if safe and safe not in picked:picked.append(safe)
        context=2 if stripped.startswith("* What went wrong:") else max(0,context-1)
    return picked[-5:]


def build_diagnosis(release):
    result_file=release/"build-result.json"
    data=json.loads(result_file.read_text()) if result_file.exists() else {}
    errors=safe_errors(release/"build.log")
    kind=("BUILD_TIMEOUT" if data.get("timed_out") else "BUILD_OOM" if data.get("oom_killed")
          else "BUILD_COMPILE_FAILED" if any(x.startswith(("e:","error:")) for x in errors)
          else "BUILD_FAILED" if data.get("exit_code") not in {None,0} else None)
    identity=release/"operation-id"
    job=identity.read_text().strip() if identity.is_file() else None
    if job is not None and not re.fullmatch(r"[0-9a-f]{32}",job):job=None
    return dict(job=job,release=release.name,build_phase="build",error_kind=kind,
                progress="finished" if data else "in_progress_or_interrupted",
                build_progress=dict(timed_out=data.get("timed_out") is True,oom_killed=data.get("oom_killed") is True,
                                    exit_code=data.get("exit_code") if type(data.get("exit_code")) is int else None,
                                    resource="memory" if data.get("oom_killed") else ("time" if data.get("timed_out") else None)),
                errors=errors,summary="Review the sanitized failed task and compiler errors" if errors
                    else ("Inspect resource limits and query the original job" if kind else "Query the original job; progress does not prove success"))


def emit_diagnosis(data):
    data=dict(data)
    data["errors"]=list(data.get("errors",[]))
    while len(json.dumps(data))>1350 and data["errors"]:
        data["errors"].pop()
    line=json.dumps(data)
    if len(line)>1350:
        # Fixed schema fallback; never cut JSON in the middle of an escaped secret.
        line=json.dumps({key:data.get(key) for key in ("job","release","build_phase","error_kind","progress","rollback")})
    print("DIAGNOSIS: "+line,flush=True)


def run_build(argv, release):
    remove_owned_build()
    result = {"exit_code": None, "timed_out": False}
    try:
        with (release / "build.log").open("wb") as log:
            try:
                done = subprocess.run(argv, stdout=log, stderr=subprocess.STDOUT, timeout=5400)
                result["exit_code"] = done.returncode
            except subprocess.TimeoutExpired:
                result["timed_out"] = True
        probe = subprocess.run(["docker", "inspect", BUILD_NAME], capture_output=True, text=True, timeout=30)
        if probe.returncode == 0:
            result["oom_killed"] = json.loads(probe.stdout)[0].get("State", {}).get("OOMKilled", False)
        (release / "build-result.json").write_text(json.dumps(result))
        if result["timed_out"] or result["exit_code"] != 0:
            emit_diagnosis(build_diagnosis(release))
            raise ValueError("build failed; diagnosis=" + json.dumps(result))
    finally:
        remove_owned_build()


def start(archive_file):
    import fcntl
    ROOT.mkdir(mode=0o700, exist_ok=True)
    with (ROOT / "build.lock").open("a") as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError("another build is still running; do not duplicate it")
        return start_locked(archive_file)


def previous_release():
    active = ROOT / "active"
    if not active.is_file() or active.is_symlink():
        return None
    release = Path(active.read_text().strip())
    if (release.parent != ROOT/"releases" or release.resolve()!=release.absolute()
            or not (release/COMPOSE).is_file()):
        return None
    jars = list((release/APP_DIR/"build").glob("*-runner.jar"))
    jars += list((release/APP_DIR/"build"/"libs").glob("*-runner.jar"))
    if not any(p.is_file() and not p.is_symlink() and p.resolve().is_relative_to(release.resolve()) for p in jars):
        return None
    return release


def wait_healthy(release):
    for attempt in range(30):
        try:
            health(release)
            return
        except Exception as exc:
            if attempt==29:
                raise ValueError("service did not become healthy: "+redact_diagnostic(str(exc))[:160]) from exc
            time.sleep(5)


def activate_release(release, previous):
    phase = "compose"
    try:
        with (release/"compose.log").open("w") as log:
            run(compose(release, "up", "-d", "--build"),stdout=log,stderr=subprocess.STDOUT)
        phase = "health"
        wait_healthy(release)
    except Exception as exc:
        outcome = dict(job=OPERATION_ID or None,release=release.name,build_phase=phase,
                       error_kind="COMPOSE_FAILED" if phase=="compose" else "SERVICE_HEALTH_FAILED",progress="failed",
                       errors=safe_errors(release/"compose.log","compose") if phase=="compose" else
                           [redact_diagnostic(str(exc))[:120]],
                       rollback="NOT_AVAILABLE",summary="New deployment failed; prior application recovery unavailable")
        if not outcome["errors"]:
            outcome["errors"]=[redact_diagnostic(str(exc))[:120]]
        if previous is not None and previous_release() == previous:
            try:
                # Rebuild only the application image from the prior existing JAR.
                # --no-deps prevents any database creation/restart or schema rollback.
                run(compose(previous,"up","-d","--build","--no-deps",APP_SERVICE))
                wait_healthy(previous)
                outcome.update(rollback="SUCCEEDED",summary="Previous application recovered; new deployment remains failed")
            except Exception:
                outcome.update(rollback="FAILED",summary="Previous application recovery failed; inspect protected diagnostics")
        (release/"start-result.json").write_text(json.dumps(outcome))
        emit_diagnosis(outcome)
        raise ValueError("new deployment activation failed; rollback="+outcome["rollback"]) from exc


def start_locked(archive_file):
    previous = previous_release()
    data = Path(archive_file).read_bytes()
    archive = validate_archive(data, SOURCE_PATHS)
    digest = hashlib.sha256(data).hexdigest()
    releases = ROOT / "releases"
    releases.mkdir(parents=True, exist_ok=True)
    release = releases / (digest[:16] + "-" + secrets.token_hex(4))
    release.mkdir(mode=0o700)
    with archive:
        extract_source(archive, release)
    (release / "source-sha256").write_text(digest)
    (release / "operation-id").write_text(OPERATION_ID)
    provision_secrets(release)
    service = release / APP_DIR
    (ROOT / "pending").write_text(str(release))
    memory, heap = build_budget()
    cache = ROOT / "gradle-cache"
    cache.mkdir(mode=0o700, exist_ok=True)
    run_build(["docker", "run", "--name", BUILD_NAME, "--label", BUILD_LABEL, "--label", "ai-team.operation-id=" + OPERATION_ID, "--user", "0:0", "--network", "bridge", "--memory", str(memory) + "m", "--memory-swap", str(memory) + "m", "--cpus", "2",
         "--mount", "type=bind,src=" + str(cache) + ",dst=/build-cache",
         "--env", "GRADLE_USER_HOME=/build-cache",
         "--mount", "type=bind,src=" + str(service) + ",dst=/workspace", "--workdir", "/workspace",
         "gradle:8.14.5-jdk21", "gradle", "--gradle-user-home", "/build-cache", "--no-daemon", "--max-workers=2", "build", "-x", "test",
         "-Dorg.gradle.jvmargs=-Xmx" + str(heap) + "m -XX:MaxMetaspaceSize=512m",
         "-Pkotlin.compiler.execution.strategy=in-process",
         "-Dquarkus.package.type=uber-jar", "-Dorg.gradle.daemon=false", "-Dquarkus.profile=prod"], release)
    activate_release(release,previous)
    # Save active release only after actual health success.
    (ROOT / "active").write_text(str(release))
    (ROOT / "pending").unlink(missing_ok=True)
    print("START_PASS: release=" + release.name)


def repair():
    import fcntl
    with (ROOT/"build.lock").open("a") as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        release=previous_release()
        if release is None:
            raise ValueError("no verified active release to recover; data retained")
        run(compose(release,"up","-d","--no-deps","--no-build",APP_SERVICE))
        wait_healthy(release)
        print("REPAIR_PASS: existing release="+release.name+"; no source build; database and data volumes retained")


def main():
    if os.geteuid() != 0:
        raise ValueError("reviewed service operations require administrator execution in the target VM")
    global BUILD_NAME, OPERATION_ID
    action = sys.argv[1]
    try:
        config = load_target()
        mode = target_mode(config)
        configured = mode == "restricted" or "service" in config
        if configured:
            configure(service_settings(config))
        elif action not in {"prepare", "exec", "cancel-build"}:
            raise config_error("this sandbox target has no service settings; use exec scripts for " + action)
        if action == "exec" and mode != "sandbox":
            raise ValueError("SANDBOX_NOT_ENABLED: exec is only available on targets the administrator declared sandbox")
    except ValueError as exc:
        # Surface the missing configuration through the fixed receipt instead of guessing a layout.
        kind = "SANDBOX_NOT_ENABLED" if str(exc).startswith("SANDBOX_NOT_ENABLED") else "SERVICE_CONFIG_REQUIRED"
        print("DIAGNOSIS: " + json.dumps(dict(error_kind=kind, progress="not_started",
                                             summary=str(exc)[:600])), flush=True)
        raise
    if action == "cancel-build":
        if not configured:
            return  # sandbox scripts are stopped with their own systemd unit; no owned build container exists
        job = sys.argv[2]
        if not re.fullmatch(r"[0-9a-f]{32}",job):
            raise ValueError("invalid cancellation identity")
        OPERATION_ID=job;BUILD_NAME="ai-team-build-"+job
        remove_owned_build(timeout=10)
    elif action == "prepare":
        prepare(mode, config["target"])
    elif action == "exec":
        sandbox_exec(sys.argv[2], config["target"])
    elif action == "start":
        start(sys.argv[2])
    elif action == "repair":
        repair()
    elif action in {"health", "diagnose"}:
        state = ROOT / ("pending" if action == "diagnose" and (ROOT / "pending").exists() else "active")
        release = Path(state.read_text().strip())
        if release.parent != ROOT / "releases" or release.is_symlink():
            raise ValueError("invalid active release")
        if action == "health":
            health(release)
        else:
            digest_file = release / "source-sha256"
            digest = digest_file.read_text().strip() if digest_file.exists() else None
            if digest is not None and (len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest)):
                digest = None
            print("DIAGNOSIS: " + json.dumps(dict(release=release.name,source_sha256=digest,
                  build_phase="inspection",error_kind=None,progress="observed",
                  summary="Check the original operation receipt before deciding whether to retry")))
            activation = release / "start-result.json"
            if activation.exists():
                record = json.loads(activation.read_text())
                safe = {key:record.get(key) for key in ("job","release","build_phase","error_kind","progress","errors","rollback","summary")}
                emit_diagnosis(safe)
            build_result = release / "build-result.json"
            emit_diagnosis(build_diagnosis(release))
            ids = subprocess.check_output(compose(release, "ps", "--all", "-q"), text=True).split()
            if not ids:
                print("DIAGNOSIS: no containers; build or Compose creation did not finish")
            for container in ids:
                data = json.loads(subprocess.check_output(["docker", "inspect", container], text=True))[0]
                state = data["State"]
                print("DIAGNOSIS: " + json.dumps({
                    "service": data["Config"].get("Labels", {}).get("com.docker.compose.service") if data["Config"].get("Labels", {}).get("com.docker.compose.service") in {DB_SERVICE,APP_SERVICE} else "other",
                    "status": state.get("Status"), "exit_code": state.get("ExitCode"),
                    "oom_killed": state.get("OOMKilled"),
                    "health": state.get("Health", {}).get("Status")}, ensure_ascii=True))
            # Never return raw inspect/config/logs: environment values may contain credentials.
    else:
        raise ValueError("unsupported action")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("SERVICE_FAILED: " + type(exc).__name__ + ": " + str(exc), file=sys.stderr)
        sys.exit(1)
