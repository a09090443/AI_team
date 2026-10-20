from contextlib import closing
import json
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

class SupportTests(unittest.TestCase):
    def fixture(self,home):
        home=Path(home);(home/"service-context").mkdir()
        endpoint=dict(audience="Windows host",url="http://127.0.0.1:8088/view/chat",probe_url="http://172.30.46.20/view/chat")
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
            out=support.probe(dict(audience="Windows",url="http://127.0.0.1:8088/view/chat",probe_url="http://172.30.46.20/view/chat"))
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
            self.assertEqual(('up','-d','--no-deps','--no-build','bestpartner'),tuple(run.call_args.args[0]));health.assert_called_once()
    def test_missing_active_release_is_not_redeployed(self):
        with patch.dict(sys.modules,{'fcntl':SimpleNamespace(LOCK_EX=1,LOCK_NB=2,flock=lambda *args:None)}),tempfile.TemporaryDirectory() as tmp,patch.object(guest,'ROOT',Path(tmp)),patch.object(guest,'previous_release',return_value=None),patch.object(guest,'run') as run:
            with self.assertRaises(ValueError):guest.repair()
            run.assert_not_called()

if __name__=='__main__':unittest.main()
