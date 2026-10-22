"""Tests for tools/uninstall_check.py. Run: python -m unittest discover -s tools -p "test_*.py" -v"""

import contextlib
import io
import tempfile
import unittest
from pathlib import Path

import uninstall_check


class FreshWorkDirTest(unittest.TestCase):
    def test_creates_a_missing_build_dir(self):
        # A fresh multi-project checkout has no root build/ when CI runs the check (run 36793729578).
        with tempfile.TemporaryDirectory() as tmp:
            build = Path(tmp) / "build"
            work = uninstall_check.fresh_work_dir(build)
            self.assertEqual(work, (build / uninstall_check.WORK_NAME).resolve())
            self.assertTrue(work.is_dir())
            self.assertEqual(list(work.iterdir()), [])

    def test_clears_only_its_previous_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            build = Path(tmp) / "build"
            old = build / uninstall_check.WORK_NAME / "world" / "level.dat"
            old.parent.mkdir(parents=True)
            old.write_bytes(b"previous run")
            sibling = build / "release" / "ctcl.jar"
            sibling.parent.mkdir()
            sibling.write_bytes(b"release jar")
            with contextlib.redirect_stdout(io.StringIO()):
                work = uninstall_check.fresh_work_dir(build)
            self.assertEqual(list(work.iterdir()), [])
            self.assertEqual(sibling.read_bytes(), b"release jar")

    def test_guard_still_refuses_another_dir(self):
        with tempfile.TemporaryDirectory() as tmp:
            build = Path(tmp) / "build"
            with self.assertRaises(ValueError):
                uninstall_check.safe_work_dir(build, build / "release")


if __name__ == "__main__":
    unittest.main()
