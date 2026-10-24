"""Project-neutral fixtures shared by the service-build script tests."""
from contextlib import closing
import json
from pathlib import Path
import sqlite3

import client
import guest

QUEUE = "deploy/sit/.agent-service"
SOURCE_PATHS = ["app-service", "deploy/sit", "deploy/sql"]
SERVICE = dict(root="/opt/ai-team-example-dev", source_paths=SOURCE_PATHS,
               compose_file="deploy/sit/docker-compose.yml", compose_project="example-dev",
               app_service="app", database_service="postgres", app_directory="app-service",
               health_url="http://127.0.0.1/health", env_file=".env.sit",
               db_password_file=".secrets/sit-db-password")
guest.configure(guest.service_settings({"service": SERVICE}))


def flow(target="sit", service_node=True):
    project = {"serviceTarget": {"name": target, "checks": {"start": "service-start", "health": "service-health"}},
               "operations": {"service-start": {"writes": [QUEUE]}, "service-health": {"writes": [QUEUE]}}}
    data = {"activeNode": "service", "snapshot": {"definition": {"project": project}}}
    if service_node:
        data["node"] = "service"
        data["snapshot"]["definition"]["nodes"] = {"service": {"gate": "SERVICE"}}
    return data


def workspace(tmp, state="STAGE_RUNNING", data=None):
    home = Path(tmp).resolve()
    root = home / "workspaces" / "task-123"
    root.mkdir(parents=True)
    with closing(sqlite3.connect(home / "ai-team.db")) as db:
        db.execute("create table task(id text,state text)")
        db.execute("create table flow_run(task_id text,data text)")
        db.execute("insert into task values ('123',?)", (state,))
        db.execute("insert into flow_run values ('123',?)", (json.dumps(flow() if data is None else data),))
        db.commit()
    return root


def queue(root):
    return client.directory(root, client.relative_path(QUEUE))
