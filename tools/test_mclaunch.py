"""Tests for tools/mclaunch.py. Run: python -m unittest discover -s tools -p "test_*.py" -v"""

import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import mclaunch

_quiet_log = mock.patch.object(mclaunch, "log")


def setUpModule():
    _quiet_log.start()


def tearDownModule():
    _quiet_log.stop()


def write_version(root: Path, data: dict) -> None:
    path = root / "versions" / data["id"] / f"{data['id']}.json"
    path.parent.mkdir(parents=True)
    path.write_text(json.dumps(data), encoding="utf-8")


class RulesTest(unittest.TestCase):
    def test_no_rules_matching_means_disallowed(self):
        self.assertFalse(mclaunch.rule_allows([{"action": "allow", "os": {"name": "osx"}}], {}, "windows", False))

    def test_the_last_matching_rule_decides(self):
        rules = [{"action": "allow"}, {"action": "disallow", "os": {"name": "windows"}}]
        self.assertFalse(mclaunch.rule_allows(rules, {}, "windows", False))
        self.assertTrue(mclaunch.rule_allows(rules, {}, "linux", False))

    def test_x86_means_a_32_bit_jvm(self):
        rule = [{"action": "allow", "os": {"arch": "x86"}}]
        self.assertTrue(mclaunch.rule_allows(rule, {}, "windows", True))
        self.assertFalse(mclaunch.rule_allows(rule, {}, "windows", False))

    def test_features_must_all_match(self):
        rule = [{"action": "allow", "features": {"is_quick_play_multiplayer": True}}]
        self.assertTrue(mclaunch.rule_allows(rule, {"is_quick_play_multiplayer": True}, "linux", False))
        self.assertFalse(mclaunch.rule_allows(rule, {}, "linux", False))

    def test_expand_keeps_plain_args_and_only_allowed_conditional_ones(self):
        arguments = ["--a", {"rules": [{"action": "allow", "features": {"is_demo_user": True}}], "value": "--demo"},
                     {"rules": [{"action": "allow", "os": {"name": "linux"}}], "value": ["--x", "1"]}]
        self.assertEqual(mclaunch.expand(arguments, {}, "linux", False), ["--a", "--x", "1"])


class InheritanceTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        write_version(self.root, {
            "id": "26.2", "mainClass": "net.minecraft.client.main.Main", "type": "release",
            "assetIndex": {"id": "32"},
            "arguments": {"jvm": ["-cp", "${classpath}"], "game": ["--username", "${auth_player_name}"]},
            "libraries": [{"name": "org.ow2.asm:asm:9.0"}, {"name": "com.mojang:jtracy:1.0:natives-linux"}],
        })
        write_version(self.root, {
            "id": "neoforge-26.2.0.88", "inheritsFrom": "26.2", "mainClass": "net.neoforged.fml.startup.Client",
            "arguments": {"jvm": ["-DlibraryDirectory=${library_directory}"], "game": ["--fml.mcVersion", "26.2"]},
            "libraries": [{"name": "org.ow2.asm:asm:9.10.1"}, {"name": "net.neoforged:bus:8.0.5"}],
        })

    def test_the_child_wins_its_parent_fills_the_rest(self):
        merged = mclaunch.merge(mclaunch.load_version(self.root, "neoforge-26.2.0.88"))
        self.assertEqual(merged["id"], "neoforge-26.2.0.88")
        self.assertEqual(merged["mainClass"], "net.neoforged.fml.startup.Client")
        # Only the parent sets these; the child must not hide them.
        self.assertEqual(merged["assetIndex"], {"id": "32"})
        self.assertEqual(merged["type"], "release")

    def test_a_childs_library_replaces_the_parents_and_classifiers_stay_distinct(self):
        merged = mclaunch.merge(mclaunch.load_version(self.root, "neoforge-26.2.0.88"))
        names = [library["name"] for library in merged["libraries"]]
        self.assertEqual(names, ["org.ow2.asm:asm:9.10.1", "net.neoforged:bus:8.0.5", "com.mojang:jtracy:1.0:natives-linux"])

    def test_the_parents_arguments_come_first(self):
        merged = mclaunch.merge(mclaunch.load_version(self.root, "neoforge-26.2.0.88"))
        self.assertEqual(merged["arguments"]["jvm"], ["-cp", "${classpath}", "-DlibraryDirectory=${library_directory}"])
        self.assertEqual(merged["arguments"]["game"], ["--username", "${auth_player_name}", "--fml.mcVersion", "26.2"])

    def test_an_installer_version_runs_on_its_parents_jar(self):
        jar = self.root / "versions" / "26.2" / "26.2.jar"
        jar.write_bytes(b"jar")
        self.assertEqual(mclaunch.version_jar(self.root, mclaunch.load_version(self.root, "neoforge-26.2.0.88")), jar)

    def test_a_missing_version_the_manifest_lacks_fails(self):
        manifest = write_manifest(self.root / "remote", {})
        with self.assertRaises(SystemExit):
            mclaunch.load_version(self.root, "neoforge-26.3.0.1", manifest)
        self.assertFalse((self.root / "versions" / "neoforge-26.3.0.1").exists())


def write_manifest(remote: Path, versions: dict) -> str:
    """A local stand-in for Mojang's version manifest: each version JSON as a file, listed by url and sha1."""
    remote.mkdir(parents=True, exist_ok=True)
    entries = []
    for version_id, (data, sha1) in versions.items():
        path = remote / f"{version_id}.json"
        path.write_text(json.dumps(data), encoding="utf-8")
        entries.append({"id": version_id, "url": path.as_uri(), "sha1": sha1 or hashlib.sha1(path.read_bytes()).hexdigest()})
    manifest = remote / "version_manifest_v2.json"
    manifest.write_text(json.dumps({"versions": entries}), encoding="utf-8")
    return manifest.as_uri()


class VanillaJsonTest(unittest.TestCase):
    """Forge's installer writes versions/<mc>/<mc>.jar but not <mc>.json (TODO 14.7)."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.vanilla = {"id": "26.1.2", "mainClass": "net.minecraft.client.main.Main", "assetIndex": {"id": "31"},
                        "libraries": [{"name": "org.ow2.asm:asm:9.0"}]}
        write_version(self.root, {"id": "26.1.2-forge-64.1.0", "inheritsFrom": "26.1.2",
                                  "mainClass": "net.minecraftforge.bootstrap.ForgeBootstrap", "libraries": []})
        (self.root / "versions" / "26.1.2").mkdir()
        (self.root / "versions" / "26.1.2" / "26.1.2.jar").write_bytes(b"jar")

    def test_a_missing_vanilla_parent_is_fetched_from_the_manifest(self):
        manifest = write_manifest(self.root / "remote", {"26.1.2": (self.vanilla, None)})
        merged = mclaunch.merge(mclaunch.load_version(self.root, "26.1.2-forge-64.1.0", manifest))
        self.assertEqual(merged["mainClass"], "net.minecraftforge.bootstrap.ForgeBootstrap")
        self.assertEqual(merged["assetIndex"], {"id": "31"})
        self.assertEqual(json.loads((self.root / "versions" / "26.1.2" / "26.1.2.json").read_text(encoding="utf-8")), self.vanilla)

    def test_a_fetched_json_with_the_wrong_sha1_never_lands(self):
        manifest = write_manifest(self.root / "remote", {"26.1.2": (self.vanilla, "0" * 40)})
        with self.assertRaises(SystemExit):
            mclaunch.load_version(self.root, "26.1.2-forge-64.1.0", manifest)
        self.assertEqual(sorted(p.name for p in (self.root / "versions" / "26.1.2").iterdir()), ["26.1.2.jar"])

    def test_an_installed_json_is_read_without_the_manifest(self):
        (self.root / "versions" / "26.1.2" / "26.1.2.json").write_text(json.dumps(self.vanilla), encoding="utf-8")
        mclaunch.load_version(self.root, "26.1.2-forge-64.1.0", "file:///no/such/manifest.json")


class SubstituteTest(unittest.TestCase):
    def test_known_arguments_are_replaced(self):
        self.assertEqual(mclaunch.substitute(["--u", "${auth_player_name}x"], {"auth_player_name": "P"}), ["--u", "Px"])

    def test_an_unknown_argument_fails_rather_than_passing_through(self):
        with self.assertRaises(SystemExit):
            mclaunch.substitute(["${user_properties}"], {})

    def test_offline_uuid_matches_javas_name_uuid_from_bytes(self):
        # java.util.UUID.nameUUIDFromBytes("OfflinePlayer:CtclGuest".getBytes(UTF_8)), from jshell.
        self.assertEqual(mclaunch.offline_uuid("CtclGuest"), "fb1866f0-7b65-3121-9602-8fa699fbeec3")


class EnsureFileTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.source = self.dir / "source.bin"
        self.source.write_bytes(b"good bytes")
        self.good_sha1 = hashlib.sha1(b"good bytes").hexdigest()
        self.url = self.source.as_uri()

    def test_a_missing_file_is_downloaded_and_checked(self):
        target = self.dir / "lib" / "a.jar"
        self.assertTrue(mclaunch.ensure_file(target, self.url, self.good_sha1))
        self.assertEqual(target.read_bytes(), b"good bytes")

    def test_a_matching_file_is_left_alone(self):
        target = self.dir / "a.jar"
        target.write_bytes(b"good bytes")
        self.assertFalse(mclaunch.ensure_file(target, "file:///nowhere", self.good_sha1))

    def test_a_bad_download_never_replaces_the_existing_file(self):
        target = self.dir / "a.jar"
        target.write_bytes(b"stale bytes")
        with self.assertRaises(SystemExit):
            mclaunch.ensure_file(target, self.url, "0" * 40)
        self.assertEqual(target.read_bytes(), b"stale bytes")
        self.assertEqual([p.name for p in self.dir.iterdir() if p.name.endswith(".part")], [])

    def test_a_missing_file_with_no_url_fails(self):
        with self.assertRaises(SystemExit):
            mclaunch.ensure_file(self.dir / "made-by-installer.jar", "", self.good_sha1)

    def test_old_style_natives_are_refused(self):
        with self.assertRaises(SystemExit):
            mclaunch.prepare_libraries(self.dir, [{"name": "a:b:1", "natives": {"linux": "natives-linux"}}], {}, "linux", False)


if __name__ == "__main__":
    unittest.main()
