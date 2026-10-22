"""Proves removing CTCL can't break a save (TODO 6.3).

Copies the server-gametest world (which holds active loaders in all three dimensions,
see DimensionGameTests) to build/uninstall-check/ and loads the copy twice in the
VANILLA server for the band's Minecraft version: no Fabric, no CTCL. Fails if vanilla
logs an unexpected WARN/ERROR, if any vanilla .dat file mentions CTCL (a leaked ticket
or registry entry), or if vanilla touched ctcl/loaders.dat (which must survive for a reinstall).

The source world is only read. The one thing ever deleted is this script's own previous
output directory, and only after the path guard below has checked it.

Usage (after `./gradlew build` has run the server gametests):
    python tools/uninstall_check.py
    python tools/uninstall_check.py --self-test   # proves the delete guard refuses bad paths
    python tools/uninstall_check.py --minecraft 26.1.2   # a hotfix built with -Pminecraft_version
    python tools/uninstall_check.py --minecraft 26.3 --world fabric/build/run/prodServerGameTest/quilt-26.3/world
        # a world saved by another install, e.g. the Quilt production server (-Pctcl_loader=quilt)
    python tools/uninstall_check.py --minecraft 26.2 --world neoforge/build/run/prodServerGameTest/26.2/gametestserver/gametestworld
        # the world NeoForge's production gametests saved (:neoforge:prodServerGameTest)
    python tools/uninstall_check.py --minecraft 26.2 --world forge/build/run/prodServerGameTest/26.2/worlds/gametest-<stamp>
        # the world Forge's production gametests saved (:forge:prodServerGameTest logs its path)
"""
import gzip
import hashlib
import shutil
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
BUILD = REPO / "build"
# The Fabric project's server gametest world. Only read: every write goes to BUILD / WORK_NAME.
SOURCE_WORLD = REPO / "fabric" / "build" / "run" / "gameTest" / "world"
WORK_NAME = "uninstall-check"
# 26.x layout: per-dimension data under dimensions/<ns>/<dim>/data (see gotchas.md).
DIMENSIONS = {name: Path("dimensions/minecraft") / name / "data" for name in ("overworld", "the_nether", "the_end")}
TICKETS = Path("minecraft/chunk_tickets.dat")
LOADERS = Path("ctcl/loaders.dat")
# Fewer vanilla .dat files than this means the scan is looking in the wrong place.
MIN_SCANNED = 10
# Expected noise: removing Fabric API, NeoForge, or Forge itself, experimental packs, and our offline test config.
# NeoForge puts every mod's data in one pack, mod_data (its ResourcePackLoader), so the line names no mod. Forge gives
# each mod its own pack, mod:<id>, Forge's own included. Vanilla drops them on the first load; the second must be clean.
EXPECTED_WARNINGS = (
    "Missing data pack fabric-",
    "Missing data pack mod_data",
    "Missing data pack mod:",
    "that are not enabled for this world, disabling pack",
    "OFFLINE/INSECURE MODE",
    "make no attempt to authenticate usernames",
    "connect with any username they choose",
    'set "online-mode" to "true"',
)


def safe_work_dir(build: Path, candidate: Path) -> Path:
    """The only directory this script may delete. Refuses anything but build/uninstall-check itself."""
    resolved = candidate.resolve()
    if resolved.name != WORK_NAME or resolved.parent != build.resolve():
        raise ValueError(f"refusing to use {resolved} as the work dir: must be {build.resolve() / WORK_NAME}")
    return resolved


def fresh_work_dir(build: Path) -> Path:
    """An empty build/uninstall-check. Deletes only this script's previous output, and creates build/ when a fresh
    multi-project checkout has none yet (Gradle only writes the subprojects' build dirs before releaseJars)."""
    work = safe_work_dir(build, build / WORK_NAME)
    if work.exists():
        print(f"removing previous output {work}")
        shutil.rmtree(work)
    work.mkdir(parents=True)
    return work


def source_world() -> Path:
    if "--world" in sys.argv:
        return Path(sys.argv[sys.argv.index("--world") + 1]).resolve()
    return SOURCE_WORLD


def minecraft_version() -> str:
    if "--minecraft" in sys.argv:
        return sys.argv[sys.argv.index("--minecraft") + 1]
    for line in (REPO / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("minecraft_version="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("minecraft_version not found in gradle.properties")


def mentions_ctcl(file: Path) -> bool:
    raw = file.read_bytes()
    try:
        raw = gzip.decompress(raw)
    except OSError:
        pass
    return b"ctcl" in raw


def tickets_with_ctcl(world: Path) -> list[str]:
    """Reads each dimension's ticket file directly, so a missing file fails instead of passing."""
    return [name for name, rel in DIMENSIONS.items() if mentions_ctcl(world / rel / TICKETS)]


def vanilla_files_with_ctcl(world: Path) -> tuple[int, list[str]]:
    """Every .dat outside our own data/ctcl folders: catches a leak wherever vanilla moves its files."""
    scanned, hits = 0, []
    for file in world.rglob("*.dat"):
        if file.parent.name == "ctcl":
            continue
        scanned += 1
        if mentions_ctcl(file):
            hits.append(file.relative_to(world).as_posix())
    return scanned, hits


def loader_hashes(world: Path) -> dict[str, str]:
    return {name: hashlib.sha256((world / rel / LOADERS).read_bytes()).hexdigest() for name, rel in DIMENSIONS.items()}


def run_vanilla(jar: Path, cwd: Path) -> list[str]:
    log: list[str] = []
    proc = subprocess.Popen(["java", "-Xmx2G", "-jar", str(jar), "--nogui"], cwd=cwd, stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    started = threading.Event()

    def pump():
        for line in proc.stdout:
            log.append(line.rstrip())
            if "Done (" in line and not started.is_set():
                started.set()
                proc.stdin.write("stop\n")
                proc.stdin.flush()

    threading.Thread(target=pump, daemon=True).start()
    try:
        proc.wait(timeout=300)
    except subprocess.TimeoutExpired:
        proc.kill()
        raise SystemExit("FAIL: vanilla server timed out; last lines:\n  " + "\n  ".join(log[-15:]))
    if proc.returncode != 0 or not started.is_set():
        raise SystemExit(f"FAIL: vanilla server exit={proc.returncode} started={started.is_set()}:\n  " + "\n  ".join(log[-15:]))
    return log


def self_test() -> None:
    build = BUILD
    for bad in (REPO, build, build / "run", build / "other", REPO / WORK_NAME, build / WORK_NAME / "nested", build / ".." / WORK_NAME):
        try:
            safe_work_dir(build, bad)
        except ValueError:
            continue
        raise SystemExit(f"FAIL: guard accepted {bad}")
    assert safe_work_dir(build, build / WORK_NAME) == (build / WORK_NAME).resolve()
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "data" / "ctcl").mkdir(parents=True)
        (root / "data" / "ctcl" / "loaders.dat").write_bytes(gzip.compress(b"ctcl own file"))
        (root / "clean.dat").write_bytes(gzip.compress(b"vanilla"))
        if vanilla_files_with_ctcl(root) != (1, []):
            raise SystemExit(f"FAIL: scan flagged a clean world: {vanilla_files_with_ctcl(root)}")
        (root / "data" / "leak.dat").write_bytes(gzip.compress(b"type:ctcl:table"))
        if vanilla_files_with_ctcl(root) != (2, ["data/leak.dat"]):
            raise SystemExit(f"FAIL: scan missed a planted CTCL leak: {vanilla_files_with_ctcl(root)}")
    print("self-test OK: guard refuses every path except build/uninstall-check; scan finds a planted leak")


def main() -> None:
    if "--self-test" in sys.argv:
        self_test()
        return
    version = minecraft_version()
    source = source_world()
    jar = Path.home() / ".gradle" / "caches" / "fabric-loom" / version / "minecraft-server.jar"
    if not (source / "level.dat").is_file():
        raise SystemExit(f"no fixture world at {source}: run ./gradlew build first (or the prod server for --world)")
    if not jar.is_file():
        raise SystemExit(f"no vanilla server jar at {jar}")

    work = fresh_work_dir(BUILD)
    print(f"source world (read only): {source}")
    shutil.copytree(source, work / "world", ignore=shutil.ignore_patterns("session.lock"))
    (work / "eula.txt").write_text("eula=true\n")
    (work / "server.properties").write_text("server-port=25599\nonline-mode=false\nlevel-name=world\n")
    world = work / "world"

    failures = []
    before = loader_hashes(world)
    if tickets_with_ctcl(world):
        failures.append(f"modded save already has CTCL tickets: {tickets_with_ctcl(world)}")
    for run in (1, 2):
        log = run_vanilla(jar, work)
        unexpected = [line for line in log if ("/WARN]" in line or "/ERROR]" in line) and not any(e in line for e in EXPECTED_WARNINGS)]
        print(f"vanilla {version} load {run}: {len(log)} log lines, {len(unexpected)} unexpected WARN/ERROR")
        failures += [f"load {run}: {line}" for line in unexpected]
        if tickets_with_ctcl(world):
            failures.append(f"load {run}: CTCL ticket in {TICKETS}: {tickets_with_ctcl(world)}")
        scanned, leaks = vanilla_files_with_ctcl(world)
        if scanned < MIN_SCANNED:
            failures.append(f"load {run}: scanned only {scanned} .dat files; the world layout has changed")
        failures += [f"load {run}: {leak} mentions ctcl" for leak in leaks]
    if loader_hashes(world) != before:
        failures.append(f"vanilla modified {LOADERS}; a reinstall would lose sizes")

    if failures:
        raise SystemExit("FAIL:\n  " + "\n  ".join(failures))
    print(f"PASS: vanilla {version} loads the CTCL world twice with no errors, no CTCL in any vanilla file, and {LOADERS.as_posix()} intact")


if __name__ == "__main__":
    main()
