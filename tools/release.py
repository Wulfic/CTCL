"""Release tooling for CI: version planning, publishing, and old-tag cleanup (TODO Stage 9).

Each band branch `band/<B>` owns the tags `v<x.y.z>+<B>` and `v<x.y.z>+<B>-<last>`. The band
name is its lowest Minecraft version, and the label after `+` is the jar's Minecraft range
(`26.1-26.3`), so the jar, its tag, and its release all carry the same name.

    python tools/release.py plan --branch band/26.1
        Prints key=value lines (also appended to $GITHUB_OUTPUT): the band, base version, other
        pinned versions, range label, next version, and whether conventional commits since the
        band's last tag call for a release.
    python tools/release.py publish --version 1.0.1 --label 26.1-26.3 --jar <jar> --sha <sha>
        Refuses unless the jar declares exactly `<version>+<label>`, then tags and releases it.
    python tools/release.py cleanup --band 26.1 --keep v1.0.1+26.1-26.3 [--force]
        Deletes the band's other tags and releases. It is a DRY RUN unless --force is given.
        It logs every deleted tag with its commit, which is the recovery path.

The version comes from tags, and CI never commits a bump. Mirrors the range logic in the
band's build.gradle, and `publish` fails closed if the two ever disagree.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
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


@dataclass(frozen=True)
class Band:
    name: str
    base: str
    others: tuple[str, ...]
    label: str
    mod_version: str

    @property
    def multi(self) -> bool:
        return bool(self.others)


def band_layout(branch: str, props: dict[str, str]) -> Band:
    if not branch.startswith("band/") or branch == "band/":
        raise ReleaseError(f"not a band branch: {branch!r}")
    name = branch.removeprefix("band/")
    pins = sorted((k.removeprefix("fabric_api.") for k in props if k.startswith("fabric_api.")), key=version_key)
    if pins:
        lines = list(dict.fromkeys(".".join(v.split(".")[:2]) for v in pins))
        base, others = pins[0], tuple(pins[1:])
        label = lines[0] if len(lines) == 1 else f"{lines[0]}-{lines[-1]}"
    else:
        base = props.get("minecraft_version", "")
        others, label = (), base
    if base != name:
        # Tag ownership relies on the band name being its lowest version.
        raise ReleaseError(f"branch {branch} but the band's base version is {base!r}")
    parse_semver(props.get("mod_version", ""))
    return Band(name, base, others, label, props["mod_version"])


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


def cmd_plan(args: argparse.Namespace, runner: Runner) -> int:
    band = band_layout(args.branch, read_properties(Path(args.properties)))
    tags = runner(["git", "tag", "--list", "v*"]).split()
    plan = make_plan(band, tags, git_commits_since(runner))
    outputs = {
        "band": band.name,
        "base": band.base,
        "others": json.dumps(list(band.others)),
        "multi": str(band.multi).lower(),
        "label": band.label,
        "version": plan.version,
        "release": str(plan.release).lower(),
        "previous_tag": plan.previous_tag,
    }
    log(f"band {band.name}: {plan.reason}; version {plan.version}+{band.label}, release={plan.release}")
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

def jar_version(jar: Path) -> str:
    with zipfile.ZipFile(jar) as z:
        return json.loads(z.read("fabric.mod.json"))["version"]


def cmd_publish(args: argparse.Namespace, runner: Runner) -> int:
    parse_semver(args.version)
    if not re.fullmatch(r"[0-9a-f]{40}", args.sha):
        raise ReleaseError(f"--sha must be a full commit sha, got {args.sha!r}")
    tag = f"v{args.version}+{args.label}"
    declared = jar_version(Path(args.jar))
    if declared != f"{args.version}+{args.label}":
        raise ReleaseError(f"{args.jar} declares {declared!r}, but the plan says {args.version}+{args.label}")
    existing = remote_tags(runner).get(tag)
    if existing:
        if existing == args.sha and tag in release_tags(runner):
            log(f"{tag} is already released at {args.sha}; nothing to do")
            return 0
        raise ReleaseError(f"{tag} already exists at {existing}, not {args.sha}; refusing to move a release")
    notes = args.notes_file or ""
    command = ["gh", "release", "create", tag, args.jar, "--target", args.sha,
               "--title", f"CTCL {args.version} for Minecraft {args.label}"]
    command += ["--notes-file", notes] if notes else ["--notes", f"Built from {args.sha}."]
    runner(command)
    log(f"released {tag} at {args.sha}")
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
    publish.add_argument("--version", required=True)
    publish.add_argument("--label", required=True)
    publish.add_argument("--jar", required=True)
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
