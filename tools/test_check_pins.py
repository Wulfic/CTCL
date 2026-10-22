"""Tests for tools/check_pins.py, on API responses recorded 2026-10-01 (tools/testdata/check_pins/, trimmed to 26.x and
21.11/1.21.11). Run: python -m unittest discover -s tools -p "test_*.py" -v
"""

import contextlib
import io
import json
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

import check_pins

DATA = Path(__file__).resolve().parent / "testdata" / "check_pins"
NEOFORGE = check_pins.neoforge_versions(json.loads((DATA / "neoforge-versions.json").read_text(encoding="utf-8")))
FORGE = check_pins.forge_promos(json.loads((DATA / "forge-promotions.json").read_text(encoding="utf-8")))

# band/26.1's pins when the responses were recorded (16.2).
PINS = {
    "neoforge.26.1": "26.1.0.19-beta",
    "neoforge.26.1.1": "26.1.1.15-beta",
    "neoforge.26.1.2": "26.1.2.112",
    "neoforge.26.2": "26.2.0.88",
    "neoforge.26.3": "26.3.0.39-beta",
    "forge.26.1": "62.0.9",
    "forge.26.1.1": "63.0.2",
    "forge.26.1.2": "64.1.0",
    "forge.26.2": "65.1.0",
    "forge.26.3": "66.0.9",
    "forge_beta_versions": "26.1,26.1.1,26.3",
}


def run(**changes: str | None) -> check_pins.Report:
    props = {**PINS, **changes}
    return check_pins.check({k: v for k, v in props.items() if v is not None}, NEOFORGE, FORGE)


class TodayTest(unittest.TestCase):
    def test_todays_pins_pass_with_no_notices(self):
        report = run()
        self.assertEqual(report.errors, [])
        self.assertEqual(report.notices, [])


class NeoForgeTest(unittest.TestCase):
    def test_beta_pin_with_a_stable_build_fails_and_names_it(self):
        report = run(**{"neoforge.26.2": "26.2.0.56-beta"})
        self.assertEqual(len(report.errors), 1)
        self.assertIn("neoforge.26.2=26.2.0.56-beta is a beta", report.errors[0])
        self.assertIn("pin 26.2.0.88", report.errors[0])

    def test_an_alpha_snapshot_build_is_not_stable(self):
        # 26.1's list holds 26.1.0.0-alpha.N+snapshot-M builds with no -beta suffix; they must not count as stable.
        self.assertFalse(check_pins.is_stable("26.1.0.0-alpha.8+snapshot-4"))
        self.assertEqual(run(**{"neoforge.26.1": "26.1.0.19-beta"}).errors, [])

    def test_unlisted_pin_fails(self):
        for pin in ("26.2.0.999", "26.1.2.112-beta", "26.2.0.88"):
            mc = "26.2" if pin != "26.2.0.88" else "26.1.2"
            with self.subTest(pin=pin):
                errors = run(**{f"neoforge.{mc}": pin}).errors
                self.assertEqual(len(errors), 1)
                self.assertIn("doesn't list it", errors[0])

    def test_older_beta_is_only_a_notice(self):
        report = run(**{"neoforge.26.3": "26.3.0.37-beta"})
        self.assertEqual(report.errors, [])
        self.assertEqual(len(report.notices), 1)
        self.assertIn("26.3.0.39-beta", report.notices[0])

    def test_older_stable_pin_passes(self):
        self.assertEqual(run(**{"neoforge.26.1.2": "26.1.2.111"}).errors, [])

    def test_prefix(self):
        self.assertEqual(check_pins.neoforge_prefix("26.1"), "26.1.0.")
        self.assertEqual(check_pins.neoforge_prefix("26.1.2"), "26.1.2.")


class ForgeTest(unittest.TestCase):
    def test_non_recommended_pin_with_a_recommended_build_fails(self):
        errors = run(**{"forge.26.1.2": "64.1.3"}).errors
        self.assertEqual(len(errors), 1)
        self.assertIn("isn't the recommended build: pin 64.1.0", errors[0])

    def test_marked_version_with_a_recommended_build_fails(self):
        errors = run(forge_beta_versions="26.1,26.1.1,26.2,26.3").errors
        self.assertEqual(len(errors), 1)
        self.assertIn("lists 26.2, but it has a recommended build", errors[0])

    def test_unmarked_latest_pin_fails(self):
        errors = run(forge_beta_versions="26.1,26.1.1").errors
        self.assertEqual(len(errors), 1)
        self.assertIn("forge.26.3=66.0.9 has no recommended build", errors[0])

    def test_missing_marker_fails_for_every_beta(self):
        self.assertEqual(len(run(forge_beta_versions=None).errors), 3)

    def test_marker_without_a_pin_fails(self):
        errors = run(**{"forge.26.3": None}).errors
        self.assertEqual(len(errors), 1)
        self.assertIn("lists 26.3, which has no forge.26.3 pin", errors[0])

    def test_pin_newer_than_latest_fails(self):
        errors = run(**{"forge.26.1": "62.0.10"}).errors
        self.assertEqual(len(errors), 1)
        self.assertIn("newer than 26.1-latest", errors[0])

    def test_version_forge_does_not_know_fails(self):
        errors = run(**{"forge.26.4": "67.0.1"}).errors
        self.assertEqual(len(errors), 1)
        self.assertIn("no build for Minecraft 26.4", errors[0])

    def test_older_builds_are_only_notices(self):
        report = run(**{"forge.26.1": "62.0.8", "forge.26.2": "65.0.9"})
        self.assertEqual(report.errors, [])
        self.assertEqual(len(report.notices), 2)
        self.assertIn("newer -latest build, 62.0.9", report.notices[0])
        self.assertIn("newer recommended build, 65.1.0", report.notices[1])

    def test_build_order_is_numeric(self):
        self.assertGreater(check_pins.build_key("62.0.10"), check_pins.build_key("62.0.9"))
        self.assertGreater(check_pins.build_key("26.1.0.19"), check_pins.build_key("26.1.0.19-beta"))


class ParseTest(unittest.TestCase):
    def test_malformed_responses_refuse(self):
        for bad in (None, [], {}, {"versions": []}, {"versions": [1]}):
            with self.subTest(bad=bad), self.assertRaises(check_pins.PinError):
                check_pins.neoforge_versions(bad)
        for bad in (None, {}, {"promos": {}}, {"promos": {"26.1-latest": 62}}):
            with self.subTest(bad=bad), self.assertRaises(check_pins.PinError):
                check_pins.forge_promos(bad)

    def test_not_a_version_refuses(self):
        with self.assertRaises(check_pins.PinError):
            check_pins.build_key("latest")


class FetchTest(unittest.TestCase):
    def test_retries_then_fails_closed_with_a_user_agent(self):
        failing = mock.Mock(side_effect=urllib.error.URLError("down"))
        with mock.patch.object(check_pins.urllib.request, "urlopen", failing), \
                mock.patch.object(check_pins.time, "sleep"), mock.patch.object(check_pins, "log"):
            with self.assertRaises(check_pins.PinError):
                check_pins.fetch_json(check_pins.FORGE_URL)
        self.assertEqual(failing.call_count, check_pins.ATTEMPTS)
        self.assertEqual(failing.call_args.args[0].get_header("User-agent"), check_pins.USER_AGENT)


class MainTest(unittest.TestCase):
    def main(self, props: dict[str, str], fetch=None, actions: bool = False, extra: tuple[str, ...] = ()) -> tuple[int, str, str]:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "gradle.properties"
            path.write_text("".join(f"{k}={v}\n" for k, v in props.items()), encoding="utf-8")
            responses = {check_pins.NEOFORGE_URL: {"versions": NEOFORGE}, check_pins.FORGE_URL: {"promos": FORGE}}
            fetch = fetch or responses.__getitem__
            out, err = io.StringIO(), io.StringIO()
            with mock.patch.dict(check_pins.os.environ, {"GITHUB_ACTIONS": "true" if actions else ""}), \
                    contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                code = check_pins.main(["--properties", str(path), *extra], fetch=fetch)
            return code, out.getvalue(), err.getvalue()

    def test_todays_pins_exit_0(self):
        code, _, err = self.main(PINS)
        self.assertEqual(code, 0, err)

    def test_violation_exits_1_with_an_annotation(self):
        code, out, _ = self.main({**PINS, "forge.26.1.2": "64.1.3"}, actions=True)
        self.assertEqual(code, 1)
        self.assertIn("::error title=Loader pins::forge.26.1.2=64.1.3 isn't the recommended build", out)

    def test_notice_alone_exits_0(self):
        code, out, _ = self.main({**PINS, "forge.26.1": "62.0.8"}, actions=True)
        self.assertEqual(code, 0)
        self.assertIn("::notice title=Loader pins::", out)

    def test_network_failure_fails_closed(self):
        def down(url):
            raise check_pins.PinError(f"{url} failed")
        code, out, _ = self.main(PINS, fetch=down, actions=True)
        self.assertEqual(code, 1)
        self.assertIn("::error title=Loader pins::REFUSED:", out)

    def test_malformed_response_fails_closed(self):
        code, _, err = self.main(PINS, fetch=lambda url: {"unexpected": True})
        self.assertEqual(code, 1)
        self.assertIn("REFUSED", err)

    def test_missing_properties_file_fails_closed(self):
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(check_pins.main(["--properties", "no/such/gradle.properties"], fetch=lambda url: {}), 1)

    def test_recorded_files_skip_the_network(self):
        def no_network(url):
            raise AssertionError(f"fetched {url}")
        code, _, err = self.main(PINS, fetch=no_network, extra=(
            "--neoforge-json", str(DATA / "neoforge-versions.json"), "--forge-json", str(DATA / "forge-promotions.json")))
        self.assertEqual(code, 0, err)

    def test_no_forge_or_neoforge_pins_skips_the_check(self):
        def no_network(url):
            raise AssertionError(f"fetched {url}")
        code, _, _ = self.main({"fabric_api.26.1": "0.145.1+26.1"}, fetch=no_network)
        self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
