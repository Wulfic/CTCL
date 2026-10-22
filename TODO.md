# TODO

The working plan. `plan-work` writes here **before** any code is written — a plan that
lives only in the chat window did not happen. Keep this current as you execute; a stale
TODO is worse than an empty one, because the next session trusts it.

> Long-lived context does **not** belong here. Decisions and gotchas go to
> `.agent/memory/`; this file is the active work list.

---

## Task template

```markdown
## <Task name>

**Goal:** <one sentence — what "done" means>
**Approach:** <chosen approach, and the one-line reason it beat the alternative>
**Not doing:** <explicit out-of-scope list — this is what stops scope creep>
**Blast radius:** <what this destroys or overwrites, and where it comes back from —
                  or "none: additive only">

- [ ] 1. <concrete step — names a real file>
- [ ] 2. <concrete step>
- [ ] 3. <the test that proves it works>

**Rollback:** <the exact command that undoes this — not "revert the change">
```

Rules for steps: each names a real file or command, each is independently verifiable, and
tests are steps rather than an afterthought. More than ~8 steps means it's a Tier 2 task —
split it into stages with commits between them.

`Blast radius:` and `Rollback:` are **mandatory** — "none, additive only" is a valid
answer, a blank field is not. Destructive steps are marked as such and gated through
`guard-destructive` when you reach them; irreversible steps go last.

---

## Active

## Quilt, NeoForge, and Forge on 26.x

**Tier:** 2. This adds new loaders, changes the band's Gradle layout, and touches CI and releases.

**Goal:** CTCL works on Fabric, Quilt, NeoForge, and Forge for every 26.x version where that loader
has a stable release, and it behaves the same way on each one. 1.21.11 stays Fabric-only. There are
as few bands and as few jars as possible: all four loaders live on `band/26.1`, adding a loader never
cuts a band, and one jar covers several loaders wherever that works.

**Owner decisions (2026-09-29):**
- Support Quilt, NeoForge, and Forge on 26.x.
- 1.21.11 stays Fabric-only, so `band/1.21.11` doesn't change.
- Keep the number of bands as low as possible.
- **Order: Quilt → NeoForge → Forge.**
- **Stable loader versions only.** When a loader has no stable release for a Minecraft version, that
  loader skips that version. It gets no pin, the jar doesn't declare it, and CI doesn't test it.
  The release doesn't wait, and nothing falls back to a beta.
- **One jar may serve several loaders and versions**, as long as each one is fully supported. For
  Quilt, that means the whole suite passes on Quilt, not just "it launches."
- **The jar name lists the range and every loader it supports**, for example
  `ctcl-1.0.0+26.1-26.3-Fabric-Quilt-Forge-NeoForge.jar`.
- `main` holds the status, the primary `TODO.md`, and `.agent/memory/`. See `decisions.md` 2026-09-23.

These reverse "Not doing: NeoForge, Forge, or Quilt" from v1. See `decisions.md`.

**Standing constraints.** They apply to every loader and were carried over from v1. See `decisions.md` and `README.md`.
- **Stable releases only.** No `-beta`, `-rc`, `-SNAPSHOT`, or `-alpha` builds of NeoForge, Forge,
  their Gradle plugins, or Quilt Loader. NeoForge marks early builds for a Minecraft version as beta,
  and those versions are skipped for NeoForge until a stable build ships.
- **Removing the mod must never break a save.** This holds on every loader. There is no synced or
  persisted registry content, and the only mod-typed registry entry is the non-persistent
  `TicketType`. The only file the mod writes is its own SavedData.
- **A LAN guest without the mod can still join**, and a guest with the mod gets a working button.
  The Forge and NeoForge connection handshakes have to be checked for this. It is not a given.
- There's no cap, no config, and no rate limit. Only `minecraft:crafting_table` counts, and the
  server owns the value.

**Approach (proposed, confirmed in 10.7):**
- *Layout:* a multi-project Gradle build on `band/26.1`, in the style of the MultiLoader template:
  - `common/` holds the core logic, SavedData, tickets, `CycleRequests`, mixins, and payload records,
    all in Mojang names.
  - `fabric/`, `neoforge/`, and `forge/` each hold a thin entrypoint that wires the loader's
    events, networking, and screen hooks to `common`.
  - Each loader uses its own native plugin: Loom, ModDevGradle, and ForgeGradle.

  This beats a band per loader, which would quadruple the cherry-pick-forward work and break
  "keep bands to a minimum." It also beats Architectury, which was declined in v1 and would add a
  runtime API dependency. Here each loader uses its own native plugin, and the jars have no shared
  runtime library.
- *Quilt:* Quilt Loader loads Fabric mods, so Quilt is covered by the Fabric build. It gets no code
  and no separate jar. Quilt goes on the jar name only once the **full** suite passes on Quilt for
  every pin: server and client gametests, the uninstall check, and the production runs. If any part
  can't pass, stop and ask before building a `quilt.mod.json` variant.
- *One combined jar (the target):* all 26.x versions are unobfuscated Mojang names, so `common`
  classes are the same on every loader. One jar could therefore carry `fabric.mod.json`,
  `META-INF/neoforge.mods.toml`, and `META-INF/mods.toml`, plus each loader's entrypoint classes.
  Each loader would read only its own metadata and load only its own entrypoint. Spike 10.5 decides
  whether this is feasible. Every loader must load it without warnings, and no loader's classes
  may be touched on another loader.
- *Jar grouping and naming:*
  - Loaders that support **the same version range** share one jar.
  - Loaders whose stable ranges differ, because some versions were skipped, get separate jars, one
    per distinct range.
  - Name: `ctcl-<v>+<range>-<Loaders>.jar`, with loaders in the order Fabric-Quilt-Forge-NeoForge.
    Examples:
    - all four loaders on the same range: `ctcl-1.0.0+26.1-26.3-Fabric-Quilt-Forge-NeoForge.jar`;
    - NeoForge skips 26.3: `ctcl-1.0.0+26.1-26.3-Fabric-Quilt-Forge.jar` and `ctcl-1.0.0+26.1-26.2-NeoForge.jar`.
  - If 10.5 says a combined jar isn't feasible, fall back to one jar per loader: `-Fabric-Quilt`,
    `-NeoForge`, `-Forge`.
  - The `+` between the mod version and the range is kept from v1. That keeps the SemVer build
    metadata and `tools/release.py` tag parsing unchanged.
  - The 1.21.11 jar keeps its v1 name, because that band doesn't change.
- *Pins per loader:* each loader has its own pins in `gradle.properties`: `neoforge.<mc>` and
  `forge.<mc>` next to `fabric_api.<mc>`, plus `quilt_loader.<mc>` for the Quilt test runs.
  A missing pin means that loader skips that version (the owner's rule), not a build failure.
  The existing missing-pin guard still applies to Fabric, which defines the band.
- *Releases:* there is still one version and one tag per band (`v<x.y.z>+<range>`). The release
  carries each jar from the grouping above. The byte-identity check and the production gametests
  run for each loader and each pin, against the jar that loader will actually use.
- *Cross-loader world moves:* every loader uses the same SavedData id (`ctcl:loaders`), so moving a
  world between loaders keeps the sizes. This is tested, not assumed.

**Not doing:**
- Quilt, NeoForge, or Forge on 1.21.11, or any change to `band/1.21.11`, including its jar name.
- Beta or RC loader builds for any version, even when they're the only build that exists.
- A band per loader, and Architectury or Stonecutter.
- Mixed-loader LAN, such as a Fabric guest on a NeoForge host. The host's loader decides which
  guests get the button, and a guest on another loader is treated as a vanilla guest.
- A config file, caps, or anything else excluded from v1. The v1 **Not doing** list still holds.
- Publishing to Modrinth or CurseForge, which remains its own gated task.

**Blast radius:**
- Stage 11 moves `src/` into `common/` and `fabric/` on `band/26.1`. The move is history-preserving,
  and the pre-move tip is tagged locally as a backup.
- After the move, `git cherry-pick -x` from `band/1.21.11` to `band/26.1` crosses a path change, so
  expect conflicts. `BANDS.md` rule 3 gets a note about this.
- The jar name changes from `ctcl-<v>+26.1-26.3.jar` to one that lists loaders.
  `tools/release.py`, `ci.yml`, and anyone downloading by name all see this. The tag format doesn't change.
- Actions minutes: each loader adds verify jobs per pin. That has to be checked against the
  2000 min/month Free private repo limit (10.6).

### Stage 10 — Research and decide (no code)  → commit `docs(memory): multi-loader research`
- [ ] 10.1 Quilt: does Quilt Loader have a **stable** release that runs 26.1–26.3? Does Fabric API run on
      it directly, or does it need Quilted Fabric API? Which 26.x versions does it skip?
- [ ] 10.2 `/research-docs` NeoForge on 26.1, 26.2, and 26.3:
      - Find the stable (non-beta) versions, which versions get skipped, and the ModDevGradle version.
      - Find the equivalents for payload registration, `ScreenEvent.Init.Post`, level load, gametests,
        and whether a client gametest exists.
      - Check whether `TICKET_TYPE` is synced and whether a vanilla client can join a NeoForge LAN host.
      - Record the answers in `gotchas.md`.
- [ ] 10.3 The same research for MinecraftForge and ForgeGradle on 26.x. If there's no stable 26.x
      Forge, Forge skips 26.x for now. Record that and skip Stage 14 until one ships.
- [ ] 10.4 Quilt spike, with no commit: run the released `ctcl-1.0.0+26.1-26.3.jar` on a Quilt install of
      26.1. Do the button, the tickets, and a restart restore all work?
- [ ] 10.5 Combined-jar spike, with no commit. Check that one jar with `fabric.mod.json` +
      `META-INF/neoforge.mods.toml` + `META-INF/mods.toml` and three entrypoints:
      - loads on each loader;
      - logs no errors or warnings about foreign metadata;
      - never class-loads another loader's classes (check with `-verbose:class` or the loader's log);
      - applies mixins correctly on each one.

      Record the result in `gotchas.md`, whichever way it goes.
- [ ] 10.6 Estimate the CI cost: jobs per push times minutes, across loaders and pins. Decide whether to
      trim the verify matrix, for example to only the base and newest pin for Forge/NeoForge.
- [ ] 10.7 `decisions.md` records:
      - the layout (a multi-project build over a band per loader or Architectury);
      - Quilt through the Fabric build;
      - the combined jar or the per-loader fallback, based on 10.5;
      - the jar grouping and naming rule;
      - skip-when-no-stable.

      Update the **Approach** here if the research changes it.

### Stage 11 — Split `band/26.1` into `common` + `fabric`, Fabric only  → commit `refactor: multi-project layout`
The behaviour must not change. A `refactor:` commit doesn't release (`tools/release.py`).
- [ ] 11.0 Backup: `git tag backup/26.1-pre-split <band/26.1 tip>`, local only. Verify it with `git rev-parse`.
- [ ] 11.1 `settings.gradle` includes `common` and `fabric`. Move the code with `git mv`: everything
      with no Fabric API imports goes to `common/src/main/java`, and the rest to `fabric/src/...`.
      Unit tests move to `common/src/test`.
- [ ] 11.2 Loader-neutral seams in `common`. The events, networking send/receive, and the screen hook
      become small interfaces that `fabric/` implements. Mixins that don't touch Fabric API stay in `common`.
- [ ] 11.3 `build.gradle` for each subproject. Range labels, `gametestJar`, prod tasks, and
      `checkReleaseJarBytes` all keep working for `fabric`.
- [ ] 11.4 The full suite on all five pins: the unit tests, 14 server gametests, `runClientGameTest`,
      `uninstall_check.py`, the prod server and client runs, and the byte check. The Fabric release
      jar keeps the same classes and `fabric.mod.json`.
- [ ] 11.5 `.github/workflows/ci.yml` and `tools/release.py` get the new task paths. Land them on
      `main` first, then merge main → band.
- [ ] 11.6 `BANDS.md` rule 3: explain how to cherry-pick a 1.21.11 fix forward across the `src/` → `common/` move.

### Stage 12 — Quilt  → commit `test: prove the fabric build on quilt`
- [ ] 12.1 `quilt_loader.<mc>` pins, stable only, and skipped where none exists.
      `prodServerGameTest` / `prodClientGameTest` gain a Quilt Loader install option (`-Pctcl_loader=quilt`).
- [ ] 12.2 The full suite on Quilt for every Quilt pin: server and client gametests, a restart restore,
      and `uninstall_check.py` on a world saved under Quilt.
- [ ] 12.3 Only once 12.2 is green: add `Quilt` to the Fabric jar's name. If Quilt's range differs from
      Fabric's, the grouping rule applies. If anything can't pass on Quilt, stop and ask.

### Stage 13 — NeoForge  → commit `feat: neoforge support`
- [ ] 13.1 `neoforge/` with ModDevGradle, `neoforge.mods.toml` (`ctcl`, ARR, a Minecraft range that
      excludes snapshots), and the stable `neoforge.<mc>` pins from 10.2. Versions with no stable
      build get no pin.
- [ ] 13.2 Wiring:
      - payloads through `RegisterPayloadHandlersEvent`;
      - the button through `ScreenEvent.Init.Post`, shown only when the server can receive the payload;
      - the level-load ticket restore;
      - the ticket type registered through `DeferredRegister`, then checked that it isn't synced.
- [ ] 13.3 The LAN guest rule: a vanilla client can join a NeoForge host that runs CTCL. Set the
      network channel to optional. Test this in the production run, or add it to the `TESTING.md` checklist if automation can't do it.
- [ ] 13.4 Tests:
      - port the server gametests (the same cases as Fabric);
      - write a client test, or record the gap in `TESTING.md` if NeoForge has no client gametest;
      - run `uninstall_check.py` on a world saved by NeoForge;
      - take a world from Fabric to NeoForge and back, and check the sizes survive.
- [ ] 13.5 Packaging: fold it into the combined jar if 10.5 proved that feasible and the ranges match,
      otherwise ship a separate jar. Run the byte check and the prod gametests on every NeoForge pin.

### Stage 14 — Forge  → commit `feat: forge support`
Blocked until 10.3 finds a stable 26.x Forge. With none, Forge skips 26.x.
- [ ] 14.1–14.5 The same steps as Stage 13, using ForgeGradle, `META-INF/mods.toml`, and `forge.<mc>`
      pins. Check how Forge's network channel handles an optional channel with a vanilla client.

### Stage 15 — CI, releases, docs  → commits on `main`, then merged main → band
- [ ] 15.1 `ci.yml`: add build and verify jobs for each loader and each pin, and a Quilt prod job.
      `publish` attaches every jar from the grouping. It checks that each jar declares
      `<plan version>+<its range>` and that its name lists exactly the loaders whose metadata it contains.
- [ ] 15.2 `tools/release.py` and `tools/test_release.py`:
      - a release with several assets, named by range plus loaders;
      - cleanup stays keep-1 per band, and it still has to refuse to touch another band;
      - a test proves the naming, and another proves the refusal.
- [ ] 15.3 `README.md`, `TESTING.md` (a LAN checklist for each loader), and the table in `BANDS.md`
      (a loader column showing each loader's range).
- [ ] 15.4 Push `band/26.1` (fast-forward, gated). Watch CI and check each released jar's metadata and name.
- [ ] 15.5 `state.md` and `gotchas.md` at each stage boundary, not just at the end.

**Owner answers (2026-09-29):**
1. Order: **Quilt → NeoForge → Forge.**
2. **Stable versions only.** A loader with no stable build for a version skips that version.
3. The Fabric jar counts as Quilt support **if Quilt is truly, fully supported**. One jar may serve
   several loaders and versions.
4. The jar name lists the range and every loader it supports, for example
   `ctcl-1.0.0+26.1-26.3-Fabric-Quilt-Forge-NeoForge.jar`, with several loaders in one jar where feasible.
5. `main` is the status branch and holds the primary `TODO.md` and memory.

**Rollback:**
- Planning: `git revert` the `docs(memory)` commit on `main`. The v1 plan is in `git show be9931e:TODO.md`.
- Stage 11 before a push: `git reset --keep backup/26.1-pre-split` on `band/26.1` (gated).
- After a push: `git revert` the stage commits. Never force-push.
- A bad multi-jar release: `gh release delete <tag> --cleanup-tag --yes`, then re-tag from the logged sha.

---

## Owner follow-ups (post-release)
- [ ] `TESTING.md` LAN checklist on 1.21.11 (old 6.1) with a second client and a vanilla guest.
- [ ] `TESTING.md` LAN checklist on 26.x (old 7.8b).
- [ ] Housekeeping. Each item is destructive, so get the owner's OK and go through `guard-destructive` first:
  - drop `../CTCL-1.21.11` `stash@{0}` (the superflat WIP, superseded by the blur fix);
  - delete the local branch `ci/1.21.11-diag` (`5a34b22`);
  - delete the remote branches `ci/1.21.11-display` (`d6f16e6`) and `ci/26.1-display` (`934de8a`).
    Both of those SHAs are the release tags, so no work is lost.
- [ ] Rotate the `ghp_` PAT that is in the git history.

---

## Done

v1 (Fabric) shipped on 2026-09-28:
- `v1.0.0+1.21.11` (`d6f16e6`)
- `v1.0.0+26.1-26.3` (`934de8a`)

CI releases on every green band push. The full stage-by-stage plan (Stages 0–9: the spec,
constraints, per-stage tests, and CI bring-up) is in git: `git show be9931e:TODO.md`. The reasoning
behind it is in `.agent/memory/decisions.md` and `gotchas.md`.

The destructive-action doctrine (`guard-destructive` skill, `settings.json` deny/ask rules) is done.
It's in the same `git show`.
