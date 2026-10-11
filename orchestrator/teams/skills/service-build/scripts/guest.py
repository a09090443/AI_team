#!/usr/bin/env python3
"""Reviewed operations inside an explicitly dedicated Ubuntu SIT VM. No arbitrary commands."""
import base64
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import secrets
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request

ROOT = Path("/opt/ai-team-bestpartner-sit")
COMPOSE = "docs/docker/sit/docker-compose.yml"


def run(argv, **kwargs):
    subprocess.run(argv, check=True, timeout=1800, **kwargs)


def prepare():
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
    ROOT.mkdir(mode=0o700, exist_ok=True)
    print("PREPARED: SSH, Docker and Compose; no database volumes removed")


def validate_archive(data):
    archive = tarfile.open(fileobj=io.BytesIO(data))
    total = 0
    for member in archive.getmembers():
        p = PurePosixPath(member.name)
        if (p.is_absolute() or ".." in p.parts or not p.parts
                or p.parts[0] not in {"bestpartner-service", "docs"}
                or (p.parts[0] == "docs" and len(p.parts) > 1 and p.parts[1] not in {"sql", "docker"})
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
        if any(x.startswith("bestpartner-sit_") for x in existing.splitlines()):
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
    (release / ".secrets").mkdir(mode=0o700)
    (release / ".secrets/sit-db-password").symlink_to(password_file)
    (release / ".env.sit").symlink_to(env)


def compose(release, *args):
    return ["docker", "compose", "--project-name", "bestpartner-sit", "-f", str(release / COMPOSE), *args]


def health(release):
    # Inspect containers directly: Compose versions differ in JSON array/JSON Lines output.
    ids = subprocess.check_output(compose(release, "ps", "-q", "postgres", "bestpartner"), text=True).split()
    if len(ids) != 2:
        raise ValueError("database and application must both be running")
    details = json.loads(subprocess.check_output(["docker", "inspect", *ids], text=True))
    if any(not d["State"]["Running"] for d in details):
        raise ValueError("a required container is not running")
    if any(d["Config"].get("Labels", {}).get("com.docker.compose.service") == "postgres"
           and d["State"].get("Health", {}).get("Status") != "healthy" for d in details):
        raise ValueError("PostgreSQL health check failed")
    with urllib.request.urlopen("http://127.0.0.1/view/chat", timeout=15) as response:
        if response.status != 200:
            raise ValueError("application health page failed")
    print("HEALTH_PASS: PostgreSQL and backend running; /view/chat HTTP 200")


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


BUILD_NAME = "ai-team-bestpartner-build"
BUILD_LABEL = "ai-team.service-build=bestpartner-sit"


def remove_owned_build():
    found = subprocess.run(["docker", "inspect", BUILD_NAME], capture_output=True, text=True, timeout=30)
    if found.returncode:
        return
    item = json.loads(found.stdout)[0]
    if item.get("Config", {}).get("Labels", {}).get("ai-team.service-build") != "bestpartner-sit":
        raise ValueError("build container name belongs to another owner")
    subprocess.run(["docker", "rm", "-f", BUILD_NAME], check=True, capture_output=True, timeout=30)


def run_build(argv, release):
    remove_owned_build()
    result = {"exit_code": None, "timed_out": False}
    try:
        with (release / "build.log").open("wb") as log:
            try:
                done = subprocess.run(argv, stdout=log, stderr=subprocess.STDOUT, timeout=900)
                result["exit_code"] = done.returncode
            except subprocess.TimeoutExpired:
                result["timed_out"] = True
        probe = subprocess.run(["docker", "inspect", BUILD_NAME], capture_output=True, text=True, timeout=30)
        if probe.returncode == 0:
            result["oom_killed"] = json.loads(probe.stdout)[0].get("State", {}).get("OOMKilled", False)
        (release / "build-result.json").write_text(json.dumps(result))
        if result["timed_out"] or result["exit_code"] != 0:
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


def start_locked(archive_file):
    data = Path(archive_file).read_bytes()
    archive = validate_archive(data)
    digest = hashlib.sha256(data).hexdigest()
    releases = ROOT / "releases"
    releases.mkdir(parents=True, exist_ok=True)
    release = releases / (digest[:16] + "-" + secrets.token_hex(4))
    release.mkdir(mode=0o700)
    extract_source(archive, release)
    provision_secrets(release)
    service = release / "bestpartner-service"
    (ROOT / "pending").write_text(str(release))
    memory, heap = build_budget()
    cache = ROOT / "gradle-cache"
    cache.mkdir(mode=0o700, exist_ok=True)
    run_build(["docker", "run", "--name", BUILD_NAME, "--label", BUILD_LABEL, "--user", "0:0", "--network", "bridge", "--memory", str(memory) + "m", "--memory-swap", str(memory) + "m", "--cpus", "2",
         "--mount", "type=bind,src=" + str(cache) + ",dst=/build-cache",
         "--env", "GRADLE_USER_HOME=/build-cache",
         "--mount", "type=bind,src=" + str(service) + ",dst=/workspace", "--workdir", "/workspace",
         "gradle:8.14.5-jdk21", "gradle", "--gradle-user-home", "/build-cache", "--no-daemon", "--max-workers=2", "build", "-x", "test",
         "-Dorg.gradle.jvmargs=-Xmx" + str(heap) + "m -XX:MaxMetaspaceSize=512m",
         "-Pkotlin.compiler.execution.strategy=in-process",
         "-Dquarkus.package.type=uber-jar", "-Dorg.gradle.daemon=false", "-Dquarkus.profile=prod"], release)
    run(compose(release, "up", "-d", "--build"))
    for attempt in range(30):
        try:
            health(release)
            break
        except Exception:
            if attempt == 29:
                raise ValueError("service did not become healthy; use diagnose; data is retained")
            time.sleep(5)
    # Save active release only after actual health success.
    (ROOT / "active").write_text(str(release))
    (ROOT / "pending").unlink(missing_ok=True)
    print("START_PASS: release=" + release.name)


def main():
    if os.geteuid() != 0:
        raise ValueError("reviewed service operations require administrator execution in the target VM")
    action = sys.argv[1]
    if action == "prepare":
        prepare()
    elif action == "start":
        start(sys.argv[2])
    elif action in {"health", "diagnose"}:
        state = ROOT / ("pending" if action == "diagnose" and (ROOT / "pending").exists() else "active")
        release = Path(state.read_text().strip())
        if release.parent != ROOT / "releases" or release.is_symlink():
            raise ValueError("invalid active release")
        if action == "health":
            health(release)
        else:
            build_result = release / "build-result.json"
            if build_result.exists():
                print("DIAGNOSIS: build=" + build_result.read_text())
            ids = subprocess.check_output(compose(release, "ps", "--all", "-q"), text=True).split()
            if not ids:
                print("DIAGNOSIS: no containers; build or Compose creation did not finish")
            for container in ids:
                data = json.loads(subprocess.check_output(["docker", "inspect", container], text=True))[0]
                state = data["State"]
                print("DIAGNOSIS: " + json.dumps({
                    "service": data["Config"].get("Labels", {}).get("com.docker.compose.service"),
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
