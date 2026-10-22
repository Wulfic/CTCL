# Version bands

CTCL keeps one git branch per **band**: a range of Minecraft versions that share a
single codebase and build script. A band ships **one release jar per loader range**, named for its
range and the loaders it holds, for example `ctcl-1.2.0+26.1-26.3-Fabric-Quilt-Forge-NeoForge.jar`. Loaders with
different ranges get a jar each (v1.1.0 shipped `...+26.1-26.3-Fabric-Quilt.jar` and
`...+26.1.2-26.2-Forge-NeoForge.jar`). Each jar is built on its loaders' base (lowest) version and
declares every release line in its range (`["~26.1", "~26.2", "~26.3"]` for Fabric).
`-Pminecraft_version=<mc>` builds a single-version jar, which the per-version test suite uses. CI
proves the release jar on every pinned version (see **CI and releases** below). Read this before
any port or cross-version fix.

`main` holds agent tooling, docs, and this file. **It has no mod code.**

| Branch | Minecraft | Loaders (range → jar) | Java | Build | Status |
|---|---|---|---|---|---|
| `band/1.21.11` | 1.21.11 | Fabric: 1.21.11 → `+1.21.11` | 21 | `net.fabricmc.fabric-loom-remap` 1.17.21 + `officialMojangMappings()` | released `v1.0.0+1.21.11`; owner LAN check pending |
| `band/26.1` | 26.1 – 26.1.2, 26.2, 26.3 | Fabric, Quilt, Forge, NeoForge: 26.1 – 26.3 → `+26.1-26.3-Fabric-Quilt-Forge-NeoForge` | 25 | multi-project (`common/` + `fabric/` + `neoforge/` + `forge/`), non-remap `net.fabricmc.fabric-loom` 1.17.21, `net.neoforged.moddev` 2.0.148, ForgeGradle 7.0.40, Gradle 9.5.1 | released `v1.1.0+26.1-26.3` (two jars); the one four-loader jar passes on every loader and pin locally and ships in 1.2.0; owner LAN check pending |

Keep the **Minecraft** and **Loaders** columns in sync with each band's `gradle.properties` pins: `fabric_api.<mc>`,
`quilt_loader.<mc>`, `neoforge.<mc>`, and `forge.<mc>`. NeoForge and Forge pin each version's stable build (for
Forge, its *recommended* build), and a beta only where that version has no stable one (owner answer 27).
`forge_beta_versions` marks Forge's betas, because Forge builds carry no suffix. CI's `tools/check_pins.py` enforces
the rule. A loader skips a version it has no pin for, so its range can be narrower than the band's. Quilt joins the
Fabric jar's name only while every Fabric pin has a Quilt pin.

Guests on a LAN world or server run the **host's loader** with CTCL, or no mods at all. A guest on a different loader
with CTCL (a Fabric client joining a NeoForge host) isn't supported or tested.

## Rules

1. **Cut a band only for a major code change** (the owner's rule, 2026-09-24). For a new version V,
   add a `fabric_api.<V>` pin to the newest band and run the full suite with `-Pminecraft_version=V`
   (no scratch branch). If it passes, the band now covers V. Add `quilt_loader.<V>`, `neoforge.<V>`, and `forge.<V>`
   the same way, each only once that loader has a stable build for V, and run that loader's tasks (`TESTING.md`). If only a few lines break and a
   call that exists on every version in the band can replace them, make that change and stay on
   one band. A new band is for when the mod's own code (mixins, core logic, API use) has to differ.
2. **A new band branches from the tip of the previous one:**
   `git switch -c band/26.1 band/1.21.11`.
3. **Fix bugs in the oldest affected band**, then `git cherry-pick -x` the fix forward one
   band at a time, running the tests at each step. **Never merge band → band or
   band → main.** A merge drags version-specific build files across.

   *Across the multi-project split.* `band/1.21.11` keeps its code in `src/`. `band/26.1` keeps it in
   `common/src/` (loader-neutral), `fabric/src/` (Fabric wiring), `neoforge/src/`, and `forge/src/`. A fix in
   `common/` reaches every loader; a fix in loader wiring may need the same change in the other two loader projects.
   - `git cherry-pick` follows files that moved whole by rename detection: `core/`, `mixin/`, `net/`,
     `LoaderData`, `CtclTickets`, the unit tests, and `GameTestSupport`.
   - Code that was split conflicts (modify/delete) or lands in the wrong half, and has to be ported by hand:
     - `CtclMod` / `CtclClient` → `fabric/.../fabric/CtclFabric` and `CtclFabricClient`;
     - the event wiring in `CtclServer`, `CtclNetworking`, and `LoaderButtons` → the Fabric entrypoints;
     - `*GameTests` → `common/src/gametest/.../*Cases` bodies plus the `fabric/src/gametest` `@GameTest` delegates.
   - Check `git status` after every pick. If a moved file isn't followed, retry with `-X find-renames=30%`.
4. **Tooling and doc changes land on `main`** and are merged main → each band. They flow
   in that direction only. The exception is progress tracking: `TODO.md` and
   `.agent/memory/` changes made on a band go in their own `docs(memory):` commits, and
   those commits are cherry-picked to `main` at each stage boundary.
5. **Tags are `v<mod>+<label>`**: `v1.0.0+1.21.11` or `v1.0.0+26.1-26.3`. The label is the
   band's whole Minecraft range (every jar's range sits inside it), and a band owns every tag whose label starts
   with its name. One tag and one release carry all of the band's jars.
   **CI creates tags. Don't tag by hand.** Only the newest tag and release per band is kept.

## CI and releases

`.github/workflows/ci.yml` runs on every push to `band/**`, and by hand through `workflow_dispatch`.
There are no PR or scheduled runs. Docs-only changes are skipped. Every Gradle task is project-qualified
(`:fabric:`, `:neoforge:`, ...) on a multi-project band, and bare on `band/1.21.11`.
- **plan:** runs the tooling tests and `tools/check_pins.py`, which checks the pin rule against the live NeoForge and
  Forge APIs and fails closed (a newer beta is only a notice). Then `tools/release.py plan`: the band, its base version
  and label, the next version, whether to release, the jar names (`jars`), and the verify matrix (`matrix`).
- **build:** on the base version, runs the unit tests, server and client gametests, the uninstall check, and the
  Fabric and Quilt production gametests (the built jars in a real install, then a restart that must restore). On a
  multi-project band it then builds `releaseJars` and stages **exactly** the planned jars, plus one gametest jar per
  loader. Any other count fails the job.
- **verify:** one job per loader and pin from `matrix`.
  - **Fabric**, on every pin but the base: the release jar must be **byte-identical** to that version's compile
    (`checkReleaseJarBytes`; javac copies version-specific constants into class files). Then the full suite runs
    recompiled, the production gametests run the release jar itself, and Quilt runs when it has that pin.
  - **NeoForge** and **Forge**, on each of their pins: the loader's build and dev gametests, the byte check, the
    production gametests in a real server install (with the restart restore), the uninstall check on the saved world,
    the client smoke test and a vanilla client joining (under Xvfb), and a Fabric → loader → Fabric world move.
  - **Cost:** `band/26.1` runs 14 verify jobs per push (4 Fabric, 5 NeoForge, 5 Forge). The estimate is about 146
    runner-minutes a push (v1.1.0's 8 jobs used about 83), so about 13 pushes a month fit in the 2000-minute free limit.
    The owner keeps the full matrix (answer 34). The 1.2.0 push's measured number replaces this estimate.
- **release:** on a push or a manual run, when `plan` calls for one, and only when every job is green:
  - Bump by conventional commits since the band's last tag: `feat` → minor, `fix`/`perf` →
    patch, `!` or `BREAKING CHANGE` → major. A change in the range label forces a patch.
    Other types don't release.
  - `release.py publish` refuses unless the staged jars are exactly `jars`, each jar's metadata (`fabric.mod.json`,
    `META-INF/mods.toml`, `META-INF/neoforge.mods.toml`) declares `<version>+<its range>`, and its name lists exactly
    the loaders that metadata implies.
  - Tag `v<version>+<label>` and publish one GitHub release with every jar. The notes open with a table of each jar's
    loaders and range. A version a loader runs on a beta build reads `26.1 (beta)`, and a line under the table names
    each beta build. Then delete the band's older tags and releases (keep 1).

The version comes from tags; `mod_version` in `gradle.properties` is only the first release.
CI never commits. Recover a deleted tag from the sha that the cleanup step logs:
`git tag <tag> <sha> && git push origin <tag>`. Stop all releases with `gh workflow disable ci.yml`.

## Porting checklist

- [ ] `/research-docs` for the target version's porting notes, for each loader (Fabric, NeoForge, Forge). Check
      tickets, SavedData, screens, payloads, and gametest.
- [ ] Port the build script first, using **stable releases only** (Loom, Gradle wrapper, Java toolchain, dependency
      configurations), then fix compile errors. `gotchas.md` has the 1.21.11 → 26.1 rename map.
- [ ] Run the full suite for every version in the band with `-Pminecraft_version=<mc>` (see `TESTING.md`).
      Then run `checkReleaseJarBytes` and the production gametests for each loader on each of its pins. CI does all
      of this on push.
- [ ] The full suite passes, plus the manual checklist (LAN sync, uninstall safety).
- [ ] Update the table above on `main`, then merge main → each band.
- [ ] Record every version-specific trap in `.agent/memory/gotchas.md`.
