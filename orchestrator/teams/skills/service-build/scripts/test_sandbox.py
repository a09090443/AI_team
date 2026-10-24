import hashlib
import io
import json
import subprocess
import tarfile
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import client
import guest
import service_fixture as fx
import ssh_client as direct
import ssh_executor as executor
import ssh_target as target

try:
    import fcntl
except ImportError:
    fcntl = SimpleNamespace(LOCK_EX=2, LOCK_NB=4, flock=lambda *args: None)


def payload(entries):
    return direct.pack([(name, content, False) for name, content in entries])


class TargetModeTests(unittest.TestCase):
    def test_install_config_sandbox_service_is_optional(self):
        self.assertEqual({"mode": "sandbox"}, guest.install_config({"mode": "sandbox"}))
        self.assertEqual({"mode": "sandbox", "service": fx.SERVICE}, guest.install_config(dict(fx.SERVICE, mode="sandbox")))
        self.assertEqual({"mode": "restricted", "service": fx.SERVICE}, guest.install_config(fx.SERVICE))
        for bad in ({}, {"mode": "root"}, {"mode": "restricted"}, [], {"mode": "sandbox", "root": "/opt/x"}):
            with self.subTest(bad=bad), self.assertRaisesRegex(ValueError, "SERVICE_CONFIG_REQUIRED"):
                guest.install_config(bad)

    def test_exec_payload_structure_is_validated(self):
        self.assertEqual(2, len(guest.validate_exec_archive(payload([("run.sh", b"true"), ("source/a.txt", b"x")])).getmembers()))
        for entries in ([("source/a.txt", b"x")], [("run.sh", b"true"), ("other/a", b"x")],
                        [("run.sh", b"true"), ("source/../../etc/passwd", b"x")],
                        [("run.sh", b"true"), ("source/.env", b"x")], [("run.sh", b"true"), ("source/.git/config", b"x")]):
            with self.subTest(entries=entries), self.assertRaises(ValueError):
                guest.validate_exec_archive(payload(entries))


class GuestExecTests(unittest.TestCase):
    def run_exec(self, tmp, code, lines):
        def script(argv, cwd, env, stdin, stdout, stderr, timeout):
            self.assertEqual("bash", argv[0])
            self.assertTrue(str(cwd).endswith("source"))
            self.assertTrue(env["AI_TEAM_SANDBOX_HOME"].endswith("example-dev/home"))
            stdout.write(("\n".join(lines) + "\n").encode())
            return SimpleNamespace(returncode=code)
        archive = Path(tmp) / "payload.tar"
        archive.write_bytes(payload([("run.sh", b"echo hi"), ("source/app/a.txt", b"x")]))
        with patch.object(guest, "SANDBOX_BASE", Path(tmp) / "sandbox"), patch.object(guest, "ROOT", None), \
                patch.object(guest.subprocess, "run", side_effect=script), patch("builtins.print") as output:
            try:
                guest.sandbox_exec(archive, "example-dev")
            finally:
                printed = [call.args[0] for call in output.call_args_list]
        return printed

    def test_success_reports_redacted_tail_and_pass_marker(self):
        with tempfile.TemporaryDirectory() as tmp:
            printed = self.run_exec(tmp, 0, ["installed docker", "password=hunter2-secret"])
            self.assertIn("EXEC_OUTPUT: installed docker", printed)
            self.assertNotIn("hunter2-secret", " ".join(printed))
            self.assertTrue(printed[-1].startswith("EXEC_PASS: exit=0"))
            run = next((Path(tmp) / "sandbox/example-dev/runs").iterdir())
            self.assertEqual(b"x", (run / "source/app/a.txt").read_bytes())

    def test_failure_is_reported_and_raises(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ValueError, "sandbox script failed"):
                self.run_exec(tmp, 3, ["apt failed"])

    def test_old_runs_are_pruned(self):
        with tempfile.TemporaryDirectory() as tmp:
            runs = Path(tmp) / "sandbox/example-dev/runs"
            for index in range(8):
                (runs / ("old%d" % index)).mkdir(parents=True)
            self.run_exec(tmp, 0, ["ok"])
            self.assertEqual(5, len(list(runs.iterdir())))


class TargetEntryTests(unittest.TestCase):
    def context(self, tmp, mode=None):
        base = Path(tmp)
        state, lib = base / "state", base / "lib"
        state.mkdir(); lib.mkdir()
        config = base / "target.json"
        data = {"target": "sit", "service": fx.SERVICE}
        if mode:
            data["mode"] = mode
        config.write_text(json.dumps(data))
        return state, lib, config

    def submit(self, tmp, action, data, mode=None, worker_output="", returncode=0):
        state, lib, config = self.context(tmp, mode)
        digest = hashlib.sha256(data).hexdigest()
        def completed(argv, stdout, **kwargs):
            stdout.write(worker_output)
            return SimpleNamespace(returncode=returncode)
        with patch.dict("sys.modules", fcntl=fcntl), patch.object(target, "STATE", state), patch.object(target, "CONFIG", config), \
                patch.object(target, "LIB", lib), patch.object(target, "ENTRY", lib / "entry"), \
                patch.object(target.os, "geteuid", return_value=0, create=True), \
                patch.object(target.sys, "stdin", SimpleNamespace(buffer=io.BytesIO(data))), \
                patch.object(target, "spawn_worker", side_effect=lambda text, job: target.worker(text)), \
                patch.object(target.subprocess, "run", side_effect=completed):
            result = target.execute("ai-team-service sit " + action + " " + "a" * 32 + " " + digest)
            if result["exit_code"] == 75 and action == "exec":
                result = target.execute("ai-team-service sit result " + "a" * 32)
        return result, lib

    def test_restricted_target_rejects_exec_and_upgrade(self):
        for action in ("exec", "upgrade"):
            with self.subTest(action=action), tempfile.TemporaryDirectory() as tmp:
                data = payload([("run.sh", b"true")]) if action == "exec" else payload([("ssh_target.py", b"x=1"), ("guest.py", b"y=2")])
                result, _ = self.submit(tmp, action, data)
                self.assertEqual(1, result["exit_code"])
                self.assertTrue(result["evidence"][0].startswith("SANDBOX_NOT_ENABLED"))

    def test_sandbox_exec_runs_through_worker_with_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            result, _ = self.submit(tmp, "exec", payload([("run.sh", b"true")]), "sandbox",
                                    "noise\nEXEC_OUTPUT: hello\nEXEC_PASS: exit=0; run=x\n")
            self.assertEqual(0, result["exit_code"])
            self.assertEqual(["EXEC_OUTPUT: hello", "EXEC_PASS: exit=0; run=x"], result["evidence"])

    def test_sandbox_exec_failure_keeps_output_for_diagnosis(self):
        with tempfile.TemporaryDirectory() as tmp:
            result, _ = self.submit(tmp, "exec", payload([("run.sh", b"false")]), "sandbox",
                                    "EXEC_OUTPUT: E: Unable to locate package\nEXEC_FAILED: exit=100\n", returncode=1)
            self.assertEqual(1, result["exit_code"])
            self.assertIn("EXEC_OUTPUT: E: Unable to locate package", result["evidence"])
            self.assertIn("EXEC_FAILED: exit=100", result["evidence"])

    def test_upgrade_replaces_then_reports_unchanged(self):
        with tempfile.TemporaryDirectory() as tmp:
            data = payload([("ssh_target.py", b"VERSION = 2\n"), ("guest.py", b"VERSION = 2\n")])
            result, lib = self.submit(tmp, "upgrade", data, "sandbox")
            self.assertEqual("UPGRADE_PASS", result["evidence"][0].split(":")[0])
            self.assertEqual(b"VERSION = 2\n", (lib / "entry").read_bytes())
            self.assertEqual(b"VERSION = 2\n", (lib / "guest.py").read_bytes())

    def test_upgrade_is_unchanged_deferred_or_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            state, lib, config = self.context(tmp, "sandbox")
            same = payload([("ssh_target.py", b"A = 1\n"), ("guest.py", b"B = 1\n")])
            (lib / "entry").write_bytes(b"A = 1\n"); (lib / "guest.py").write_bytes(b"B = 1\n")
            c = json.loads(config.read_text())
            with patch.object(target, "STATE", state), patch.object(target, "LIB", lib), patch.object(target, "ENTRY", lib / "entry"):
                result = target.upgrade_entry("a" * 32, hashlib.sha256(same).hexdigest(), same, c)
                self.assertTrue(result["evidence"][0].startswith("UPGRADE_UNCHANGED"))
                newer = payload([("ssh_target.py", b"A = 2\n"), ("guest.py", b"B = 2\n")])
                (state / ("b" * 32 + ".running")).write_text("start")
                result = target.upgrade_entry("a" * 32, hashlib.sha256(newer).hexdigest(), newer, c)
                self.assertEqual(75, result["exit_code"]); self.assertEqual(b"A = 1\n", (lib / "entry").read_bytes())
                (state / ("b" * 32 + ".running")).unlink()
                broken = payload([("ssh_target.py", b"def broken(:\n"), ("guest.py", b"B = 2\n")])
                with self.assertRaises(SyntaxError):
                    target.upgrade_entry("a" * 32, hashlib.sha256(broken).hexdigest(), broken, c)
                self.assertEqual(b"A = 1\n", (lib / "entry").read_bytes())
                extra = payload([("ssh_target.py", b"A = 2\n"), ("guest.py", b"B = 2\n"), ("evil.py", b"x")])
                self.assertEqual(1, target.upgrade_entry("a" * 32, hashlib.sha256(extra).hexdigest(), extra, c)["exit_code"])


class AgentSideTests(unittest.TestCase):
    def test_exec_payload_is_deterministic_and_skips_secrets(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = fx.workspace(tmp)
            for name, content in (("app-service/a.txt", "a"), ("deploy/sit/.env.sit", "SECRET"),
                                  ("deploy/sit/sandbox/start.sh", "echo start")):
                (root / name).parent.mkdir(parents=True, exist_ok=True)
                (root / name).write_text(content)
            listing = b"app-service/a.txt\0deploy/sit/.env.sit\0deploy/sit/sandbox/start.sh\0deploy/sql/deleted.sql\0"
            c = {"source_paths": fx.SOURCE_PATHS}
            with patch.object(direct.subprocess, "check_output", return_value=listing):
                first = direct.exec_payload(root, c, "deploy/sit/sandbox/start.sh")
                second = direct.exec_payload(root, c, "deploy/sit/sandbox/start.sh")
            self.assertEqual(hashlib.sha256(first).hexdigest(), hashlib.sha256(second).hexdigest())
            with tarfile.open(fileobj=io.BytesIO(first)) as archive:
                names = archive.getnames()
                self.assertEqual(b"echo start", archive.extractfile("run.sh").read())
            self.assertEqual(["run.sh", "source/app-service/a.txt", "source/deploy/sit/sandbox/start.sh"], names)
            guest.validate_exec_archive(first).close()

    def test_exec_script_path_is_validated(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = fx.workspace(tmp)
            (root / ".hidden").mkdir(); (root / ".hidden/run.sh").write_text("x")
            for bad in ("../outside.sh", ".hidden/run.sh", "missing.sh", "/etc/passwd"):
                with self.subTest(bad=bad), self.assertRaises(ValueError):
                    client.sandbox_script(root, bad)

    def test_different_exec_script_waits_for_pending_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = fx.workspace(tmp)
            queue = fx.queue(root)
            for name in ("deploy/sit/a.sh", "deploy/sit/b.sh"):
                (root / name).write_text("true")
            request = dict(id="a" * 32, action="exec", task="123", created=1, script="deploy/sit/a.sh")
            (queue / "request.json").write_text(json.dumps(request))
            with patch.object(client, "workspace", return_value=root), patch("builtins.print") as output:
                self.assertEqual(0, client.submit("exec", timeout=0, script="deploy/sit/b.sh"))
            shown = json.loads(output.call_args[0][0])
            self.assertEqual(("OPERATION_PENDING", "deploy/sit/a.sh"), (shown["status"], shown["script"]))
            self.assertEqual(request, json.loads((queue / "request.json").read_text()))

    def test_new_exec_request_records_script(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = fx.workspace(tmp)
            (root / "deploy/sit").mkdir(parents=True, exist_ok=True)
            (root / "deploy/sit/start.sh").write_text("true")
            with patch.object(client, "workspace", return_value=root), patch.object(client, "source_head", return_value="head"), \
                    patch.object(client, "authorized", return_value=True), patch("builtins.print"):
                client.submit("exec", timeout=0, script="deploy/sit/start.sh")
            saved = json.loads((root / fx.QUEUE / "request.json").read_text())
            self.assertEqual(("exec", "deploy/sit/start.sh"), (saved["action"], saved["script"]))
            with self.assertRaises(ValueError):
                client.submit("exec", timeout=0)


class ExecutorIntakeTests(unittest.TestCase):
    def intake(self, request):
        with tempfile.TemporaryDirectory() as tmp:
            root = fx.workspace(tmp)
            home = root.parent.parent
            (fx.queue(root) / "request.json").write_text(json.dumps(request))
            with patch.object(executor.ssh_client, "config", return_value={"target": "sit"}), \
                    patch.object(executor.client, "source_head", return_value="head"), \
                    patch.object(executor.ssh_client, "execute_job", return_value=None):
                executor.poll(home, None)
            job_file = home / "operation-jobs" / "123" / (request["id"] + ".json")
            accepted = (root / fx.QUEUE / (request["id"] + ".accepted")).exists()
            return (json.loads(job_file.read_text()) if job_file.exists() else None), accepted

    def test_new_request_is_accepted_into_a_journal(self):
        job, accepted = self.intake(dict(id="a" * 32, action="health", task="123", created=int(time.time())))
        self.assertEqual(("health", "service"), (job["action"], job["node"]))
        self.assertTrue(accepted)

    def test_exec_request_keeps_script_and_rejects_bad_path(self):
        job, _ = self.intake(dict(id="a" * 32, action="exec", task="123", created=1, script="deploy/sit/start.sh"))
        self.assertEqual("deploy/sit/start.sh", job["script"])
        job, accepted = self.intake(dict(id="b" * 32, action="exec", task="123", created=1, script="../escape.sh"))
        self.assertIsNone(job); self.assertFalse(accepted)

    def test_entry_upgrade_only_for_sandbox_and_at_most_every_ten_minutes(self):
        state = {}
        with patch.object(executor.ssh_client, "config", return_value={"target": "sit"}), \
                patch.object(executor.ssh_client, "upgrade_entry") as upgrade:
            executor.maybe_upgrade(None, state)
            upgrade.assert_not_called()
        state = {}
        with patch.object(executor.ssh_client, "config", return_value={"target": "sit", "mode": "sandbox"}), \
                patch.object(executor.ssh_client, "upgrade_entry", return_value=dict(evidence=["UPGRADE_UNCHANGED: ok"])) as upgrade, \
                patch("builtins.print"):
            executor.maybe_upgrade(None, state)
            executor.maybe_upgrade(None, state)
            upgrade.assert_called_once()


if __name__ == "__main__":
    unittest.main()
