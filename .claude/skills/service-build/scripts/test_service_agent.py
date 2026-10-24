import subprocess
import io
import json
from pathlib import Path
import tarfile
import tempfile
import time
import sys
import sqlite3
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import broker
import client
import guest
import service_fixture as fx


def archive(entries):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w") as out:
        for name, kind in entries:
            info = tarfile.TarInfo(name)
            info.type = kind
            info.linkname = "/etc/passwd" if kind == tarfile.SYMTYPE else ""
            if kind == tarfile.REGTYPE:
                info.size = 4
                out.addfile(info, io.BytesIO(b"test"))
            else:
                out.addfile(info)
    return stream.getvalue()


class SourceTests(unittest.TestCase):
    def test_valid_product_archive(self):
        data = archive([("deploy", tarfile.DIRTYPE), ("app-service/build.gradle.kts", tarfile.REGTYPE),
                        ("deploy/sit/docker-compose.yml", tarfile.REGTYPE)])
        self.assertEqual(3, len(guest.validate_archive(data, fx.SOURCE_PATHS).getmembers()))

    def test_archive_rejects_escape_secret_links_and_unconfigured_paths(self):
        for name, kind in [("/etc/passwd", tarfile.REGTYPE), ("deploy/../../etc/passwd", tarfile.REGTYPE),
                           ("app-service/.env.sit", tarfile.REGTYPE), ("README.md", tarfile.REGTYPE),
                           ("deploy/other/file", tarfile.REGTYPE), ("deploy/sitx/file", tarfile.REGTYPE),
                           ("deploy/sit/.ssh/id_rsa", tarfile.REGTYPE), ("deploy/sit/link", tarfile.SYMTYPE),
                           ("deploy/sit/hardlink", tarfile.LNKTYPE), ("deploy/sit/fifo", tarfile.FIFOTYPE)]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                guest.validate_archive(archive([(name, kind)]), fx.SOURCE_PATHS)

    def test_service_settings_never_guess_missing_values(self):
        with self.assertRaisesRegex(ValueError, "SERVICE_CONFIG_REQUIRED: target configuration has no service"):
            guest.service_settings({"target": "sit"})
        partial = dict(fx.SERVICE)
        del partial["compose_project"], partial["root"]
        with self.assertRaisesRegex(ValueError, "missing service settings: root, compose_project"):
            guest.service_settings({"service": partial})
        for key, value in [("root", "/"), ("root", "relative/dir"), ("compose_file", "other/docker-compose.yml"),
                           ("app_directory", "../escape"), ("compose_project", "Bad Name"),
                           ("database_service", "app"), ("health_url", "file:///etc/passwd"),
                           ("env_file", "deploy/sit/.env"), ("source_paths", [".git"])]:
            with self.subTest(key=key, value=value), self.assertRaisesRegex(ValueError, "SERVICE_CONFIG_REQUIRED"):
                guest.service_settings({"service": dict(fx.SERVICE, **{key: value})})
        self.assertEqual(fx.SERVICE, guest.service_settings({"service": fx.SERVICE}))

    def test_source_pin_is_enforced_before_guest_execution(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "manifest.json"
            manifest.write_text(json.dumps({"app-service/build.gradle.kts": "0" * 64}))
            b = broker.Broker({"state_directory": tmp, "source_manifest": str(manifest), "source_paths": fx.SOURCE_PATHS})
            with self.assertRaisesRegex(ValueError, "manifest"):
                b.verify_source(archive([("app-service/build.gradle.kts", tarfile.REGTYPE)]))


class QueueTests(unittest.TestCase):
    def test_accepted_request_waits_even_when_poll_heartbeat_is_stale(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = fx.workspace(tmp)
            queue = fx.queue(root)
            request = {"id": "a" * 32, "task": "123", "action": "prepare", "created": int(time.time())}
            (queue / "request.json").write_text(json.dumps(request))
            (queue / (request["id"] + ".accepted")).write_text("accepted")
            result = dict(request, exit_code=0)
            with patch.object(client, "workspace", return_value=root), \
                    patch("time.monotonic", side_effect=[0, 0, 20, 20, 22, 22]), \
                    patch("time.sleep", side_effect=lambda _: (queue / (request["id"] + ".json")).write_text(json.dumps(result))):
                self.assertEqual(0, client.submit("prepare", timeout=100))

    def test_guest_run_uses_vbox_default_argv_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            b = broker.Broker({"state_directory": tmp})
            with patch.object(b, "vbox") as call:
                b.run_guest("/usr/bin/id", ["-u"], timeout=30)
            args = call.call_args.args[1]
            self.assertEqual(["-u"], args[args.index("--") + 1:])

    def test_only_active_service_tasks_can_request_privileged_work(self):
        with tempfile.TemporaryDirectory() as tmp:
            flow = fx.flow("example-dev")
            root = fx.workspace(tmp, data=flow)
            c = sqlite3.connect(root.parent.parent / "ai-team.db")
            self.assertTrue(client.authorized(root, "example-dev"))
            self.assertTrue(client.authorized(root))
            c.execute("update task set state='AWAIT_HUMAN'")
            c.commit()
            self.assertFalse(client.authorized(root))
            c.execute("update task set state='STAGE_RUNNING'")
            flow["snapshot"]["definition"]["project"]["serviceTarget"]["name"] = "other-host"
            c.execute("update flow_run set data=?", (json.dumps(flow),))
            c.commit()
            self.assertFalse(client.authorized(root, "example-dev"))
            self.assertTrue(client.authorized(root))
            del flow["snapshot"]["definition"]["project"]["serviceTarget"]["name"]
            c.execute("update flow_run set data=?", (json.dumps(flow),))
            c.commit()
            self.assertFalse(client.authorized(root))
            c.close()

    def test_request_directory_comes_only_from_start_operation_writes(self):
        self.assertEqual(Path(fx.QUEUE), client.request_directory(fx.flow()))
        for writes in (None, [], [fx.QUEUE, "other"], ["../escape"], ["deploy/*"], ["/abs"]):
            flow = fx.flow()
            flow["snapshot"]["definition"]["project"]["operations"]["service-start"]["writes"] = writes
            with self.subTest(writes=writes), self.assertRaisesRegex(ValueError, "SERVICE_CONFIG_REQUIRED"):
                client.request_directory(flow)

    def test_active_service_without_request_directory_fails_explicitly(self):
        with tempfile.TemporaryDirectory() as tmp:
            flow = fx.flow()
            flow["snapshot"]["definition"]["project"]["operations"] = {}
            root = fx.workspace(tmp, data=flow)
            with patch.object(client, "workspace", return_value=root), \
                    self.assertRaisesRegex(ValueError, "SERVICE_CONFIG_REQUIRED"):
                client.submit("prepare", timeout=0)
            self.assertFalse((root / fx.QUEUE).exists())

    def test_rejects_stale_wrong_task_and_unknown_action(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = fx.workspace(tmp)
            queue = fx.queue(root)
            relative = client.relative_path(fx.QUEUE)
            baseline = {"id": "a" * 32, "task": "123", "action": "prepare", "created": int(time.time())}
            for changes in [{"task": "124"}, {"action": "shell"}, {"created": 0}, {"id": "../bad"}]:
                (queue / "request.json").write_text(json.dumps(baseline | changes))
                with self.assertRaises(ValueError):
                    client.broker_read(root, relative)
            (queue / "request.json").write_text(json.dumps(baseline))
            self.assertEqual(baseline, client.broker_read(root, relative))

    def test_broker_replays_receipt_without_reexecuting(self):
        with tempfile.TemporaryDirectory() as tmp:
            b = broker.Broker({"state_directory": tmp, "tasks": ["123"], "target": "sit"})
            request = {"id": "a" * 32, "task": "123", "action": "prepare", "created": int(time.time())}
            def remote(mode, task, data=None):
                return json.dumps(request).encode() if mode == "broker-read" else b""
            with patch.object(b, "remote", side_effect=remote), patch.object(b, "execute", return_value={"exit_code": 0}) as execute:
                b.poll()
                b.poll()
                execute.assert_called_once()

    def test_crash_does_not_repeat_uncertain_mutation(self):
        with tempfile.TemporaryDirectory() as tmp:
            b = broker.Broker({"state_directory": tmp, "tasks": ["123"], "target": "sit"})
            request = {"id": "a" * 32, "task": "123", "action": "start", "created": int(time.time())}
            (Path(tmp) / (request["id"] + ".running")).write_text("123")
            with patch.object(b, "remote", side_effect=[json.dumps(request).encode(), b""]), patch.object(b, "execute") as execute:
                b.poll()
                execute.assert_not_called()
            result = json.loads((Path(tmp) / (request["id"] + ".json")).read_text())
            self.assertEqual(75, result["exit_code"])

    def test_no_credentials_cannot_start_vm_commands(self):
        with tempfile.TemporaryDirectory() as tmp:
            b = broker.Broker({"state_directory": tmp, "password_file": str(Path(tmp) / "absent")})
            with patch("subprocess.run") as run, self.assertRaisesRegex(ValueError, "DEV_LOGIN_REQUIRED"):
                b.vbox("run", [])
            run.assert_not_called()


class TransportTests(unittest.TestCase):
    def test_worker_survives_transient_poll_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            b = broker.Broker({"state_directory": tmp})
            with patch.object(b, "poll", side_effect=[RuntimeError("offline"), None]) as poll, patch("time.sleep", side_effect=[None, KeyboardInterrupt]) as sleep:
                with self.assertRaises(KeyboardInterrupt):
                    broker.run_loop(b)
            self.assertEqual(2, poll.call_count)
            self.assertEqual([3, 3], [x.args[0] for x in sleep.call_args_list])

    def test_virtualbox_login_copy_failure_always_cleans_guest_secret(self):
        with tempfile.TemporaryDirectory() as tmp:
            b = broker.Broker({"state_directory": tmp, "guest_user": "deploy", "password_file": str(Path(tmp) / "login")})
            request = {"id": "c" * 32, "action": "prepare", "task": "123"}
            guest_dir = "/home/deploy/.ai-team-service-" + request["id"]
            def transfer(verb, args, **kwargs):
                if verb == "copyto" and args[-1] == guest_dir + "/login":
                    self.assertEqual(str((Path(tmp) / "login").resolve()), args[0])
                    raise RuntimeError("copy interrupted")
                return b""
            with patch.object(b, "run_guest", return_value=b"1000") as run, patch.object(b, "vbox", side_effect=transfer) as vbox:
                with self.assertRaisesRegex(RuntimeError, "copy interrupted"):
                    b.execute(request, "123")
            self.assertIn((("/usr/bin/mkdir", ["-m", "0700", guest_dir]), {"timeout": 30}), [(x.args, x.kwargs) for x in run.call_args_list])
            self.assertTrue(any(x.args == ("rm", ["--force", guest_dir + "/login"]) for x in vbox.call_args_list))

    def test_ssh_target_uses_pinned_host_key_and_never_calls_virtualbox(self):
        with tempfile.TemporaryDirectory() as tmp:
            b = broker.Broker({"state_directory": tmp, "transport": "ssh"})
            request = {"id": "b" * 32, "action": "prepare", "task": "123"}
            outputs = [b"/tmp/ai-team-service-AbCd1234\n", b"", b"PREPARED: ready\n", b"", b""]
            with patch.object(b, "target_ssh", side_effect=outputs) as ssh, patch.object(b, "vbox") as vbox:
                result = b.execute(request, "123")
            self.assertEqual(0, result["exit_code"])
            self.assertEqual(["sudo", "-n", "python3", "/tmp/ai-team-service-AbCd1234/guest.py", "prepare"], ssh.call_args_list[2].args[0])
            vbox.assert_not_called()

    def test_ssh_command_keeps_identity_and_host_key_checks(self):
        with tempfile.TemporaryDirectory() as tmp:
            b = broker.Broker({"state_directory": tmp, "target_ssh": "dev@target.example", "target_port": 22,
                              "target_identity_file": "key file", "target_known_hosts_file": "hosts file"})
            with patch("subprocess.run", return_value=SimpleNamespace(returncode=0,stdout=b"ok")) as run:
                self.assertEqual(b"ok", b.target_ssh(["sudo", "-n", "python3", "/tmp/job/guest.py", "health"]))
            argv = run.call_args.args[0]
            self.assertIn("StrictHostKeyChecking=yes", argv)
            self.assertIn("UserKnownHostsFile=hosts file", argv)
            self.assertIn("key file", argv)
            self.assertEqual("sudo -n python3 /tmp/job/guest.py health", argv[-1])

    def test_missing_marker_cannot_be_reported_as_success(self):
        with self.assertRaisesRegex(RuntimeError, "completion marker"):
            broker.Broker.receipt("health", b"HTTP might work")


class HealthTests(unittest.TestCase):
    def test_build_timeout_removes_only_owned_container_and_keeps_result(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(guest, "remove_owned_build") as cleanup, patch("subprocess.run", side_effect=[subprocess.TimeoutExpired("docker", 900), SimpleNamespace(returncode=1)]):
                with self.assertRaisesRegex(ValueError, "build failed"):
                    guest.run_build(["docker", "run"], Path(tmp))
            self.assertEqual(2, cleanup.call_count)
            result = json.loads((Path(tmp) / "build-result.json").read_text())
            self.assertTrue(result["timed_out"])

    def test_build_cleanup_refuses_foreign_container(self):
        found = SimpleNamespace(returncode=0, stdout=json.dumps([{"Config": {"Labels": {}}}]))
        with patch("subprocess.run", return_value=found) as run:
            with self.assertRaisesRegex(ValueError, "another owner"):
                guest.remove_owned_build()
            self.assertEqual(1, run.call_count)

    def test_build_budget_reserves_guest_memory(self):
        memory, heap = guest.build_budget("MemTotal: 3479552 kB\n")
        self.assertLessEqual(memory, 3398 - 1024)
        self.assertLessEqual(heap + 1024, memory)
        with self.assertRaisesRegex(ValueError, "guest memory"):
            guest.build_budget("MemTotal: 2097152 kB\n")

    def test_http_alone_cannot_claim_success(self):
        details = [{"State": {"Running": True, "Health": {"Status": "unhealthy"}},
                    "Config": {"Labels": {"com.docker.compose.service": "postgres"}}},
                   {"State": {"Running": True}, "Config": {"Labels": {"com.docker.compose.service": "app"}}}]
        with patch("subprocess.check_output", side_effect=["db app", json.dumps(details)]) as output, \
                self.assertRaisesRegex(ValueError, "database health"):
            guest.health(Path("/test"))
        self.assertEqual(["ps", "-q", "postgres", "app"], output.call_args_list[0].args[0][-4:])

    def test_existing_volume_without_secrets_is_not_reset(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(guest, "ROOT", Path(tmp)), patch.dict(sys.modules, {
                    "cryptography.hazmat.primitives.ciphers.aead": SimpleNamespace(AESGCM=None)}), \
                    patch("subprocess.check_output", return_value="example-dev_sit-postgres-data\n"), \
                    patch.object(guest, "run") as run, self.assertRaisesRegex(ValueError, "existing SIT volumes"):
                guest.provision_secrets(Path(tmp) / "release")
            run.assert_not_called()
            self.assertFalse((Path(tmp) / "secrets/db-password").exists())


if __name__ == "__main__":
    unittest.main()
