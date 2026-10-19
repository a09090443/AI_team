import io
import json
import subprocess
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import ssh_client as direct
import ssh_target as target

class DirectTests(unittest.TestCase):
    def fixture(self, tmp):
        root=Path(tmp).resolve()/"task-123";root.mkdir()
        c={"transport":"direct-ssh","target":"bestpartner-dev","destination":"gary@172.30.46.2","port":22,
           "identity_file":str(Path(tmp)/"identity"),"known_hosts_file":str(Path(tmp)/"known_hosts")}
        for k in ("identity_file","known_hosts_file"): Path(c[k]).write_text("fixture")
        f=Path(tmp)/"config.json";f.write_text(json.dumps(c));return root,c,f

    def test_strict_ssh_has_no_windows_or_password_fallback(self):
        with tempfile.TemporaryDirectory() as tmp:
            _,c,f=self.fixture(tmp);cmd=direct.command(direct.config(f),"health","a"*32)
            for required in ("BatchMode=yes","PasswordAuthentication=no","StrictHostKeyChecking=yes","IdentitiesOnly=yes"):
                self.assertIn(required,cmd)
            self.assertEqual("gary@172.30.46.2",cmd[-2]);self.assertNotIn("broker",str(cmd))
            for bad in ("virtualbox","ssh"):
                c["transport"]=bad;f.write_text(json.dumps(c))
                with self.assertRaises(ValueError): direct.config(f)

    def test_reuses_pending_request_and_keeps_it_on_transport_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root,c,f=self.fixture(tmp);queue=direct.client.directory(root)
            req={"id":"a"*32,"action":"health","task":"123","created":int(time.time())-10000}
            (queue/"request.json").write_text(json.dumps(req))
            with patch.object(direct.client,"workspace",return_value=root), patch.object(direct.client,"authorized",return_value=True), patch.object(direct.subprocess,"run",return_value=SimpleNamespace(stdout=b"",returncode=255)):
                self.assertEqual(75,direct.run(f,"health"))
            self.assertEqual(req,json.loads((queue/"request.json").read_text()))

    def test_no_active_service_means_no_ssh_and_no_queue(self):
        with tempfile.TemporaryDirectory() as tmp:
            root,c,f=self.fixture(tmp)
            with patch.object(direct.client,"workspace",return_value=root),patch.object(direct.client,"authorized",return_value=False),patch.object(direct.subprocess,"run") as run:
                with self.assertRaises(ValueError): direct.run(f,"prepare")
                run.assert_not_called()
            self.assertFalse((root/direct.client.RELATIVE).exists())

    def test_success_receipt_is_bound_to_action_target_id_and_archive(self):
        with tempfile.TemporaryDirectory() as tmp:
            root,c,f=self.fixture(tmp);queue=direct.client.directory(root)
            req={"id":"a"*32,"action":"health","task":"123","created":int(time.time())}
            (queue/"request.json").write_text(json.dumps(req))
            response=dict(id=req["id"],action="health",target="other",source_sha256=None,exit_code=0,completed=1,evidence=["HEALTH_PASS: fixture"])
            with patch.object(direct.client,"workspace",return_value=root),patch.object(direct.client,"authorized",return_value=True),patch.object(direct.subprocess,"run") as run:
                run.return_value=SimpleNamespace(stdout=json.dumps(response).encode(),returncode=0)
                with self.assertRaises(ValueError): direct.run(f,"health")
                self.assertTrue((queue/"request.json").exists())
                response["target"]=c["target"];run.return_value.stdout=json.dumps(response).encode()
                self.assertEqual(0,direct.run(f,"health"))
                self.assertFalse((queue/"request.json").exists())

    def test_unreconciled_legacy_accepted_request_never_executes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root,c,f=self.fixture(tmp);queue=direct.client.directory(root)
            req={"id":"a"*32,"action":"start","task":"123","created":1}
            (queue/"request.json").write_text(json.dumps(req));(queue/(req["id"]+".accepted")).write_text("accepted")
            with patch.object(direct.client,"workspace",return_value=root),patch.object(direct.client,"authorized",return_value=True),patch.object(direct.subprocess,"run") as run:
                with self.assertRaisesRegex(ValueError,"reconcile"):direct.run(f,"start")
                run.assert_not_called()

    def test_missing_committed_deployment_paths_are_reported_before_archive(self):
        with patch.object(direct.subprocess,"check_output",side_effect=[b"", b"bestpartner-service\0docs/sql\0"]) as git:
            with self.assertRaisesRegex(direct.SourcePreflightError,"SOURCE_MISSING_PATHS:.*docs/docker/sit"):
                direct.archive_source(Path("fixture"))
            self.assertEqual(2,git.call_count)

    def test_dirty_source_is_not_reported_as_ssh_failure(self):
        with patch.object(direct.subprocess,"check_output",return_value=b" M docs/docker/sit/file") as git:
            with self.assertRaisesRegex(direct.SourcePreflightError,"SOURCE_WORKSPACE_DIRTY"):
                direct.archive_source(Path("fixture"))
            self.assertEqual(1,git.call_count)

    def test_source_preflight_failure_persists_specific_receipt_without_ssh(self):
        with tempfile.TemporaryDirectory() as tmp:
            root,c,f=self.fixture(tmp);queue=direct.client.directory(root)
            req={"id":"a"*32,"action":"start","task":"123","created":int(time.time())}
            (queue/"request.json").write_text(json.dumps(req))
            with patch.object(direct.client,"workspace",return_value=root),patch.object(direct.client,"authorized",return_value=True),patch.object(direct,"archive_source",side_effect=direct.SourcePreflightError("SOURCE_MISSING_PATHS: docs/docker/sit")),patch.object(direct.subprocess,"run") as ssh:
                self.assertEqual(1,direct.run(f,"start"));ssh.assert_not_called()
            result=json.loads((queue/(req["id"]+".json")).read_text())
            self.assertEqual(["SOURCE_MISSING_PATHS: docs/docker/sit"],result["evidence"])
            self.assertFalse((queue/"request.json").exists())

class TargetTests(unittest.TestCase):
    def test_only_fixed_commands_and_target_are_allowed(self):
        self.assertEqual(("health","a"*32,None),target.request("ai-team-service sit health "+"a"*32,"sit"))
        for text in ("bash -c id","ai-team-service sit health "+"a"*32+";reboot","ai-team-service other health "+"a"*32,"ai-team-service sit start "+"a"*32):
            with self.assertRaises(ValueError): target.request(text,"sit")

    def execute_fixture(self,tmp):
        base=Path(tmp);state=base/"state";lib=base/"lib";lib.mkdir()
        config=base/"target.json";config.write_text(json.dumps({"target":"sit","source_manifest":str(base/"manifest.json")}))
        return state,lib,config

    def test_cached_receipt_prevents_reexecution_and_rejects_id_reuse(self):
        with tempfile.TemporaryDirectory() as tmp:
            state,lib,config=self.execute_fixture(tmp);state.mkdir()
            receipt=dict(id="a"*32,target="sit",action="health",source_sha256=None,exit_code=0)
            (state/("a"*32+".json")).write_text(json.dumps(receipt))
            # fcntl is native on Linux; fixture lock on Windows only.
            try: import fcntl
            except ImportError: fcntl=SimpleNamespace(LOCK_EX=2,flock=lambda *args:None)
            with patch.dict("sys.modules",fcntl=fcntl),patch.object(target,"STATE",state),patch.object(target,"CONFIG",config),patch.object(target.os,"geteuid",return_value=0,create=True),patch.object(target.subprocess,"run") as run:
                self.assertEqual(receipt,target.execute("ai-team-service sit health "+"a"*32))
                with self.assertRaises(ValueError):target.execute("ai-team-service sit prepare "+"a"*32)
                run.assert_not_called()

    def test_interrupted_marker_never_silently_restarts(self):
        with tempfile.TemporaryDirectory() as tmp:
            state,lib,config=self.execute_fixture(tmp);state.mkdir();(state/("a"*32+".running")).write_text("start")
            try: import fcntl
            except ImportError: fcntl=SimpleNamespace(LOCK_EX=2,flock=lambda *args:None)
            with patch.dict("sys.modules",fcntl=fcntl),patch.object(target,"STATE",state),patch.object(target,"CONFIG",config),patch.object(target.os,"geteuid",return_value=0,create=True),patch.object(target.subprocess,"run") as run:
                result=target.execute("ai-team-service sit start "+"a"*32+" "+"b"*64)
                self.assertEqual(75,result["exit_code"]);run.assert_not_called()

class ExecutionTests(unittest.TestCase):
    def test_client_keeps_uncertain_receipt_request_for_explicit_retry(self):
        import client
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp).resolve()/"task-123";queue=client.directory(root)
            req={"id":"a"*32,"action":"start","task":"123","created":int(time.time())}
            (queue/"request.json").write_text(json.dumps(req))
            (queue/(req["id"]+".json")).write_text(json.dumps(dict(req,exit_code=75)))
            # Result arrives while this call is waiting; it must not discard the request.
            def arrive(_):
                (queue/(req["id"]+".json")).write_text(json.dumps(dict(req,exit_code=75)))
            with patch.object(client,"workspace",return_value=root),patch.object(client.time,"sleep",side_effect=arrive):
                self.assertEqual(75,client.submit("start",timeout=5))
            self.assertTrue((queue/"request.json").exists())

    def test_source_rejected_before_root_guest_is_executed(self):
        import hashlib,tarfile
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp);state=base/"state";manifest=base/"manifest.json";manifest.write_text(json.dumps({"bestpartner-service/test.txt":"0"*64}))
            config=base/"target.json";config.write_text(json.dumps({"target":"sit","source_manifest":str(manifest)}))
            stream=io.BytesIO()
            with tarfile.open(fileobj=stream,mode="w") as archive:
                item=tarfile.TarInfo("bestpartner-service/test.txt");item.size=4;archive.addfile(item,io.BytesIO(b"test"))
            data=stream.getvalue();digest=hashlib.sha256(data).hexdigest()
            try:import fcntl
            except ImportError:fcntl=SimpleNamespace(LOCK_EX=2,flock=lambda *args:None)
            with patch.dict("sys.modules",fcntl=fcntl),patch.object(target,"STATE",state),patch.object(target,"CONFIG",config),patch.object(target.os,"geteuid",return_value=0,create=True),patch.object(target.sys,"stdin",SimpleNamespace(buffer=io.BytesIO(data))),patch.object(target.subprocess,"run") as run:
                result=target.execute("ai-team-service sit start "+"a"*32+" "+digest)
                self.assertEqual(1,result["exit_code"]);run.assert_not_called()

if __name__=="__main__":unittest.main()
