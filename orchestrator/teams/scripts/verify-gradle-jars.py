#!/usr/bin/env python3
"""Build configured Gradle/Maven sample roots in an isolated copy and inspect plain JARs.

Usage: python3 verify-gradle-jars.py target/qa-jars restfuls endpoints [--offline]
All paths are relative to the current Git worktree. No product paths are built in.
"""
import pathlib
import shutil
import subprocess
import sys
import tempfile
import zipfile


def relative(value):
    path = pathlib.PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts or not path.parts or "\\" in value:
        raise ValueError("Expected a safe relative path: " + value)
    return pathlib.Path(*path.parts)


def check_jar(path):
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        if any(n.startswith(("BOOT-INF/", "org/springframework/boot/loader/")) for n in names):
            raise ValueError("Expected a plain JAR, found Boot layout: " + str(path))
        classes = [n for n in names if n.endswith(".class") and n != "module-info.class"]
        if not classes:
            raise ValueError("JAR contains no application classes: " + str(path))
        bad = jar.testzip()
        if bad:
            raise ValueError("Corrupt JAR entry: " + bad)
        print("JAR_PASS", path.name, "classes=" + str(len(classes)), flush=True)


def main(args):
    offline = "--offline" in args
    args = [a for a in args if a != "--offline"]
    if len(args) < 2:
        raise ValueError("Expected output directory and at least one sample root")
    repo = pathlib.Path.cwd().resolve()
    output = repo / relative(args[0])
    roots = [relative(a) for a in args[1:]]
    if any(output == repo / r or (repo / r) in output.parents or output in (repo / r).parents for r in roots):
        raise ValueError("Output directory must not overlap sample roots")
    for path in [output, *[repo / r for r in roots]]:
        for part in [path, *path.parents]:
            if part == repo:
                break
            if part.is_symlink():
                raise ValueError("Symlink path is not allowed: " + str(part))
    tracked = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", *map(str, roots)]
    ).split(b"\0")
    files = sorted({relative(p.decode("utf-8")) for p in tracked if p})
    projects = sorted({p.parent for p in files if p.name in {"build.gradle", "build.gradle.kts", "pom.xml"}})
    if not projects:
        raise ValueError("No Gradle/Maven sample projects found in configured roots")
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="run-", dir=output) as temporary:
        work = pathlib.Path(temporary)
        for path in files:
            source = repo / path
            if source.is_symlink() or any(p.is_symlink() for p in source.parents if p != repo and repo in p.parents):
                raise ValueError("Symlink sample source is not allowed: " + str(path))
            if not source.is_file():
                raise ValueError("Missing sample source: " + str(path))
            dest = work / path
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, dest)
        for project in projects:
            maven = (work / project / "pom.xml").is_file()
            argv = (["mvn", "-B", "-f", str(work / project / "pom.xml"), "package"] if maven else
                    ["gradle", "-p", str(work / project), "--no-daemon", "jar"])
            if offline:
                argv.append("-o" if maven else "--offline")
            print("BUILD", str(project), flush=True)
            subprocess.run(argv, check=True, timeout=600)
            jars = [p for p in (work / project / ("target" if maven else "build/libs")).glob("*.jar")
                    if not p.name.endswith(("-sources.jar", "-javadoc.jar"))]
            if not jars:
                raise ValueError("Gradle produced no JAR: " + str(project))
            for jar in jars:
                check_jar(jar)
    print("SAMPLES_PASS projects=" + str(len(projects)), flush=True)


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except (ValueError, OSError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        print("SAMPLES_FAIL:", error, file=sys.stderr, flush=True)
        sys.exit(1)
