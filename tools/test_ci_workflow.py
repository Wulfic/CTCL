"""Static checks of .github/workflows/ci.yml against tools/release.py (TODO 15.1).

CI runs only on a band push, so a workflow that calls release.py with stale flags, reads a plan output that
doesn't exist, or runs a bare Gradle task in the multi-loader build fails only after the push. These catch that first.
Run: python -m unittest discover -s tools -p "test_*.py" -v
"""

import contextlib
import io
import re
import shlex
import unittest
from pathlib import Path
from unittest import mock

import release
from test_release import PINS_26_ALL

WORKFLOW = Path(__file__).resolve().parent.parent / ".github" / "workflows" / "ci.yml"
# Tasks only the root project has. Every other task must name its project, or a bare name also runs in :neoforge and
# :forge (gotchas.md "Multi-loader harness traps").
ROOT_TASKS = {"releaseJars"}
# A project prefix: literal (:fabric:), or the workflow's layout variables (empty on a single-project band).
QUALIFIED = re.compile(r"^(:[A-Za-z]|\$\{?(FABRIC|COMMON)\}?[A-Za-z]|:\$\{?LOADER\}?:[A-Za-z])")
FLAGS_VARIABLE = re.compile(r"^\$\{?[A-Z_]+\}?$")


def workflow_lines() -> list[str]:
    """ci.yml with shell line continuations joined."""
    return re.sub(r"\\\n\s*", " ", WORKFLOW.read_text(encoding="utf-8")).splitlines()


def gradle_tasks(line: str) -> list[str]:
    """The task names on a `./gradlew ...` line, skipping flags, flag variables, and anything after a pipe."""
    command = line.split("./gradlew", 1)[1].split("|", 1)[0]
    return [token for token in shlex.split(command)
            if not token.startswith("-") and not FLAGS_VARIABLE.match(token)]


class WorkflowTest(unittest.TestCase):
    def test_every_release_call_parses(self):
        calls = [line.split("python3 tools/release.py", 1)[1] for line in workflow_lines() if "python3 tools/release.py" in line]
        self.assertGreaterEqual(len(calls), 3, "expected plan, publish, and cleanup calls")
        for call in calls:
            with self.subTest(call=call.strip()), contextlib.redirect_stderr(io.StringIO()) as err:
                try:
                    release.parser().parse_args(shlex.split(call))
                except SystemExit:
                    self.fail(f"release.py rejects `{call.strip()}`: {err.getvalue().strip()}")

    def test_publish_ships_the_staged_jars_dir(self):
        publish = [line for line in workflow_lines() if "tools/release.py publish" in line]
        self.assertEqual(len(publish), 1)
        self.assertIn("--jars-dir release-jars", publish[0])

    def test_plan_outputs_the_workflow_reads_exist(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        declared = dict(re.findall(r"^ {6}(\w+): \$\{\{ steps\.plan\.outputs\.(\w+) \}\}$", text, re.MULTILINE))
        for key, value in declared.items():
            self.assertEqual(key, value, f"plan job output {key} maps to steps.plan.outputs.{value}")
        read = set(re.findall(r"needs\.plan\.outputs\.(\w+)", text))
        self.assertTrue(read)
        self.assertEqual(read - set(declared), set(), "outputs the workflow reads but the plan job doesn't export")
        band = release.band_layout("band/26.1", PINS_26_ALL)
        with mock.patch.object(release, "log"):
            emitted = release.plan_outputs(release.make_plan(band, [], lambda tag: []))
        self.assertEqual(set(declared) - set(emitted), set(), "plan job outputs release.py never prints")

    def test_gradle_tasks_name_their_project(self):
        lines = [line for line in workflow_lines() if "./gradlew" in line]
        self.assertTrue(lines)
        for line in lines:
            with self.subTest(line=line.strip()):
                # A command in a variable ($quilt tasks...) hides its tasks from this check.
                self.assertNotRegex(line, r"=[\"']?\./gradlew", "spell out ./gradlew; don't store it in a variable")
                tasks = gradle_tasks(line)
                self.assertTrue(tasks, "a ./gradlew call with no task")
                bare = [t for t in tasks if t not in ROOT_TASKS and not QUALIFIED.match(t)]
                self.assertEqual(bare, [], "bare task names run in every project that has them")

    def test_prod_mods_dir_is_absolute(self):
        # Each project resolves -Pctcl_prod_mods with its own fileTree(), so a relative dir would be fabric/release-jars in
        # :fabric and find no jars.
        text = WORKFLOW.read_text(encoding="utf-8")
        values = re.findall(r"-Pctcl_prod_mods=(\S+)", text)
        self.assertTrue(values)
        for value in values:
            self.assertIn(value.strip('"'), ("$PROD_MODS", "${PROD_MODS}"))
        self.assertRegex(text, r"\n\s+PROD_MODS: \$\{\{ github\.workspace \}\}/release-jars\n")

    def test_plan_job_checks_the_live_pins_first(self):
        # Every later job needs plan, so a pin check here gates the build and the release (owner answer 27).
        lines = workflow_lines()
        start = lines.index("  plan:")
        end = next(i for i in range(start + 1, len(lines)) if re.match(r"^  \S", lines[i]))
        job = lines[start:end]
        checks = [i for i, line in enumerate(job) if "tools/check_pins.py" in line]
        self.assertEqual(len(checks), 1, "the plan job must run tools/check_pins.py once")
        self.assertRegex(job[checks[0]], r"run: python3 tools/check_pins\.py$", "CI checks the live APIs, not recordings")
        self.assertLess(checks[0], job.index("      - id: plan"))

    def test_setup_gradle_writes_the_cache(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        uses = re.findall(r"uses: gradle/actions/setup-gradle@", text)
        writing = re.findall(r"uses: gradle/actions/setup-gradle@\S+\n\s+with:\n\s+cache-read-only: false\n", text)
        self.assertTrue(uses)
        self.assertEqual(len(writing), len(uses), "every setup-gradle must set cache-read-only: false (owner answer 18)")

    def test_failure_logs_carry_the_thread_dumps(self):
        # The harness writes <console>-threads.txt before it kills a hung or dead process. No log glob matches that name, so
        # without its own path the dump never leaves the runner (gotchas.md 2026-10-01).
        text = WORKFLOW.read_text(encoding="utf-8")
        uploads = re.findall(r"uses: actions/upload-artifact@\S+\n(?:.*\n)*?\s+path: \|\n((?:\s{12}\S.*\n)+)", text)
        logs = [paths for paths in uploads if "hs_err_pid" in paths]
        self.assertGreaterEqual(len(logs), 2, "expected the build and verify failure-log uploads")
        for paths in logs:
            self.assertIn("*/build/run/**/*-threads.txt", paths.split())


class GradleTasksTest(unittest.TestCase):
    """The parser the checks above rely on."""

    def test_flags_and_pipes_are_not_tasks(self):
        line = './gradlew $GRADLE_FLAGS -Pmod_version="$VERSION" "-Pctcl_world=$W" ${FABRIC}build :forge:x | tee a.log'
        self.assertEqual(gradle_tasks(line), ["${FABRIC}build", ":forge:x"])

    def test_bare_and_qualified(self):
        self.assertIsNone(QUALIFIED.match("prodServerGameTest"))
        for task in (":fabric:build", "${FABRIC}build", "$COMMON" + "build", ":$LOADER:prodClientSmoke", ":${LOADER}:build"):
            self.assertIsNotNone(QUALIFIED.match(task), task)


if __name__ == "__main__":
    unittest.main()
