"""Prepares a Minecraft client from installed launcher JSON and writes the java command that runs it (TODO 13.7).

NeoForge and Forge only install a client into a launcher directory (`--install-client`), and no tool runs one without
the launcher. This does the launcher's part for the production harness:
- reads versions/<id>/<id>.json, following inheritsFrom, and fetches a vanilla one the installer didn't write (Forge's);
- applies the OS and feature rules;
- downloads any missing library or asset and checks every file's sha1;
- substitutes the ${...} arguments.

It writes the command as JSON and does not start the game. Gradle starts it, so the harness owns the process and can
end exactly that one.

Usage:
    python tools/mclaunch.py --root <install dir> --version <id> --game-dir <dir> --assets <dir> --java <java> \\
        --username <name> --out <command.json> [--jvm-arg <arg>]... [--quick-play-multiplayer <host:port>]

The only files written are under --root/libraries (missing libraries), --root/versions (a missing vanilla JSON),
--assets, --game-dir, and --out. Nothing is deleted, and a file whose sha1 doesn't match is replaced only by a download
that does match.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
import platform
import re
import sys
import tempfile
import urllib.request
import uuid
from pathlib import Path

LAUNCHER_NAME = "ctcl-mclaunch"
DOWNLOAD_WORKERS = 16
VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"


def log(message: str) -> None:
    print(f"mclaunch: {message}", flush=True)


def os_name() -> str:
    if sys.platform.startswith("win"):
        return "windows"
    if sys.platform == "darwin":
        return "osx"
    return "linux"


def rule_allows(rules: list, features: dict, os_id: str, is_x86: bool) -> bool:
    """The launcher's rule semantics: start disallowed, and the last matching rule decides."""
    allowed = False
    for rule in rules:
        matches = True
        os_rule = rule.get("os", {})
        if "name" in os_rule and os_rule["name"] != os_id:
            matches = False
        if "arch" in os_rule and (os_rule["arch"] == "x86") != is_x86:
            matches = False
        for feature, wanted in rule.get("features", {}).items():
            if bool(features.get(feature, False)) != wanted:
                matches = False
        if matches:
            allowed = rule["action"] == "allow"
    return allowed


def fetch_vanilla_json(path: Path, version_id: str, manifest_url: str) -> None:
    """Forge's installer writes the vanilla client jar but not its JSON, which a launcher gets from Mojang's manifest.
    Only an id the manifest lists is fetched, so a loader version that was never installed still fails."""
    log(f"no version JSON at {path}; looking {version_id} up in {manifest_url}")
    with urllib.request.urlopen(manifest_url, timeout=60) as response:
        manifest = json.loads(response.read().decode("utf-8"))
    entry = next((v for v in manifest["versions"] if v["id"] == version_id), None)
    if entry is None:
        raise SystemExit(f"no version JSON at {path}, and Mojang's manifest has no {version_id}: install {version_id} first")
    ensure_file(path, entry["url"], entry["sha1"])


def load_version(root: Path, version_id: str, manifest_url: str = VERSION_MANIFEST) -> list:
    """The version and its inheritsFrom parents, child first."""
    chain = []
    seen = set()
    current = version_id
    while current:
        if current in seen:
            raise SystemExit(f"inheritsFrom loops back to {current}")
        seen.add(current)
        path = root / "versions" / current / f"{current}.json"
        if not path.is_file():
            fetch_vanilla_json(path, current, manifest_url)
        data = json.loads(path.read_text(encoding="utf-8"))
        chain.append(data)
        current = data.get("inheritsFrom")
    return chain


def library_key(name: str) -> str:
    """group:artifact[:classifier], without the version: a child's library replaces its parent's."""
    parts = name.split("@")[0].split(":")
    return ":".join(parts[:2] + parts[3:])


def merge(chain: list) -> dict:
    """A child's libraries and arguments come first, and its mainClass wins. Everything else comes from the nearest version that sets it."""
    merged = {"libraries": [], "arguments": {"jvm": [], "game": []}}
    seen = set()
    for data in chain:
        for library in data.get("libraries", []):
            key = library_key(library["name"])
            if key not in seen:
                seen.add(key)
                merged["libraries"].append(library)
        for kind in ("jvm", "game"):
            # The parent's arguments go first, as the launcher orders them.
            merged["arguments"][kind] = data.get("arguments", {}).get(kind, []) + merged["arguments"][kind]
        for field in ("mainClass", "assetIndex", "assets", "type"):
            if merged.get(field) is None and data.get(field) is not None:
                merged[field] = data[field]
    merged["id"] = chain[0]["id"]
    return merged


def expand(arguments: list, features: dict, os_id: str, is_x86: bool) -> list:
    result = []
    for argument in arguments:
        if isinstance(argument, str):
            result.append(argument)
        elif rule_allows(argument.get("rules", []), features, os_id, is_x86):
            value = argument["value"]
            result.extend(value if isinstance(value, list) else [value])
    return result


def substitute(arguments: list, values: dict) -> list:
    """Replaces every ${name}. An unknown one fails: a launcher that guesses would start a broken game."""
    def replace(match):
        name = match.group(1)
        if name not in values:
            raise SystemExit(f"unknown launcher argument ${{{name}}}")
        return values[name]
    return [re.sub(r"\$\{([a-zA-Z_]+)}", replace, argument) for argument in arguments]


def sha1_of(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def ensure_file(path: Path, url: str, sha1: str) -> bool:
    """Makes path hold the file with this sha1, downloading it if needed. Returns True if it downloaded."""
    if path.is_file() and (not sha1 or sha1_of(path) == sha1):
        return False
    if not url:
        raise SystemExit(f"{path} is missing or doesn't match its sha1, and it has no download url: the installer should have made it")
    path.parent.mkdir(parents=True, exist_ok=True)
    # Download next to the target and move it in only once the sha1 matches, so a bad download never lands. The temp
    # name is short: library paths already come close to Windows' 260-character limit.
    handle, temporary = tempfile.mkstemp(dir=path.parent, suffix=".part")
    os.close(handle)
    temporary_path = Path(temporary)
    try:
        with urllib.request.urlopen(url, timeout=60) as response, temporary_path.open("wb") as out:
            while block := response.read(1 << 20):
                out.write(block)
        actual = sha1_of(temporary_path)
        if sha1 and actual != sha1:
            raise SystemExit(f"{url} has sha1 {actual}, expected {sha1}")
        os.replace(temporary_path, path)
    finally:
        if temporary_path.exists():
            temporary_path.unlink()
    return True


def library_path(library: dict) -> tuple:
    """(relative path, url, sha1) for a library, from its downloads entry or its Maven name."""
    artifact = library.get("downloads", {}).get("artifact")
    if artifact:
        return artifact["path"], artifact.get("url", ""), artifact.get("sha1", "")
    group, name, version, *rest = library["name"].split(":")
    classifier = f"-{rest[0]}" if rest else ""
    path = f"{group.replace('.', '/')}/{name}/{version}/{name}-{version}{classifier}.jar"
    base = library.get("url", "")
    return path, (base.rstrip("/") + "/" + path) if base else "", ""


def prepare_libraries(root: Path, libraries: list, features: dict, os_id: str, is_x86: bool) -> list:
    wanted = []
    for library in libraries:
        if "natives" in library:
            raise SystemExit(f"{library['name']} uses the old natives layout, which this launcher doesn't support")
        if library.get("rules") and not rule_allows(library["rules"], features, os_id, is_x86):
            continue
        path, url, sha1 = library_path(library)
        wanted.append((root / "libraries" / path, url, sha1))
    with concurrent.futures.ThreadPoolExecutor(DOWNLOAD_WORKERS) as pool:
        downloaded = sum(pool.map(lambda entry: ensure_file(*entry), wanted))
    log(f"{len(wanted)} libraries ready ({downloaded} downloaded)")
    return [path for path, _, _ in wanted]


def prepare_assets(assets: Path, index: dict) -> None:
    index_path = assets / "indexes" / f"{index['id']}.json"
    ensure_file(index_path, index["url"], index["sha1"])
    objects = json.loads(index_path.read_text(encoding="utf-8"))["objects"]
    wanted = {entry["hash"] for entry in objects.values()}
    entries = [(assets / "objects" / h[:2] / h, f"https://resources.download.minecraft.net/{h[:2]}/{h}", h) for h in sorted(wanted)]
    with concurrent.futures.ThreadPoolExecutor(DOWNLOAD_WORKERS) as pool:
        downloaded = sum(pool.map(lambda entry: ensure_file(*entry), entries))
    log(f"{len(entries)} assets ready in {assets} ({downloaded} downloaded)")


def version_jar(root: Path, chain: list) -> Path:
    """The launched version's own jar, or its nearest parent's: an installer-made version has none of its own."""
    for data in chain:
        jar = root / "versions" / data["id"] / f"{data['id']}.jar"
        if jar.is_file():
            return jar
    raise SystemExit(f"no client jar for {chain[0]['id']} or its parents under {root / 'versions'}")


def offline_uuid(username: str) -> str:
    """Vanilla's offline UUID: UUID.nameUUIDFromBytes("OfflinePlayer:" + name)."""
    digest = bytearray(hashlib.md5(f"OfflinePlayer:{username}".encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


def build_command(args, chain: list, merged: dict, classpath: list) -> list:
    root = args.root.resolve()
    game_dir = args.game_dir.resolve()
    natives = game_dir / "natives"
    features = {"is_quick_play_multiplayer": bool(args.quick_play_multiplayer)}
    values = {
        "auth_player_name": args.username,
        "version_name": merged["id"],
        "game_directory": str(game_dir),
        "assets_root": str(args.assets.resolve()),
        "assets_index_name": merged["assetIndex"]["id"],
        "auth_uuid": offline_uuid(args.username),
        "auth_access_token": "0",
        "clientid": "0",
        "auth_xuid": "0",
        "version_type": merged.get("type") or "release",
        "natives_directory": str(natives),
        "launcher_name": LAUNCHER_NAME,
        "launcher_version": "1",
        "classpath": os.pathsep.join(str(p) for p in classpath),
        "classpath_separator": os.pathsep,
        "library_directory": str(root / "libraries"),
        "quickPlayMultiplayer": args.quick_play_multiplayer or "",
    }
    os_id, is_x86 = os_name(), platform.architecture()[0] == "32bit"
    jvm = substitute(expand(merged["arguments"]["jvm"], features, os_id, is_x86), values)
    game = substitute(expand(merged["arguments"]["game"], features, os_id, is_x86), values)
    return [str(args.java)] + jvm + list(args.jvm_arg) + [merged["mainClass"]] + game


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, required=True, help="the install dir holding versions/ and libraries/")
    parser.add_argument("--version", required=True, help="the version id to launch, e.g. neoforge-26.2.0.88 or 26.2")
    parser.add_argument("--game-dir", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True, help="an assets dir (indexes/ + objects/), shared between runs")
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--username", required=True)
    parser.add_argument("--out", type=Path, required=True, help="where to write the command as JSON")
    parser.add_argument("--jvm-arg", action="append", default=[], help="an extra JVM argument, before the main class")
    parser.add_argument("--quick-play-multiplayer", help="host:port to join straight after launch")
    args = parser.parse_args(argv)

    root = args.root.resolve()
    chain = load_version(root, args.version)
    merged = merge(chain)
    features = {"is_quick_play_multiplayer": bool(args.quick_play_multiplayer)}
    classpath = prepare_libraries(root, merged["libraries"], features, os_name(), platform.architecture()[0] == "32bit")
    classpath.append(version_jar(root, chain))
    prepare_assets(args.assets.resolve(), merged["assetIndex"])
    args.game_dir.mkdir(parents=True, exist_ok=True)
    (args.game_dir / "natives").mkdir(exist_ok=True)
    command = build_command(args, chain, merged, classpath)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({"command": command, "gameDir": str(args.game_dir.resolve())}, indent=1), encoding="utf-8")
    log(f"{merged['id']}: main class {merged['mainClass']}, {len(classpath)} classpath entries; command in {args.out}")


if __name__ == "__main__":
    main()
