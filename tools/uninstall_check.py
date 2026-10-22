"""Proves removing CTCL can't break a save (TODO 6.3).

Copies the server-gametest world (which holds active loaders in all three dimensions,
see DimensionGameTests) to build/uninstall-check/ and loads the copy twice in the
VANILLA server for the band's Minecraft version: no Fabric, no CTCL. Fails if vanilla
logs an unexpected WARN/ERROR, if any chunks.dat holds a CTCL ticket, or if vanilla
touched ctcl_loaders.dat (which must survive for a reinstall).

The source world is only read. The one thing ever deleted is this script's own previous
output directory, and only after the path guard below has checked it.

Usage (after `./gradlew build` has run the server gametests):
    python tools/uninstall_check.py
    python tools/uninstall_check.py --self-test   # proves the delete guard refuses bad paths
"""
import gzip
import hashlib
import shutil
import subprocess
import sys
import threading
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
BUILD = REPO / "build"
SOURCE_WORLD = BUILD / "run" / "gameTest" / "world"
WORK_NAME = "uninstall-check"
DIMENSIONS = {"overworld": Path("data"), "nether": Path("DIM-1/data"), "end": Path("DIM1/data")}
# Expected noise: removing Fabric API itself, experimental packs, and our offline test config.
EXPECTED_WARNINGS = (
    "Missing data pack fabric-",
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


def minecraft_version() -> str:
    for line in (REPO / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("minecraft_version="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("minecraft_version not found in gradle.properties")


def chunks_with_ctcl(world: Path) -> list[str]:
    hits = []
    for name, rel in DIMENSIONS.items():
        raw = (world / rel / "chunks.dat").read_bytes()
        try:
            raw = gzip.decompress(raw)
        except OSError:
            pass
        if b"ctcl" in raw:
            hits.append(name)
    return hits


def loader_hashes(world: Path) -> dict[str, str]:
    return {name: hashlib.sha256((world / rel / "ctcl_loaders.dat").read_bytes()).hexdigest() for name, rel in DIMENSIONS.items()}


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
    print("self-test OK: guard refuses every path except build/uninstall-check")


def main() -> None:
    if "--self-test" in sys.argv:
        self_test()
        return
    version = minecraft_version()
    jar = Path.home() / ".gradle" / "caches" / "fabric-loom" / version / "minecraft-server.jar"
    if not (SOURCE_WORLD / "level.dat").is_file():
        raise SystemExit(f"no fixture world at {SOURCE_WORLD}: run ./gradlew build first")
    if not jar.is_file():
        raise SystemExit(f"no vanilla server jar at {jar}")

    work = safe_work_dir(BUILD, BUILD / WORK_NAME)
    if work.exists():
        print(f"removing previous output {work}")
        shutil.rmtree(work)
    work.mkdir()
    shutil.copytree(SOURCE_WORLD, work / "world", ignore=shutil.ignore_patterns("session.lock"))
    (work / "eula.txt").write_text("eula=true\n")
    (work / "server.properties").write_text("server-port=25599\nonline-mode=false\nlevel-name=world\n")
    world = work / "world"

    failures = []
    before = loader_hashes(world)
    if chunks_with_ctcl(world):
        failures.append(f"modded save already has CTCL tickets in chunks.dat: {chunks_with_ctcl(world)}")
    for run in (1, 2):
        log = run_vanilla(jar, work)
        unexpected = [line for line in log if ("/WARN]" in line or "/ERROR]" in line) and not any(e in line for e in EXPECTED_WARNINGS)]
        print(f"vanilla {version} load {run}: {len(log)} log lines, {len(unexpected)} unexpected WARN/ERROR")
        failures += [f"load {run}: {line}" for line in unexpected]
        if chunks_with_ctcl(world):
            failures.append(f"load {run}: CTCL ticket in chunks.dat: {chunks_with_ctcl(world)}")
    if loader_hashes(world) != before:
        failures.append("vanilla modified ctcl_loaders.dat; a reinstall would lose sizes")

    if failures:
        raise SystemExit("FAIL:\n  " + "\n  ".join(failures))
    print(f"PASS: vanilla {version} loads the CTCL world twice with no errors, no CTCL tickets, and ctcl_loaders.dat intact")


if __name__ == "__main__":
    main()
