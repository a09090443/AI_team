from contextlib import closing
import json
import os
import sys
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch,MagicMock
from types import SimpleNamespace
import urllib.error
import support
import guest
import ssh_target
import service_fixture  # configures guest with project-neutral settings

class SupportTests(unittest.TestCase):
    def setUp(self):
        support.DEADLINE=None
    def tearDown(self):
        support.DEADLINE=None
    def worker(self,home,generation="one"):
        with patch.dict(sys.modules,{'fcntl':SimpleNamespace(LOCK_EX=1,LOCK_NB=2,flock=lambda *args:None)}):
            return support.job(home,"1049","1048","sit",generation)
    def test_worker_duplicate_generation_reuses_result_without_new_probe(self):
        with tempfile.TemporaryDirectory() as tmp:
            home,e=self.fixture(tmp)
            with patch.object(support,"run",return_value=dict(healthy=True,operations=[],endpoints=[e])) as run:
                self.worker(home);self.worker(home)
            self.assertEqual(1,run.call_count)
            job=json.loads((home/"service-support/1049/job.json").read_text())
            self.assertEqual("COMPLETED",job["state"])
            self.assertTrue(json.loads((home/"service-support/1049/current.json").read_text())["healthy"])
    def test_worker_restart_preserves_deadline_and_reconciles_same_generation(self):
        with tempfile.TemporaryDirectory() as tmp:
            home,e=self.fixture(tmp)
            with patch.object(support,"run",side_effect=[dict(healthy=False,operations=[dict(exit_code=75)]),dict(healthy=True,operations=[])]):
                self.worker(home)
                old=json.loads((home/"service-support/1049/job.json").read_text())
                self.worker(home)
            new=json.loads((home/"service-support/1049/job.json").read_text())
            self.assertEqual("RUNNING",old["state"]);self.assertEqual("COMPLETED",new["state"])
            self.assertEqual(old["deadline"],new["deadline"]);self.assertEqual(old["started_at"],new["started_at"])
    def test_worker_deadline_never_starts_another_remote_action(self):
        with tempfile.TemporaryDirectory() as tmp:
            home,e=self.fixture(tmp)
            path=home/"service-support/1049/job.json"
            support.client.atomic_json(path,dict(version=1,task="1049",parent="1048",target="sit",generation="one",state="RUNNING",deadline=1))
            with patch.object(support,"run") as run:self.worker(home)
            run.assert_not_called();self.assertEqual("FAILED",json.loads(path.read_text())["state"])
    @unittest.skipUnless(os.name=="posix","Linux process lock")
    def test_real_lock_rejects_duplicate_worker(self):
        import fcntl
        with tempfile.TemporaryDirectory() as tmp:
            home,e=self.fixture(tmp);directory=home/"service-support/1049";directory.mkdir(parents=True)
            with (directory/"job.lock").open("a+") as stream:
                fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
                with patch.object(support,"run") as run:
                    self.assertEqual(75,support.job(home,"1049","1048","sit","one"))
                run.assert_not_called()
    @unittest.skipUnless(os.name=="posix","Linux worker process and flock")
    def test_real_worker_interruption_recovers_same_deadline_and_cached_result(self):
        import subprocess
        import threading
        from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
        arrived=threading.Event();release=threading.Event();requests=[]
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                requests.append(self.path);arrived.set();release.wait(10)
                try:self.send_response(200);self.end_headers()
                except OSError:pass
            def log_message(self,*args):pass
        server=ThreadingHTTPServer(("127.0.0.1",0),Handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        process=None
        try:
            with tempfile.TemporaryDirectory() as tmp:
                home,e=self.fixture(tmp);config_path=home/"service-context/sit.json"
                config=json.loads(config_path.read_text());config["access_endpoints"][0]["probe_url"]="http://127.0.0.1:%s/view/chat"%server.server_port
                config_path.write_text(json.dumps(config))
                command=[sys.executable,str(Path(support.__file__).resolve()),str(home),"1049","1048","sit","--job","one"]
                process=subprocess.Popen(command,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
                self.assertTrue(arrived.wait(10))
                path=home/"service-support/1049/job.json";before=json.loads(path.read_text())
                process.terminate();process.communicate(timeout=10);process=None;release.set()
                subprocess.run(command,check=True,capture_output=True,timeout=15)
                after=json.loads(path.read_text());self.assertEqual("COMPLETED",after["state"])
                self.assertEqual(before["deadline"],after["deadline"])
                subprocess.run(command,check=True,capture_output=True,timeout=10)
                self.assertEqual(2,len(requests)) # interrupted probe + recovered probe; cached replay adds none
                result=json.loads((home/"service-support/1049/current.json").read_text())
                self.assertTrue(result["healthy"]);self.assertFalse(result["repair_attempted"])
        finally:
            release.set()
            if process is not None:process.kill();process.communicate(timeout=10)
            server.shutdown();server.server_close();thread.join(timeout=5)
    def test_same_failed_repair_without_new_diagnostic_is_not_retried(self):
        with tempfile.TemporaryDirectory() as tmp:
            home,e=self.fixture(tmp)
            diagnosis=dict(exit_code=0,evidence=['DIAGNOSIS: unhealthy app'])
            with patch.object(support,"probe",return_value=dict(e,healthy=False)),patch.object(support.ssh_client,"config",return_value=dict(target="sit")),patch.object(support,"remote",side_effect=[dict(exit_code=1),diagnosis,dict(exit_code=1)]):
                support.run(home,"1049","1048","sit")
            support.client.atomic_json(home/"service-support/1049/repair.json",dict(result=dict(exit_code=1)))
            with patch.object(support,"probe",return_value=dict(e,healthy=False)),patch.object(support.ssh_client,"config",return_value=dict(target="sit")),patch.object(support,"remote",side_effect=[dict(exit_code=1),diagnosis]) as remote:
                result=support.run(home,"1049","1048","sit")
            self.assertEqual(2,remote.call_count);self.assertFalse(result['repair_attempted'])
            self.assertEqual("SAME_DIAGNOSTIC_AFTER_FAILED_REPAIR",result['retry_suppressed'])
    def test_pending_repair_is_reconciled_before_new_health_or_repair(self):
        with tempfile.TemporaryDirectory() as tmp:
            home,e=self.fixture(tmp)
            support.client.atomic_json(home/"service-support/1049/repair.json",dict(id='a'*32))
            with patch.object(support,"probe") as probe,patch.object(support.ssh_client,"config",return_value=dict(target="sit")),patch.object(support,"remote",return_value=dict(exit_code=75)) as remote:
                result=support.run(home,"1049","1048","sit")
            probe.assert_not_called();self.assertEqual(['repair'],[c.args[3] for c in remote.call_args_list])
            self.assertEqual("OPERATION_PENDING",result['diagnosis']['category'])
    def fixture(self,home):
        home=Path(home);(home/"service-context").mkdir()
        endpoint=dict(audience="Windows host",url="http://127.0.0.1:8088/view/chat",probe_url="http://192.0.2.20/view/chat")
        config=dict(target="sit",access_endpoints=[endpoint],config_file="private-config",auto_repair_existing_release=True)
        (home/"service-context/sit.json").write_text(json.dumps(config))
        flow=dict(dialogue=True,parentId="1048",dialogueRole="infra",snapshot=dict(definition=dict(project=dict(serviceTarget=dict(name="sit")),nodes=dict(service=dict(gate="SERVICE",role="infra")))) )
        with closing(sqlite3.connect(home/"ai-team.db")) as db:
            db.isolation_level=None
            db.execute("create table task(id text,state text)");db.execute("create table flow_run(task_id text,data text)")
            db.execute("insert into task values('1049','STAGE_RUNNING')");db.execute("insert into flow_run values('1049',?)",(json.dumps(flow),))
        return home,endpoint
    def test_healthy_wrong_old_url_uses_registered_full_user_url_no_repair(self):
        with tempfile.TemporaryDirectory() as tmp:
            home,e=self.fixture(tmp)
            with patch.object(support,"probe",return_value=dict(e,healthy=True,http_status=200)),patch.object(support,"remote") as remote:
                out=support.run(home,"1049","1048","sit")
            self.assertTrue(out["healthy"]);remote.assert_not_called();self.assertEqual("/view/chat",support.urllib.parse.urlsplit(out["endpoints"][0]["url"]).path)
    def test_probe_does_not_claim_user_network_or_follow_redirects(self):
        opener=MagicMock();response=SimpleNamespace(status=200)
        opener.open.return_value.__enter__.return_value=response
        with patch.object(support.urllib.request,"build_opener",return_value=opener):
            out=support.probe(dict(audience="Windows",url="http://127.0.0.1:8088/view/chat",probe_url="http://192.0.2.20/view/chat"))
        self.assertTrue(out["healthy"]);self.assertFalse(out["user_route_verified"]);self.assertEqual("Agent VM",out["probe_origin"])
        self.assertIsNone(support.NoRedirect().redirect_request(None,None,None,None,None,None))
    def test_http404_is_not_health_success(self):
        opener=MagicMock();opener.open.side_effect=urllib.error.HTTPError('http://target/',404,'missing',{},None)
        with patch.object(support.urllib.request,"build_opener",return_value=opener):
            out=support.probe(dict(audience="user",url="http://target/",probe_url="http://target/"))
        self.assertFalse(out["healthy"]);self.assertEqual(404,out["http_status"])
    def test_unhealthy_service_diagnoses_repairs_then_rechecks_without_build(self):
        with tempfile.TemporaryDirectory() as tmp:
            home,e=self.fixture(tmp)
            with patch.object(support,"probe",side_effect=[dict(e,healthy=False),dict(e,healthy=True)]),patch.object(support.ssh_client,"config",return_value=dict(target="sit")),patch.object(support,"remote",side_effect=[dict(exit_code=1),dict(exit_code=0),dict(exit_code=0)]) as remote:
                out=support.run(home,"1049","1048","sit")
            self.assertTrue(out["healthy"]);self.assertTrue(out["repair_attempted"])
            self.assertEqual(['health','diagnose','repair'],[c.args[3] for c in remote.call_args_list])
    def test_healthy_backend_wrong_route_does_not_restart(self):
        with tempfile.TemporaryDirectory() as tmp:
            home,e=self.fixture(tmp)
            with patch.object(support,"probe",return_value=dict(e,healthy=False)),patch.object(support.ssh_client,"config",return_value=dict(target="sit")),patch.object(support,"remote",return_value=dict(exit_code=0)) as remote:
                out=support.run(home,"1049","1048","sit")
            self.assertFalse(out['healthy']);self.assertFalse(out['repair_attempted']);self.assertEqual(1,remote.call_count)
    def test_unconfirmed_health_never_submits_new_repair(self):
        with tempfile.TemporaryDirectory() as tmp:
            home,e=self.fixture(tmp)
            with patch.object(support,"probe",return_value=dict(e,healthy=False)),patch.object(support.ssh_client,"config",return_value=dict(target="sit")),patch.object(support,"remote",return_value=dict(exit_code=75)) as remote:
                out=support.run(home,"1049","1048","sit")
            self.assertFalse(out['repair_attempted']);self.assertEqual(1,remote.call_count)
    def test_wrong_parent_or_role_never_probes(self):
        with tempfile.TemporaryDirectory() as tmp:
            home,e=self.fixture(tmp)
            with patch.object(support,"probe") as probe:
                with self.assertRaises(ValueError):support.run(home,"1049","1111","sit")
                probe.assert_not_called()
    def test_finished_dialogue_cannot_modify_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            home,e=self.fixture(tmp)
            with closing(sqlite3.connect(home/'ai-team.db')) as db:
                db.isolation_level=None;db.execute("update task set state='COMPLETED'")
            with self.assertRaises(ValueError):support.run(home,"1049","1048","sit")
    def test_credentials_query_and_non_http_urls_rejected(self):
        for u in ['file:///etc/passwd','http://user:secret@host/','http://host/?token=secret','http://host/#secret']:
            with self.assertRaises(ValueError):support.url(u)
    def test_target_protocol_repair_has_no_source_digest(self):
        self.assertEqual(('repair','a'*32,None),ssh_target.request('ai-team-service sit repair '+'a'*32,'sit'))
    def test_existing_release_repair_never_builds_or_touches_database(self):
        with patch.dict(sys.modules,{'fcntl':SimpleNamespace(LOCK_EX=1,LOCK_NB=2,flock=lambda *args:None)}),tempfile.TemporaryDirectory() as tmp,patch.object(guest,'ROOT',Path(tmp)),patch.object(guest,'previous_release',return_value=Path(tmp)/'releases/old'),patch.object(guest,'compose',side_effect=lambda release,*args:list(args)),patch.object(guest,'run') as run,patch.object(guest,'wait_healthy') as health:
            guest.repair()
            self.assertEqual(('up','-d','--no-deps','--no-build','app'),tuple(run.call_args.args[0]));health.assert_called_once()
    def test_missing_active_release_is_not_redeployed(self):
        with patch.dict(sys.modules,{'fcntl':SimpleNamespace(LOCK_EX=1,LOCK_NB=2,flock=lambda *args:None)}),tempfile.TemporaryDirectory() as tmp,patch.object(guest,'ROOT',Path(tmp)),patch.object(guest,'previous_release',return_value=None),patch.object(guest,'run') as run:
            with self.assertRaises(ValueError):guest.repair()
            run.assert_not_called()

if __name__=='__main__':unittest.main()
