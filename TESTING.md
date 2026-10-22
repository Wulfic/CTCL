# Testing

## Automated: run these before every commit on a band

| Command | What it proves | Time |
|---|---|---|
| `./gradlew build` | Compiles `common/` (which fails on any loader import, gametest sources included), `fabric/`, `neoforge/`, and `forge/` with `-Werror`. Runs the unit tests in `common/` (JUnit 5 + Mockito, under Fabric's classloader as a test-only harness). Then runs **all server gametests** headless: 13 CTCL cases in `fabric/`, then the same 13 in `neoforge/` and in `forge/`, each on its base, 26.1.2. | ~5 min |
| `./gradlew runClientGameTest` | Real client: open a table, click through `1,3,5,7,9,0` with the mouse, toggle the recipe book, click again, check the server's value and the layout. **Opens a window.** It passes silently and fails with `AssertionError`. | ~2 min |
| `python tools/uninstall_check.py` | Loads the gametest world, which has loaders in all three dimensions, in the **vanilla** server twice. There must be no unexpected errors, no `ctcl` in `chunk_tickets.dat` or any other vanilla `.dat`, and every `ctcl/loaders.dat` must be untouched. Run it after `build`. For another version, pass `--minecraft <mc>`. | ~1 min |
| `python tools/uninstall_check.py --self-test` | The script's delete guard refuses every path except `build/uninstall-check`, and the scan finds a planted `ctcl` leak | instant |
| `python -m unittest discover -s tools -p "test_*.py"` | `release.py` (jar naming, the publish checks on each jar's metadata and name, band guards), `ci.yml` (static checks on its tasks and plan outputs), and `mclaunch.py` (rules, inheritance, sha1-checked downloads) | instant |

Gametest counts: every server reports 14 tests, which are the 13 CTCL cases plus vanilla's own `minecraft:always_pass`.
NeoForge and Forge assert the 13 CTCL ids by name from the report.

**Name the project in production tasks.** `prodServerGameTest`, `checkReleaseJarBytes`, and the other harness tasks
exist in `fabric/`, `neoforge/`, and `forge/`, and a bare task name runs in every project that has it. Use
`:fabric:...`, `:neoforge:...`, or `:forge:...`.

**Release jars: one per range** (`decisions.md` "Stage 14.8"). Every loader covers 26.1 – 26.3 today, so on the base
`./gradlew releaseJars` writes one jar, `build/release/ctcl-<mod>+26.1-26.3-Fabric-Quilt-Forge-NeoForge.jar`. The
Fabric, Forge, and NeoForge builds merge into it, and a shared entry with different bytes fails the task. Every harness picks its
jars from `-Pctcl_prod_mods` by the loaders a jar's name lists, so one directory can hold everything:

```sh
./gradlew releaseJars :fabric:gametestJar :neoforge:gametestJar :forge:gametestJar   # on the base, after build
cp build/release/*.jar fabric/build/libs/*-gametest.jar neoforge/build/libs/*-gametest.jar forge/build/libs/*-gametest.jar <dir>/
```

`releaseJars` never deletes. Any other jar already in `build/release` fails it.

The server gametest world (`fabric/build/run/gameTest/world`) **persists between runs**. Every
test clears its own area first. `./gradlew clean` resets it.

### Every Minecraft version in the band

This band covers 26.1 – 26.3 from one source. The **release jar** is built on 26.1,
`ctcl-<mod>+26.1-26.3-Fabric-Quilt-Forge-NeoForge.jar`, and its `fabric.mod.json` declares
`["~26.1", "~26.2", "~26.3"]`. With
`-Pminecraft_version=<v>`, the build makes a jar for that version alone (`~<v>`). CI runs the table
above for each of `26.1`, `26.1.1`, `26.1.2`, `26.2`, and `26.3` on every push. By hand:

```sh
./gradlew clean build -Pminecraft_version=26.2   # clean: an older version can't load a newer gametest world
./gradlew runClientGameTest -Pminecraft_version=26.2
python tools/uninstall_check.py --minecraft 26.2  # reads the server gametest world from the build
```

Each version's Fabric API pin is `fabric_api.<version>` in `gradle.properties`. A version with
no pin fails the build, and `-Pfabric_api_version=<v>` overrides the pin. To add a new
Minecraft version, add its pin and run the table. If it passes, update `BANDS.md`. If the
mod's own code has to change, that's when a new band is cut (`BANDS.md` rule 1).

Recompiling only proves the **source** works on each version. Two more checks prove the
**release jar** itself:

```sh
# <dir>: the release jars and gametest jars, staged as above
./gradlew :fabric:checkReleaseJarBytes :fabric:prodServerGameTest :fabric:prodClientGameTest -Pminecraft_version=26.3 -Pctcl_prod_mods=<dir>
```

- `checkReleaseJarBytes`: the release jar must be byte-identical to the 26.3 compile (only
  `fabric.mod.json` and the manifest may differ). javac copies compile-time constants into
  class files, and their values can change between versions. `InputConstants.MOUSE_BUTTON_LEFT`
  is 0 on 26.1 and 1 on 26.3. So "it compiles on 26.3" is not the same as "the 26.1 jar runs on 26.3".
- `prodServerGameTest` / `prodClientGameTest`: run the prebuilt jars (not a recompile) in a
  production Fabric server or client of that version. Each version has its own run directory,
  because an older server can't read a world a newer one saved. A server run on a world that already
  exists also checks the **restart restore**: the Nether and the End must each log `Restored N crafting
  table loader(s)` with N ≥ 1 (the tables `DimensionCases` leaves there).

### Quilt

Quilt Loader runs the Fabric jar unchanged. `quilt_loader.<mc>` in `gradle.properties` pins a stable Quilt for
each version, and the jar is named `-Fabric-Quilt` only while every Fabric pin has one. `checkQuiltFloor`
(part of `check`) fails if `fabric.mod.json`'s `fabricloader` floor rises above what a pinned Quilt provides
(0.19.3 for Quilt 0.30.1). `-Pctcl_loader=quilt` switches both production tasks to Quilt: the server is a real
`quilt-installer` install, and the client is Loom's with Quilt Loader in place of Fabric Loader. CI runs this for
every version with a Quilt pin:

```sh
q="-Pctcl_loader=quilt -Pminecraft_version=26.3 -Pctcl_prod_mods=<dir>"
./gradlew $q :fabric:prodServerGameTest   # the server gametests on Quilt
./gradlew $q :fabric:prodServerGameTest   # again: the restart restore on the Quilt-saved world
python tools/uninstall_check.py --minecraft 26.3 --world fabric/build/run/prodServerGameTest/quilt-26.3/world
./gradlew $q :fabric:prodClientGameTest   # the client gametest on Quilt
```

### NeoForge

`neoforge.<mc>` in `gradle.properties` pins one NeoForge build per version: the stable build where there is one
(26.1.2, 26.2), and a beta where there isn't (26.1, 26.1.1, 26.3; owner answer 27). `tools/check_pins.py`, in CI's
`plan` job, checks that rule against NeoForge's Maven and fails closed. `settings.gradle` leaves `neoforge/` out of a
build with `-Pminecraft_version` set to a version with no pin. The NeoForge build is compiled on NeoForge's base, 26.1,
and ships inside the release jar. It declares `minecraft [26.1,26.4)`, and for `neoforge` each pin and newer, up to
the next pinned version (a line's last pin runs to the end of its line).

The server gametests are vanilla data (test functions plus `test_instance` JSON in the gametest jar), so the same 13
cases run in development and in a real install. NeoForge turns its own gametest hooks off in every real install
(`gotchas.md` 2026-09-29), so the harness uses NeoForge's installer and FML's production `GameTestServer`.

```sh
n="-Pminecraft_version=26.2 -Pctcl_prod_mods=<dir>"   # <dir>: staged as above
./gradlew $n :neoforge:checkReleaseJarBytes   # byte-identical to the 26.2 compile (ignoring the manifest, the metadata, and Forge's own entries)
./gradlew $n :neoforge:prodServerGameTest     # a real server install: 13/13 from the report, then a restart that must restore
./gradlew $n :neoforge:prodClientSmoke        # a real client: open a table, the button reads 0, click, it reads 1, and the server stored 1. Opens a window.
./gradlew $n :neoforge:prodVanillaGuest       # a real vanilla client joins the server running the release jar. Opens a window.
python tools/uninstall_check.py --minecraft 26.2 --world neoforge/build/run/prodServerGameTest/26.2-release/gametestserver/gametestworld
```

- **World moves:** `:neoforge:prodServerRestore -Pctcl_world=<world dir>` boots the release jar on a copy of another
  install's world, and it must restore. `:fabric:prodServerGameTest -Pctcl_world=<dir>` does the same on Fabric. Going
  Fabric → NeoForge → Fabric keeps every size.
- **Clients:** NeoForge only installs clients into a launcher dir, so `tools/mclaunch.py` does the launcher's part
  (libraries, assets, and the command). The assets are cached in `~/.gradle/caches/ctcl-mclaunch/assets`, about 480 MB the
  first time. The client smoke test (`common/src/gametestClient`) is our own, because NeoForge has no client gametest API.
- **26.3's whitelist:** a fresh 26.3 server writes `white-list=true` to `server.properties`, so `prodVanillaGuest`
  writes `white-list=false` before the server starts. A server you set up by hand on 26.3 needs the same.
- **Leave the game windows alone** while these run: they are real clients, and a click or a key reaches them.
- The harness never deletes anything. Each run dir holds one set of jars, and a foreign jar in `mods/` fails the run
  (`./gradlew :neoforge:clean` resets it). A hung game is killed after 15 minutes, and only the process the task started.
  Before the kill, its thread dump goes to `<console>-threads.txt`, and the CI failure artifact carries it.
- A smoke-test client whose main thread dies is dumped and killed at once, not after 15 minutes: Forge's early-window crash
  prompt keeps the JVM alive. The one retry is Forge's "Timed out trying to setup the Game Window" (its 10 s limit, which a cold
  GL start under Xvfb can overrun). `check` runs `checkClientDeathKill` and `checkHangDump` to prove both kill paths.

### Forge

`forge.<mc>` pins one Forge build per version: the **recommended** build where there is one (64.1.0 on 26.1.2,
65.1.0 on 26.2), and the `-latest` build where there isn't (62.0.9 on 26.1, 63.0.2 on 26.1.1, 66.0.9 on 26.3). Forge
builds carry no beta suffix, so those versions are listed in `forge_beta_versions`, and `check_pins.py` fails if the
list disagrees with Forge's promotions. The Forge build ships in the same release jar. Its `META-INF/mods.toml`
declares `loaderVersion [62,)`, `minecraft [26.1,26.4)`, and `forge [62.0.9,63),[63.0.2,64),[64.1.0,65),[65.1.0,66),[66.0.9,67)`:
each pin and newer within its major. `checkLoaderRange` (`jar` and `check` depend on it) fails unless every pin
satisfies those ranges, and unless the gametest mod's `loaderVersion` accepts every pin's FML too.

Forge has no production gametest launcher, so the harness runs a **normal Forge server**. When
`-Dctcl.gameTestReport=<path>` is set, the test-only gametest mod runs every CTCL case through vanilla's runner at
startup, writes the JUnit report, and stops the server (`decisions.md` "Stage 14.5"). Each run gets a new flat world
under `worlds/`. The dev run is `:forge:runGametestGameTestServer`, which is part of `build`.

```sh
f="-Pminecraft_version=26.2 -Pctcl_prod_mods=<dir>"   # <dir>: staged as above
./gradlew $f :forge:checkReleaseJarBytes   # byte-identical to the 26.2 compile (ignoring the manifest, the metadata, and NeoForge's own entries)
./gradlew $f :forge:prodServerGameTest     # a real server install: 13/13 from the report, then a restart that must restore
./gradlew $f :forge:prodClientSmoke        # a real Forge client, the same smoke test as NeoForge. Opens a window.
./gradlew $f :forge:prodVanillaGuest       # a real vanilla client joins the Forge server. Opens a window.
python tools/uninstall_check.py --minecraft 26.2 --world <the "Forge saved the gametest world at" path>
```

- **World moves:** `:forge:prodServerRestore -Pctcl_world=<world dir>` works as NeoForge's does. Going Fabric → Forge →
  Fabric keeps every size.
- The first run of each pin installs Forge (the installer's `--installServer` / `--installClient`), about a minute more.
  Mavenizer's first run (in the Gradle user home) takes about 5 minutes.
- The same rules as NeoForge apply: leave the game windows alone, one set of jars per run dir, and `./gradlew :forge:clean`
  resets it.

### Local sweep timings (warm Gradle caches)

The whole suite runs every loader on every pin, in background parts that each stay under the 2-hour limit
(`TODO.md` 16.4: three parts).
- NeoForge: 8.3–8.8 min per pin. Forge: 8.6–9.1 min per pin. The client smoke test and the vanilla guest take about
  3 min of each (2026-10-01).
- Base: 8.2 min. Quilt: 3.4 min per pin. Fabric verify: 4.7–5.0 min per pin where its `build` ran Fabric alone, and
  7.0 min where it also ran the NeoForge and Forge dev gametests (2026-09-30). Every pin now does the latter.

Before a long local run: close extra VS Code windows, set the Java extension to lightweight mode, and never reload the
window mid-run (`gotchas.md` "VS Code's Java and Gradle extensions").

## Manual: LAN with a second client

The automated suites can't run two game clients against one integrated host. Do this once per release **for each
loader**, and again whenever the networking or button code changes. Tick each loader's column as you go.

Machines: **A** is the host, **B** is a guest with the mod, and **V** is a vanilla guest (a third client, or B with
every mod removed). A and B run the same loader and the same jar:

| Loader | Minecraft | Jar on A and B | Also install |
|---|---|---|---|
| Fabric | any of 26.1 – 26.3 | `ctcl-<mod>+26.1-26.3-Fabric-Quilt-Forge-NeoForge.jar` | Fabric Loader, Fabric API |
| Quilt | any of 26.1 – 26.3 | the same jar | Quilt Loader, Fabric API |
| NeoForge | any of 26.1 – 26.3 | the same jar | nothing |
| Forge | any of 26.1 – 26.3 | the same jar | nothing |

A guest on a different loader than the host isn't supported (`BANDS.md`), so there's no cross-loader step.
`prodVanillaGuest` already covers a vanilla client joining a dedicated NeoForge or Forge server, but not an
integrated LAN host.

| # | Step | Fabric | Quilt | NeoForge | Forge |
|---|---|---|---|---|---|
| 0 | Minecraft version used | ____ | ____ | ____ | ____ |
| 1 | **A:** create a singleplayer world, place a crafting table, open it. The button reads `0`. | [ ] | [ ] | [ ] | [ ] |
| 2 | **A:** click to `5`. Open to LAN. | [ ] | [ ] | [ ] | [ ] |
| 3 | **B:** join and open the same table. It reads `5`. | [ ] | [ ] | [ ] | [ ] |
| 4 | **A and B:** both keep the table open. B clicks. A's button updates to match without reopening. | [ ] | [ ] | [ ] | [ ] |
| 5 | **A:** walk more than 300 blocks away. A furnace smelting inside the 5×5 area keeps smelting (check its output when you return). | [ ] | [ ] | [ ] | [ ] |
| 6 | **V:** joins with no error, opens the table, crafts normally, and sees no button. | [ ] | [ ] | [ ] | [ ] |
| 7 | **A:** set tables in the Nether and the End, save and quit, reopen. Every table shows its old number. | [ ] | [ ] | [ ] | [ ] |
| 8 | **A:** a table exactly on a chunk border, and one at negative X/Z, each load the expected chunks. Check with F3 + G chunk borders and the F3 "Chunks" line. | [ ] | [ ] | [ ] | [ ] |
| 9 | **A:** press Esc in singleplayer before opening to LAN. A farm in a loaded chunk stops while paused (expected, see README). | [ ] | [ ] | [ ] | [ ] |

Record anything surprising in `.agent/memory/gotchas.md`.
