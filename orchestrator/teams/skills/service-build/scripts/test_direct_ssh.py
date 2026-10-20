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
            with patch.dict("sys.modules",fcntl=fcntl),patch.object(target,"STATE",state),patch.object(target,"CONFIG",config),patch.object(target.os,"geteuid",return_value=0,create=True),patch.object(target.sys,"stdin",SimpleNamespace(buffer=io.BytesIO(b""))),patch.object(target.subprocess,"run") as run:
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
                self.assertEqual(0,client.submit("start",timeout=5))
            self.assertTrue((queue/"request.json").exists())

    def target_start(self,tmp,data,digest=None,invoke=False):
        import hashlib
        base=Path(tmp);state=base/"state"
        config=base/"target.json"
        # A stale legacy manifest reference must never block a normal new version.
        config.write_text(json.dumps({"target":"sit","source_manifest":str(base/"nonexistent-old-manifest")}))
        try:import fcntl
        except ImportError:fcntl=SimpleNamespace(LOCK_EX=2,flock=lambda *args:None)
        def completed(argv,stdout,**kwargs):
            stdout.write("START_PASS: release=fixture-new-version\n")
            return SimpleNamespace(returncode=0)
        def launch(text,job):
            target.worker(text)
        with patch.dict("sys.modules",fcntl=fcntl),patch.object(target,"STATE",state),patch.object(target,"CONFIG",config),patch.object(target.os,"geteuid",return_value=0,create=True),patch.object(target.sys,"stdin",SimpleNamespace(buffer=io.BytesIO(data))),patch.object(target,"spawn_worker",side_effect=launch),patch.object(target.subprocess,"run",side_effect=completed) as run:
            result=target.execute("ai-team-service sit start "+"a"*32+" "+(digest or hashlib.sha256(data).hexdigest()))
            if result["exit_code"] == 75:
                result=target.execute("ai-team-service sit result "+"a"*32)
            self.assertEqual(int(invoke),run.call_count)
            return result

    def archive(self,path,content=b"new version"):
        import tarfile
        stream=io.BytesIO()
        with tarfile.open(fileobj=stream,mode="w") as archive:
            item=tarfile.TarInfo(path);item.size=len(content);archive.addfile(item,io.BytesIO(content))
        return stream.getvalue()

    def test_new_committed_source_needs_no_manifest_update(self):
        for path in ["bestpartner-service/new-file.txt","docs/docker/sit/docker-compose.yml"]:
            with self.subTest(path=path),tempfile.TemporaryDirectory() as tmp:
                result=self.target_start(tmp,self.archive(path),invoke=True)
                self.assertEqual(0,result["exit_code"])
                self.assertEqual(["START_PASS: release=fixture-new-version"],result["evidence"])

    def test_archive_traversal_still_rejected_before_guest(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(1,self.target_start(tmp,self.archive("../../etc/passwd"))["exit_code"])

    def test_archive_digest_mismatch_still_rejected_before_guest(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(1,self.target_start(tmp,self.archive("bestpartner-service/file"),digest="0"*64)["exit_code"])

    def test_malformed_archive_has_a_failure_receipt(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(1,self.target_start(tmp,b"not a tar archive")["exit_code"])


class ReliabilityTests(unittest.TestCase):
    def test_executor_closes_connections_on_every_poll(self):
        import ssh_executor as executor
        import sqlite3
        with tempfile.TemporaryDirectory() as tmp:
            home=Path(tmp).resolve()
            with sqlite3.connect(home/"ai-team.db") as db:
                db.execute("create table task(id text,state text)")
                db.execute("create table flow_run(task_id text,data text)")
            db.close()
            real=sqlite3.connect
            connections=[]
            class Tracked:
                def __init__(self,*a,**kw):
                    self.db=real(*a,**kw);self.closed=False;connections.append(self)
                def execute(self,*a):return self.db.execute(*a)
                def close(self):self.closed=True;self.db.close()
            with patch.object(executor.ssh_client,"config",return_value={"target":"sit"}),patch.object(executor.sqlite3,"connect",Tracked):
                for _ in range(100):executor.poll(home,None)
            self.assertTrue(all(c.closed for c in connections))
            self.assertTrue((home/"operation-executor-heartbeat").exists())

    def test_unknown_remote_result_never_launches(self):
        fixture=TargetTests()
        with tempfile.TemporaryDirectory() as tmp:
            state,lib,config=fixture.execute_fixture(tmp)
            try:import fcntl
            except ImportError:fcntl=SimpleNamespace(LOCK_EX=2,flock=lambda *a:None)
            with patch.dict("sys.modules",fcntl=fcntl),patch.object(target,"STATE",state),patch.object(target,"CONFIG",config),patch.object(target.os,"geteuid",return_value=0,create=True),patch.object(target,"spawn_worker") as launch:
                for action in ("result","status"):
                    self.assertEqual("UNKNOWN",target.execute("ai-team-service sit "+action+" "+"a"*32)["state"])
                launch.assert_not_called()

    def test_ambiguous_ssh_only_queries_same_id(self):
        job=dict(id="a"*32,action="start",source_sha256="b"*64,launched=True)
        with patch.object(direct,"remote_result",return_value=None) as query,patch.object(direct.subprocess,"run") as launch:
            for _ in range(3):self.assertIsNone(direct.execute_job({"target":"sit"},Path("fixture"),job))
            launch.assert_not_called()
            self.assertEqual(3,query.call_count)

    def test_pending_accepted_returns_zero_without_task_authorization(self):
        import client
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp).resolve()/"task-123";q=client.directory(root)
            req=dict(id="a"*32,task="123",action="health",created=1)
            (q/"request.json").write_text(json.dumps(req));(q/("a"*32+".accepted")).touch()
            with patch.object(client,"workspace",return_value=root),patch.object(client,"authorized",side_effect=AssertionError("accepted job needs no new authorization")):
                self.assertEqual(0,client.submit("health",timeout=0))
                self.assertTrue((q/"request.json").exists())

    def test_accepted_job_completes_after_task_awaits_human(self):
        import client
        import sqlite3
        import ssh_executor as executor
        with tempfile.TemporaryDirectory() as tmp:
            home=Path(tmp).resolve();root=home/"workspaces"/"task-123";root.mkdir(parents=True)
            db=sqlite3.connect(home/"ai-team.db")
            db.execute("create table task(id text,state text)")
            db.execute("create table flow_run(task_id text,data text)")
            db.execute("insert into task values ('123','AWAIT_HUMAN')")
            db.execute("insert into flow_run values ('123',?)",(json.dumps({"activeNode":"service"}),))
            db.commit();db.close()
            job=dict(version=1,id="a"*32,task="123",node="service",action="start",target="sit",
                     source_head="head",source_sha256="b"*64,state="UNCERTAIN",updated=1,
                     deadline=int(time.time())+100,result=None,error_kind=None,launched=True,verified=False)
            executor.save(home,job)
            receipt=dict(id="a"*32,action="start",target="sit",source_sha256="b"*64,
                         exit_code=0,completed=1,evidence=["START_PASS: release=fixture"])
            with patch.object(executor.ssh_client,"config",return_value={"target":"sit"}),patch.object(executor.ssh_client,"execute_job",return_value=receipt):
                executor.poll(home,None)
            saved=json.loads((home/"operation-jobs"/"123"/("a"*32+".json")).read_text())
            self.assertEqual("SUCCEEDED",saved["state"]);self.assertTrue(saved["verified"])
            self.assertEqual(receipt,saved["result"])
            self.assertEqual("service",saved["node"])

    def test_paused_task_reports_inactive_before_worker_offline(self):
        import client
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp).resolve()/"task-123";q=client.directory(root)
            (q/"request.json").write_text(json.dumps(dict(id="a"*32,task="123",action="health",created=1)))
            with patch.object(client,"workspace",return_value=root),patch.object(client,"authorized",return_value=False),patch("builtins.print") as output:
                self.assertEqual(75,client.submit("health",timeout=0))
                self.assertIn("TASK_NOT_ACTIVE",output.call_args[0][0])
                self.assertNotIn("OFFLINE",output.call_args[0][0])

    def test_active_task_without_executor_heartbeat_reports_offline(self):
        import client
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp).resolve()/"task-123";q=client.directory(root)
            (q/"request.json").write_text(json.dumps(dict(id="a"*32,task="123",action="health",created=1)))
            with patch.object(client,"workspace",return_value=root),patch.object(client,"authorized",return_value=True),patch("builtins.print") as output:
                self.assertEqual(75,client.submit("health",timeout=0))
                self.assertIn("SERVICE_EXECUTOR_OFFLINE",output.call_args[0][0])

    def test_checkpoint_reuses_verified_head_and_target_only(self):
        import client
        import sqlite3
        with tempfile.TemporaryDirectory() as tmp:
            home=Path(tmp).resolve();root=home/"workspaces"/"task-123"
            root.mkdir(parents=True)
            db=sqlite3.connect(home/"ai-team.db")
            db.execute("create table flow_run(task_id text,data text)")
            flow={"snapshot":{"definition":{"project":{"serviceTarget":{"name":"sit"}}}}}
            db.execute("insert into flow_run values (?,?)",("123",json.dumps(flow)));db.commit();db.close()
            receipt=dict(id="a"*32,action="start",target="sit",exit_code=0)
            client.atomic_json(home/"operation-jobs"/"123"/("a"*32+".json"),
                               dict(task="123",action="start",target="sit",source_head="head",
                                    state="SUCCEEDED",verified=True,result=receipt))
            with patch.object(client,"workspace",return_value=root),patch.object(client,"source_head",return_value="head"):
                self.assertEqual(0,client.submit("start",timeout=0))
                self.assertFalse((root/client.RELATIVE/"request.json").exists())

    def test_finished_remote_start_can_be_queried_without_archive(self):
        with tempfile.TemporaryDirectory() as tmp:
            state,lib,config=TargetTests().execute_fixture(tmp);state.mkdir()
            receipt=dict(id="a"*32,action="start",target="sit",source_sha256="b"*64,
                         exit_code=0,completed=1,evidence=["START_PASS: release=fixture"])
            (state/("a"*32+".json")).write_text(json.dumps(receipt))
            try:import fcntl
            except ImportError:fcntl=SimpleNamespace(LOCK_EX=2,flock=lambda *a:None)
            with patch.dict("sys.modules",fcntl=fcntl),patch.object(target,"STATE",state),patch.object(target,"CONFIG",config),patch.object(target.os,"geteuid",return_value=0,create=True),patch.object(target,"spawn_worker") as launch:
                self.assertEqual(receipt,target.execute("ai-team-service sit result "+"a"*32))
                launch.assert_not_called()

    def test_health_without_current_verified_start_is_never_success(self):
        receipt=dict(id="a"*32,target="sit",action="health",source_sha256=None,
                     exit_code=0,completed=1,evidence=["HEALTH_PASS: old service"])
        job=dict(id="a"*32,task="123",action="health",source_head="head",target="sit",source_sha256=None)
        with tempfile.TemporaryDirectory() as tmp,patch.object(direct,"remote_result",return_value=receipt):
            with self.assertRaisesRegex(ValueError,"verified current start"):
                direct.execute_job({"target":"sit"},Path(tmp)/"workspaces"/"task-123",job)


class MockProtocolTests(unittest.TestCase):
    """Cross-platform Linux-E2E mock mode: no SSH, systemd, Docker or VM access."""
    def target_context(self,tmp):
        from contextlib import ExitStack
        stack=ExitStack()
        state,lib,config=TargetTests().execute_fixture(tmp);state.mkdir()
        try:import fcntl
        except ImportError:fcntl=SimpleNamespace(LOCK_EX=2,flock=lambda *a:None)
        stack.enter_context(patch.dict("sys.modules",fcntl=fcntl))
        for name,value in (("STATE",state),("LIB",lib),("CONFIG",config)):
            stack.enter_context(patch.object(target,name,value))
        stack.enter_context(patch.object(target.os,"geteuid",return_value=0,create=True))
        return stack,state

    def test_systemd_unit_has_exact_lifecycle_and_cleanup(self):
        with patch.object(target.subprocess,"run",return_value=SimpleNamespace(returncode=0)) as run:
            target.spawn_worker("ai-team-service sit health "+"a"*32,"a"*32)
        argv=run.call_args[0][0]
        self.assertEqual("systemd-run",argv[0])
        self.assertIn("--unit=ai-team-service-job-"+"a"*32+".service",argv)
        for value in ("KillMode=control-group","TimeoutStopSec=30s","RuntimeMaxSec=7000s"):
            self.assertIn("--property="+value,argv)
        self.assertTrue(any("ExecStopPost=" in x and x.endswith("cancel-build "+"a"*32) for x in argv))
        self.assertNotIn("start_new_session",run.call_args.kwargs)

    def test_cached_start_drains_bounded_input(self):
        with tempfile.TemporaryDirectory() as tmp:
            stack,state=self.target_context(tmp)
            with stack,patch.object(target,"spawn_worker") as spawn:
                value=dict(id="a"*32,target="sit",action="start",source_sha256="b"*64,exit_code=0)
                (state/("a"*32+".json")).write_text(json.dumps(value))
                stream=io.BytesIO(b"x"*8192)
                with patch.object(target.sys,"stdin",SimpleNamespace(buffer=stream)):
                    self.assertEqual(value,target.execute("ai-team-service sit start "+"a"*32+" "+"b"*64))
                self.assertEqual(8192,stream.tell());spawn.assert_not_called()

    def test_spawn_failure_unknown_can_retry_same_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            stack,state=self.target_context(tmp)
            with stack,patch.object(target,"unit_status",return_value={"LoadState":"not-found","ActiveState":"inactive"}),patch.object(target,"spawn_worker",side_effect=[OSError("unavailable"),None]) as spawn:
                command="ai-team-service sit prepare "+"a"*32
                self.assertEqual("UNCERTAIN",target.execute(command)["state"])
                self.assertEqual("UNKNOWN",target.execute("ai-team-service sit result "+"a"*32)["state"])
                self.assertEqual("RUNNING",target.execute(command)["state"])
                self.assertEqual(2,spawn.call_count)
                self.assertEqual(spawn.call_args_list[0],spawn.call_args_list[1])

    def test_cancel_only_exact_unit_and_owned_cleanup(self):
        with tempfile.TemporaryDirectory() as tmp:
            stack,state=self.target_context(tmp)
            with stack,patch.object(target,"unit_status",return_value={"LoadState":"loaded","ActiveState":"inactive"}),patch.object(target,"cleanup_build",return_value=True) as cleanup,patch.object(target.subprocess,"run",return_value=SimpleNamespace(returncode=0)) as run:
                (state/("a"*32+".request")).write_text("ai-team-service sit start "+"a"*32+" "+"b"*64)
                other=state/("c"*32+".json");other.write_text('{"untouched":true}')
                result=target.execute("ai-team-service sit cancel "+"a"*32)
                self.assertEqual(130,result["exit_code"])
                self.assertEqual("FAILED",result["state"])
                self.assertEqual(["systemctl","stop","ai-team-service-job-"+"a"*32+".service"],run.call_args[0][0])
                cleanup.assert_called_once_with("a"*32)
                self.assertEqual('{"untouched":true}',other.read_text())

    def test_cancel_stop_timeout_persists_uncertain_receipt(self):
        with tempfile.TemporaryDirectory() as tmp:
            stack,state=self.target_context(tmp)
            with stack,patch.object(target.subprocess,"run",side_effect=subprocess.TimeoutExpired("systemctl",45)),patch.object(target,"cleanup_build",side_effect=subprocess.TimeoutExpired("cleanup",35)):
                (state/("a"*32+".request")).write_text("ai-team-service sit start "+"a"*32+" "+"b"*64)
                result=target.execute("ai-team-service sit cancel "+"a"*32)
                self.assertEqual("UNCERTAIN",result["state"])
                self.assertEqual(result,json.loads((state/("a"*32+".json")).read_text()))

    def test_worker_timeout_retains_receipt_when_cleanup_also_times_out(self):
        with tempfile.TemporaryDirectory() as tmp:
            stack,state=self.target_context(tmp)
            with stack,patch.object(target.subprocess,"run",side_effect=subprocess.TimeoutExpired("guest",6900)),patch.object(target,"cleanup_build",side_effect=subprocess.TimeoutExpired("cleanup",35)):
                value=target.worker("ai-team-service sit prepare "+"a"*32)
                self.assertEqual("REMOTE_TIMEOUT",value["error_kind"])
                self.assertEqual("UNCERTAIN",value["build_cleanup"])
                self.assertEqual(value,json.loads((state/("a"*32+".json")).read_text()))

    def test_interrupt_marker_arriving_before_launch_blocks_side_effect(self):
        import client
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp).resolve()/"workspaces"/"task-123";root.mkdir(parents=True)
            job=dict(id="a"*32,task="123",node="service",action="prepare",source_head="head")
            marker=dict(version=1,id=job["id"],task="123",node="service",requested_at=1,reason="user_interrupt")
            client.atomic_json(root.parent.parent/"operation-cancellations"/"123"/(job["id"]+".json"),marker)
            with patch.object(direct,"remote_result",return_value=dict(id=job["id"],target="sit",exit_code=75,state="UNKNOWN")),patch.object(client,"authorized",return_value=True),patch.object(direct.subprocess,"run") as send:
                self.assertIsNone(direct.execute_job({"target":"sit"},root,job))
                send.assert_not_called()

    def test_cancel_before_delayed_submission_fences_same_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            stack,state=self.target_context(tmp)
            with stack,patch.object(target,"spawn_worker") as spawn:
                self.assertEqual("UNCERTAIN",target.execute("ai-team-service sit cancel "+"a"*32)["state"])
                self.assertEqual("UNCERTAIN",target.execute("ai-team-service sit prepare "+"a"*32)["state"])
                spawn.assert_not_called()

    def test_cancel_preserves_completed_receipt(self):
        with tempfile.TemporaryDirectory() as tmp:
            stack,state=self.target_context(tmp)
            with stack,patch.object(target.subprocess,"run") as stop:
                value=dict(id="a"*32,target="sit",action="start",exit_code=0)
                record=state/("a"*32+".json");record.write_text(json.dumps(value));before=record.read_bytes()
                self.assertEqual(value,target.execute("ai-team-service sit cancel "+"a"*32))
                self.assertEqual(before,record.read_bytes());stop.assert_not_called()

    def test_cancel_without_verified_stop_is_uncertain(self):
        with tempfile.TemporaryDirectory() as tmp:
            stack,state=self.target_context(tmp)
            with stack,patch.object(target,"unit_status",return_value=None),patch.object(target,"cleanup_build",return_value=False),patch.object(target.subprocess,"run",return_value=SimpleNamespace(returncode=1)):
                (state/("a"*32+".request")).write_text("ai-team-service sit prepare "+"a"*32)
                result=target.execute("ai-team-service sit cancel "+"a"*32)
                self.assertEqual("UNCERTAIN",result["state"]);self.assertEqual(75,result["exit_code"])

    def test_confirmed_unknown_resends_same_id_after_lost_transport(self):
        import hashlib
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp).resolve()/"workspaces"/"task-123";root.mkdir(parents=True)
            job=dict(id="a"*32,task="123",action="start",target="sit",source_head="head",
                     source_sha256=hashlib.sha256(b"archive").hexdigest(),launched=True,deadline=int(time.time())+100)
            unknown=dict(id="a"*32,target="sit",exit_code=75,state="UNKNOWN")
            with patch.object(direct,"remote_result",return_value=unknown),patch.object(direct.client,"authorized",return_value=True),patch.object(direct.client,"source_head",return_value="head"),patch.object(direct,"archive_source",return_value=b"archive"),patch.object(direct,"command",return_value=["mock-ssh"]) as command,patch.object(direct.subprocess,"run",return_value=SimpleNamespace(returncode=255,stdout=b"")):
                for _ in range(2):self.assertIsNone(direct.execute_job({"target":"sit"},root,job))
                self.assertEqual(2,command.call_count)
                self.assertTrue(all(call.args[2]=="a"*32 for call in command.call_args_list))

    def test_paused_unknown_never_resubmits(self):
        unknown=dict(id="a"*32,target="sit",exit_code=75,state="UNKNOWN")
        job=dict(id="a"*32,action="start",launched=True)
        with patch.object(direct,"remote_result",return_value=unknown),patch.object(direct.subprocess,"run") as launch:
            self.assertIsNone(direct.execute_job({"target":"sit"},Path("fixture"),job,allow_launch=False))
            launch.assert_not_called()

    def test_pending_other_action_returns_existing_health(self):
        import client
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp).resolve()/"task-123";queue=client.directory(root)
            req=dict(id="a"*32,action="health",task="123",created=1)
            (queue/"request.json").write_text(json.dumps(req))
            with patch.object(client,"workspace",return_value=root),patch("builtins.print") as output:
                self.assertEqual(0,client.submit("prepare",timeout=0))
                pending=json.loads(output.call_args[0][0])
                self.assertEqual("health",pending["action"]);self.assertEqual("RUNNING",pending["execution_state"])
                self.assertEqual(req,json.loads((queue/"request.json").read_text()))

    def test_health_cache_keeps_wire_digest_none_and_links_start_separately(self):
        import client
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp).resolve()/"workspaces"/"task-123";root.mkdir(parents=True)
            start=dict(id="b"*32,task="123",node="service",action="start",target="sit",source_head="head",
                       source_sha256="c"*64,state="SUCCEEDED",verified=True,updated=1,
                       result=dict(exit_code=0,evidence=["HEALTH_PASS: legacy format","START_PASS: release=old"]))
            client.atomic_json(root.parent.parent/"operation-jobs"/"123"/("b"*32+".json"),start)
            receipt=dict(id="a"*32,action="health",target="sit",source_sha256=None,exit_code=0,
                         completed=1,evidence=["HEALTH_PASS: release=old; PostgreSQL and backend running"])
            job=dict(id="a"*32,task="123",node="service",action="health",target="sit",source_head="head",source_sha256=None)
            with patch.object(direct,"remote_result",side_effect=lambda *a:dict(receipt)):
                for _ in range(2):
                    result=direct.execute_job({"target":"sit"},root,job)
                    self.assertIsNone(result["source_sha256"])
                    self.assertEqual("c"*64,result["start_source_sha256"])
                    self.assertEqual("b"*32,result["start_id"])

    def executor_fixture(self,tmp,state="STAGE_RUNNING",launched=True):
        import sqlite3
        import ssh_executor as executor
        home=Path(tmp).resolve();root=home/"workspaces"/"task-123";root.mkdir(parents=True)
        db=sqlite3.connect(home/"ai-team.db")
        db.execute("create table task(id text,state text)");db.execute("create table flow_run(task_id text,data text)")
        db.execute("insert into task values ('123',?)",(state,))
        db.execute("insert into flow_run values ('123',?)",(json.dumps({"activeNode":"service"}),))
        db.commit();db.close()
        job=dict(version=1,id="a"*32,task="123",node="service",action="start",target="sit",
                 source_head="head",source_sha256="b"*64,state="RUNNING",updated=1,
                 deadline=int(time.time())+100,result=None,error_kind=None,launched=launched,verified=False)
        executor.save(home,job)
        return home,root,job

    def test_expired_poll_has_backoff_and_cannot_launch(self):
        import ssh_executor as executor
        with tempfile.TemporaryDirectory() as tmp:
            home,root,job=self.executor_fixture(tmp)
            job["deadline"]=1;executor.save(home,job)
            with patch.object(executor.ssh_client,"config",return_value={"target":"sit"}),patch.object(executor.client,"authorized",return_value=True),patch.object(executor.ssh_client,"execute_job",return_value=None) as execute:
                executor.poll(home,None);executor.poll(home,None)
                self.assertEqual(1,execute.call_count)
                self.assertFalse(execute.call_args.kwargs["allow_launch"])
            saved=json.loads((home/"operation-jobs"/"123"/("a"*32+".json")).read_text())
            self.assertEqual("UNCERTAIN",saved["state"]);self.assertEqual("OPERATION_DEADLINE",saved["error_kind"])

    def test_interrupt_marker_cancels_only_matching_job(self):
        import client
        import ssh_executor as executor
        with tempfile.TemporaryDirectory() as tmp:
            home,root,job=self.executor_fixture(tmp,"AWAIT_HUMAN")
            marker=dict(version=1,id=job["id"],task=job["task"],node=job["node"],requested_at=1,reason="user_interrupt")
            client.atomic_json(home/"operation-cancellations"/"123"/(job["id"]+".json"),marker)
            with patch.object(executor.ssh_client,"config",return_value={"target":"sit"}),patch.object(executor.ssh_client,"execute_job",return_value=None) as execute:
                executor.poll(home,None)
                self.assertTrue(execute.call_args.kwargs["cancel"])
                self.assertFalse(execute.call_args.kwargs["allow_launch"])
            marker["node"]="other";client.atomic_json(home/"operation-cancellations"/"123"/(job["id"]+".json"),marker)
            self.assertFalse(executor.cancellation_requested(home,job))

    def test_paused_unlaunched_does_not_launch(self):
        import ssh_executor as executor
        with tempfile.TemporaryDirectory() as tmp:
            home,root,job=self.executor_fixture(tmp,"AWAIT_HUMAN",launched=False)
            with patch.object(executor.ssh_client,"config",return_value={"target":"sit"}),patch.object(executor.ssh_client,"execute_job") as execute:
                executor.poll(home,None);execute.assert_not_called()

    def test_aborted_stops_existing_but_never_creates_new_remote(self):
        import ssh_executor as executor
        with tempfile.TemporaryDirectory() as tmp:
            home,root,job=self.executor_fixture(tmp,"ABORTED")
            with patch.object(executor.ssh_client,"config",return_value={"target":"sit"}),patch.object(executor.ssh_client,"execute_job",return_value=None) as execute:
                executor.poll(home,None)
                self.assertTrue(execute.call_args.kwargs["cancel"])
                self.assertFalse(execute.call_args.kwargs["allow_launch"])

    def test_unrelated_task_does_not_create_service_directory(self):
        import ssh_executor as executor
        import client
        with tempfile.TemporaryDirectory() as tmp:
            home,root,job=self.executor_fixture(tmp)
            (home/"operation-jobs"/"123"/(job["id"]+".json")).unlink()
            with patch.object(executor.ssh_client,"config",return_value={"target":"sit"}):
                executor.poll(home,None)
            self.assertFalse((root/client.RELATIVE).exists())

    def test_terminal_journal_and_receipt_are_untouched(self):
        import ssh_executor as executor
        with tempfile.TemporaryDirectory() as tmp:
            home,root,job=self.executor_fixture(tmp,"ABORTED")
            job.update(state="SUCCEEDED",verified=True,result=dict(exit_code=0))
            executor.save(home,job);path=home/"operation-jobs"/"123"/(job["id"]+".json");before=path.read_bytes()
            with patch.object(executor.ssh_client,"config",return_value={"target":"sit"}),patch.object(executor.ssh_client,"execute_job") as execute:
                executor.poll(home,None)
                execute.assert_not_called()
            self.assertEqual(before,path.read_bytes())

    def test_job_cleanup_refuses_another_jobs_container(self):
        import guest
        item={"Config":{"Labels":{"ai-team.service-build":"bestpartner-sit","ai-team.operation-id":"c"*32}}}
        with patch.object(guest,"OPERATION_ID","a"*32),patch.object(guest,"BUILD_NAME","ai-team-build-"+"a"*32),patch.object(guest.subprocess,"run",return_value=SimpleNamespace(returncode=0,stdout=json.dumps([item]))) as run:
            with self.assertRaisesRegex(ValueError,"different operation"):guest.remove_owned_build()
            self.assertEqual(1,run.call_count)


class RollbackTests(unittest.TestCase):
    def fixture(self,tmp):
        root=Path(tmp).resolve();previous=root/"releases"/"old";new=root/"releases"/"new"
        for release in (previous,new):
            (release/"docs/docker/sit").mkdir(parents=True);(release/"docs/docker/sit/docker-compose.yml").touch()
        (previous/"bestpartner-service/build").mkdir(parents=True)
        (previous/"bestpartner-service/build/app-runner.jar").write_bytes(b"existing-jar")
        (root/"active").write_text(str(previous))
        return root,previous,new

    def test_compose_failure_recovers_old_artifact_but_stays_failed(self):
        import guest
        with tempfile.TemporaryDirectory() as tmp:
            root,previous,new=self.fixture(tmp)
            with patch.object(guest,"ROOT",root),patch.object(guest,"run",side_effect=[ValueError("compose failed"),None]) as run,patch.object(guest,"wait_healthy") as healthy:
                with self.assertRaisesRegex(ValueError,"rollback=SUCCEEDED"):guest.activate_release(new,previous)
                command=run.call_args_list[1].args[0]
                self.assertIn(str(previous/guest.COMPOSE),command)
                self.assertEqual(["up","-d","--build","--no-deps","bestpartner"],command[-5:])
                self.assertNotIn("gradle"," ".join(command).lower())
                healthy.assert_called_once_with(previous)
            self.assertEqual(str(previous),(root/"active").read_text())
            self.assertEqual("SUCCEEDED",json.loads((new/"start-result.json").read_text())["rollback"])

    def test_health_failure_recovery_failure_remains_failed(self):
        import guest
        with tempfile.TemporaryDirectory() as tmp:
            root,previous,new=self.fixture(tmp)
            with patch.object(guest,"ROOT",root),patch.object(guest,"run"),patch.object(guest,"wait_healthy",side_effect=[ValueError("new unhealthy"),ValueError("old unhealthy")]):
                with self.assertRaisesRegex(ValueError,"rollback=FAILED"):guest.activate_release(new,previous)
            result=json.loads((new/"start-result.json").read_text())
            self.assertEqual("health",result["build_phase"]);self.assertEqual("FAILED",result["rollback"])

    def test_missing_old_artifact_never_attempts_recovery(self):
        import guest
        with tempfile.TemporaryDirectory() as tmp:
            root,previous,new=self.fixture(tmp)
            (previous/"bestpartner-service/build/app-runner.jar").unlink()
            with patch.object(guest,"ROOT",root),patch.object(guest,"run",side_effect=ValueError("failure")) as run:
                with self.assertRaisesRegex(ValueError,"NOT_AVAILABLE"):guest.activate_release(new,previous)
                self.assertEqual(1,run.call_count)

    def test_build_failure_does_not_restart_previous_application(self):
        import guest
        with tempfile.TemporaryDirectory() as tmp:
            root,previous,new=self.fixture(tmp)
            archive=root/"source.tar";archive.write_bytes(ExecutionTests().archive("bestpartner-service/build.gradle.kts"))
            with patch.object(guest,"ROOT",root),patch.object(guest,"provision_secrets"),patch.object(guest,"build_budget",return_value=(3000,1000)),patch.object(guest,"run_build",side_effect=ValueError("build failed")),patch.object(guest,"run") as run,patch.object(guest,"activate_release") as activate:
                with self.assertRaisesRegex(ValueError,"build failed"):guest.start_locked(archive)
                run.assert_not_called();activate.assert_not_called()
            self.assertEqual(str(previous),(root/"active").read_text())


class DiagnosticTests(unittest.TestCase):
    def test_build_errors_redact_saved_and_pattern_secrets(self):
        import guest
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);release=root/"releases"/"fixture";release.mkdir(parents=True)
            secrets=root/"secrets";secrets.mkdir()
            (secrets/"env.sit").write_text("CONFIG_ENCRYPTION_KEY=fixture-encryption-value\nOPENROUTER_API_KEY=fixture-api-value\n")
            (secrets/"db-password").write_text("fixture-db-value")
            (release/"operation-id").write_text("a"*32)
            (release/"build-result.json").write_text(json.dumps(dict(exit_code=1,timed_out=False,oom_killed=False)))
            (release/"build.log").write_text(
                "irrelevant raw env dump SHOULD_NOT_BE_INCLUDED\n"+
                "> Task :compileKotlin FAILED\n"+
                "FAILURE: Build failed with an exception.\n"+
                "* What went wrong:\n"+
                "Execution failed for task ':compileKotlin'.\n"+
                "e: Main.kt:12 unresolved reference fixture-encryption-value fixture-db-value\n"+
                "e: Main.kt:13 token=pattern-token-value password=pattern-password-value fixture-api-value\n")
            with patch.object(guest,"ROOT",root),patch("builtins.print") as output:
                data=guest.build_diagnosis(release);guest.emit_diagnosis(data)
                rendered=output.call_args[0][0]
                for value in ("fixture-encryption-value","fixture-db-value","fixture-api-value","pattern-token-value","pattern-password-value","SHOULD_NOT_BE_INCLUDED"):
                    self.assertNotIn(value,rendered)
                self.assertIn("[REDACTED]",rendered)
                self.assertIn("compileKotlin",rendered);self.assertIn("unresolved reference",rendered)
                self.assertEqual("BUILD_COMPILE_FAILED",data["error_kind"])
                self.assertEqual("a"*32,data["job"]);self.assertEqual("fixture",data["release"])
                json.loads(rendered[len("DIAGNOSIS: "):])

    def test_tail_and_json_size_are_bounded(self):
        import guest
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/"build.log"
            path.write_text("e: OLD_ERROR_OUTSIDE_TAIL\n"+"x"*40000+"\n"+"\n".join("e: "+("編譯錯誤"*80)+str(i) for i in range(15)))
            with patch.object(guest,"ROOT",Path(tmp)):
                errors=guest.safe_errors(path)
                self.assertLessEqual(len(errors),5);self.assertTrue(all(len(x)<=120 for x in errors))
                self.assertNotIn("OLD_ERROR_OUTSIDE_TAIL"," ".join(errors))
                with patch("builtins.print") as output:
                    guest.emit_diagnosis(dict(job="a"*32,release="fixture",build_phase="build",errors=errors))
                line=output.call_args[0][0]
                self.assertLessEqual(len(line),1361)
                json.loads(line[len("DIAGNOSIS: "):])

    def test_build_timeout_and_oom_are_explicit_resources(self):
        import guest
        with tempfile.TemporaryDirectory() as tmp:
            release=Path(tmp)
            for field,kind,resource in (("timed_out","BUILD_TIMEOUT","time"),("oom_killed","BUILD_OOM","memory")):
                (release/"build-result.json").write_text(json.dumps({field:True,"exit_code":137}))
                with patch.object(guest,"ROOT",release):data=guest.build_diagnosis(release)
                self.assertEqual(kind,data["error_kind"])
                self.assertEqual(resource,data["build_progress"]["resource"])

    def test_reconcile_trusted_prepare_or_start_uses_remote_action(self):
        import hashlib
        import ssh_executor as executor
        for action in ("prepare","start"):
            with self.subTest(action=action),tempfile.TemporaryDirectory() as tmp:
                home,root,job=MockProtocolTests().executor_fixture(tmp,"AWAIT_HUMAN")
                (home/"operation-jobs"/"123"/(job["id"]+".json")).unlink()
                digest=hashlib.sha256(b"archive").hexdigest()
                receipt=dict(id=job["id"],target="sit",action=action,source_sha256=digest if action=="start" else None,
                             exit_code=0,completed=1,evidence=["PREPARED: fixture"] if action=="prepare" else ["HEALTH_PASS: legacy","START_PASS: release=old"])
                with patch.object(executor.ssh_client,"config",return_value={"target":"sit"}),patch.object(executor.client,"source_head",return_value="head"),patch.object(executor.ssh_client,"archive_source",return_value=b"archive"),patch.object(executor.ssh_client,"remote_result",return_value=receipt):
                    executor.reconcile(home,None,"123",job["id"],"head",digest)
                record=json.loads((home/"operation-jobs"/"123"/(job["id"]+".json")).read_text())
                self.assertEqual(action,record["action"]);self.assertTrue(record["verified"])
                self.assertEqual(receipt["source_sha256"],record["source_sha256"])


class NullFlowTests(unittest.TestCase):
    def test_historical_null_flows_do_not_block_current_service(self):
        import sqlite3
        import client
        import ssh_executor as executor
        with tempfile.TemporaryDirectory() as tmp:
            home,root,job=MockProtocolTests().executor_fixture(tmp)
            valid={"node":"service","activeNode":"service","snapshot":{"definition":{
                "nodes":{"service":{"gate":"SERVICE"}},"project":{"serviceTarget":{"name":"sit"}}}}}
            bad=[None,[],"legacy",{"snapshot":None},{"snapshot":{"definition":None}},
                 {"snapshot":{"definition":{"project":None}}},
                 {"snapshot":{"definition":{"project":{"serviceTarget":None}}}},
                 {"snapshot":{"definition":{"nodes":None}}},
                 {"snapshot":{"definition":{"project":{"serviceTarget":[]}}}},
                 {"snapshot":{"definition":{"project":{"serviceTarget":"legacy"}}}},
                 {"node":"service","snapshot":{"definition":{"nodes":{"service":None}}}},
                 {"node":[],"snapshot":{"definition":{"nodes":[],"project":[]}}},
                 {"node":"plan","snapshot":{"definition":{"nodes":{"plan":{"gate":"PLAN"}},"project":{"serviceTarget":None}}}}]
            db=sqlite3.connect(home/"ai-team.db")
            for index,flow in enumerate(bad,1):
                task=str(index)
                (home/"workspaces"/("task-"+task)).mkdir()
                db.execute("insert into task values (?,?)",(task,"STAGE_RUNNING"))
                db.execute("insert into flow_run values (?,?)",(task,json.dumps(flow)))
            db.execute("update flow_run set data=? where task_id='123'",(json.dumps(valid),))
            # Missing task directory is an ordinary skipped historical row.
            db.execute("insert into task values ('999','STAGE_RUNNING')")
            db.execute("insert into flow_run values ('999','null')")
            db.commit();db.close()
            with patch.object(executor.ssh_client,"config",return_value={"target":"sit"}),patch.object(executor.ssh_client,"execute_job",return_value=None) as execute:
                executor.poll(home,None)
                execute.assert_called_once()
                self.assertTrue(execute.call_args.kwargs["allow_launch"])
            for index in range(1,len(bad)+1):
                self.assertFalse(client.authorized(home/"workspaces"/("task-"+str(index)),"sit"))
                self.assertFalse((home/"workspaces"/("task-"+str(index))/client.RELATIVE).exists())
            self.assertTrue(client.authorized(root,"sit"))

    def test_null_checkpoint_target_is_not_reused_or_crash(self):
        import sqlite3
        import client
        with tempfile.TemporaryDirectory() as tmp:
            home,root,job=MockProtocolTests().executor_fixture(tmp)
            job.update(action="prepare",state="SUCCEEDED",verified=True,result=dict(exit_code=0))
            client.atomic_json(home/"operation-jobs"/"123"/(job["id"]+".json"),job)
            db=sqlite3.connect(home/"ai-team.db")
            db.execute("update flow_run set data=?", (json.dumps({"activeNode":"service","snapshot":{"definition":{"project":{"serviceTarget":None}}}}),))
            db.commit();db.close()
            with patch.object(client,"workspace",return_value=root),patch.object(client,"source_head",return_value="head"),patch("builtins.print") as output:
                self.assertEqual(75,client.submit("prepare",timeout=0))
                self.assertIn("TASK_NOT_ACTIVE",output.call_args.args[0])

    def test_invalid_json_job_is_skipped_before_valid_job(self):
        import ssh_executor as executor
        with tempfile.TemporaryDirectory() as tmp:
            home,root,job=MockProtocolTests().executor_fixture(tmp,"AWAIT_HUMAN")
            jobs=home/"operation-jobs"/"123"
            fixtures={"000.json":"null","001.json":"{broken","002.json":"[]",
                      "003.json":"false","004.json":"{}","005.json":'{"task":"other","target":"sit"}',
                      "006.json":"unreadable"}
            for name,data in fixtures.items():(jobs/name).write_text(data)
            original_glob=Path.glob
            original_read=Path.read_text
            visited=[]
            def ordered_glob(path,pattern,*args,**kwargs):
                result=original_glob(path,pattern,*args,**kwargs)
                return iter(sorted(result,key=lambda p:p.name)) if path==jobs else result
            def read_record(path,*args,**kwargs):
                if path.parent==jobs:
                    visited.append(path.name)
                    if path.name=="006.json":raise PermissionError("fixture")
                return original_read(path,*args,**kwargs)
            with patch.object(Path,"glob",new=ordered_glob),patch.object(Path,"read_text",new=read_record),patch.object(executor.ssh_client,"config",return_value={"target":"sit"}),patch.object(executor.ssh_client,"execute_job",return_value=None) as execute:
                executor.poll(home,None)
                execute.assert_called_once()
                self.assertEqual(job["id"],execute.call_args.args[2]["id"])
            self.assertEqual(list(fixtures),visited[:len(fixtures)])
            for name,data in fixtures.items():self.assertEqual(data,(jobs/name).read_text())


if __name__=="__main__":unittest.main()
