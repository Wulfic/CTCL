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
    "archives_base_name": "ctcl",
    "fabric_api.26.1": "0.145.1+26.1",
    "fabric_api.26.1.1": "0.145.4+26.1.1",
    "fabric_api.26.1.2": "0.155.3+26.1.2",
    "fabric_api.26.2": "0.161.0+26.2",
    "fabric_api.26.3": "0.161.0+26.3",
}
# band/26.1 at v1.1.0: every loader, NeoForge and Forge on their stable pins only, so two jars.
PINS_26_ALL = {
    **PINS_26,
    **{f"quilt_loader.{mc}": "0.30.1" for mc in ("26.1", "26.1.1", "26.1.2", "26.2", "26.3")},
    "neoforge.26.1.2": "26.1.2.112",
    "neoforge.26.2": "26.2.0.88",
    "forge.26.1.2": "64.1.0",
    "forge.26.2": "65.1.0",
}
# band/26.1 since Stage 16: NeoForge and Forge on every pin, a beta build where there's no stable one (owner answer 27).
PINS_26_BETAS = {
    **PINS_26_ALL,
    "neoforge.26.1": "26.1.0.19-beta",
    "neoforge.26.1.1": "26.1.1.15-beta",
    "neoforge.26.3": "26.3.0.39-beta",
    "forge.26.1": "62.0.9",
    "forge.26.1.1": "63.0.2",
    "forge.26.3": "66.0.9",
    "forge_beta_versions": "26.1,26.1.1,26.3",
}
GRADLE_PROPERTIES = Path(__file__).resolve().parent.parent / "gradle.properties"
PROPS_1_21_11 = {"minecraft_version": "1.21.11", "fabric_api_version": "0.141.6+1.21.11", "mod_version": "1.0.0",
                 "archives_base_name": "ctcl"}
SHA_A = "a" * 40
SHA_B = "b" * 40

DESTRUCTIVE = (("gh", "release", "delete"), ("gh", "release", "create"), ("git", "push"))


class FakeRunner:
    """Answers read-only commands from canned output and records every call."""

    def __init__(self, tags=None, releases=(), assets=None):
        self.tags = tags or {}
        self.releases = list(releases)
        self.assets = assets or {}
        self.calls = []

    def __call__(self, args):
        args = list(args)
        self.calls.append(args)
        if args[:3] == ["git", "ls-remote", "--tags"]:
            return "".join(f"{sha}\trefs/tags/{name}\n" for name, sha in self.tags.items())
        if args[:3] == ["gh", "release", "list"]:
            return json.dumps([{"tagName": t} for t in self.releases])
        if args[:3] == ["gh", "release", "view"]:
            return json.dumps({"assets": [{"name": n} for n in self.assets.get(args[3], [])]})
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
        props = {"mod_version": "1.0.0", "archives_base_name": "ctcl", "fabric_api.26.1": "x", "fabric_api.26.1.2": "y"}
        self.assertEqual(release.band_layout("band/26.1", props).label, "26.1")

    def test_versions_sort_numerically(self):
        props = {"mod_version": "1.0.0", "archives_base_name": "ctcl", "fabric_api.26.9": "x", "fabric_api.26.10": "y"}
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


class ReleaseJarsTest(unittest.TestCase):
    """The expected jars mirror gradle/release.gradle, fabric/build.gradle, and gradle/pins.gradle."""

    def names(self, props, version="1.1.0", branch="band/26.1"):
        band = release.band_layout(branch, props)
        return [jar.name(band.archives, version) for jar in band.jars]

    def test_todays_band_ships_two_jars(self):
        self.assertEqual(self.names(PINS_26_ALL), [
            "ctcl-1.1.0+26.1-26.3-Fabric-Quilt.jar",
            "ctcl-1.1.0+26.1.2-26.2-Forge-NeoForge.jar",
        ])

    def test_loaders_on_the_same_range_share_one_jar(self):
        props = dict(PINS_26_ALL)
        for mc in ("26.1", "26.1.1", "26.3"):
            props[f"neoforge.{mc}"] = "x"
            props[f"forge.{mc}"] = "y"
        self.assertEqual(self.names(props), ["ctcl-1.1.0+26.1-26.3-Fabric-Quilt-Forge-NeoForge.jar"])

    def test_quilt_joins_only_when_pinned(self):
        props = {k: v for k, v in PINS_26_ALL.items() if not k.startswith("quilt_loader.")}
        self.assertEqual(self.names(props)[0], "ctcl-1.1.0+26.1-26.3-Fabric.jar")

    def test_loader_label_on_one_line_is_its_pins(self):
        props = {k: v for k, v in PINS_26_ALL.items() if not k.startswith(("forge.", "neoforge."))}
        self.assertEqual(self.names({**props, "neoforge.26.2": "x"})[1], "ctcl-1.1.0+26.2-NeoForge.jar")
        self.assertEqual(self.names({**props, "neoforge.26.1.1": "x", "neoforge.26.1.2": "y"})[1],
                         "ctcl-1.1.0+26.1.1-26.1.2-NeoForge.jar")

    def test_single_project_band_keeps_its_v1_name(self):
        self.assertEqual(self.names(PROPS_1_21_11, "1.0.0", "band/1.21.11"), ["ctcl-1.0.0+1.21.11.jar"])

    def test_matrix_covers_every_loader_on_every_pin_but_the_base(self):
        band = release.band_layout("band/26.1", PINS_26_ALL)
        self.assertEqual([(m["loader"], m["minecraft"]) for m in band.matrix], [
            ("fabric", "26.1.1"), ("fabric", "26.1.2"), ("fabric", "26.2"), ("fabric", "26.3"),
            ("neoforge", "26.1.2"), ("neoforge", "26.2"), ("forge", "26.1.2"), ("forge", "26.2"),
        ])
        self.assertTrue(all(m["quilt"] for m in band.matrix if m["loader"] == "fabric"))
        self.assertTrue(band.quilt)
        self.assertEqual(release.band_layout("band/1.21.11", PROPS_1_21_11).matrix, ())

    def test_plan_outputs_carry_the_jars_and_matrix(self):
        band = release.band_layout("band/26.1", PINS_26_ALL)
        plan = release.make_plan(band, ["v1.0.0+26.1-26.3"], lambda tag: [("feat: forge", "")])
        outputs = release.plan_outputs(plan)
        self.assertEqual(json.loads(outputs["jars"]), self.names(PINS_26_ALL))
        self.assertEqual(len(json.loads(outputs["matrix"])), 8)
        self.assertEqual((outputs["label"], outputs["version"], outputs["quilt"], outputs["multi"]),
                         ("26.1-26.3", "1.1.0", "true", "true"))

    def test_refuses_partial_or_stray_quilt(self):
        partial = {k: v for k, v in PINS_26_ALL.items() if k != "quilt_loader.26.3"}
        stray = {**PINS_26_ALL, "quilt_loader.26.4": "0.30.1"}
        for name, props in (("partial", partial), ("stray", stray)):
            with self.subTest(name), self.assertRaises(release.ReleaseError):
                release.band_layout("band/26.1", props)

    def test_refuses_a_gap_between_release_lines(self):
        props = {**{k: v for k, v in PINS_26_ALL.items() if k != "neoforge.26.2"}, "neoforge.26.3": "x"}
        with self.assertRaisesRegex(release.ReleaseError, "gap"):
            release.band_layout("band/26.1", props)

    def test_refuses_a_loader_outside_the_band(self):
        for key in ("forge.26.4", "neoforge.26.0"):
            with self.subTest(key), self.assertRaisesRegex(release.ReleaseError, "outside"):
                release.band_layout("band/26.1", {**PINS_26_ALL, key: "x"})


class BetaMarksTest(unittest.TestCase):
    """Owner answer 36: the notes mark each version a loader runs on a beta build, then name the builds."""

    @staticmethod
    def betas(props):
        return {r.loader: r.betas for jar in release.band_layout("band/26.1", props).jars for r in jar.loaders}

    def notes(self, props, branch="band/26.1"):
        return release.release_notes(release.band_layout(branch, props), "1.2.0", None, SHA_A)

    def test_suffixed_neoforge_and_marked_forge_pins_are_betas(self):
        self.assertEqual(self.betas(PINS_26_BETAS), {
            "Fabric": (),
            "Quilt": (),
            "NeoForge": (("26.1", "26.1.0.19-beta"), ("26.1.1", "26.1.1.15-beta"), ("26.3", "26.3.0.39-beta")),
            "Forge": (("26.1", "62.0.9"), ("26.1.1", "63.0.2"), ("26.3", "66.0.9")),
        })

    def test_a_forge_pin_outside_the_beta_list_is_stable(self):
        # Forge builds carry no suffix, so only forge_beta_versions marks one (owner answer 30).
        props = {**PINS_26_BETAS, "forge_beta_versions": "26.1"}
        self.assertEqual(self.betas(props)["Forge"], (("26.1", "62.0.9"),))
        self.assertEqual(self.betas({**PINS_26_BETAS, "forge_beta_versions": ""})["Forge"], ())

    def test_notes_mark_beta_versions_and_name_the_builds(self):
        notes = self.notes(PINS_26_BETAS)
        jar = "ctcl-1.2.0+26.1-26.3-Fabric-Quilt-Forge-NeoForge.jar"
        self.assertIn(f"| `{jar}` | Fabric | 26.1, 26.1.1, 26.1.2, 26.2, 26.3 |", notes)
        self.assertIn(f"| `{jar}` | NeoForge | 26.1 (beta), 26.1.1 (beta), 26.1.2, 26.2, 26.3 (beta) |", notes)
        self.assertIn(f"| `{jar}` | Forge | 26.1 (beta), 26.1.1 (beta), 26.1.2, 26.2, 26.3 (beta) |", notes)
        builds = next(line for line in notes.splitlines() if line.startswith("(beta)"))
        self.assertIn("NeoForge 26.1.0.19-beta on 26.1, 26.1.1.15-beta on 26.1.1, 26.3.0.39-beta on 26.3", builds)
        self.assertIn("Forge 62.0.9 on 26.1, 63.0.2 on 26.1.1, 66.0.9 on 26.3", builds)
        self.assertLess(notes.index("(beta) "), notes.index(f"Built from {SHA_A}"))

    def test_notes_have_no_beta_line_without_betas(self):
        self.assertNotIn("(beta)", self.notes(PINS_26_ALL))
        self.assertNotIn("(beta)", self.notes(PROPS_1_21_11, "band/1.21.11"))

    def test_gradle_properties_plan_one_jar_on_every_loader_and_pin(self):
        # The band as committed: one jar, and a verify job per loader and pin but Fabric's base (owner answer 34).
        props = release.read_properties(GRADLE_PROPERTIES)
        band = release.band_layout("band/26.1", props)
        fabric = release.pins_for(props, "fabric_api")
        self.assertEqual([jar.name(band.archives, "1.2.0") for jar in band.jars],
                         [f"ctcl-1.2.0+{band.label}-Fabric-Quilt-Forge-NeoForge.jar"])
        self.assertEqual({r.loader: list(r.versions) for r in band.jars[0].loaders},
                         {loader: fabric for loader in ("Fabric", "Quilt", "Forge", "NeoForge")})
        self.assertEqual(len(band.matrix), (len(fabric) - 1) + 2 * len(fabric))


class PublishGuardTest(unittest.TestCase):
    TAG = "v1.1.0+26.1-26.3"
    FABRIC = "ctcl-1.1.0+26.1-26.3-Fabric-Quilt.jar"
    FORGE = "ctcl-1.1.0+26.1.2-26.2-Forge-NeoForge.jar"

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.props = self.root / "gradle.properties"
        self.write_props(PINS_26_ALL)
        self.dir = self.root / "release-jars"
        self.dir.mkdir()

    def write_props(self, props):
        self.props.write_text("".join(f"{k}={v}\n" for k, v in props.items()), encoding="utf-8")

    def jar(self, name, fabric=None, forge=None, neoforge=None):
        with zipfile.ZipFile(self.dir / name, "w") as z:
            if fabric:
                z.writestr("fabric.mod.json", json.dumps({"id": "ctcl", "version": fabric}))
            for path, version in (("META-INF/mods.toml", forge), ("META-INF/neoforge.mods.toml", neoforge)):
                if version:
                    z.writestr(path, f'modLoader = "javafml"\n[[mods]]\nmodId = "ctcl"\nversion = "{version}"\n'
                                     '[[dependencies.ctcl]]\nmodId = "minecraft"\nversionRange = "[26.1.2,26.3)"\n')

    def good_jars(self):
        self.jar(self.FABRIC, fabric="1.1.0+26.1-26.3")
        self.jar(self.FORGE, forge="1.1.0+26.1.2-26.2", neoforge="1.1.0+26.1.2-26.2")
        self.jar("ctcl-1.1.0+26.1-26.3-Fabric-Quilt-gametest.jar", fabric="1.1.0+26.1-26.3")   # staged too; never shipped

    def publish(self, runner, version="1.1.0", sha=SHA_A, branch="band/26.1", notes_file=None):
        args = ["publish", "--branch", branch, "--properties", str(self.props), "--version", version,
                "--jars-dir", str(self.dir), "--sha", sha]
        return release.main(args + (["--notes-file", str(notes_file)] if notes_file else []), runner)

    def assert_refused(self, runner, **kwargs):
        self.assertEqual(self.publish(runner, **kwargs), 1)
        self.assertEqual(runner.destructive_calls(), [])

    def test_creates_one_release_with_every_jar(self):
        self.good_jars()
        runner = FakeRunner()
        self.assertEqual(self.publish(runner), 0)
        created = runner.destructive_calls()
        self.assertEqual(len(created), 1)
        call = created[0]
        self.assertEqual(call[:4], ["gh", "release", "create", self.TAG])
        self.assertEqual([Path(a).name for a in call if a.endswith(".jar")], [self.FABRIC, self.FORGE])
        self.assertIn(SHA_A, call)
        self.assertEqual(call[call.index("--title") + 1], "CTCL 1.1.0 for Minecraft 26.1-26.3")
        notes = call[call.index("--notes") + 1]
        self.assertIn(f"| `{self.FABRIC}` | Quilt | 26.1, 26.1.1, 26.1.2, 26.2, 26.3 |", notes)
        self.assertIn(f"| `{self.FORGE}` | NeoForge | 26.1.2, 26.2 |", notes)

    def test_notes_file_follows_the_jar_table(self):
        self.good_jars()
        notes_file = self.root / "notes.md"
        notes_file.write_text("Changes since v1.0.0+26.1-26.3:\n- feat: forge\n", encoding="utf-8")
        runner = FakeRunner()
        self.assertEqual(self.publish(runner, notes_file=notes_file), 0)
        call = runner.destructive_calls()[0]
        notes = call[call.index("--notes") + 1]
        self.assertLess(notes.index(self.FORGE), notes.index("- feat: forge"))

    def test_single_project_band_publishes_its_one_jar(self):
        self.write_props(PROPS_1_21_11)
        self.jar("ctcl-1.0.0+1.21.11.jar", fabric="1.0.0+1.21.11")
        runner = FakeRunner()
        self.assertEqual(self.publish(runner, version="1.0.0", branch="band/1.21.11"), 0)
        self.assertEqual(runner.destructive_calls()[0][:4], ["gh", "release", "create", "v1.0.0+1.21.11"])

    def test_refuses_a_missing_jar(self):
        self.jar(self.FABRIC, fabric="1.1.0+26.1-26.3")
        self.assert_refused(FakeRunner())

    def test_refuses_an_extra_jar(self):
        self.good_jars()
        self.jar("ctcl-1.0.0+26.1-26.3-Fabric-Quilt.jar", fabric="1.0.0+26.1-26.3")   # last release's, left behind
        self.assert_refused(FakeRunner())

    def test_refuses_a_jar_that_declares_another_version(self):
        self.good_jars()
        self.jar(self.FORGE, forge="1.1.0+26.1.2-26.2", neoforge="1.1.0+26.1.2")
        self.assert_refused(FakeRunner())

    def test_refuses_a_name_that_lists_a_loader_the_jar_lacks(self):
        self.good_jars()
        self.jar(self.FORGE, forge="1.1.0+26.1.2-26.2")
        self.assert_refused(FakeRunner())

    def test_refuses_metadata_the_name_does_not_list(self):
        self.good_jars()
        self.jar(self.FABRIC, fabric="1.1.0+26.1-26.3", forge="1.1.0+26.1-26.3")
        self.assert_refused(FakeRunner())

    def test_refuses_unreadable_metadata(self):
        self.good_jars()
        with zipfile.ZipFile(self.dir / self.FORGE, "w") as z:
            z.writestr("META-INF/mods.toml", "[[mods]\nmodId = ")
            z.writestr("META-INF/neoforge.mods.toml", 'modLoader = "javafml"')
        self.assert_refused(FakeRunner())
        (self.dir / self.FORGE).write_bytes(b"not a zip")
        self.assert_refused(FakeRunner())

    def test_refuses_to_move_an_existing_tag(self):
        self.good_jars()
        self.assert_refused(FakeRunner(tags={self.TAG: SHA_B}, releases=[self.TAG]))

    def test_rerun_of_a_finished_release_is_a_no_op(self):
        self.good_jars()
        runner = FakeRunner(tags={self.TAG: SHA_A}, releases=[self.TAG], assets={self.TAG: [self.FORGE, self.FABRIC]})
        self.assertEqual(self.publish(runner), 0)
        self.assertEqual(runner.destructive_calls(), [])

    def test_rerun_refuses_a_release_with_other_assets(self):
        self.good_jars()
        self.assert_refused(FakeRunner(tags={self.TAG: SHA_A}, releases=[self.TAG], assets={self.TAG: [self.FABRIC]}))

    def test_refuses_a_short_sha_or_bad_version(self):
        self.good_jars()
        for kwargs in ({"sha": "abc123"}, {"version": "1.0"}, {"version": ""}):
            with self.subTest(**kwargs):
                self.assert_refused(FakeRunner(), **kwargs)


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

    def test_a_jar_label_is_not_a_band(self):
        # 26.1.2-26.2 is the Forge-NeoForge jar's label. A tag carrying it belongs to no band-26.1 release.
        tags = {**self.TAGS, "v1.0.0+26.1.2-26.2": SHA_A}
        runner = self.runner(tags=tags, releases=self.RELEASES + ["v1.0.0+26.1.2-26.2"])
        self.assertEqual(self.cleanup(runner), 0)
        self.assertNotIn("v1.0.0+26.1.2-26.2", [arg for call in runner.destructive_calls() for arg in call])

    def test_nothing_to_delete_is_fine(self):
        runner = FakeRunner(tags={"v1.0.0+26.1-26.3": SHA_A}, releases=["v1.0.0+26.1-26.3"])
        self.assertEqual(self.cleanup(runner, keep="v1.0.0+26.1-26.3"), 0)
        self.assertEqual(runner.destructive_calls(), [])


if __name__ == "__main__":
    unittest.main()
