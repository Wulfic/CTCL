"""Enforce the loader pin rule in gradle.properties (owner answers 27, 30, 31; TODO Stage 16.3).

A Forge or NeoForge pin is the stable build of its Minecraft version, or a beta only where that version has no stable
build. NeoForge marks unstable builds with a suffix (`-beta`, `-alpha...`). Forge builds carry no suffix, so stable means
the `<mc>-recommended` promotion, and a pin on a `-latest` build must be listed in `forge_beta_versions`.

    python tools/check_pins.py [--properties gradle.properties] [--neoforge-json FILE] [--forge-json FILE]

It exits 1 on a violation, and on any network or parse error: it fails closed. A newer beta than a beta pin, or a newer
recommended build than a Forge pin, is only a notice (answer 31). Bumping a pin needs a fresh sweep.
The --*-json flags read recorded API responses instead of the network.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Sequence

from release import read_properties, version_key

NEOFORGE_URL = "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge"
FORGE_URL = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
# files.minecraftforge.net answers 403 to urllib's default User-Agent (gotchas.md 2026-10-01).
USER_AGENT = "ctcl-check-pins"
ATTEMPTS = 3
FORGE_BETA_KEY = "forge_beta_versions"
NUMERIC = re.compile(r"^(\d+(?:\.\d+)*)(.*)$")

Fetch = Callable[[str], object]


def log(message: str) -> None:
    print(f"check_pins: {message}", file=sys.stderr, flush=True)


class PinError(Exception):
    """The check couldn't run. It fails closed."""


@dataclass
class Report:
    errors: list[str] = field(default_factory=list)
    notices: list[str] = field(default_factory=list)


def build_key(version: str) -> tuple[tuple[int, ...], bool]:
    """Orders loader builds: by number, and a stable build after a prerelease of the same number."""
    match = NUMERIC.match(version)
    if not match:
        raise PinError(f"{version!r} isn't a loader version")
    return tuple(int(p) for p in match.group(1).split(".")), not match.group(2)


def is_stable(version: str) -> bool:
    return build_key(version)[1]


def pins(props: dict[str, str], prefix: str) -> dict[str, str]:
    found = {k.removeprefix(prefix + "."): v for k, v in props.items() if k.startswith(prefix + ".")}
    return dict(sorted(found.items(), key=lambda kv: version_key(kv[0])))


def neoforge_prefix(mc: str) -> str:
    """NeoForge's build prefix for a Minecraft version: 26.1 → `26.1.0.`, 26.1.2 → `26.1.2.`."""
    parts = mc.split(".")
    return ".".join(parts + ["0"] * (3 - len(parts))) + "."


def check_neoforge(neo_pins: dict[str, str], versions: Sequence[str], report: Report) -> None:
    for mc, pin in neo_pins.items():
        prefix = neoforge_prefix(mc)
        builds = sorted((v for v in versions if v.startswith(prefix)), key=build_key)
        if pin not in builds:
            report.errors.append(f"neoforge.{mc}={pin}: NeoForge's Maven doesn't list it under {prefix}*")
            continue
        stable = [v for v in builds if is_stable(v)]
        if not is_stable(pin):
            if stable:
                report.errors.append(f"neoforge.{mc}={pin} is a beta, but Minecraft {mc} has a stable build: pin {stable[-1]}")
            elif build_key(builds[-1]) > build_key(pin):
                report.notices.append(f"neoforge.{mc}={pin}: a newer beta, {builds[-1]}, exists (no stable build yet)")


def check_forge(forge_pins: dict[str, str], marked: set[str], promos: dict[str, str], report: Report) -> None:
    for mc in sorted(marked - set(forge_pins), key=version_key):
        report.errors.append(f"{FORGE_BETA_KEY} lists {mc}, which has no forge.{mc} pin")
    for mc, pin in forge_pins.items():
        recommended, latest = promos.get(f"{mc}-recommended"), promos.get(f"{mc}-latest")
        if not recommended and not latest:
            report.errors.append(f"forge.{mc}={pin}: Forge's promotions have no build for Minecraft {mc}")
            continue
        if recommended:
            if mc in marked:
                report.errors.append(f"{FORGE_BETA_KEY} lists {mc}, but it has a recommended build: pin forge.{mc}={recommended} and unmark it")
            elif build_key(pin) > build_key(recommended):
                report.errors.append(f"forge.{mc}={pin} isn't the recommended build: pin {recommended}")
            elif build_key(pin) < build_key(recommended):
                report.notices.append(f"forge.{mc}={pin}: a newer recommended build, {recommended}, exists")
            continue
        if mc not in marked:
            report.errors.append(f"forge.{mc}={pin} has no recommended build, so it's a beta: add {mc} to {FORGE_BETA_KEY}")
        if build_key(pin) > build_key(latest):
            report.errors.append(f"forge.{mc}={pin} is newer than {mc}-latest ({latest}): Forge doesn't list it")
        elif build_key(pin) < build_key(latest):
            report.notices.append(f"forge.{mc}={pin}: a newer -latest build, {latest}, exists (no recommended build yet)")


def check(props: dict[str, str], neoforge_versions: Sequence[str], forge_promos: dict[str, str]) -> Report:
    report = Report()
    marked = {v.strip() for v in props.get(FORGE_BETA_KEY, "").split(",") if v.strip()}
    check_neoforge(pins(props, "neoforge"), neoforge_versions, report)
    check_forge(pins(props, "forge"), marked, forge_promos, report)
    return report


# --- inputs -----------------------------------------------------------------------------------

def fetch_json(url: str) -> object:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(1, ATTEMPTS + 1):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
            log(f"{url}: attempt {attempt}/{ATTEMPTS} failed: {error}")
            if attempt < ATTEMPTS:
                time.sleep(5 * attempt)
    raise PinError(f"{url} failed {ATTEMPTS} times; refusing to pass the pin check without it")


def neoforge_versions(data: object) -> list[str]:
    versions = data.get("versions") if isinstance(data, dict) else None
    if not isinstance(versions, list) or not versions or not all(isinstance(v, str) for v in versions):
        raise PinError("NeoForge's versions response has no `versions` list of strings")
    return versions


def forge_promos(data: object) -> dict[str, str]:
    promos = data.get("promos") if isinstance(data, dict) else None
    if not isinstance(promos, dict) or not promos or not all(isinstance(v, str) for v in promos.values()):
        raise PinError("Forge's promotions response has no `promos` map of strings")
    return promos


def load(path: str | None, url: str, fetch: Fetch) -> object:
    if path is None:
        return fetch(url)
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PinError(f"can't read {path}: {error}") from error


# --- entry point ------------------------------------------------------------------------------

def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--properties", default="gradle.properties")
    p.add_argument("--neoforge-json", help="a recorded NeoForge versions response, instead of the network")
    p.add_argument("--forge-json", help="a recorded Forge promotions_slim.json, instead of the network")
    return p


def annotate(level: str, message: str) -> None:
    """A GitHub Actions annotation on CI; a plain log line elsewhere."""
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print(f"::{level} title=Loader pins::{message}", flush=True)
    log(f"{level.upper()}: {message}")


def main(argv: Sequence[str] | None = None, fetch: Fetch = fetch_json) -> int:
    args = parser().parse_args(argv)
    try:
        props = read_properties(Path(args.properties))
        neo = pins(props, "neoforge")
        forge = pins(props, "forge")
        if not neo and not forge and FORGE_BETA_KEY not in props:
            log(f"{args.properties} pins no NeoForge or Forge build; nothing to check")
            return 0
        report = check(props, neoforge_versions(load(args.neoforge_json, NEOFORGE_URL, fetch)),
                       forge_promos(load(args.forge_json, FORGE_URL, fetch)))
    except (PinError, OSError) as error:
        annotate("error", f"REFUSED: {error}")
        return 1
    for notice in report.notices:
        annotate("notice", notice)
    for error in report.errors:
        annotate("error", error)
    if report.errors:
        log(f"{len(report.errors)} pin rule violation(s); fix gradle.properties (owner answer 27)")
        return 1
    log(f"{len(neo)} NeoForge and {len(forge)} Forge pins follow the rule ({len(report.notices)} notice(s))")
    return 0


if __name__ == "__main__":
    sys.exit(main())
