"""Tests for tools/release.py. Run: python -m unittest discover -s tools -p "test_*.py" -v"""

import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

import release

# The fake runner's "deleted v1.0.0..." lines read like real deletions in a CI log; keep them out of it.
_quiet_log = mock.patch.object(release, "log")


def setUpModule():
    _quiet_log.start()


def tearDownModule():
    _quiet_log.stop()

PINS_26 = {
    "minecraft_version": "26.1",
    "mod_version": "1.0.0",
    "fabric_api.26.1": "0.145.1+26.1",
    "fabric_api.26.1.1": "0.145.4+26.1.1",
    "fabric_api.26.1.2": "0.155.3+26.1.2",
    "fabric_api.26.2": "0.161.0+26.2",
    "fabric_api.26.3": "0.161.0+26.3",
}
PROPS_1_21_11 = {"minecraft_version": "1.21.11", "fabric_api_version": "0.141.6+1.21.11", "mod_version": "1.0.0"}
SHA_A = "a" * 40
SHA_B = "b" * 40

DESTRUCTIVE = (("gh", "release", "delete"), ("gh", "release", "create"), ("git", "push"))


class FakeRunner:
    """Answers read-only commands from canned output and records every call."""

    def __init__(self, tags=None, releases=()):
        self.tags = tags or {}
        self.releases = list(releases)
        self.calls = []

    def __call__(self, args):
        args = list(args)
        self.calls.append(args)
        if args[:3] == ["git", "ls-remote", "--tags"]:
            return "".join(f"{sha}\trefs/tags/{name}\n" for name, sha in self.tags.items())
        if args[:3] == ["gh", "release", "list"]:
            return json.dumps([{"tagName": t} for t in self.releases])
        return ""

    def destructive_calls(self):
        return [c for c in self.calls if any(tuple(c[: len(p)]) == p for p in DESTRUCTIVE)]


class CommitBumpTest(unittest.TestCase):
    def test_types(self):
        cases = {
            "feat: add a button": "minor",
            "feat(client): add a button": "minor",
            "fix: release the ticket": "patch",
            "fix(server): release the ticket": "patch",
            "perf: fewer ticket updates": "patch",
            "feat!: new save format": "major",
            "fix(data)!: drop old saves": "major",
            "docs: readme": None,
            "docs(memory): stage 9 plan": None,
            "build: one jar per version": None,
            "test: client gametest": None,
            "ci: workflow": None,
            "Merge branch 'main' into band/26.1": None,
            "feature: not conventional": None,
            "fix:missing space": None,
        }
        for subject, expected in cases.items():
            with self.subTest(subject=subject):
                self.assertEqual(release.commit_bump(subject, ""), expected)

    def test_breaking_change_footer_is_major(self):
        self.assertEqual(release.commit_bump("fix: x", "details\n\nBREAKING CHANGE: saves move"), "major")
        self.assertEqual(release.commit_bump("feat: x", "BREAKING-CHANGE: y"), "major")
        # A footer on a non-conventional commit doesn't release.
        self.assertIsNone(release.commit_bump("Merge branch 'main'", "BREAKING CHANGE: y"))

    def test_highest_wins(self):
        self.assertEqual(release.highest_bump([("docs: a", ""), ("fix: b", ""), ("feat: c", "")]), "minor")
        self.assertEqual(release.highest_bump([("fix: b", ""), ("feat!: c", "")]), "major")
        self.assertIsNone(release.highest_bump([("docs: a", ""), ("chore: b", "")]))
        self.assertIsNone(release.highest_bump([]))

    def test_bump_version(self):
        self.assertEqual(release.bump_version("1.2.3", "patch"), "1.2.4")
        self.assertEqual(release.bump_version("1.2.3", "minor"), "1.3.0")
        self.assertEqual(release.bump_version("1.2.3", "major"), "2.0.0")
        with self.assertRaises(release.ReleaseError):
            release.bump_version("1.2", "patch")


class BandLayoutTest(unittest.TestCase):
    def test_multi_version_band_matches_build_gradle(self):
        band = release.band_layout("band/26.1", PINS_26)
        self.assertEqual(band.base, "26.1")
        self.assertEqual(band.others, ("26.1.1", "26.1.2", "26.2", "26.3"))
        self.assertEqual(band.label, "26.1-26.3")
        self.assertTrue(band.multi)

    def test_single_version_band(self):
        band = release.band_layout("band/1.21.11", PROPS_1_21_11)
        self.assertEqual((band.base, band.others, band.label, band.multi), ("1.21.11", (), "1.21.11", False))

    def test_one_release_line_has_no_range(self):
        props = {"mod_version": "1.0.0", "fabric_api.26.1": "x", "fabric_api.26.1.2": "y"}
        self.assertEqual(release.band_layout("band/26.1", props).label, "26.1")

    def test_versions_sort_numerically(self):
        props = {"mod_version": "1.0.0", "fabric_api.26.9": "x", "fabric_api.26.10": "y"}
        self.assertEqual(release.band_layout("band/26.9", props).label, "26.9-26.10")

    def test_refuses_a_branch_that_is_not_the_bands_base(self):
        for branch in ("band/26.2", "main", "band/", "feature/x"):
            with self.subTest(branch=branch), self.assertRaises(release.ReleaseError):
                release.band_layout(branch, PINS_26)

    def test_refuses_a_bad_mod_version(self):
        with self.assertRaises(release.ReleaseError):
            release.band_layout("band/26.1", {**PINS_26, "mod_version": "1.0.0-SNAPSHOT"})


class BandTagsTest(unittest.TestCase):
    def test_ownership_is_exact(self):
        tags = [
            "v1.0.0+26.1-26.3", "v1.1.0+26.1", "v1.2.0+26.1-26.4",   # band 26.1
            "v1.0.0+26.1.2", "v1.0.0+1.21.11", "v1.0.0+26.2-26.3",    # other bands
            "v1.0+26.1", "v1.0.0+26.1-26.3x", "1.0.0+26.1", "v1.0.0+26.1-",  # malformed
        ]
        self.assertEqual(release.band_tags("26.1", tags), ["v1.0.0+26.1-26.3", "v1.1.0+26.1", "v1.2.0+26.1-26.4"])
        self.assertEqual(release.band_tags("1.21.11", tags), ["v1.0.0+1.21.11"])

    def test_sorted_by_semver_not_text(self):
        self.assertEqual(release.band_tags("26.1", ["v1.10.0+26.1", "v1.9.0+26.1"]), ["v1.9.0+26.1", "v1.10.0+26.1"])

    def test_refuses_empty_band(self):
        with self.assertRaises(release.ReleaseError):
            release.band_tags("", ["v1.0.0+26.1"])


class PlanTest(unittest.TestCase):
    band = release.band_layout("band/26.1", PINS_26)

    def plan(self, tags, commits):
        return release.make_plan(self.band, tags, lambda tag: commits)

    def test_first_release_uses_mod_version(self):
        plan = self.plan(["v3.0.0+1.21.11"], [])
        self.assertEqual((plan.version, plan.release, plan.previous_tag), ("1.0.0", True, ""))

    def test_feat_since_last_tag_is_minor(self):
        plan = self.plan(["v1.0.0+26.1-26.3", "v1.0.1+26.1-26.3"], [("docs: a", ""), ("feat: b", "")])
        self.assertEqual((plan.version, plan.release, plan.previous_tag), ("1.1.0", True, "v1.0.1+26.1-26.3"))

    def test_docs_only_does_not_release(self):
        plan = self.plan(["v1.0.0+26.1-26.3"], [("docs(memory): a", ""), ("ci: b", "")])
        self.assertFalse(plan.release)
        self.assertEqual(plan.version, "1.0.1-dev")

    def test_range_change_forces_a_patch(self):
        plan = self.plan(["v1.0.0+26.1-26.2"], [("build: pin 26.3", "")])
        self.assertEqual((plan.version, plan.release), ("1.0.1", True))

    def test_other_bands_tags_are_ignored(self):
        plan = self.plan(["v1.0.0+26.1-26.3", "v9.0.0+1.21.11", "v5.0.0+26.1.2"], [("fix: a", "")])
        self.assertEqual(plan.version, "1.0.1")


class PublishGuardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def jar(self, version):
        path = Path(self.tmp.name) / "ctcl.jar"
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("fabric.mod.json", json.dumps({"id": "ctcl", "version": version}))
        return str(path)

    def publish(self, runner, version="1.0.1", label="26.1-26.3", jar_version="1.0.1+26.1-26.3", sha=SHA_A):
        return release.main(["publish", "--version", version, "--label", label, "--jar", self.jar(jar_version), "--sha", sha], runner)

    def test_creates_release_when_everything_matches(self):
        runner = FakeRunner()
        self.assertEqual(self.publish(runner), 0)
        created = runner.destructive_calls()
        self.assertEqual(len(created), 1)
        self.assertEqual(created[0][:4], ["gh", "release", "create", "v1.0.1+26.1-26.3"])
        self.assertIn(SHA_A, created[0])

    def test_refuses_a_jar_that_declares_another_version(self):
        runner = FakeRunner()
        self.assertEqual(self.publish(runner, jar_version="1.0.1+26.1"), 1)
        self.assertEqual(runner.destructive_calls(), [])

    def test_refuses_to_move_an_existing_tag(self):
        runner = FakeRunner(tags={"v1.0.1+26.1-26.3": SHA_B}, releases=["v1.0.1+26.1-26.3"])
        self.assertEqual(self.publish(runner), 1)
        self.assertEqual(runner.destructive_calls(), [])

    def test_rerun_of_a_finished_release_is_a_no_op(self):
        runner = FakeRunner(tags={"v1.0.1+26.1-26.3": SHA_A}, releases=["v1.0.1+26.1-26.3"])
        self.assertEqual(self.publish(runner), 0)
        self.assertEqual(runner.destructive_calls(), [])

    def test_refuses_a_short_sha_or_bad_version(self):
        for kwargs in ({"sha": "abc123"}, {"version": "1.0"}, {"version": ""}):
            runner = FakeRunner()
            with self.subTest(**kwargs):
                self.assertEqual(self.publish(runner, **kwargs), 1)
                self.assertEqual(runner.destructive_calls(), [])


class CleanupGuardTest(unittest.TestCase):
    TAGS = {
        "v1.0.0+26.1-26.3": SHA_A,
        "v1.0.1+26.1-26.3": SHA_A,
        "v1.1.0+26.1-26.3": SHA_B,
        "v1.0.0+1.21.11": SHA_A,     # another band: never touched
        "v0.9.0+26.1": SHA_A,        # this band, tag without a release
        "not-a-release": SHA_A,
    }
    RELEASES = ["v1.0.0+26.1-26.3", "v1.0.1+26.1-26.3", "v1.1.0+26.1-26.3", "v1.0.0+1.21.11"]

    def cleanup(self, runner, band="26.1", keep="v1.1.0+26.1-26.3", force=True):
        return release.main(["cleanup", "--band", band, "--keep", keep] + (["--force"] if force else []), runner)

    def runner(self, **overrides):
        return FakeRunner(tags=overrides.get("tags", dict(self.TAGS)), releases=overrides.get("releases", self.RELEASES))

    def test_deletes_only_this_bands_older_tags(self):
        runner = self.runner()
        self.assertEqual(self.cleanup(runner), 0)
        self.assertEqual(runner.destructive_calls(), [
            ["git", "push", "origin", "--delete", "refs/tags/v0.9.0+26.1"],
            ["gh", "release", "delete", "v1.0.0+26.1-26.3", "--cleanup-tag", "--yes"],
            ["gh", "release", "delete", "v1.0.1+26.1-26.3", "--cleanup-tag", "--yes"],
        ])

    def test_dry_run_is_the_default(self):
        runner = self.runner()
        self.assertEqual(self.cleanup(runner, force=False), 0)
        self.assertEqual(runner.destructive_calls(), [])

    def test_refuses_a_keep_tag_from_another_band(self):
        # v9 outranks every 26.1 tag, so no other guard would stop this deleting the whole band.
        runner = self.runner(tags={**self.TAGS, "v9.0.0+1.21.11": SHA_B}, releases=self.RELEASES + ["v9.0.0+1.21.11"])
        self.assertEqual(self.cleanup(runner, keep="v9.0.0+1.21.11"), 1)
        self.assertEqual(runner.destructive_calls(), [])

    def test_refuses_an_empty_band(self):
        runner = self.runner()
        self.assertEqual(self.cleanup(runner, band=""), 1)
        self.assertEqual(runner.destructive_calls(), [])

    def test_refuses_a_keep_tag_missing_from_origin(self):
        tags = dict(self.TAGS)
        del tags["v1.1.0+26.1-26.3"]
        runner = self.runner(tags=tags)
        self.assertEqual(self.cleanup(runner), 1)
        self.assertEqual(runner.destructive_calls(), [])

    def test_refuses_a_keep_tag_without_a_release(self):
        runner = self.runner(releases=[r for r in self.RELEASES if r != "v1.1.0+26.1-26.3"])
        self.assertEqual(self.cleanup(runner), 1)
        self.assertEqual(runner.destructive_calls(), [])

    def test_refuses_when_a_newer_tag_exists(self):
        runner = self.runner()
        self.assertEqual(self.cleanup(runner, keep="v1.0.1+26.1-26.3"), 1)
        self.assertEqual(runner.destructive_calls(), [])

    def test_nothing_to_delete_is_fine(self):
        runner = FakeRunner(tags={"v1.0.0+26.1-26.3": SHA_A}, releases=["v1.0.0+26.1-26.3"])
        self.assertEqual(self.cleanup(runner, keep="v1.0.0+26.1-26.3"), 0)
        self.assertEqual(runner.destructive_calls(), [])


if __name__ == "__main__":
    unittest.main()
