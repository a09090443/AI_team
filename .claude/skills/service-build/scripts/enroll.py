#!/usr/bin/env python3
"""Operator-only enrollment; never reads or prints a login password."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import tarfile

from broker import Broker, checked_config
from guest import validate_archive


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("config", help="private copy of host.example.json")
    parser.add_argument("source_task", help="task whose committed deployment sources were reviewed")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9]+", args.source_task):
        raise ValueError("invalid source task")
    config = checked_config(args.config)
    destination = Path(config["source_manifest"])
    if destination.exists():
        raise ValueError("source approval exists; review differences before replacing it")
    b = Broker(config)
    root = config["agent_home"] + "/workspaces/task-" + args.source_task
    data = b.ssh(["git", "-C", root, "archive", "HEAD", "bestpartner-service", "docs/docker/sit", "docs/sql"])
    archive = validate_archive(data)
    manifest = {member.name: hashlib.sha256(archive.extractfile(member).read()).hexdigest()
                for member in archive.getmembers() if member.isfile()}
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("x", encoding="utf-8") as out:
        json.dump(manifest, out, indent=2)
    print("Enrolled source manifest: " + str(len(manifest)) + " files; SHA256=" + hashlib.sha256(data).hexdigest())


if __name__ == "__main__":
    main()
