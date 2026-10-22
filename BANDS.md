# Version bands

CTCL keeps one git branch per **band**: a range of Minecraft versions that share a
single codebase and build script. A band ships **one release jar** named for its range, for
example `ctcl-1.0.0+26.1-26.3.jar`. The jar is built on the band's base (lowest) version and
declares every release line in the range (`["~26.1", "~26.2", "~26.3"]`).
`-Pminecraft_version=<mc>` builds a single-version jar, which the per-version test suite uses. CI
proves the release jar on every pinned version (see **CI and releases** below). Read this before
any port or cross-version fix.

`main` holds agent tooling, docs, and this file. **It has no mod code.**

| Branch | Minecraft | Java | Build | Status |
|---|---|---|---|---|
| `band/1.21.11` | 1.21.11 | 21 | `net.fabricmc.fabric-loom-remap` 1.17.21 + `officialMojangMappings()` | built; owner LAN check pending |
| `band/26.1` | 26.1 – 26.1.2, 26.2, 26.3 (one jar: `+26.1-26.3`) | 25 | non-remap `net.fabricmc.fabric-loom` 1.17.21, Gradle 9.5.1 | built, full suite on all five versions; owner LAN check pending |

Keep the **Minecraft** column in sync with the `fabric_api.<mc>` pins in each band's `gradle.properties`.

## Rules

1. **Cut a band only for a major code change** (the owner's rule, 2026-09-24). For a new version V,
   add a `fabric_api.<V>` pin to the newest band and run the full suite with `-Pminecraft_version=V`
   (no scratch branch). If it passes, the band now covers V. If only a few lines break and a
   call that exists on every version in the band can replace them, make that change and stay on
   one band. A new band is for when the mod's own code (mixins, core logic, API use) has to differ.
2. **A new band branches from the tip of the previous one:**
   `git switch -c band/26.1 band/1.21.11`.
3. **Fix bugs in the oldest affected band**, then `git cherry-pick -x` the fix forward one
   band at a time, running the tests at each step. **Never merge band → band or
   band → main.** A merge drags version-specific build files across.
4. **Tooling and doc changes land on `main`** and are merged main → each band. They flow
   in that direction only. The exception is progress tracking: `TODO.md` and
   `.agent/memory/` changes made on a band go in their own `docs(memory):` commits, and
   those commits are cherry-picked to `main` at each stage boundary.
5. **Tags are `v<mod>+<label>`**: `v1.0.0+1.21.11` or `v1.0.0+26.1-26.3`. The label is the
   jar's Minecraft range, and a band owns every tag whose label starts with its name.
   **CI creates tags. Don't tag by hand.** Only the newest tag and release per band is kept.

## CI and releases

`.github/workflows/ci.yml` runs on every push and PR to `band/**`. Docs-only changes are skipped.
- **build:** on the base version, runs the unit tests, server and client gametests, the
  uninstall check, and the production gametests (the built jars in a real Fabric install).
- **verify:** one job per other pinned version. The release jar must be **byte-identical** to
  that version's compile (`checkReleaseJarBytes`; javac copies version-specific constants into
  class files). Then the full suite runs recompiled, and the production gametests run the
  release jar itself.
- **release:** on a push, when `tools/release.py plan` calls for one:
  - Bump by conventional commits since the band's last tag: `feat` → minor, `fix`/`perf` →
    patch, `!` or `BREAKING CHANGE` → major. A change in the range label forces a patch.
    Other types don't release.
  - Tag `v<version>+<label>`, publish the GitHub release with the jar, then delete the band's
    older tags and releases.

The version comes from tags; `mod_version` in `gradle.properties` is only the first release.
CI never commits. Recover a deleted tag from the sha that the cleanup step logs:
`git tag <tag> <sha> && git push origin <tag>`. Stop all releases with `gh workflow disable ci.yml`.

## Porting checklist

- [ ] `/research-docs` for the target version's Fabric porting notes. Check tickets,
      SavedData, screens, payloads, and gametest.
- [ ] Port the build script first, using **stable releases only** (Loom, Gradle wrapper, Java toolchain, dependency
      configurations), then fix compile errors. `gotchas.md` has the 1.21.11 → 26.1 rename map.
- [ ] Run the full suite for every version in the band with `-Pminecraft_version=<mc>` (see `TESTING.md`).
      Then run `checkReleaseJarBytes` and the production gametests for each one. CI does all of this on push.
- [ ] The full suite passes, plus the manual checklist (LAN sync, uninstall safety).
- [ ] Update the table above on `main`, then merge main → each band.
- [ ] Record every version-specific trap in `.agent/memory/gotchas.md`.
