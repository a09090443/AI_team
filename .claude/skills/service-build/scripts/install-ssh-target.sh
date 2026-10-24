#!/usr/bin/env bash
# Administrator-only, one-time target setup; no password input or private key handling.
set -euo pipefail
umask 077
[[ $(id -u) == 0 ]] || { echo 'Run with sudo on the target VM'; exit 1; }
user=${1:?usage: install-ssh-target.sh login-user public-key-file target-name service-config}
pubfile=${2:?missing public key}; target=${3:?missing target}
# Product layout (root, source paths, Compose project and services); see assets/target-service.example.json.
service=${4:?missing product service configuration}
[[ $user =~ ^[a-z_][a-z0-9_-]*$ && $target =~ ^[a-z0-9][a-z0-9-]{0,63}$ ]] || exit 1
getent passwd "$user" >/dev/null
command -v visudo >/dev/null
here=$(cd "$(dirname "$0")" && pwd)
python3 - "$pubfile" <<'PY'
import json,re,sys
from pathlib import Path
key=Path(sys.argv[1]).read_text().strip()
assert re.fullmatch(r"ssh-ed25519 [A-Za-z0-9+/=]+(?: [^\r\n]+)?",key), "invalid public key"

PY
# Validate before changing the target; a missing or invalid setting stops here instead of being guessed.
python3 - "$here" "$service" <<'PY'
import json,sys
from pathlib import Path
sys.path.insert(0,sys.argv[1])
from guest import service_settings
service_settings({"service":json.loads(Path(sys.argv[2]).read_text())})
PY
install -d -m 0755 /usr/local/lib/ai-team-service
install -d -m 0700 /etc/ai-team-service /var/lib/ai-team-service
install -m 0755 "$here/ssh_target.py" /usr/local/sbin/ai-team-ssh-service
install -m 0644 "$here/guest.py" /usr/local/lib/ai-team-service/guest.py
python3 - "$here" "$target" "$service" <<'PY'
import json,sys
from pathlib import Path
sys.path.insert(0,sys.argv[1])
from guest import service_settings
settings=service_settings({"service":json.loads(Path(sys.argv[3]).read_text())})
Path("/etc/ai-team-service/target.json").write_text(json.dumps({"target":sys.argv[2],"service":settings}))
PY
sudoers=$(mktemp)
trap 'rm -f "$sudoers"' EXIT
printf '%s ALL=(root) NOPASSWD: /usr/local/sbin/ai-team-ssh-service *\n' "$user" > "$sudoers"
visudo -cf "$sudoers"
install -m 0440 "$sudoers" /etc/sudoers.d/ai-team-ssh-service
python3 - "$user" "$pubfile" <<'PY'
import os,pwd,sys
from pathlib import Path
u=pwd.getpwnam(sys.argv[1]); home=Path(u.pw_dir); directory=home/".ssh"
assert not directory.is_symlink()
directory.mkdir(mode=0o700,exist_ok=True);os.chown(directory,u.pw_uid,u.pw_gid)
auth=directory/"authorized_keys";assert not auth.is_symlink()
key=Path(sys.argv[2]).read_text().strip()
prefix = 'restrict,command="sudo -n /usr/local/sbin/ai-team-ssh-service \\"$SSH_ORIGINAL_COMMAND\\"" ' 
line=prefix+key
existing=auth.read_text() if auth.exists() else ""
# Refuse an existing unrestricted copy rather than silently pretending it was restricted.
keydata=key.split()[1]
if any(keydata in old and old!=line for old in existing.splitlines()):
    raise ValueError("same public key already enrolled differently; review before replacing")
if line not in existing.splitlines():
    with auth.open("a") as out: out.write(("\n" if existing and not existing.endswith("\n") else "")+line+"\n")
os.chmod(auth,0o600);os.chown(auth,u.pw_uid,u.pw_gid)
PY
echo 'Restricted SSH target installed. Existing service data and other keys retained.'
