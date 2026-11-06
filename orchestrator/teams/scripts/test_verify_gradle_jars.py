import importlib.util
import pathlib
import tempfile
import unittest
import zipfile
from unittest import mock

spec = importlib.util.spec_from_file_location("jars", pathlib.Path(__file__).with_name("verify-gradle-jars.py"))
jars = importlib.util.module_from_spec(spec)
spec.loader.exec_module(jars)


class JarVerificationTest(unittest.TestCase):
    def test_rejects_empty_boot_and_corrupt_archives(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "app.jar"
            for entry in ["README.md", "BOOT-INF/classes/App.class"]:
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr(entry, b"content")
                with self.assertRaises(ValueError):
                    jars.check_jar(path)
            path.write_bytes(b"not a zip")
            with self.assertRaises(zipfile.BadZipFile):
                jars.check_jar(path)

    def test_accepts_plain_jar(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "app-plain.jar"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("com/example/App.class", b"compiled")
            jars.check_jar(path)

    def test_missing_projects_fail_instead_of_passing(self):
        with mock.patch.object(jars.subprocess, "check_output", return_value=b""):
            with self.assertRaisesRegex(ValueError, "No Gradle"):
                jars.main(["target/qa-jars", "examples"])

    def test_paths_cannot_escape_or_overlap(self):
        for path in ["../elsewhere", "/tmp/out", "a/../../b", "a\\b"]:
            with self.assertRaises(ValueError):
                jars.relative(path)
        with self.assertRaisesRegex(ValueError, "overlap"):
            jars.main(["examples/output", "examples"])


if __name__ == "__main__":
    unittest.main()
