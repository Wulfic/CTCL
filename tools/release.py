"""Release tooling for CI: version planning, publishing, and old-tag cleanup (TODO Stage 9).

Each band branch `band/<B>` owns the tags `v<x.y.z>+<B>` and `v<x.y.z>+<B>-<last>`. The band
name is its lowest Minecraft version, and the label after `+` is the band's Minecraft range
(`26.1-26.3`). A release carries one jar per loader range (TODO 14.8), each named
`ctcl-<x.y.z>+<its range>-<Loaders>.jar`; a single-project band's one jar has no loaders suffix.

    python tools/release.py plan --branch band/26.1
        Prints key=value lines (also appended to $GITHUB_OUTPUT): the band, base version, other
        pinned versions, range label, next version, and whether conventional commits since the
        band's last tag call for a release.
    python tools/release.py publish --branch band/26.1 --version 1.1.0 --jars-dir <dir> --sha <sha>
        Refuses unless <dir> holds exactly the plan's release jars, each declaring `<version>+<its label>` in the
        metadata of exactly the loaders its name lists. Then it tags `v<version>+<band label>` and releases every jar.
    python tools/release.py cleanup --band 26.1 --keep v1.0.1+26.1-26.3 [--force]
        Deletes the band's other tags and releases. It is a DRY RUN unless --force is given.
        It logs every deleted tag with its commit, which is the recovery path.

The version comes from tags, and CI never commits a bump. Mirrors the range and jar logic in
fabric/build.gradle, gradle/pins.gradle, and gradle/release.gradle, and `publish` fails closed
if the two ever disagree.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tomllib
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Sequence

Runner = Callable[[Sequence[str]], str]

CONVENTIONAL = re.compile(r"^(?P<type>[a-z]+)(?:\([^)]*\))?(?P<bang>!)?: \S")
BREAKING = re.compile(r"^BREAKING[ -]CHANGE: ", re.MULTILINE)
SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
BUMPS = {"major": 3, "minor": 2, "patch": 1}


def log(message: str) -> None:
    print(f"release: {message}", file=sys.stderr, flush=True)


class ReleaseError(Exception):
    """A guard refused. Nothing was changed."""


def run_command(args: Sequence[str]) -> str:
    log("$ " + " ".join(args))
    result = subprocess.run(list(args), capture_output=True, text=True)
    if result.returncode != 0:
        raise ReleaseError(f"{args[0]} exited {result.returncode}: {result.stderr.strip()}")
    return result.stdout


# --- versions ---------------------------------------------------------------------------------

def version_key(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))


def parse_semver(version: str) -> tuple[int, int, int]:
    match = SEMVER.match(version)
    if not match:
        raise ReleaseError(f"not a plain x.y.z version: {version!r}")
    return int(match[1]), int(match[2]), int(match[3])


def bump_version(version: str, bump: str) -> str:
    major, minor, patch = parse_semver(version)
    if bump == "major":
        return f"{major + 1}.0.0"
    if bump == "minor":
        return f"{major}.{minor + 1}.0"
    if bump == "patch":
        return f"{major}.{minor}.{patch + 1}"
    raise ReleaseError(f"unknown bump {bump!r}")


def commit_bump(subject: str, body: str) -> str | None:
    """The bump one commit calls for: feat → minor, fix/perf → patch, `!` or BREAKING CHANGE → major."""
    match = CONVENTIONAL.match(subject)
    if not match:
        return None
    if match["bang"] or BREAKING.search(body):
        return "major"
    return {"feat": "minor", "fix": "patch", "perf": "patch"}.get(match["type"])


def highest_bump(commits: Sequence[tuple[str, str]]) -> str | None:
    bumps = [b for b in (commit_bump(subject, body) for subject, body in commits) if b]
    return max(bumps, key=BUMPS.__getitem__, default=None)


# --- band layout (mirrors build.gradle) -------------------------------------------------------

def read_properties(path: Path) -> dict[str, str]:
    props = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


# Jar names list loaders in this order (gradle/release.gradle loaderOrder). Quilt loads the Fabric jar, so it has no
# metadata of its own; the loaders with their own build pin <prefix>.<mc>, and their Gradle project is <prefix>.
LOADER_ORDER = ("Fabric", "Quilt", "Forge", "NeoForge")
LOADER_PROJECTS = {"neoforge": "NeoForge", "forge": "Forge"}
LOADER_METADATA = {"fabric.mod.json": "Fabric", "META-INF/mods.toml": "Forge", "META-INF/neoforge.mods.toml": "NeoForge"}


@dataclass(frozen=True)
class LoaderRange:
    loader: str
    versions: tuple[str, ...]


@dataclass(frozen=True)
class ReleaseJar:
    """One release jar: the loaders whose release builds share a label (gradle/release.gradle releaseJars)."""
    label: str
    loaders: tuple[LoaderRange, ...]
    suffixed: bool = True

    @property
    def loader_names(self) -> tuple[str, ...]:
        return tuple(r.loader for r in self.loaders)

    def name(self, archives: str, version: str) -> str:
        suffix = "-" + "-".join(self.loader_names) if self.suffixed else ""
        return f"{archives}-{version}+{self.label}{suffix}.jar"


@dataclass(frozen=True)
class Band:
    name: str
    base: str
    others: tuple[str, ...]
    label: str
    mod_version: str
    archives: str = "ctcl"
    jars: tuple[ReleaseJar, ...] = ()
    matrix: tuple[dict, ...] = ()
    quilt: bool = False

    @property
    def multi(self) -> bool:
        return bool(self.matrix)


def release_line(version: str) -> str:
    return ".".join(version.split(".")[:2])


def next_line(line: str) -> str:
    major, minor = (int(p) for p in line.split("."))
    return f"{major}.{minor + 1}"


def pins_for(props: dict[str, str], prefix: str) -> list[str]:
    return sorted((k.removeprefix(prefix + ".") for k in props if k.startswith(prefix + ".")), key=version_key)


def loader_label(loader: str, pins: Sequence[str]) -> str:
    """A NeoForge or Forge release jar's label, as gradle/pins.gradle loaderPlan computes it."""
    lines = list(dict.fromkeys(release_line(v) for v in pins))
    for previous, line in zip(lines, lines[1:]):
        if next_line(previous) != line:
            raise ReleaseError(f"{loader} pins leave a gap between {previous} and {line}: one jar can't cover it")
    top = pins[-1] if release_line(pins[0]) == lines[-1] else lines[-1]
    return pins[0] if pins[0] == top else f"{pins[0]}-{top}"


def loader_jars(props: dict[str, str], fabric_pins: list[str], fabric_label: str) -> tuple[tuple[ReleaseJar, ...], bool]:
    """Every release jar of a multi-project band, and whether Quilt is supported."""
    quilt_pins = pins_for(props, "quilt_loader")
    stray = sorted(set(quilt_pins) - set(fabric_pins), key=version_key)
    if stray:
        raise ReleaseError(f"quilt_loader pins for {stray} have no fabric_api pin: Quilt runs the Fabric jar")
    if quilt_pins and quilt_pins != fabric_pins:
        raise ReleaseError(f"Quilt is pinned for {quilt_pins} but Fabric for {fabric_pins}: pin Quilt on every version or none")
    ranges = {fabric_label: [LoaderRange("Fabric", tuple(fabric_pins))]}
    if quilt_pins:
        ranges[fabric_label].append(LoaderRange("Quilt", tuple(quilt_pins)))
    fabric_lines = {release_line(v) for v in fabric_pins}
    for prefix, loader in LOADER_PROJECTS.items():
        pins = pins_for(props, prefix)
        if not pins:
            continue
        outside = [v for v in pins if version_key(v) < version_key(fabric_pins[0]) or release_line(v) not in fabric_lines]
        if outside:
            raise ReleaseError(f"{loader} pins {outside} fall outside the band's range {fabric_label}")
        ranges.setdefault(loader_label(loader, pins), []).append(LoaderRange(loader, tuple(pins)))
    jars = tuple(ReleaseJar(label, tuple(sorted(group, key=lambda r: LOADER_ORDER.index(r.loader))))
                 for label, group in sorted(ranges.items(), key=lambda item: min(LOADER_ORDER.index(r.loader) for r in item[1])))
    return jars, bool(quilt_pins)


def band_layout(branch: str, props: dict[str, str]) -> Band:
    if not branch.startswith("band/") or branch == "band/":
        raise ReleaseError(f"not a band branch: {branch!r}")
    name = branch.removeprefix("band/")
    archives = props.get("archives_base_name", "")
    if not archives:
        raise ReleaseError("gradle.properties has no archives_base_name: the jar names come from it")
    pins = pins_for(props, "fabric_api")
    matrix: tuple[dict, ...] = ()
    quilt = False
    if pins:
        # A multi-project band (fabric/build.gradle): every jar lists its loaders.
        lines = list(dict.fromkeys(release_line(v) for v in pins))
        base, others = pins[0], tuple(pins[1:])
        label = lines[0] if len(lines) == 1 else f"{lines[0]}-{lines[-1]}"
        jars, quilt = loader_jars(props, pins, label)
        matrix = tuple({"loader": "fabric", "minecraft": v, "quilt": quilt} for v in others) + tuple(
            {"loader": prefix, "minecraft": v, "quilt": False} for prefix in LOADER_PROJECTS for v in pins_for(props, prefix))
    else:
        # A single-project, single-version band (band/1.21.11) keeps the v1 name: no loaders suffix.
        base = props.get("minecraft_version", "")
        others, label = (), base
        jars = (ReleaseJar(label, (LoaderRange("Fabric", (base,)),), suffixed=False),)
    if base != name:
        # Tag ownership relies on the band name being its lowest version.
        raise ReleaseError(f"branch {branch} but the band's base version is {base!r}")
    parse_semver(props.get("mod_version", ""))
    return Band(name, base, others, label, props["mod_version"], archives, jars, matrix, quilt)


def band_tag_pattern(band: str) -> re.Pattern[str]:
    if not band:
        raise ReleaseError("refusing an empty band name")
    return re.compile(rf"^v(\d+\.\d+\.\d+)\+{re.escape(band)}(?:-\d+(?:\.\d+)*)?$")


def band_tags(band: str, tags: Sequence[str]) -> list[str]:
    """The band's tags, oldest first."""
    pattern = band_tag_pattern(band)
    owned = [t for t in tags if pattern.match(t)]
    return sorted(owned, key=lambda t: parse_semver(pattern.match(t)[1]))


def tag_version(tag: str) -> str:
    return tag[1:].split("+", 1)[0]


# --- plan -------------------------------------------------------------------------------------

@dataclass(frozen=True)
class Plan:
    band: Band
    previous_tag: str
    version: str
    release: bool
    reason: str


def make_plan(band: Band, tags: Sequence[str], commits_since: Callable[[str], list[tuple[str, str]]]) -> Plan:
    owned = band_tags(band.name, tags)
    if not owned:
        return Plan(band, "", band.mod_version, True, f"first release of band {band.name}")
    previous = owned[-1]
    last = tag_version(previous)
    bump = highest_bump(commits_since(previous))
    reason = f"commits since {previous} call for a {bump} bump" if bump else f"no feat/fix/perf/breaking commits since {previous}"
    if not bump and previous.split("+", 1)[1] != band.label:
        bump, reason = "patch", f"Minecraft range changed from {previous.split('+', 1)[1]} to {band.label}"
    if bump:
        return Plan(band, previous, bump_version(last, bump), True, reason)
    return Plan(band, previous, bump_version(last, "patch") + "-dev", False, reason)


def git_commits_since(runner: Runner) -> Callable[[str], list[tuple[str, str]]]:
    def commits(tag: str) -> list[tuple[str, str]]:
        try:
            runner(["git", "merge-base", "--is-ancestor", tag, "HEAD"])
        except ReleaseError as error:
            raise ReleaseError(f"{tag} is not an ancestor of HEAD; refusing to guess the next version") from error
        out = runner(["git", "log", "--format=%s%x1f%b%x1e", f"{tag}..HEAD"])
        return [tuple(entry.strip("\n").split("\x1f", 1)) for entry in out.split("\x1e") if entry.strip()]
    return commits


def plan_outputs(plan: Plan) -> dict[str, str]:
    band = plan.band
    return {
        "band": band.name,
        "base": band.base,
        "others": json.dumps(list(band.others)),
        "multi": str(band.multi).lower(),
        "quilt": str(band.quilt).lower(),
        # CI's verify matrix: {loader, minecraft, quilt} per job.
        "matrix": json.dumps(list(band.matrix)),
        # The release jars CI must stage, in name order of the loaders they carry.
        "jars": json.dumps([jar.name(band.archives, plan.version) for jar in band.jars]),
        "label": band.label,
        "version": plan.version,
        "release": str(plan.release).lower(),
        "previous_tag": plan.previous_tag,
    }


def cmd_plan(args: argparse.Namespace, runner: Runner) -> int:
    band = band_layout(args.branch, read_properties(Path(args.properties)))
    tags = runner(["git", "tag", "--list", "v*"]).split()
    plan = make_plan(band, tags, git_commits_since(runner))
    outputs = plan_outputs(plan)
    log(f"band {band.name}: {plan.reason}; version {plan.version}+{band.label}, release={plan.release}")
    for jar in band.jars:
        log(f"  jar {jar.name(band.archives, plan.version)}: " + "; ".join(f"{r.loader} {', '.join(r.versions)}" for r in jar.loaders))
    lines = "".join(f"{k}={v}\n" for k, v in outputs.items())
    sys.stdout.write(lines)
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as out:
            out.write(lines)
    return 0


# --- remote state -----------------------------------------------------------------------------

def remote_tags(runner: Runner) -> dict[str, str]:
    """Tag name → commit sha on origin (annotated tags are peeled)."""
    tags: dict[str, str] = {}
    for line in runner(["git", "ls-remote", "--tags", "origin"]).splitlines():
        sha, ref = line.split("\t", 1)
        name = ref.removeprefix("refs/tags/")
        if name.endswith("^{}"):
            tags[name[:-3]] = sha
        else:
            tags.setdefault(name, sha)
    return tags


def release_tags(runner: Runner) -> set[str]:
    out = runner(["gh", "release", "list", "--limit", "1000", "--json", "tagName"])
    return {r["tagName"] for r in json.loads(out or "[]")}


# --- publish ----------------------------------------------------------------------------------

def jar_metadata(jar: Path) -> dict[str, str]:
    """Loader → the version CTCL declares in that loader's metadata, for every loader metadata file in the jar."""
    declared = {}
    try:
        with zipfile.ZipFile(jar) as z:
            names = set(z.namelist())
            for path, loader in LOADER_METADATA.items():
                if path not in names:
                    continue
                text = z.read(path).decode("utf-8")
                if path.endswith(".json"):
                    declared[loader] = json.loads(text)["version"]
                    continue
                mods = [m for m in tomllib.loads(text).get("mods", []) if m.get("modId") == "ctcl"]
                if len(mods) != 1:
                    raise ReleaseError(f"{jar.name}: {path} declares {len(mods)} ctcl mods, not 1")
                declared[loader] = mods[0].get("version", "")
    except (zipfile.BadZipFile, KeyError, ValueError) as error:
        # ValueError covers bad JSON, TOML, and UTF-8; KeyError a fabric.mod.json with no version.
        raise ReleaseError(f"{jar.name}: unreadable loader metadata ({type(error).__name__}: {error})") from error
    return declared


def check_release_jars(band: Band, version: str, jars_dir: Path) -> list[tuple[ReleaseJar, Path]]:
    """The plan's release jars in jars_dir, each proven to declare its own label and exactly its named loaders."""
    expected = {jar.name(band.archives, version): jar for jar in band.jars}
    # The staged gametest jars ride along in CI's artifact; they are never shipped.
    found = {p.name for p in jars_dir.glob("*.jar") if not p.name.endswith("-gametest.jar")}
    if found != set(expected):
        raise ReleaseError(f"{jars_dir} holds {sorted(found)}, but the plan ships exactly {sorted(expected)}")
    checked = []
    for name, jar in expected.items():
        path = jars_dir / name
        declared = jar_metadata(path)
        # Quilt reads fabric.mod.json, so it's in the name with no metadata of its own.
        wanted = set(jar.loader_names) - {"Quilt"}
        if set(declared) != wanted:
            raise ReleaseError(f"{name} names loaders {list(jar.loader_names)}, but its metadata is for {sorted(declared)}")
        for loader, value in declared.items():
            if value != f"{version}+{jar.label}":
                raise ReleaseError(f"{name}: its {loader} metadata declares {value!r}, not {version}+{jar.label}")
        log(f"  {name}: {', '.join(f'{k} {v}' for k, v in sorted(declared.items()))}")
        checked.append((jar, path))
    return checked


def release_notes(band: Band, version: str, notes_file: str | None, sha: str) -> str:
    rows = [f"| `{jar.name(band.archives, version)}` | {r.loader} | {', '.join(r.versions)} |" for jar in band.jars for r in jar.loaders]
    table = "\n".join(["| Jar | Loader | Minecraft |", "|---|---|---|", *rows])
    body = Path(notes_file).read_text(encoding="utf-8") if notes_file else f"Built from {sha}.\n"
    return f"{table}\n\n{body}"


def release_assets(runner: Runner, tag: str) -> set[str]:
    out = runner(["gh", "release", "view", tag, "--json", "assets"])
    return {a["name"] for a in json.loads(out or "{}").get("assets", [])}


def cmd_publish(args: argparse.Namespace, runner: Runner) -> int:
    parse_semver(args.version)
    if not re.fullmatch(r"[0-9a-f]{40}", args.sha):
        raise ReleaseError(f"--sha must be a full commit sha, got {args.sha!r}")
    band = band_layout(args.branch, read_properties(Path(args.properties)))
    tag = f"v{args.version}+{band.label}"
    jars = check_release_jars(band, args.version, Path(args.jars_dir))
    names = {path.name for _, path in jars}
    existing = remote_tags(runner).get(tag)
    if existing:
        if existing == args.sha and tag in release_tags(runner):
            assets = release_assets(runner, tag)
            if assets != names:
                raise ReleaseError(f"{tag} is released at {args.sha} with {sorted(assets)}, not {sorted(names)}; fix it by hand")
            log(f"{tag} is already released at {args.sha} with every jar; nothing to do")
            return 0
        raise ReleaseError(f"{tag} already exists at {existing}, not {args.sha}; refusing to move a release")
    command = ["gh", "release", "create", tag, *(str(path) for _, path in jars), "--target", args.sha,
               "--title", f"CTCL {args.version} for Minecraft {band.label}",
               "--notes", release_notes(band, args.version, args.notes_file, args.sha)]
    runner(command)
    log(f"released {tag} at {args.sha} with {len(jars)} jar(s): {', '.join(sorted(names))}")
    return 0


# --- cleanup ----------------------------------------------------------------------------------

def cleanup_targets(band: str, keep: str, tags: dict[str, str], releases: set[str]) -> list[str]:
    """The band's tags/releases to delete, oldest first. Raises instead of guessing."""
    pattern = band_tag_pattern(band)
    if not pattern.match(keep):
        raise ReleaseError(f"keep tag {keep!r} does not belong to band {band!r}")
    if keep not in tags:
        raise ReleaseError(f"keep tag {keep} is not on origin; refusing to clean up around a release that doesn't exist")
    if keep not in releases:
        raise ReleaseError(f"keep tag {keep} has no GitHub release; refusing to clean up")
    owned = band_tags(band, sorted(set(tags) | releases))
    newer = [t for t in owned if parse_semver(tag_version(t)) > parse_semver(tag_version(keep))]
    if newer:
        raise ReleaseError(f"band {band} has tags newer than {keep}: {newer}; refusing to clean up")
    return [t for t in owned if t != keep]


def cmd_cleanup(args: argparse.Namespace, runner: Runner) -> int:
    tags = remote_tags(runner)
    releases = release_tags(runner)
    targets = cleanup_targets(args.band, args.keep, tags, releases)
    mode = "deleting" if args.force else "DRY RUN, would delete"
    log(f"band {args.band}: keeping {args.keep}; {mode} {len(targets)} old tag(s)")
    for tag in targets:
        sha = tags.get(tag, "unknown")
        log(f"  {tag} at {sha} (recover: git tag {tag} {sha} && git push origin {tag})")
    if not args.force:
        return 0
    deleted = 0
    for tag in targets:
        if tag in releases:
            runner(["gh", "release", "delete", tag, "--cleanup-tag", "--yes"])
        else:
            runner(["git", "push", "origin", "--delete", f"refs/tags/{tag}"])
        deleted += 1
        log(f"deleted {tag} ({deleted}/{len(targets)})")
    log(f"band {args.band}: deleted {deleted} old tag(s); {args.keep} remains")
    return 0


# --- entry point ------------------------------------------------------------------------------

def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    plan = sub.add_parser("plan")
    plan.add_argument("--branch", required=True)
    plan.add_argument("--properties", default="gradle.properties")
    publish = sub.add_parser("publish")
    publish.add_argument("--branch", required=True)
    publish.add_argument("--properties", default="gradle.properties")
    publish.add_argument("--version", required=True)
    publish.add_argument("--jars-dir", required=True, help="the staged release jars; gametest jars in it are skipped")
    publish.add_argument("--sha", required=True)
    publish.add_argument("--notes-file")
    cleanup = sub.add_parser("cleanup")
    cleanup.add_argument("--band", required=True)
    cleanup.add_argument("--keep", required=True)
    cleanup.add_argument("--force", action="store_true", help="actually delete (default: dry run)")
    return p


def main(argv: Sequence[str] | None = None, runner: Runner = run_command) -> int:
    args = parser().parse_args(argv)
    handler = {"plan": cmd_plan, "publish": cmd_publish, "cleanup": cmd_cleanup}[args.command]
    try:
        return handler(args, runner)
    except ReleaseError as error:
        log(f"REFUSED: {error}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
