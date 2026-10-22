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
  **Narrowed 2026-10-01 (Stage 16, answer 27):** a beta is allowed only when that version has no stable build.
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

### Stage 10 — Research and decide (no code)  → commit `docs(memory): multi-loader research` ✅ 2026-09-29
The facts are in `gotchas.md` 2026-09-29 (four entries), and the choices are in `decisions.md` 2026-09-29 "two jars today".
- [x] 10.1 Quilt: the newest stable is 0.30.1, and it runs 26.1–26.3 with none skipped. Fabric API runs on it directly
      (Quilted Fabric API is dead). The catch: 0.30.1 provides `fabricloader` 0.19.3, and our floor was `>=0.19.5`.
      The owner said to lower it to `>=0.19.3`.
- [x] 10.2 NeoForge:
      - stable only on **26.1.2** (from `.71`) and **26.2** (from `.57`); 26.1, 26.1.1, and 26.3 are beta-only, so they're skipped;
      - ModDevGradle 2.0.147;
      - an event exists for every hook we use, but there is **no client gametest API**;
      - `TICKET_TYPE` isn't synced, and a vanilla guest is fine if every payload is `optional()`.
- [x] 10.3 Forge: stable means a `-recommended` build exists (the owner's call). That's **26.1.2 = 64.1.0** and
      **26.2 = 65.1.0**, and the other versions are skipped. ForgeGradle 7.0.x. Stage 14 is **unblocked**.
- [x] 10.4 Quilt spike: the released jar, with only the floor patched to 0.19.3, passes **14/14 server gametests** on Quilt 0.30.1 on
      26.1 and on 26.3. The client (the button) needs the harness option, so it moves to 12.1–12.2.
- [x] 10.5 Combined-jar spike: one jar loads on Fabric, Quilt, NeoForge 26.2.0.88, and Forge 65.1.0.
      - Only the loader's own entrypoint is class-loaded, and the mixin applies once.
      - Getting there needed mixin `compatibilityLevel` `JAVA_21` + `minVersion`, because Forge's Mixin 0.8.7 rejects
        `JAVA_25`, plus a `pack.mcmeta`.
      - Client mixins and networking are proven in Stages 13–14.
- [x] 10.6 CI: ~33 → ~80 billed min per `band/26.1` push, about 24 pushes a month on the Free plan. No trim, and Quilt
      folds into the Fabric verify job per pin.
- [x] 10.7 `decisions.md` is written. The Approach change: the ranges differ today, so a release carries **two jars**:
      `+26.1-26.3-Fabric-Quilt` and `+26.1.2-26.2-Forge-NeoForge`. The grouping comes from the pins, so they merge once the ranges match.

### Stage 11 — Split `band/26.1` into `common` + `fabric`, Fabric only  → commit `refactor: multi-project layout` ✅ 2026-09-29
The behaviour must not change. A `refactor:` commit doesn't release (`tools/release.py`).
- [x] 11.0 Backup: `git tag backup/26.1-pre-split <band/26.1 tip>`, local only. Verify it with `git rev-parse`.
- [x] 11.1 `settings.gradle` includes `common` and `fabric`. Move the code with `git mv`: everything
      with no Fabric API imports goes to `common/src/main/java`, and the rest to `fabric/src/...`.
      Unit tests move to `common/src/test`.
- [x] 11.2 Loader-neutral seams in `common`. The events, networking send/receive, and the screen hook
      become small interfaces that `fabric/` implements. Mixins that don't touch Fabric API stay in `common`.
      Server gametest bodies move to `common` as plain `Consumer<GameTestHelper>` methods, so each loader
      registers the same cases (NeoForge uses `RegisterGameTestsEvent`, not an annotation scan).
- [x] 11.3 `build.gradle` for each subproject. Range labels, `gametestJar`, prod tasks, and
      `checkReleaseJarBytes` all keep working for `fabric`.
- [x] 11.4 The full suite on all five pins: the unit tests, 14 server gametests, `runClientGameTest`,
      `uninstall_check.py`, the prod server and client runs, and the byte check. The Fabric release
      jar keeps the same mixins, payload ids, SavedData id, and `fabric.mod.json`, except for the two
      entrypoint class names.
      **Result:** all five pins are green, with 92 unit tests (as before the split) and 14/14 server gametests on each.
      The jar diff against the pre-split build is exactly the planned one: the entrypoints moved, `Ctcl` and
      `PayloadSender` were added, and the mixin configs are byte-identical. One 26.3 prod client run hit a native crash and
      passed on rerun (`gotchas.md`).
- [x] 11.5 `.github/workflows/ci.yml` and `tools/release.py` get the new task paths. Land them on
      `main` first, then merge main → band. The owner's call (2026-09-29): drop the `pull_request`
      trigger, so CI runs on push (plus manual dispatch) only. There's no schedule, and there never was.
- [x] 11.6 `BANDS.md` rule 3: explain how to cherry-pick a 1.21.11 fix forward across the `src/` → `common/` move.

**Stage 11 design (written before the code, 2026-09-29):**
- *Gradle:* the root applies Loom with `apply false` (one plugin classloader). `common` applies Loom
  with `splitEnvironmentSourceSets()`, depending only on `minecraft` plus `compileOnly net.fabricmc:sponge-mixin`
  (the Mixin annotations, Loader 0.19.5's own version). So `common` **can't compile** with a Fabric
  import: that's the guard. Unit tests stay on `fabric-loader-junit` as a test-only dependency.
  `fabric` adds `common/src/{main,client,gametest}` as extra source dirs. It compiles them itself, so
  its jar is self-contained, and it keeps all the existing version, label, pin, prod, and byte-check logic.
- *Layout detection:* CI and `uninstall_check.py` are shared with `band/1.21.11` (single project). They
  pick `fabric/build` when `fabric/build.gradle` exists and `build` otherwise. The Gradle task names don't
  change, because the root runs a task by name in every subproject that has it.
- *Seams:* no ServiceLoader, because a combined jar would list every loader's implementation. Each
  entrypoint passes its implementation in explicitly.
  - `Ctcl` (common) holds `MOD_ID` and `LOGGER`.
  - `CtclServer`: the public `onLevelLoad`, `onLevelUnload`, `onLevelTick`, and `onServerStopped`.
  - `CtclTickets`: `ID` + `LOADER`, and each loader registers them.
  - `CtclNetworking.init(PayloadSender)`: `canSend` and `send` for the S2C payload.
  - `LoaderButtons.attach(screen, Consumer<CycleLoaderC2S>)` returns the button for the loader to add
    to the screen. `LoaderButtons.refresh(screen)` runs every frame.
  - Fabric entrypoints: `net.wulfnet.ctcl.fabric.CtclFabric` and `...fabric.client.CtclFabricClient`.
- *Gametests:* the 14 server case bodies move to `common/src/gametest` as static `(GameTestHelper) -> void`
  methods. `fabric/src/gametest` keeps thin `@GameTest` delegates with the same method names, so the test
  names don't change. The client gametest stays in `fabric`, because NeoForge has no client gametest API.

### Stage 12 — Quilt  → commit `feat: support quilt through the fabric jar` ✅ 2026-09-29
- [x] 12.1 In `fabric.mod.json`, `fabricloader` goes to `>=0.19.3`, below the `loader_version` pin.
      Add a guard in the build that fails if the floor is ever above what the Quilt pin provides.
      Add `quilt_loader.<mc>=0.30.1` for all five pins; skip any pin without a stable Quilt.
      `prodServerGameTest` / `prodClientGameTest` gain a Quilt Loader install option (`-Pctcl_loader=quilt`).
- [x] 12.2 The full suite on Quilt for every Quilt pin: server and client gametests, a restart restore,
      and `uninstall_check.py` on a world saved under Quilt.
- [x] 12.3 Only once 12.2 is green: the Fabric jar becomes `ctcl-<v>+26.1-26.3-Fabric-Quilt.jar`. If Quilt's range
      differs from Fabric's, the grouping rule applies. If anything can't pass on Quilt, stop and ask.
      **Result:** Quilt 0.30.1 on all five pins, each against the 26.1 release jar:
      - the server passes 14/14, and a second run passes the restart restore;
      - vanilla loads the Quilt-saved world cleanly (the uninstall check);
      - the client gametest passes.

      The Fabric suite is green on all five pins with the renamed jar `ctcl-1.0.0+26.1-26.3-Fabric-Quilt.jar`.
      Negative checks:
      - `checkQuiltFloor` blocks `>=0.19.5` and non-`>=` floors;
      - the naming blocks a Quilt-only pin and partial Quilt coverage.

**Stage 12 design (written before the code, 2026-09-29):**
- *Quilt pins:* `quilt_loader.<mc>` in `gradle.properties`, plus `quilt_installer_version` (0.15.1, the stable
  installer). Quilt Loader and its launcher metadata (`quilt-loader-<v>.json`) come from Quilt's maven, which is
  limited to `org.quiltmc` by a content filter.
- *Floor guard:* `checkQuiltFloor`, hooked to `check`, reads `quilt_loader.provides[fabricloader].version` out of every
  pinned Quilt Loader jar. It fails if `fabric.mod.json`'s `fabricloader` floor is above it.
- *`-Pctcl_loader=quilt`:* switches both prod tasks to Quilt, with run dirs of their own (`run/<task>/quilt-<mc>`).
  - Client: Loom's `ClientProductionRunTask` with Fabric Loader and its libraries swapped for Quilt Loader and the
    `common`+`client` libraries from its metadata, main class `org.quiltmc...KnotClient`, and mods through
    `-Dloader.addMods` (Quilt ignores `fabric.addMods`). This fails closed unless exactly the expected Fabric Loader
    jars were removed.
  - Server: the real Quilt install. `quilt-installer install server <mc> <loader> --download-server` into the run dir,
    then `java -jar quilt-server-launch.jar nogui` with `-Dloader.addMods` and `-Dfabric-api.gametest`.
  - A missing `quilt_loader.<mc>` pin under `-Pctcl_loader=quilt` is a build error, not a silent Fabric run.
- *Restart restore:* after any prod server run whose world already existed, the task asserts `Restored N crafting table
  loader(s)` with N ≥ 1 in `minecraft:the_nether` and `minecraft:the_end`. Only `DimensionCases` puts tables there.
  The Quilt check runs the server twice.
- *Uninstall on a Quilt world:* `uninstall_check.py --world <dir>` reads the Quilt prod server's world instead of the
  dev gametest world.
- *Jar name (12.3):* the loaders suffix is computed from the pins: `Fabric`, plus `-Quilt` when every Fabric pin has a
  Quilt pin. Partial Quilt coverage fails the build, because a separate Quilt jar isn't built. The suffix goes on as the
  jar's classifier, so the name is `ctcl-<v>+<range>-Fabric-Quilt.jar`. CI finds the one jar that isn't gametest or
  sources, rather than assuming the name.

### Stage 13 — NeoForge  → five commits (13.1, 13.4, 13.6, 13.7 on `band/26.1`; 13.8 on `main`) ✅ 2026-09-29
Tier 2. Plan written 2026-09-29, before any code. The owner's answers (6–9 below) widen the old 13.3–13.5: every proof is
automated, and the server suite runs in a real NeoForge install.
**Pre-stage tips:** `band/26.1` `76451bd` and `main` `26b995b`.

**Facts behind the design (checked 2026-09-29):**
- The newest stable NeoForge builds are `26.1.2.112` and `26.2.0.88`, and 26.3 is still beta-only. ModDevGradle is `2.0.148`.
- NeoForge fires `RegisterGameTestsEvent` only outside production (`GameTestHooks.isGametestEnabled`). FML
  decides it's in production when the `SharedConstants` on the classpath has no FML reference, which is true of every real install.
  FML 11 still ships `net.neoforged.fml.startup.GameTestServer`, which starts vanilla's `GameTestMainUtil` with mods loaded.
- `GameTestMainUtil` resets its world dir each run, so a restart restore needs a normal server run afterwards.
- There are **13** CTCL server cases (6 loader, 6 networking, 1 dimension). Fabric's run reports 14 tests in
  `minecraft:default`, so one of the 14 isn't ours. 13.4 names it and corrects the records that say "14 CTCL tests".

- [x] 13.1 Shared prep → commit `build: mixin configs at JAVA_21; pack.mcmeta`
      - `ctcl.mixins.json` and `ctcl.client.mixins.json` get `compatibilityLevel` `JAVA_21` + `minVersion` `0.8`.
      - `common/src/main/resources/pack.mcmeta`: `min_format` 84, `max_format` 121 (the spike's values).
      - Fabric base suite: unit tests, server gametests, client gametest. The full sweep is 13.8.
- [x] 13.2 Build
      - Root `build.gradle`: `net.neoforged.moddev` `apply false`, next to Loom.
      - `gradle.properties`: `moddev_version=2.0.148`, `neoforge.26.1.2=26.1.2.112`, and `neoforge.26.2=26.2.0.88`.
      - `settings.gradle`: include `neoforge` unless an explicit `-Pminecraft_version` has no `neoforge.<mc>` pin. That
        decision is logged. With no `-P`, NeoForge builds its release jar on its own base, the lowest NeoForge pin.
      - `neoforge/build.gradle`: ModDevGradle, with `common/src/{main,client}` as `main` srcDirs, like `fabric/`.
        The label comes from the pins: the lowest pin exactly, then the highest release line (`1.0.0+26.1.2-26.2`).
        The jar classifier is `NeoForge`, so the jar is `ctcl-1.0.0+26.1.2-26.2-NeoForge.jar`.
        A gap between release lines fails the build, because it would need a jar of its own.
      - `neoforge.mods.toml`, generated from the pins:
        - `minecraft` `[26.1.2,26.3)`;
        - `neoforge` `[26.1.2.112,26.2),[26.2.0.88,26.3)`: the tested pins and newer on each line (owner answer 9);
        - `ctcl`, ARR, and both configs as `[[mixins]]`.
- [x] 13.3 Wiring, in `net.wulfnet.ctcl.neoforge.CtclNeoForge` (`@Mod`) and `...neoforge.client.CtclNeoForgeClient` (`@Mod`, `dist = CLIENT`)
      - Server:
        - `DeferredRegister` for `TICKET_TYPE`;
        - `LevelEvent.Load`/`Unload`, for `ServerLevel` only;
        - `LevelTickEvent.Post`;
        - `ServerStoppedEvent`.
      - Networking: `RegisterPayloadHandlersEvent`, with `optional()` for both payloads. The C2S handler goes to
        `CtclNetworking.onCycle` on the server thread. `PayloadSender.canSend` asks the connection whether it has the channel.
      - Client:
        - the S2C handler goes to `LoaderButtons.onSize`;
        - `ScreenEvent.Init.Post` attaches the button only if the server has the channel;
        - `ScreenEvent.Render.Pre` refreshes it;
        - logging out clears the sizes.
- [x] 13.4 Dev gametests → commit `feat: neoforge support` (13.2–13.4)
      **Result (2026-09-29):** `19ebd6d` + `dda46a2`. 13/13 CTCL on 26.1.2 and 26.2 (dev). The client wiring is compiled but
      not yet exercised; 13.7 proves it. Mutations: a wrong assertion, a missing instance JSON, and doctored reports all fail.
      - Test registration is data-driven vanilla, the same in dev and production, and doesn't touch NeoForge internals:
        - the gametest mod `ctcl_gametest` (NeoForge ids have no `-`) registers each case as a `TEST_FUNCTION` through `DeferredRegister`;
        - test instances are JSON in `common/src/gametest/resources/data/ctcl_gametest/test_instance/`, with `max_ticks` from
          the Fabric annotations, plus an empty structure. Forge reuses them in Stage 14.
      - Drift guard: at server start, the gametest mod fails the run if any registered function has no instance, or any
        instance has no function. Every harness asserts that exactly 13 CTCL tests ran.
      - The ModDevGradle `gameTestServer` run is hooked to `check`, like Fabric's `runGameTest`. It must pass 13/13 on both pins.
      - A `gametestJar` feeds the production harness.
      - Fallback, only if the datapack instances don't load: a test-only mixin that enables `RegisterGameTestsEvent`,
        shipped in the gametest jar and never in the release jar. If both fail, stop and ask.
- [x] 13.5 Production server, a real install
      - `installNeoForgeServer`: the NeoForge installer (`--install-server`) into `build/run/prodServerGameTest/neoforge-<mc>`.
      - `prodServerGameTest`:
        - launches FML's `GameTestServer` main with the installer's own args file;
        - the release jar and the gametest jar go in `mods/`, and the task fails closed unless there is exactly one of each;
        - it passes on exit 0 **and** a log line with 13 CTCL tests passed.
      - `prodServerRestart`: a normal server (`net.neoforged.fml.startup.Server`) on the gametest world. It sends `stop` on
        stdin after `Done`, and the existing restore rule (`Restored N>=1` in the Nether and the End) must hold.
      - `checkReleaseJarBytes` for NeoForge: only `META-INF/neoforge.mods.toml` and the manifest may differ.
- [x] 13.6 Worlds → commit `test: neoforge production harness` (13.5–13.6)
      - Cross-loader move on 26.1.2 and 26.2: Fabric's prod world goes into a NeoForge restart run (`-Pctcl_world=<dir>`),
        which must restore. That world goes back into a Fabric prod server run, which must restore too.
      - `uninstall_check.py --world` on a world saved by NeoForge.
      **Result (2026-09-29):** `b3651a0` + `89fb730`.
      - The release jar built on 26.1.2 is byte-identical to the 26.2 compile.
      - 13/13 in a real install, then the restart restore, on NeoForge 26.1.2.112 and 26.2.0.88.
      - Fabric → NeoForge → Fabric restores on both pins. The uninstall check passes on NeoForge worlds.
      - A second production gate turned up: NeoForge ticks the test runner only outside production. The gametest mod ticks it
        when NeoForge won't, and the mixin fallback wasn't needed. The prod task is `prodServerGameTest`, and the restore is `prodServerRestore`.
- [x] 13.7 Client smoke and vanilla guest → commit `test: neoforge client smoke and vanilla guest`
      - `tools/mclaunch.py` + `tools/test_mclaunch.py`: a minimal launcher that runs a client from installed version JSON.
        It handles OS rules, the library classpath, `${}` args, and an asset cache. It serves the NeoForge client (installer
        `--install-client`), the vanilla guest, and the Forge client in Stage 14. No existing tool runs a NeoForge client without a launcher.
      - Client driver: `common/src/gametest` (client part), wired to the NeoForge gametest mod's client tick. It runs only
        with `-Dctcl.clientSmoke=<result file>`. The flow is:
        1. create a superflat world with the menu blur off;
        2. place a table and open its menu;
        3. wait for the button, then click it;
        4. check that the server size is 1 and the button reads `1`;
        5. write PASS and quit.

        A step timeout writes FAIL. `prodClientSmoke` fails unless the file says PASS, so a missing file is a failure.
      - `prodVanillaGuest`: the NeoForge prod server runs as a normal server with `online-mode=false`, and a vanilla client
        joins through `mclaunch.py --quickPlayMultiplayer`. It passes when the server logs `<name> joined the game`. Then it
        stops the server and ends the client process, only by the PID it started.
      **Result (2026-09-29):** `ec6e581`, plus `72c30c0` (`TESTING.md`). With the release jar built on 26.1.2, on 26.1.2.112 and 26.2.0.88:
      - `prodClientSmoke` passes;
      - `prodVanillaGuest` passes: a real vanilla client joins.

      Mutations:
      - without `optional()`, NeoForge refuses the vanilla client and the check fails in seconds;
      - with the click ignored, the smoke test never passes.

      `tools/test_mclaunch.py` has 18 tests, and the whole tool suite is 49. The driver tracks screens through loader events,
      because `Minecraft.screen` and `getOverlay()` changed after 26.1.2 (`gotchas.md`).
- [x] 13.8 Sweep and record → commit `docs(memory): stage 13 done` on `main`
      **Result (2026-09-29):** the whole sweep is green on `band/26.1` `ebe0517`, in 64 min. Every phase exited 0, and every
      log has its pass line; no test task was up-to-date. The first rerun's "base exit 1" was the session ending, which
      killed the Gradle daemon (`gotchas.md`).
      - Base: clean, `build` (Fabric 14/14 + NeoForge 13/13 + the client gametest), the uninstall check, 49 tool tests,
        Fabric prod, and the restart restore.
      - Fabric on 26.1.1, 26.1.2, 26.2, and 26.3: `checkReleaseJarBytes`, `build` + client gametest, the uninstall
        check, and prod server + client with the release jars.
      - Quilt on all 5 pins: prod server, the restart restore, the uninstall check, and the prod client.
      - NeoForge on 26.1.2 and 26.2: `checkReleaseJarBytes`, `prodServerGameTest`, `prodClientSmoke`,
        `prodVanillaGuest`, the uninstall check, and Fabric → NeoForge → Fabric.
      - `TESTING.md` "NeoForge" landed in `72c30c0`.

      **Timings (local, warm Gradle caches) for the 15.1 estimate:**
      - Base phase: 10 min. `build` + `:fabric:runClientGameTest` alone is 5.2 min.
      - Fabric verify, per pin: 5.3–6.4 min. `build` + client gametest is 2.4–3.7 min of that.
      - Quilt, per pin: 3.3–3.4 min. The first prod server is 74–81 s, because it includes the Quilt install.
      - NeoForge, per pin: 6.6–6.9 min. The four prod tasks are 3.6–3.7 min, and the cross-loader move is 2.5 min.

**Not doing in Stage 13:**
- CI jobs (15.1) and release changes (15.2).
- Forge, and merging the NeoForge jar with Forge's (Stage 14).
- NeoForge on 26.1, 26.1.1, or 26.3, which are beta-only.
- A NeoForge dev-client run config beyond what debugging needs.
- Any change to `band/1.21.11`.

**Blast radius (Stage 13):**
- 13.1 changes the Fabric jar's bytes (two JSON files and a new `pack.mcmeta`). The 13.8 sweep covers it.
- A wrong `settings.gradle` include could silently drop NeoForge from a build. The decision is logged, and 15.1 asserts the jar exists.
- Installers and servers write only inside `neoforge/build/run/<task>/<leaf>`. Any reset asserts its path is inside that root.
- The harness kills only processes it started, by PID. Nothing touches git history or the remote.

**Rollback (Stage 13, before the push):** `git reset --keep 76451bd` on `band/26.1` and `git reset --keep 26b995b` on `main`.
Both are gated through `guard-destructive`, and after a push it's `git revert` only.

### Stage 14 — Forge  → six commits (14.1, 14.4, 14.6, 14.7, 14.8 on `band/26.1`; 14.9 on `main`)
Tier 2. Plan written 2026-09-29, before any code. Unblocked (10.3). Pins: `forge.26.1.2=64.1.0` and `forge.26.2=65.1.0`,
the recommended builds. Other versions are skipped.
**Pre-stage tips:** `band/26.1` `ec1a5c2` and `main` `27e4078`.
**Owner answer (2026-09-29):** Forge meets the same bar as NeoForge (answers 6–9):
- server gametests in a real Forge install;
- the automated client smoke;
- an automated vanilla guest;
- Fabric → Forge → Fabric;
- `mods.toml` accepts the tested pins and newer on each line.

**Facts behind the design (checked 2026-09-29):**
- Promotions haven't moved: `-recommended` is still 64.1.0 (26.1.2) and 65.1.0 (26.2). `-latest` is 64.1.3 and 65.1.3,
  which are unpromoted. 26.1, 26.1.1, and 26.3 (`66.0.8`) are latest-only, so Forge skips them.
- The 26.2 MDK:
  - ForgeGradle `[7.0.17,8)`, newest 7.0.40. FG7 doesn't apply `java`, so the project applies it itself.
  - Repositories: `minecraft.mavenizer(it)`, `fg.forgeMaven`, and `fg.minecraftLibsMaven`. The dependency is
    `minecraft.dependency('net.minecraftforge:forge:<mc>-<v>')`.
  - EventBus 7: listeners go through `XEvent.BUS.addListener`, and the mod constructor takes `FMLJavaModLoadingContext`
    (`getModBusGroup()`). The `mods.toml` `loaderVersion` is the Forge major.
- FG7, Loom, and ModDevGradle in one build: **they coexist.** A scratch copy of `band/26.1` with FG7 7.0.40 `apply false`
  at the root compiled `:fabric`, `:neoforge`, and `:forge` together, under the root's `-Xlint:all -Werror`. That took
  5m43s, most of it Mavenizer's first run: it decompiles, patches, and recompiles Minecraft into the Gradle user home.
- Gametests:
  - `ForgeGameTestHooks.isGametestEnabled()` is false in production.
  - The production launcher has only `forge_client` and `forge_server`. `server_gametest` exists only in the dev and
    userdev handlers.
  - ~~Forge ticks `GameTestTicker` and registers `/test` with no production gate.~~ **Wrong (re-checked 2026-09-29 in the
    65.1.0 patches):** `MinecraftServer.tickChildren` ticks `GameTestTicker` only when `isGametestEnabled()`, the same gate
    NeoForge has. So the Forge gametest mod ticks it itself when that gate is off, as 13.5 did for NeoForge.
    Vanilla ships `GameTestRunner`, `GlobalTestReporter`, and `JUnitLikeTestReporter`.
  - Dev: the userdev `gameTestServer` run launches vanilla's `net.minecraft.gametest.Main`. The gate is on in dev only
    through `-Dforge.gameTestServer=true` (or `forge.enableGameTest`), so the run sets it.
- Networking: `ChannelBuilder.named(..).optional().payloadChannel()`.
- The installer has headless `--installServer <dir>` and `--installClient <dir>`. The server launches with
  `@user_jvm_args.txt @libraries/net/minecraftforge/forge/<mc>-<v>/win_args.txt`.

**Design:**
- **Owner answer 10:** the real-install server cases run in a **normal Forge server**. The test-only gametest mod does
  the work, and only when `-Dctcl.gameTestReport=<absolute path>` is set:
  1. at server start, it runs every `ctcl_gametest` instance through vanilla's runner;
  2. it writes the JUnit report through `GlobalTestReporter`;
  3. it stops the server.

  The run passes on exit 0 plus `checkGameTestReport`: exactly the 13 cases, all passed.
- **Owner answer 11:** the closing sweep covers every loader on every pin.
- **Shared harness.** The loader-neutral helpers in `neoforge/build.gradle` move to `gradle/harness.gradle`, so Forge
  doesn't copy about 300 lines:
  - the report check, process start and await, `runServer`, `checkRestored`, `stageMods`, and `prepareClient`;
  - the installer task.

  The label and range logic moves to `gradle/pins.gradle`.
- **One jar per range** (`decisions.md` 2026-09-29). The root task `releaseJars` groups the loader projects by label and
  writes `build/release/ctcl-<v>+<label>-<Loaders>.jar`.
  - A one-loader group is a straight copy, so the Fabric jar keeps its bytes.
  - A merged group fails on any shared path whose bytes differ, and on a conflicting manifest attribute.
  - The task exists only on a release build, meaning one with no `-Pminecraft_version`.
- Each harness picks the release jar whose name lists its loader. `checkReleaseJarBytes` ignores the entries owned by
  other loaders (their metadata and `net/wulfnet/ctcl/<loader>/`), and it fails on any other extra entry.
- `displayTest` stays at its default, as on NeoForge.

- [x] 14.1 Shared harness → commit `refactor: shared loader harness`
      **Result (2026-09-29):** `1b913eb`. `neoforge/build.gradle` went from 559 to about 175 lines. `loaderHarness(spec)` in
      `gradle/harness.gradle` registers the shared tasks and returns the helpers that `prodServerGameTest` needs.
      `loaderPlan(prefix, name, loaderBound)` in `gradle/pins.gradle` takes the loader's upper bound per line: NeoForge
      passes the next Minecraft line, and Forge will pass its next major.
      - On the base and on 26.2: the jars' entries, the lifecycle line, and `:neoforge:tasks --all` match the baseline.
      - On 26.1.2: dev 13/13, prod 13/13 plus the restore, `prodServerRestore`, `prodClientSmoke` PASS,
        `prodVanillaGuest`, `checkReleaseJarBytes` against the 26.2 compile, and `checkGameTestReport`.
      - Negative checks still fail: a gap pin (`-Pneoforge.26.4`), a report missing one case, and a jar with one altered class.
      - `gradle/harness.gradle`, and the label and range logic in `gradle/pins.gradle`, both taken out of
        `neoforge/build.gradle` with no behaviour change.
      - Scope (2026-09-29): the shared **tasks** move too, not just the helpers, parameterized by loader: the installers,
        `checkGameTestReport`, `prodServerRestore`, `prodClientSmoke`, `prodVanillaGuest`, and `checkReleaseJarBytes`.
        Helpers alone would still leave Forge copying about 150 lines of task bodies. Only `prodServerGameTest` stays
        per-loader: NeoForge swaps in FML's `GameTestServer` main, and Forge runs a normal server.
      - Proof:
        - `:neoforge:runGameTestServer` passes 13/13 and `:neoforge:prodServerGameTest` passes on 26.1.2;
        - the jars' entries on the base and on 26.2, the lifecycle line, and `:neoforge:tasks --all` all match the
          pre-refactor baseline.

        The full NeoForge rerun is in 14.8–14.9.
- [x] 14.2 Build
      - Root `build.gradle`: `net.minecraftforge.gradle` `apply false`.
      - `gradle.properties`: `forgegradle_version=7.0.40`, `forge.26.1.2=64.1.0`, and `forge.26.2=65.1.0`.
      - `settings.gradle`: `forge` is included by NeoForge's rule, with one loop for both.
      - `forge/build.gradle`:
        - FG7 plus `java`, with `common/src/{main,client}` as `main` srcDirs;
        - the label from the pins, the same `1.0.0+26.1.2-26.2`, and the classifier `Forge`;
        - the manifest's `MixinConfigs: ctcl.mixins.json,ctcl.client.mixins.json`.
      - `META-INF/mods.toml`, generated from the pins:
        - `loaderVersion` `[64,)`;
        - `minecraft` `[26.1.2,26.3)`;
        - `forge` `[64.1.0,65),[65.1.0,66)`: each pin and newer on its line, where Forge's major is its line.
- [x] 14.3 Wiring, in `net.wulfnet.ctcl.forge.CtclForge` (`@Mod`) and `...forge.client.CtclForgeClient`
      `CtclForgeClient` is registered only on `Dist.CLIENT`, so a server never loads a client class.
      - Server:
        - `DeferredRegister` for `TICKET_TYPE`;
        - `LevelEvent.Load`/`Unload`, for `ServerLevel` only;
        - `TickEvent.LevelTickEvent.Post`;
        - `ServerStoppedEvent`.
      - Networking: `ChannelBuilder...optional().payloadChannel()` with both payloads. The C2S handler goes to
        `CtclNetworking.onCycle` on the server thread. `PayloadSender.canSend` asks whether the connection has the channel.
      - Client:
        - the S2C handler goes to `LoaderButtons.onSize`;
        - `ScreenEvent.Init.Post` attaches the button only if the server has the channel;
        - `ScreenEvent.Render.Pre` refreshes it;
        - `ClientPlayerNetworkEvent.LoggingOut` clears the sizes.
- [x] 14.4 Dev gametests → commit `feat: forge support` (14.2–14.4)
      - `forge/src/gametest`: the `ctcl_gametest` mod registers each case as a `TEST_FUNCTION` through
        `DeferredRegister`. It uses NeoForge's instance JSON and the same drift guard.
      - FG7's `gameTestServer` run, with `--report`, is hooked to `check`. It runs after NeoForge's, so two game JVMs
        never run at once. It must pass 13/13 on 26.1.2 and on 26.2.
      - Mutation: a wrong assertion fails the run.
      **Result (2026-09-29, 14.2–14.4):**
      - The jar is `ctcl-1.0.0+26.1.2-26.2-Forge.jar`. Its `mods.toml` declares `[64,)`, forge `[64.1.0,65),[65.1.0,66)`, and
        minecraft `[26.1.2,26.3)`, and its manifest has `MixinConfigs`. It compiles under `-Xlint:all -Werror`.
      - The run task is `runGametestGameTestServer`, because FG7 makes one run task per source set. It passes 13/13 on
        26.1.2 and on 26.2. Flipping one assertion (`breaking_the_table_releases_it_immediately`) fails the run with that
        case's message.
      - A version Forge skips (`-Pminecraft_version=26.3`) logs "Forge skips Minecraft 26.3" and leaves `:forge` out.
      - It took four fixes to get there: `merge-source-sets`, a gametest `pack.mcmeta`, `--mixin.config` in dev, and the lazy
        task match (`gotchas.md` "Forge dev gametest traps").
      - Design changes: `@Mod` has no dist on Forge, so `CtclForge` picks the client handler by `FMLEnvironment.dist`, and
        both payloads share one channel (`decisions.md` "Stage 14.2–14.4"). The harness is wired now, and its prod tasks
        are proven in 14.5–14.7.
      - `CtclForgeClient` (screen events, the S2C handler) has no runtime proof until the client smoke test in 14.7.
      - Full `./gradlew build` on the base is green (3m32s): Fabric, NeoForge, and Forge each pass 13/13. The first try went red
        on the known stale Fabric world (`gotchas.md` "Skipping `clean`"). `:fabric:clean` fixed it, with the owner's OK.
- [x] 14.5 Production server
      - `installForgeServer`: the installer's `--installServer` into `forge/build/run/prodServerGameTest/<mc>`.
      - `prodServerGameTest`:
        - a normal server on a flat world, with `-Dctcl.gameTestReport`;
        - the release jar and `*-Forge-gametest.jar` go in `mods/`, and the task fails closed unless there is exactly one of each;
        - it passes on exit 0 and 13/13 in the report;
        - then the server restarts on that world, and the restore must hold in the Nether and the End.
      - The runner, `ProdGameTests`, lives in `common/src/gametest` and uses only vanilla API.
      - The Forge gametest mod ticks `GameTestTicker` on `ServerTickEvent.Post` exactly when
        `ForgeGameTestHooks.isGametestEnabled()` is false (the facts above), so it can't double-tick in dev.
      - `checkReleaseJarBytes` for Forge.
      **Result (2026-09-30):** in `04fc2cb` `test: forge production harness`, with 14.6.
      - On 26.1.2 (Forge 64.1.0) and 26.2 (65.1.0), with base-built jars:
        - `checkReleaseJarBytes` passes (39 entries);
        - `prodServerGameTest` runs 13/13 in about 12 s;
        - the restart restores 3 loaders in the overworld and 1 each in the Nether and the End.

        Each pin takes about 2 min, including the Forge installer's first run.
      - **Design changes** (`decisions.md` "Stage 14.5"):
        - `ProdGameTests` lives in `forge/src/gametest`, not `common/`, because 26.3 changed the runner's API;
        - each run gets a new world, `worlds/gametest-<stamp>`;
        - `server.properties` is written each run: flat, the layers spelled out, `pause-when-empty-seconds=0`;
        - a start that fails halts the server with no report;
        - the installer resolves through the named `prodInstaller` configuration.
      - **Mutations:**
        - A flipped assertion in `breaking_the_table_releases_it_immediately` fails the run with that case's message.
        - A non-flat world fails in 45 s with "need a flat overworld" and no report.
      - Gametest sources compile on the base and on 26.2 for all three loaders, and on 26.3 for Fabric. The base `build`
        is green (3m24s).
      - Traps: Mavenizer hid the installer, and 26.3 changed the runner API (`gotchas.md` "Forge production harness traps").
- [x] 14.6 Worlds → commit `test: forge production harness` (14.5–14.6)
      - Fabric → Forge → Fabric on both pins, through `prodServerRestore -Pctcl_world`.
      - `uninstall_check.py --world` on a world saved by Forge.
      **Result (2026-09-30):** On both pins:
      - `uninstall_check.py --world` passes on the world the Forge gametests saved.
      - The Fabric prod world goes to Forge through `:forge:prodServerRestore`, then back to Fabric through
        `:fabric:prodServerGameTest -Pctcl_world`. Each hop restores the Nether and the End.

      The first uninstall run failed on `Missing data pack mod:ctcl|forge|ctcl_gametest`. That's Forge's per-mod data
      packs, dropped by vanilla on the first load, and the second load was clean. The check now expects them, as it
      does Fabric's and NeoForge's. Per pin: about 2 min for the prod gametests, 40 s for the uninstall check, and 3.5 min
      for the world moves. The script is `forge-worlds.sh` in this session's scratchpad
      (the session's scratchpad); 14.9 folds it into the sweep.
- [x] 14.7 Client smoke and vanilla guest → commit `test: forge client smoke and vanilla guest`
      - `installForgeClient` runs `--installClient`, then `tools/mclaunch.py` prepares the Forge client. Anything new
        that Forge's version JSON needs goes into mclaunch with a test.
      - The Forge gametest mod feeds `ClientSmoke` its client ticks and screen events. `prodClientSmoke` passes only on PASS.
      - `prodVanillaGuest`: a vanilla client joins the plain Forge server.
      - Mutations: without `optional()`, the guest is refused; with the click ignored, the smoke test never passes.
      **Result (2026-09-30):** `5b1c86a`. With base-built jars, on 26.1.2 (Forge 64.1.0) and 26.2 (65.1.0):
      - `prodClientSmoke` passes (82 s and 108 s, including the first client install). That's also the first runtime proof
        of `CtclForgeClient`: the screen events, the S2C handler, and the C2S click.
      - `prodVanillaGuest` passes: a real vanilla client joins (96 s and 78 s).
      - `CtclForgeClientSmoke` is registered only when `FMLEnvironment.dist == CLIENT`, so the dev gametest server never
        loads it. The base `build` is green (3m08s), with Forge and NeoForge each passing 13/13.

      Two fixes (`gotchas.md` "Forge client harness traps"):
      - Forge's installer writes the vanilla jar but not its JSON. `mclaunch.py` now fetches a missing vanilla JSON from
        Mojang's manifest, sha1-checked. There are four new tests, and the tool suite is 52.
      - Forge refuses a vanilla client before it has a name, so the guest check missed the refusal and failed only on
        its timeout. The harness spec now takes `guestRefusal`. NeoForge's guest still passes on 26.1.2.

      Mutations:
      - without `optional()`, the guest is refused in 92 s, and the error quotes Forge's line;
      - with the click dropped, the smoke test fails with `timed out in WAIT_FOR_CLICK`, and the button reads `0`.
- [x] 14.8 One jar → commit `build: one jar per loader range`
      - The root `releaseJars` task, as designed above. The harnesses and both `checkReleaseJarBytes` read the merged jar.
      - Proof:
        - one prod server run for each of Forge and NeoForge, on the merged `ctcl-1.0.0+26.1.2-26.2-Forge-NeoForge.jar`;
        - the Fabric jar in `build/release` is byte-identical to the one in `fabric/build/libs`.

        The full runs are in 14.9.
      - Negative checks, each of which must fail: a shared class that differs, a conflicting manifest attribute, and an
        extra entry outside another loader's owned paths.
      **Plan (2026-09-30):** Checked first: the base Forge and NeoForge jars differ only in their own toml, their own
      `net/wulfnet/ctcl/<loader>/`, and the manifest (Forge's `MixinConfigs`; NeoForge's is empty), so the merge has no
      conflict to resolve.
      - New `gradle/release.gradle`, applied by the root, `fabric/`, and `gradle/harness.gradle`:
        - `loaderOrder` (Fabric, Quilt, Forge, NeoForge: the owner's example name) and `loaderOwned` (each loader's
          metadata file and `net/wulfnet/ctcl/<loader>/`);
        - `jarLoaders(name)`: the loader tokens after the version in a jar's name (`...-Forge-NeoForge.jar` → both);
        - `mergeReleaseJars(jars, out)`: one jar is a straight copy; several are merged into a temp file, then moved.
          It fails on a shared path with different bytes, a conflicting manifest attribute, or a manifest section;
        - `releaseJarDiff(release, built, ignored)`: the entry comparison both `checkReleaseJarBytes` share.
      - Each loader project publishes `ext.releaseJar = [label, loaders, jar]` on a release build. `loaderPlan` returns
        `isReleaseBuild`.
      - Root `releaseJars` (registered only with no `-Pminecraft_version`): it groups by label and writes
        `build/release/ctcl-<v>+<label>-<Loaders>.jar`. **It never deletes:** any other jar already in `build/release` fails
        the task. It depends on `checkReleaseJarMerge`.
      - Root `checkReleaseJarMerge`: a self-test on tiny synthetic jars. A differing shared class and a conflicting
        manifest attribute must both fail with no output written; a clean merge must pass; `releaseJarDiff` must flag an
        extra entry outside the owned paths.
      - Harness and Fabric pick prod jars by `jarLoaders`, not by a name suffix. Fabric's gametest jar becomes
        `*-Fabric-Quilt-gametest.jar`, so a mixed directory has one gametest jar per loader, and `ci.yml`'s copy line follows.
      - `checkReleaseJarBytes` also ignores the owned paths of the **other loaders the release jar's name lists**, and
        nothing else.
      - Not doing: hooking `releaseJars` into `build` (the root has no `base` plugin, and a root `clean` would wipe the
        pre-split leftovers in `build/`). CI calls it in 15.1.
      **Result (2026-09-30):** `64b3364`. `releaseJars` writes `ctcl-1.0.0+26.1-26.3-Fabric-Quilt.jar` (byte-identical to
      `fabric/build/libs`, `cmp`) and `ctcl-1.0.0+26.1.2-26.2-Forge-NeoForge.jar` (59 entries, `MixinConfigs` kept). A rerun
      gives the same sha1. From one mixed directory (both release jars plus all three gametest jars):
      - `checkReleaseJarBytes` passes for Fabric, Forge, and NeoForge on 26.1.2 and on 26.2;
      - `prodServerGameTest` on 26.1.2: Forge 13/13 plus the restore (twice), NeoForge 13/13 plus the restore, and
        Fabric plus its restore, all on the shipped jars;
      - the base `build` is green (3m22s): Fabric 14/14, NeoForge 13/13, Forge 13/13.

      Negative checks, each refused:
      - `checkReleaseJarMerge`: a shared class that differs and a conflicting `MixinConfigs`, with no output written.
        Disabling the byte comparison turns the self-test red ("the merge accepted it");
      - `:forge:checkReleaseJarBytes` on the merged jar plus `net/wulfnet/ctcl/Stray.class`, and plus
        `net/wulfnet/ctcl/fabric/Sneaky.class` (a loader the name doesn't list);
      - a stale `ctcl-0.9.0+26.1-Fabric.jar` in `build/release`: `releaseJars` fails and the real jars keep their sha1.

      Found on the way (`gotchas.md` "Stage 14.8"): Gradle called `releaseJars` up to date with a stranger jar in its
      output dir, so the task now always runs. The first Forge run died of native OOM (the machine's commit limit). The
      `-release` run dirs still hold the 14.7 per-loader jars, and `stageMods` refuses them. The two
      `prodServerGameTest/26.1.2-release` ones were moved to this session's scratchpad (`stale-mods/`); 14.9 must move the rest
      (`prodServer/*-release`, `prodClient/*/game-smoke`, `prodServerGameTest/26.2-release`, both loaders).
- [x] 14.9 Sweep and record → commit `docs(memory): stage 14 done` on `main`
      - Everything (answer 11): the 13.8 phases, all against `build/release` jars, plus Forge on 26.1.2 and 26.2:
        - `checkReleaseJarBytes`, `prodServerGameTest`, `prodClientSmoke`, and `prodVanillaGuest`;
        - the uninstall check;
        - Fabric → Forge → Fabric.
      - Per-loader timings for 15.1, and `TESTING.md` "Forge".
      **Plan (2026-09-30):** `sweep.sh` in this session's scratchpad, run from `band/26.1` `2bb10f6`, in two background
      halves so each fits the 2 h limit and dies with this session only if the session ends:
      - **Part 1:**
        - Base: `clean` (owner answer 12: it clears the 14.7 jars in the `-release` run dirs and re-proves fresh
          installs), `build :fabric:runClientGameTest` (Fabric, NeoForge, and Forge dev), the uninstall check, the tool
          tests, and Fabric prod plus the restart restore.
        - Staging: the three `gametestJar` tasks and `releaseJars`, then copy the two release jars and three gametest jars to
          `release-all/`, failing unless there is exactly one of each.
        - Fabric on 26.1.1, 26.1.2, 26.2, and 26.3: the same four steps as 13.8, against `release-all/`.
      - **Part 2:**
        - Quilt on all 5 pins, as in 13.8.
        - NeoForge, then Forge, on 26.1.2 and 26.2: `checkReleaseJarBytes`, `prodServerGameTest`, `prodClientSmoke`,
          `prodVanillaGuest`, the uninstall check on the world the loader saved, and Fabric → loader → Fabric.
      - Every task is project-qualified. Each phase logs its own `TIME` lines. Before each half: free commit ≥ 12 GB and no
        stray Gradle daemons (owner answer 13).
      - Passes only if every phase exits 0 with its pass line and no test task is up to date. A log that just stops means
        read `hs_err_pid*.log` first.
      **Result (2026-09-30):** the whole sweep is green on `band/26.1` `2bb10f6`, using the shipped jars in one mixed
      directory (`release-all/`: 2 release jars and 3 gametest jars; the Forge-NeoForge jar's sha1 is `8c8a0293`). Every phase
      exits 0 with its pass line, and no test task was up to date. It took about 80 min. `TESTING.md` "Forge" and the timings
      are in `40bcb29` on `band/26.1`.
      - Base: `clean`, `build` (Fabric 14/14, NeoForge 13/13, Forge 13/13, the client gametest), the uninstall check, 52 tool
        tests, and Fabric prod plus the restart restore.
      - Fabric on 26.1.1, 26.1.2, 26.2, and 26.3: `checkReleaseJarBytes`, `build` + the client gametest (on 26.1.2 and 26.2 that
        includes NeoForge and Forge dev 13/13), the uninstall check, and prod server + client.
      - Quilt on all 5 pins: prod server, the restart restore, the uninstall check, and the prod client. **26.3's client died of
        the known native crash** (`0xC0000005` in the first resource reload, no `hs_err`; `gotchas.md` 2026-09-29) and passed
        on a single rerun of that task. It's the second time this has happened.
      - NeoForge and Forge on 26.1.2 and 26.2: `checkReleaseJarBytes` (each ignoring the other's entries), 13/13 plus the
        restore, the client smoke PASS, the vanilla guest joins, the uninstall check, and Fabric → loader → Fabric.

      **Timings for 15.1 (local, warm caches):**
      - Base: 8.2 min.
      - Fabric verify, per pin: 4.7–5.0 min on 26.1.1 and 26.3, and 7.0 min on 26.1.2 and 26.2 (NeoForge and Forge dev run in `build`).
      - Quilt: 3.4 min per pin.
      - NeoForge: 6.7 min per pin.
      - Forge: 7.2–7.3 min per pin, 4.7 min of it the four prod tasks. A cold CI runner adds Mavenizer (~5 min) and both installers.

      Two false starts, neither a test failure: a VS Code window reload ended the session and killed part 1, then the Java
      extension locked a NeoForge jar during `clean` (`gotchas.md` "VS Code's Java and Gradle extensions").

**Not doing in Stage 14:**
- CI (15.1), release tooling (15.2), and `README.md`/`BANDS.md` (15.3). CI keeps reading `fabric/build/libs` until 15.1.
- Forge `-latest` builds, and Forge on 26.1, 26.1.1, or 26.3.
- Forge's `@GameTest` annotation scan: it's off in production, and the data-driven instances run in both.
- A Forge dev-client run config beyond what debugging needs.
- Mixed-loader LAN, and any change to `band/1.21.11`.

**Blast radius (Stage 14):**
- 14.1 refactors the verified NeoForge harness. A mistake shows up in 14.1's proof and in the 14.9 sweep.
- 14.8 changes the NeoForge release jar's name and bytes, because the Forge entries and manifest join it. CI doesn't
  read it until 15.1, and nothing is pushed.
- FG7's Mavenizer writes only to the Gradle user home and `forge/build`. Installers and servers write only inside
  `forge/build/run/<task>/<leaf>`, and every reset asserts that root.
- The harness kills only processes it started, by PID. Nothing touches git history or the remote.

**Rollback (Stage 14, before the push):** `git reset --keep ec1a5c2` on `band/26.1` and `git reset --keep 27e4078` on `main`.
Both are gated through `guard-destructive`, and after a push it's `git revert` only.

### Stage 15 — CI, releases, docs  → commits on `main`, then merged main → band ✅ 2026-10-01
- [x] 15.1 `ci.yml`: add build and verify jobs for each loader and each pin, and a Quilt prod job.
      `publish` attaches every jar from the grouping. It checks that each jar declares
      `<plan version>+<its range>` and that its name lists exactly the loaders whose metadata it contains.
      **Result (2026-09-30, `ci: full loader matrix` on `main`):**
      - 58 tool tests pass, 8 of them in the new `tools/test_ci_workflow.py`. The old `ci.yml` fails 4 of its checks,
        and all 8 workflow mutants were killed.
      - The staging step ran against fixtures: the happy path (5 jars, as in the sweep), 5 refusals, and a single project.
      - Two bugs were found before any run: a relative `-Pctcl_prod_mods`, and an empty-plan guard that never fired
        (`gotchas.md`).
      - **Not run on Linux yet** (15.1b dropped). 15.4's CI run is the first.
- [x] ~~15.1b~~ Dropped (owner answer 20).
- [x] 15.2 `tools/release.py` and `tools/test_release.py`:
      - a release with several assets, named by range plus loaders;
      - cleanup stays keep-1 per band, and it still has to refuse to touch another band;
      - a test proves the naming, and another proves the refusal.
      **Result (2026-09-30, `3b0b298` on `main`):**
      - 50 tool tests pass, 23 of them new. All 8 guard mutants were killed.
      - `plan` on `band/26.1` gives `1.1.0`, `jars` = `...+26.1-26.3-Fabric-Quilt.jar` and
        `...+26.1.2-26.2-Forge-NeoForge.jar`, and an 8-job matrix.
      - `check_release_jars` passes the real 1.0.0 jars from the 14.9 sweep. The generated TOML parses.
- [x] 15.3 `README.md`, `TESTING.md` (a LAN checklist for each loader), and the table in `BANDS.md`
      (a loader column showing each loader's range).
      **Result (2026-09-30):**
      - `main` `3c2144e`: `BANDS.md` gets the **Loaders** column, the new pins to keep in sync, the cross-loader rule, and
        a rewritten **CI and releases** (plan outputs, the loader matrix, publish's checks, one release with every jar).
      - `band/26.1` `adc0f03`: `README.md` has a requirements table (both jars, each loader's floor per pin), a
        "Between loaders" upgrade note, and a build section covering `neoforge/`, `forge/`, and `releaseJars`.
        `TESTING.md`'s LAN section is one step list with a tick column per loader, plus a setup table.
      - Every pin and task name was checked against `band/26.1` and `ci.yml`. The old text said release runs "on a push"
        only, but it also runs on `workflow_dispatch`. Fixed.
      - 58 tool tests pass on `main`. The band's tool suite passes too (no `test_ci_workflow.py` until 15.4's merge).
- [x] 15.4 Push `band/26.1` (fast-forward, gated). Watch CI and check each released jar's metadata and name.
      **Result (2026-10-01):** CI `36822455012` at `b37e23b` passed all 11 jobs and released `v1.1.0+26.1-26.3` with
      `ctcl-1.1.0+26.1-26.3-Fabric-Quilt.jar` (sha256 `42e29303…`) and `ctcl-1.1.0+26.1.2-26.2-Forge-NeoForge.jar` (`7e0a4249…`).
      Both downloaded assets match GitHub's digests and pass `check_release_jars`. The notes table lists 4 loader rows.
      Keep-1 deleted the `v1.0.0+26.1-26.3` release and tag (backup: `~/.ctcl-backups/release-v1.0.0+26.1-26.3/`).
      `v1.0.0+1.21.11` is untouched.
      **Pre-push record (2026-09-30):**
      - `origin/main` was `60750ac`, and `origin/band/26.1` was `00b3b78`. Both pushes are fast-forwards (dry-run checked).
      - The merge is `96b8780` on `band/26.1`, and its one conflict, `ci.yml`, takes `main`'s side (answer 25). Before a push, undo it with `git reset --keep adc0f03`.
      - The v1.0.0 backup is `~/.ctcl-backups/release-v1.0.0+26.1-26.3/`: the jar (sha256 `023b3b0a…`, which matches GitHub's digest), `notes.md`,
        and `meta.txt` (title, tag, and `934de8a`).
      - Local check on the merge: 79 tool tests pass; `plan` gives `1.1.0`, two jars, and 8 matrix jobs; CI's staging script stages
        2 release jars + 3 gametest jars; `check_release_jars` passes both; `cleanup` refuses before v1.1.0 exists.
      - Local gotcha: Windows `python` writes CRLF, so the staging script's `mapfile` needs a `tr -d '
'` shim when it runs locally. Linux doesn't.
      **15.4 CI so far (2026-10-01), with nothing released and v1.0.0 untouched:**
      - Run `36793729578` (`96b8780`): the build job failed because there's no root `build/` on a fresh checkout. Fixed in `c9fbb20` (`gotchas.md`).
      - Run `36794641676` (`c9fbb20`): 8 of 9 jobs passed. `verify (forge 26.1.2)` failed: the **restart** server (5 loaders restored,
        `stop` typed at `Done`) hung after "Saving worlds" until the 15-minute kill. The first run (13/13) had stopped cleanly.
        Forge 26.2 passed the same flow, and so did 26.1.2 locally in 14.9. No thread dump was taken.
      - **Owner answer 27:** diagnose before releasing. Nothing is pushed until the hang is understood or fixed.
- [x] 15.4a Forge 26.1.2 restart hang: instrumented and not reproduced (1 in 7 CI restarts, then 8/8 green). The open item is under
      "Owner follow-ups". Plan as run:
  1. `gradle/harness.gradle` `awaitExit`: before the timeout kill, take two `jstack -l` dumps of the server JVM, 10 s apart, into
     `<console>-threads.txt` (the failure artifact already uploads the run dirs). Log the path, and log any jstack failure. A test
     proves a dump is written when a process outlives its timeout.
  2. Reproduce locally: `:forge:prodServerGameTest -Pminecraft_version=26.1.2`, plus a loop of restarts on the saved world with `stop` typed
     at `Done`. Also run it under a CPU limit (CI has 2 to 4 vCPUs), and get a dump of the hang.
  3. From the dump, decide whether it's CTCL (tickets in the shutdown `hasWork()` loop) or Forge/vanilla. Fix CTCL, or make the harness
     wait for the restored chunks before `stop` and record the upstream bug. Either way, a test that fails without the fix.
  4. Push `band/26.1`, which reruns CI. Only a fully green run releases.
  - Not doing: rerunning the failed job until it passes; any change to `band/1.21.11`.
  **Findings (2026-10-01):**
  - Step 1 is done: `b37e23b` on `band/26.1`, local and unpushed. `checkHangDump` passes on Windows and on the Linux runner, and it fails with the dump removed.
  - Step 2: 0 hangs in 20 local restarts with the merged 1.1.0 jar (10 on all cores, 10 pinned to 2). 0 hangs in 6 on CI (throwaway
    `ci/26.1-forge-hang`, run `36821119831`, 2 shards × 3, with `:forge:build` first as in verify; branch deleted, bundle in `~/.ctcl-backups/`).
    So far, 1 hang in 7 CI restarts and 1 in 27 overall.
  - The only difference in the hung run: its post-`Done` save took about 5.5 s (Worker-Main was still generating chunks) instead of about 1 s,
    so vanilla's `stopServer` loop (`while (chunkMap.hasWork())`, calling `deactivateTicketsOnClosing` each pass) started with worldgen in flight.
    CTCL adds tickets only on level load and level tick, and neither runs in that loop. Unproven without a dump.
  - **Owner answer 28 (2026-10-01):** push `main` + `band/26.1` with the dump instrumentation. A green run releases. A recurrence
    fails closed with dumps attached, and the fix comes from that data. The hang stays open in `gotchas.md`.
- [x] 15.5 `state.md` and `gotchas.md` at each stage boundary, not just at the end.

**Stage 15 plan (written before the code, 2026-09-30).** Order: 15.2 → 15.1 → 15.1b → 15.3 → 15.4, because CI's release job
calls `release.py`. Stop after each one.
**Pre-stage tips:** `main` `e82ebc2` and `band/26.1` `6deb140`.
**Owner answers (2026-09-30):**
16. De-risk Linux **locally in Docker** before the push (15.1b). Nothing is pushed until 15.4.
17. CI runs **one verify job per loader and pin**.
18. Band pushes **write the Gradle cache** (`cache-read-only: false`). Today every run is cold: setup-gradle is read-only
    off the default branch, and CI never runs on `main`.
19. 15.4 pushes **`main` and `band/26.1`** only. `band/1.21.11` stays as released, and `ci.yml` keeps single-project support.
20. **Skip the Docker shakedown** (15.1b, asked 2026-09-30 when Docker Desktop wasn't running). The first CI run is the Linux test.
21. **Ignore `*.launch`** in `.gitignore`.
22. The LAN checklist is **one list with a loader grid** (columns Fabric / Quilt / NeoForge / Forge), not four copies.
23. **Cross-loader guests are unsupported**, and the docs say so. There's no hand test for it.
24. **`main`'s placeholder `README.md` stays.** Replacing it would conflict with the band's README in 15.4's merge.
25. **15.4 runs end to end** (2026-09-30): back up v1.0.0, merge, verify locally, then push `main` + `band/26.1` and watch CI
    without another check-in. The merge's one conflict, `ci.yml`, takes `main`'s side, whose gametest glob covers `64b3364`'s rename.
26. **Housekeeping after 15.4:** drop `stash@{0}` (exported to a patch first), delete the local `ci/1.21.11-diag` branch, and delete the
    remote `ci/1.21.11-display` and `ci/26.1-display` branches. Each one goes through the gates. The PAT rotation stays with the owner.

- 15.2 `tools/release.py` → commit `ci: multi-jar releases` on `main`
  - `plan` reads every loader's pins, mirroring Gradle:
    - Fabric's label, as today (`fabric/build.gradle`);
    - `+Quilt` when every Fabric pin has a Quilt pin, and a failure on partial Quilt coverage;
    - NeoForge and Forge, as `loaderPlan` does (`gradle/pins.gradle`): the lowest pin, then the top pin or top line, and
      a failure on a gap between lines.
  - New `plan` outputs:
    - `jars`: the expected jar names, grouped by label in the order Fabric-Quilt-Forge-NeoForge;
    - `matrix`: `{loader, minecraft}` for Fabric on each non-base pin, and for NeoForge and Forge on each of their pins.
  - A guard: every loader's range must sit inside the band's label.
  - `publish --jars-dir <dir>` replaces `--jar`. It refuses unless the dir's release jars are exactly the expected set.
    Each jar's metadata must declare `<version>+<its label>`: `fabric.mod.json`, `META-INF/mods.toml`, and
    `META-INF/neoforge.mods.toml`, read with `tomllib`. Its name must list exactly the loaders that metadata implies,
    where Fabric metadata means Fabric, plus Quilt when the pins say so.
  - One `gh release create` with every jar. The notes start with a jar table (name, loaders, range).
  - A rerun is a no-op only if the release's asset names match the expected set. Otherwise it refuses.
  - `cleanup` is unchanged: keep-1 by band.
  - Tests:
    - the naming today (2 jars) and a merged future (1 jar);
    - refusals: partial Quilt, a gap, a wrong version in one jar, a missing jar, an extra jar, a mislabelled loader, and a
      rerun with different assets;
    - cleanup never touches another band's multi-jar release.
- 15.1 `.github/workflows/ci.yml` → commit `ci: full loader matrix` on `main`
  - Every Gradle task is project-qualified (`gotchas.md` "Multi-loader harness traps"). The single-project branch
    (`band/1.21.11`) keeps today's bare tasks, and `fabric/build.gradle` decides which set runs.
  - `build` (the Fabric base):
    - `:common:build :fabric:build :fabric:runClientGameTest`, the uninstall check, Fabric prod plus the restart restore,
      and Quilt on the base;
    - then `releaseJars` plus the three `gametestJar` tasks. It stages exactly `plan.jars` plus one gametest jar per loader
      into `release-jars/`, and fails on any other count.
  - `verify`: `matrix` from `plan`.
    - `fabric`: as today (byte check, recompiled suite, uninstall check, prod), plus Quilt.
    - `neoforge`/`forge`: `:<l>:build`, `checkReleaseJarBytes`, `prodServerGameTest`, the uninstall check on the saved
      world, `prodClientSmoke` + `prodVanillaGuest` under `xvfb-run -a`, and Fabric → loader → Fabric. That's sweep.sh
      `cross()`, moved into the workflow.
  - `release`: `publish --jars-dir release-jars`, then cleanup as before.
  - `setup-gradle` gets `cache-read-only: false`. The failure logs also collect the NeoForge/Forge run dirs and `hs_err_pid*.log`.
  - **Refined before the code (2026-09-30), since 15.1b is dropped:**
    - `defaults.run.shell: bash`, which gives `-eo pipefail`. The Forge job tees Gradle's output to find the world path it logs.
    - `-Pctcl_python=python3` in `GRADLE_FLAGS`, because the harness defaults to `python`, which a Linux image may not have.
    - The NeoForge/Forge jobs start one `Xvfb :99` and put `DISPLAY` in `GITHUB_ENV` before the first Gradle call.
      `xvfb-run -a ./gradlew` would give the display only to the client, and it would have to reach an already-running
      daemon's `ProcessBuilder` through Gradle's environment propagation. Loom's own tasks still wrap themselves in `xvfb-run`.
    - The task prefixes come from `FABRIC`/`COMMON` (`:fabric:`/`:common:`, or empty on a single project). Loader jobs use `:$LOADER:`.
    - The build job stages from `build/release` (multi) or `build/libs` (single), by `plan.jars`. The multi case also stages
      one gametest jar per loader project.
    - Test: `tools/test_ci_workflow.py`. Every `release.py` call in `ci.yml` must parse with `release.parser()`. Every
      `needs.plan.outputs.X` must be a plan-job output and a `plan_outputs` key. Every `./gradlew` task must be qualified or
      prefixed. `setup-gradle` must write the cache. A revert to today's `ci.yml` fails every one of these.
    - `.gitignore` gets `*.launch` (ForgeGradle's Eclipse run configs; owner answer 21).
- ~~15.1b Linux shakedown in Docker~~ **Dropped (owner answer 20).** The first Linux run of the NeoForge/Forge harness is 15.4's CI run. A
  failing run spends minutes but can't release, because `release` needs every job green. The static review of the harness for
  Linux found nothing: `unix_args.txt` is picked by `File.pathSeparator`, and `mclaunch.py` resolves Linux rules. The old plan was:
  - An `ubuntu:24.04` container with `--cpus 2 --memory 7g`, the size of a private-repo runner. It has Temurin 21+25,
    xvfb, and `mesa-vulkan-drivers`, and clones the repo from a **read-only** mount into the container.
  - The Gradle home is a named volume, `ctcl-ci-gradle`, which is removable and holds only caches.
  - It runs the workflow's `build` steps, then the `neoforge` and `forge` verify steps on 26.2.
  - Fixes go to `band/26.1` or `main` as ordinary commits. The script stays in the scratchpad, and its findings go to `gotchas.md`.
- 15.3 docs, as listed above → commit `docs: multi-loader readme, bands, lan checklists` on `main` (and on the band if the
  file lives there).
  - **Refined before the edit (2026-09-30, answers 22–24):**
    - `band/26.1` (one `docs:` commit): `README.md` and `TESTING.md`. They live only on the bands. `band/1.21.11` is untouched.
    - `main` (one `docs:` commit): `BANDS.md` only. The band gets it through 15.4's merge, so it's never edited on the band
      (an edit on both sides would conflict).
    - `README.md`: all four loaders in the intro, requirements (each loader's floor per pin, both jars), and building
      (`neoforge/`, `forge/`, `releaseJars`). Guests use the host's loader or no mods; cross-loader is unsupported.
    - `TESTING.md` "Manual: LAN": a setup table per loader (jar, Fabric API or not), then one step list with a tick grid.
    - `BANDS.md`: a **Loaders** column (each loader's range), and **CI and releases** rewritten for the loader matrix, the
      multi-jar release, and the jars' names.
    - Check: every version, pin, jar name, and task name in the diff is grepped against `band/26.1` and `ci.yml`.
      The CI's `paths-ignore: '**.md'` means this commit alone never triggers a run.
- 15.4 merge `main` → `band/26.1`. Then, gated: push `main`, then `band/26.1` (a fast-forward, not a force). Watch the run.
  - Expected: `v1.1.0+26.1-26.3` with two jars, and cleanup deleting `v1.0.0+26.1-26.3` (logged sha: `934de8a`).
  - Check each asset's name and metadata against `plan.jars`.

**Not doing in Stage 15:** any change to `band/1.21.11` or its release; `act`; a `pull_request` trigger; a schedule;
Modrinth or CurseForge.
**Blast radius (Stage 15):**
- 15.2/15.1: `main`, local commits only.
- 15.1b: a Docker container and the `ctcl-ci-gradle` volume. The repo is mounted read-only.
- 15.4 is the only outward step:
  - two pushes, both fast-forwards;
  - CI creates the `v1.1.0` tag and release;
  - keep-1 deletes the `v1.0.0+26.1-26.3` release and tag.
**Rollback (Stage 15):**
- Before the push: `git reset --keep e82ebc2` on `main`, gated.
- Docker: `docker rm -f ctcl-ci` and `docker volume rm ctcl-ci-gradle`. That volume holds caches only.
- After the push:
  - `git revert` the commits;
  - a bad release: `gh release delete v1.1.0+26.1-26.3 --cleanup-tag --yes`;
  - v1.0.0 back: `git tag v1.0.0+26.1-26.3 934de8a && git push origin v1.0.0+26.1-26.3`, then
    `gh release create v1.0.0+26.1-26.3 <backed-up jar> --notes-file <backed-up notes>`. **Before the push**, 15.4 downloads
    the v1.0.0 asset and notes to `~/.ctcl-backups/` and checks the jar's sha256. Without that step, cleanup loses the jar
    for good.

**Owner answers (2026-09-29):**
1. Order: **Quilt → NeoForge → Forge.**
2. **Stable versions only.** A loader with no stable build for a version skips that version.
3. The Fabric jar counts as Quilt support **if Quilt is truly, fully supported**. One jar may serve
   several loaders and versions.
4. The jar name lists the range and every loader it supports, for example
   `ctcl-1.0.0+26.1-26.3-Fabric-Quilt-Forge-NeoForge.jar`, with several loaders in one jar where feasible.
5. `main` is the status branch and holds the primary `TODO.md` and memory.

**Owner answers for Stage 13 (2026-09-29):**
6. NeoForge server gametests run in a **real NeoForge install**, not just the dev server.
7. The client side gets **our own automated client smoke test**, since NeoForge has no client gametest API. Forge reuses it.
8. The vanilla-guest rule is **automated**: a real vanilla client joins the NeoForge server.
9. `neoforge.mods.toml` accepts **the tested pins and newer** on each line, not every stable build.

**Owner answers for Stage 14 (2026-09-29):**
10. Forge has no production gametest launcher, so the server cases run in a **normal Forge server**, where the test-only
    gametest mod starts vanilla's runner and writes the report.
11. The closing sweep covers **everything**: every loader on every pin.

**Owner answers for Stages 14.9–15 (2026-09-30):**
12. The 14.9 sweep starts with `./gradlew clean`, as 13.8 did, so it also proves fresh Forge and NeoForge installs.
13. The owner closes extra VS Code windows (their Java servers held about 12 GB of commit) before the sweep.
14. CI runs the full loader matrix (Fabric, Quilt, NeoForge, Forge) **on every push**, as Fabric does today, even though
    the repo is private and its minutes are metered.
15. A band release keeps the tag `v<version>+<band's full range>` (`+26.1-26.3`) and its title. The release notes list
    each jar with its loaders and range. Keep-1 cleanup keeps working by band.

**Rollback:**
- Planning: `git revert` the `docs(memory)` commit on `main`. The v1 plan is in `git show be9931e:TODO.md`.
- Stage 11 before a push: `git reset --keep backup/26.1-pre-split` on `band/26.1` (gated).
- After a push: `git revert` the stage commits. Never force-push.
- A bad multi-jar release: `gh release delete <tag> --cleanup-tag --yes`, then re-tag from the logged sha.

---

## Stage 16 — Forge and NeoForge on 26.1–26.3, like Fabric/Quilt

**Tier:** 2. It changes the owner's stable-only rule, the pin plan in `gradle/pins.gradle`, the CI matrix, and the release jar set.

**Goal:** Forge and NeoForge cover 26.1–26.3, the same range as Fabric and Quilt, with the full suite on every pin. Ideally the
release ships **one** jar: `ctcl-<v>+26.1-26.3-Fabric-Quilt-Forge-NeoForge.jar`.

**Owner answers (2026-10-01):**
- 27. **A beta only when there is no stable build to use.** This applies to both Forge and NeoForge, on each Minecraft version.
  A version with a stable build uses the stable build. A beta pin moves to stable once a stable build ships. This narrows
  the "stable releases only" rule above. It doesn't remove it.
  - For NeoForge, stable means no `-beta` suffix. For Forge, stable means a `-recommended` promotion. Without one, the pin
    is that version's `-latest`.
- 28. If one jar can't cover every loader on 26.1–26.3, **split by range**: one combined jar where the code works, and an extra
  jar only for the range that needs different code. Everything stays on `band/26.1`.
- 29. (16.2) Each pin's loader range runs **up to the next pinned version on its line**, and a line's last pin runs to the end
  of the line. This keeps v1.1.0's `neoforge [26.1.2.112,26.2),[26.2.0.88,26.3)` and answer 9's "newer builds on its line".
  The rejected option was "next patch" (`[26.1.2.112,26.1.3)`), which would reject a future 26.1.3 NeoForge.
- 30. (16.3) Forge builds carry no suffix, so a Forge pin on a `-latest` build is **marked** in `gradle.properties` as
  `forge_beta_versions=26.1,26.1.1,26.3`. `check_pins.py` fails if the list disagrees with the promotions. NeoForge's
  mark is its own `-beta` suffix. The release notes and README read the marks offline.
- 31. (16.3) When a newer beta or `-latest` build ships after a beta pin, CI writes a `::notice::` and **doesn't fail**.
  Bumping a beta pin needs a fresh sweep anyway.

**Facts (checked live 2026-10-01; `gotchas.md` 2026-09-29 had the same picture):**

| MC | NeoForge pin | Forge pin |
|---|---|---|
| 26.1 | `26.1.0.19-beta` (beta, no stable) | `62.0.9` (latest, no recommended) |
| 26.1.1 | `26.1.1.15-beta` (beta, no stable) | `63.0.2` (latest, no recommended) |
| 26.1.2 | `26.1.2.112` (stable, unchanged) | `64.1.0` (recommended, unchanged) |
| 26.2 | `26.2.0.88` (stable, unchanged) | `65.1.0` (recommended, unchanged) |
| 26.3 | `26.3.0.39-beta` (beta, no stable) | `66.0.9` (latest, no recommended) |

**Approach:**
- *Pins drive everything.* The range, label, jar grouping, CI matrix, and `settings.gradle` inclusion already come from
  `<loader>.<mc>` pins. Adding the six pins moves both loaders' base to 26.1 and their label to `26.1-26.3`, and
  `releaseJars` then merges all four loaders into one jar, because the labels match.
- *The structural fix:* `loaderPlan` writes **one loader range per release line**, bounded from that line's lowest pin.
  Forge's 26.1 line has three majors (62 for 26.1, 63 for 26.1.1, 64 for 26.1.2), so it would declare `[62.0.9,63)` and
  reject Forge on 26.1.1 and 26.1.2. Ranges become **one per pinned Minecraft version**: `[pin, bound)`.
  - Forge's bound is `major + 1`.
  - NeoForge's bound is the next patch prefix, so 26.1 gives `[26.1.0.19-beta,26.1.1)`.
  - This keeps owner answer 9, "the tested pin and newer", for each version instead of each line.
- *The beta-rule guard:* a new `tools/check_pins.py` runs in CI's `plan` job. It reads NeoForge's Maven versions API
  and Forge's `promotions_slim.json`, and **fails** in two cases. Network errors fail closed.
  - A pin is a beta or `-latest` while that version has a stable build. The message names the stable build to pin.
  - A pin is a beta while it isn't marked as one.
  The parsing gets unit tests on recorded API responses, and those tests run with the other tool tests.
- *The release notes and README* mark each version that runs on a beta loader build.

**Not doing:**
- Any change to Fabric, Quilt, or `band/1.21.11`.
- Beta pins where a stable build exists (answer 27).
- A new band, unless the spike proves the code can't build from one base (answer 28 keeps it on `band/26.1`).
- Re-diagnosing the Forge 26.1.2 restart hang. That stays a watch item (Owner follow-ups).

**Blast radius:**
- `band/26.1`: `gradle.properties`, `gradle/pins.gradle`, `settings.gradle` comments, `ci.yml`, and any version shims in
  `forge/` or `neoforge/`. All of it is additive or a reviewed edit, and each is undone with `git revert`.
- **The release:** CI's green band push releases v1.2.0 and **runs `release.py cleanup --force`** (`ci.yml:363`), which
  deletes the v1.1.0 release and tag. Its two jars and notes get backed up to `~/.ctcl-backups/` and verified first
  (as with v1.0.0). The jar names change from two jars to one, which matters to anyone downloading by name.
- **Actions minutes:** six more verify jobs per push (3 versions × 2 loaders). This is measured in 16.5 against the
  2000 min/month limit.

### Stages, each a stop for the owner
- [x] 16.0 This plan, the `decisions.md` entry, and `state.md` → commit `docs(memory): stage 16 plan` on `main`.
- [x] 16.1 **Spike** (local, uncommitted, on `band/26.1`). Add the six pins and compile `:forge` and `:neoforge` at each new
      pin (`-Pminecraft_version=26.1|26.1.1|26.3`), then on the new base 26.1 for the release jar. Record every API
      difference against 26.1.2/26.2, and check that Forge 62's Mixin still takes `JAVA_21`. Then decide between one jar
      and a split by range (answer 28). → `gotchas.md` and a stop.
      ✅ 2026-10-01: everything compiles with no shims, and the Mixin is the same; one jar (`gotchas.md` 2026-10-01 "16.1 spike").
- [x] 16.2 `gradle/pins.gradle`: the per-version loader ranges, plus a test that proves Forge 26.1.1 and 26.1.2 are inside
      the declared range (it fails on today's per-line code). The six pins go in `gradle.properties`, with shims only
      if 16.1 found differences. → commit `feat: forge and neoforge on 26.1-26.3`.
      ✅ 2026-10-01 (`db0a4d8`, band/26.1): the test is `checkLoaderRange`. It reads the processed mods.toml and runs FML's own
      maven-artifact `VersionRange` against every claimed pin, and `jar` and `check` depend on it. It went red on the per-line
      code (`forge 63.0.2`, `64.1.0` outside `[62.0.9,63),[65.1.0,66),[66.0.9,67)`) and is green on the base, 26.1.1, 26.1.2,
      and 26.3. `release.py plan` now gives one jar, `…+26.1-26.3-Fabric-Quilt-Forge-NeoForge.jar`, and 14 verify jobs.
      No shims. `releaseJars` wasn't run: `build/release` holds the local v1.1.0 jars, and 16.4 starts with `clean`.
- [x] 16.3 `tools/check_pins.py` and its tests, wired into the `plan` job. A mutant check proves the guard fails on a beta
      pin that has a stable build. → commit `ci: enforce the beta-only-without-stable pin rule`.
      ✅ 2026-10-01 (`3f0af53`, band/26.1): 27 tests in `test_check_pins.py` on recordings, plus a `test_ci_workflow.py` check
      that the plan job runs it before the plan. Three code mutants each turn the suite red. The live mutant (`neoforge.26.2`
      beta, `forge.26.1.2=64.1.3`, 26.3 unmarked) exits 1 and names all three. Today's pins pass live with no notices.
      **Plan (2026-10-01):**
      - *Stable* means no `-` suffix for NeoForge (the 26.1.0 list also has `26.1.0.0-alpha.8+snapshot-4`, so it isn't
        just `-beta`) and a `<mc>-recommended` promotion for Forge. A NeoForge `<mc>` maps to the build prefix
        `<mc padded to 3 parts>.` (26.1 → `26.1.0.`).
      - **Fail** (exit 1, `::error::`): a pin the API doesn't list; a NeoForge beta pin when that version has a stable
        build (names the newest stable); a Forge pin other than the recommended one when one exists, unless it's an
        older one (that's a notice); a `forge_beta_versions` entry with a recommended build, or with no Forge pin; a
        Forge pin with no recommended build that isn't in `forge_beta_versions`; a Forge pin newer than `-latest`;
        any network or parse error (3 tries, then fail closed).
      - **Notice** (answer 31): a newer beta than a beta pin, a newer `-latest` than a Forge beta pin, a newer recommended
        than a Forge stable pin.
      - `--neoforge-json` and `--forge-json` read recorded files instead of the network. Tests use trimmed recordings in
        `tools/testdata/check_pins/`. `test_ci_workflow.py` asserts the `plan` job runs the check before the plan.
      - Mutant check: the CLI against the live APIs with a scratch copy of `gradle.properties` that pins
        `neoforge.26.2` on a beta, `forge.26.1.2=64.1.3`, and `forge_beta_versions` without 26.3. It must exit 1 and
        name all three. The repo file is never edited.
      - Not in scope: Fabric and Quilt pins.
- [x] 16.4 The full local sweep, starting with `clean`, on all 10 Forge and NeoForge pins: the build and byte check, prod
      server gametests, the client smoke test, the vanilla guest, the restart restore, and `uninstall_check.py`. The merged
      jar loads on all four loaders. Fabric and Quilt get a regression pass on the base and on 26.3.
      **Owner answers (2026-10-01):**
      - 32. The owner closes the extra VS Code window before the sweep (answer 13 again).
      - 33. Stop the orphaned Gradle daemons first: this repo's 9.5.1, and two 8.9 daemons from elsewhere. Done.
      - 34. **CI keeps the full matrix** (answer 14 stands): 14 verify jobs per band push. The estimate is about 146 runner-minutes
        per push (v1.1.0's run used about 83), so about 13 pushes a month fit under 2000. 16.5 records the number.
      **Plan (2026-10-01):** `sweep.sh` in this session's scratchpad (14.9's script, adapted). It runs on `band/26.1` `3f0af53`
      with `-Pmod_version=1.2.0` on every Gradle call, so the swept jar is the one `plan` ships. It runs in three background parts,
      each under the 2 h limit, and each part is read in this session:
      - **Part 1 (Fabric/Quilt and staging):**
        - Base 26.1: `clean`, then root `build :fabric:runClientGameTest`. That is the first dev run of NeoForge and Forge on their
          26.1 beta pins. Then the uninstall check, the tool tests, and Fabric prod plus the restart restore.
        - Staging: `releaseJars` and the three `gametestJar` tasks. `build/release` must hold exactly
          `ctcl-1.2.0+26.1-26.3-Fabric-Quilt-Forge-NeoForge.jar`, and each `libs` exactly one `1.2.0` gametest jar. The 4 jars are
          copied to `release-all/`, which must start empty. Fail closed otherwise.
        - Quilt on 26.1 against `release-all/`: server twice (restore), uninstall check, client.
        - 26.3: `clean`, the byte check, root `build :fabric:runClientGameTest` (which also runs NeoForge and Forge dev on 26.3),
          the uninstall check, Fabric prod server + client, then Quilt as on 26.1.
      - **Part 2 (NeoForge)**, then **Part 3 (Forge)**, each on 26.1, 26.1.1, 26.1.2, 26.2, 26.3 in that order, as CI's verify job:
        `:<l>:build`, `checkReleaseJarBytes` + `prodServerGameTest` (13 cases, then the restart restore), the uninstall check on the
        saved world, `prodClientSmoke` + `prodVanillaGuest`, then Fabric → loader → Fabric. No `clean` between loader pins:
        prod run dirs are per version, and the pins go oldest to newest.
      - **Before each part:** no Gradle daemon parented by VS Code, and free commit ≥ 12 GB.
      - **Passes only if** every phase logs `RESULT ... exit 0` and no prod/gametest task is `UP-TO-DATE`. The known 26.3
        client crash (`0xC0000005`, no mod code yet) gets one rerun of that task. A second failure means stop and get a core
        dump. A runtime failure on a beta pin goes to `gotchas.md`, and then answer 28 applies (split by range).
      - **Blast radius:** `clean` deletes every `*/build`. **Correction:** it doesn't touch the root `build/release` (`gotchas.md`), so the
        local v1.1.0 jars there were moved to the scratchpad (`local-v1.1.0-jars/`, sha1 checked) instead. Nothing in git changes.
      ✅ **Result (2026-10-01):** the whole sweep is green on `band/26.1` `5b04a79`. It found three bugs, all in test-only code or the harness,
      and each got its own commit on `band/26.1` (local only):
      - `10e3d45` `fix(forge)`: the Forge gametest mod's `loaderVersion` was hardcoded `[64,)`, so Forge 62/63 refused it. It now expands
        `fml_range`, and `checkLoaderRange` checks `loaderVersion` in both tomls. It was red on 62 and 63 before the fix.
      - `1363ee1` `fix(forge)`: Forge's `ProdGameTests` didn't compile on 26.3 (three runner signatures changed). It now uses a runtime
        overload bridge. It compiles on all 5 pins, and the base-built jar passes Forge prod on 26.1 and 26.3.
      - `5b04a79` `test(harness)`: a fresh 26.3 server defaults to `white-list=true`, so the vanilla guest was refused. The guest check now
        writes `white-list=false`.
      - The shipped jar is `ctcl-1.2.0+26.1-26.3-Fabric-Quilt-Forge-NeoForge.jar` (sha1 `18ea2e56`). None of the three fixes changes its
        bytes: the gametest jars and the harness are separate. It ran on all four loaders and every pin below:
        - Base 26.1: `clean`, `build` (Fabric, NeoForge, and Forge dev suites; NeoForge and Forge on their 26.1 beta pins), the client
          gametest, the uninstall check, 110 tool tests, and Fabric prod plus the restore.
        - Quilt 26.1 and 26.3, and Fabric 26.3: all green. Fabric 26.3's prod client hit the known native crash (`0xC0000409` this time)
          and passed on its one rerun.
        - NeoForge and Forge on 26.1, 26.1.1, 26.1.2, 26.2, and 26.3: `build` (dev 13/13), the byte check, prod 13/13 plus the restore,
          the uninstall check, the client smoke PASS, the vanilla guest joins, and Fabric → loader → Fabric. Every pin passes, the betas included.
        - No test task was `UP-TO-DATE`. The 26.3 guest and the 26.3 world move ran after `5b04a79`, and the Forge prod runs ran after `1363ee1`.
      - **Answer 28: one jar.** There was no runtime break on any pin, so there's no split by range.
      - **Timings (local, warm, per pin):** NeoForge 8.3–8.8 min, Forge 8.6–9.1 min (the client smoke test plus the guest take about 3 min
        of that). Fabric/Quilt are as in 14.9.
- [x] 16.5 `ci.yml` and the release notes: confirm the matrix, mark the beta-pinned versions, and measure the minutes.
      Docs: `README.md` (the support table), `BANDS.md`, `TESTING.md`. → commits on `main`, then merge main → band.
      **Owner answers (2026-10-01):**
      - 35. The minutes are measured from the real band push, after the rest of the list is done ("just push once you're done").
        16.5 records the 16.4 estimate; the push in 16.6 records the measured number.
      - 36. Beta marks: `26.1 (beta)` inline in the loader's Minecraft column, then one line under the table naming each
        beta build pinned.
      - 37. Branches as in 15.3/16.3, **not** "commits on main": `release.py` + tests, `ci.yml` (if it changes), `README.md`,
        and `TESTING.md` on `band/26.1`; `BANDS.md`, `TODO.md`, and memory on `main`. The main → band merge is in 16.6.
      **Plan (2026-10-01):**
      - *Notes* (`band/26.1`, commit `feat(release): mark beta loader pins in the release notes`): `LoaderRange` gets
        `betas: tuple[tuple[str, str], ...]` (Minecraft version → pinned build), filled in `loader_jars`. A NeoForge pin is a
        beta when it has a `-` suffix (as `check_pins.is_stable`); a Forge pin when its version is in `forge_beta_versions`.
        Fabric, Quilt, and the 1.21.11 band never have betas. `release_notes` writes `26.1 (beta)` and, only when any pin is
        a beta, the line under the table. The plan log names the betas too.
        Tests first (`test_release.py`): marks only on marked versions; no line when nothing is a beta; a Forge version in
        `forge_beta_versions` is marked, one outside isn't; the real `gradle.properties` gives exactly the six betas of
        answer 27's table. Each must fail on today's code.
      - *Matrix:* a `test_release.py` check that the real `gradle.properties` plans 14 verify jobs (4 Fabric+Quilt,
        5 NeoForge, 5 Forge) and one jar. `ci.yml` itself only changes if the notes step needs it (it shouldn't: the table
        is written by `publish`).
      - *Docs* (`band/26.1`, commit `docs: forge and neoforge on 26.1-26.3`): `README.md` support table (one jar, four
        loaders, every pin, beta marks, the loader floors per version), drop "NeoForge and Forge skip 26.1, 26.1.1, 26.3",
        and a note that a fresh 26.3 server writes `white-list=true`. `TESTING.md`: one staged jar, `loaderVersion` from
        `fml_range`, the 16.4 sweep timings, the whitelist note.
      - *Docs* (`main`, commit `docs: bands for forge and neoforge on 26.1-26.3`): `BANDS.md` row and rule text (answer 27
        replaces "skip every version with no stable build"), and the CI cost: 14 verify jobs, ~146 runner-min per push
        (estimate; 16.6 replaces it with the measured number).
      - **Not doing:** any change to the matrix shape, Fabric/Quilt, or `band/1.21.11`; a push (16.6).
      - **Blast radius:** additive edits to tracked files on two branches, each undone with `git revert`. Nothing destructive.
      ✅ **Result (2026-10-01):**
      - `bf00424` (band/26.1) `feat(release)`: `LoaderRange.betas`, `beta_pins`, and `marked_versions` (the notes table and
        the plan log). 5 new tests in `BetaMarksTest`; three were red on the old code. 115 tool tests green. The plan log now
        reads `Forge 26.1 (beta), 26.1.1 (beta), 26.1.2, 26.2, 26.3 (beta)`. `ci.yml` didn't change.
      - `ea2508e` (band/26.1) `docs`: README (a per-version loader-floor table, one jar), TESTING.md (the declared ranges are read
        from the staged 1.2.0 jar), and the stale stable-only comment in `settings.gradle` (`gradlew help` exits 0).
      - `8fb8130` (main) `docs`: BANDS.md row, pin rule, `check_pins.py` in `plan`, the beta marks, and the cost estimate.
- [x] 16.6 Pre-push: back up v1.1.0 (gated), merge main → band, and record the push in `state.md`. Then push `main` and
      `band/26.1` (answer 35: the owner approved the push once everything else is done), watch the run, and record the
      measured minutes in `BANDS.md` and `state.md`.
      **Owner answers (2026-10-01):**
      - 38. Push in the same session once the backup and the merge are verified. There's no further stop.
      - 39. If a run fails on the Forge 26.1.2 restart hang (`didn't exit within`), read `<console>-threads.txt` and record it
        in `gotchas.md`, then run `gh run rerun --failed` **once**. A green rerun releases v1.2.0.
      - 40. A real CI failure: fix it on `band/26.1` within the 3-attempt budget, test each fix locally first, and push again.
      **Pre-push record (2026-10-01):**
      - *Backup* (gates 1–3): `~/.ctcl-backups/release-v1.1.0+26.1-26.3/` holds both jars, `notes.md` (the release body), and
        `meta.txt` (title, tag, `b37e23b`). The SHA-256 of each jar matches GitHub's asset digest
        (`42e29303…` Fabric-Quilt, `7e0a4249…` Forge-NeoForge). `b37e23b` is in `band/26.1`'s history, so the tag comes back
        from git.
      - *Blast radius of the push:* a green band run releases `v1.2.0+26.1-26.3` (one jar) and `release.py cleanup --force`
        deletes the `v1.1.0+26.1-26.3` release and tag. `v1.0.0+1.21.11` is a different band and isn't touched.
      - *Undo* (if v1.1.0 is needed by name):
        `git push origin b37e23b:refs/tags/v1.1.0+26.1-26.3`, then
        `gh release create v1.1.0+26.1-26.3 --verify-tag --title "CTCL 1.1.0 for Minecraft 26.1-26.3" --notes-file notes.md *.jar`
        from the backup folder. A bad v1.2.0: `git revert` on the band and push (never force-push).
      **First run (2026-10-01, run 36851633937 on `8391a44`):** 15 of 17 jobs green, no release. Two flakes, neither in our code
      (`gotchas.md` 2026-10-01): a silent Fabric 26.2 dev client gametest (30-min step timeout) and a `Connection reset` while the
      Fabric server launcher downloaded the Minecraft server in Forge 26.1.1's world move.
      - 41. (owner) Rerun the failed jobs once. If either fails again, stop and report.
      **Rerun (attempt 2):** Fabric 26.2 passed (7 min). Forge 26.1.1 failed in `prodClientSmoke`: Forge 63's early window has a
      10 s limit, the window took longer on Xvfb, and the main thread died. Forge's crash prompt (tinyfd `y/n:`, on a non-daemon
      thread) then kept the JVM alive until the 15-min watchdog (`gotchas.md` 2026-10-01, corrected entry). The thread dump
      wasn't in the artifact, because the upload globs miss `*-threads.txt`.
      - 42. (owner) Fix it, then push again. 43. (owner) **Fail fast and retry once**: kill a client whose main thread died, and
        retry the smoke test once, only on Forge's early-window timeout. Forge's early window stays on (it is what users run).
      **Plan (16.6a, `band/26.1`, commit `fix(harness): kill a client whose main thread died, retry forge's window timeout`):**
      - `gradle/harness.gradle`: a `startClient` wrapper over `startProcess`. On a console line starting `Exception in thread "main"`
        it takes one thread dump (`<console>-threads.txt`) and kills the client, and it returns a `died` flag with the process.
        `prodClientSmoke` uses it. Main died + the console has `Timed out trying to setup the Game Window` → log a warning and
        run the client once more. Main died for any other reason, or twice → fail at once with the console and dump paths.
      - Test first: a `checkClientDeathKill` self-test in `check` (like `checkHangDump`): a JVM that starts a non-daemon thread
        that sleeps, then throws from main. It must be killed within 30 s (the timeout is 2 min), with `died` set and a dump
        that shows the sleeping thread. The retry decision is its own closure, checked on fabricated consoles (the timeout →
        retry; another main death → no retry; a clean console → no retry). The mutant run (kill turned off) must go red.
      - `.github/workflows/ci.yml`: add `*/build/run/**/*-threads.txt` to the build and verify artifact paths. Test first: a
        `test_ci_workflow.py` check that every `upload-artifact` path list has it (red on today's file).
      - Verify locally: `:forge:checkClientDeathKill :neoforge:checkClientDeathKill`, the tool tests, `:forge:build` on the base,
        and `:forge:prodClientSmoke` on 26.1.1 (the failed pin) to prove the happy path still passes.
      - **Not doing:** turning Forge's early window off; a retry for any other failure; the vanilla guest's client (the server
        side bounds it); NeoForge's early-window text (no failure seen).
      - **Blast radius:** test-harness and CI-workflow edits on `band/26.1` only. The release jar's bytes don't change. Undo with
        `git revert`. The push is a new full CI run (~150 runner-min). A green run releases v1.2.0 and deletes v1.1.0 (backed up).
      ✅ **16.6a done (2026-10-01, `cc621cd` on band/26.1):** `startClient`, `earlyWindowTimedOut`, and the retry in `prodClientSmoke`;
      `checkClientDeathKill` in `check`; the `*-threads.txt` artifact glob plus `test_failure_logs_carry_the_thread_dumps` (red on the
      old `ci.yml`). The self-test's first run went red, because the kill never happened: a toolchain provider read off the build
      thread (`gotchas.md`). After the fix it kills in 1 s on both loaders. Green: 116 tool tests, `:forge:build :neoforge:build` on
      the base (dev 13/13 each, `checkLoaderRange`, both self-tests), and `:forge:prodClientSmoke` on 26.1.1 with the 1.2.0 jar
      (PASS, twice). The retry branch itself can't be triggered locally (it needs a real window timeout). Its rule is tested
      on recorded consoles. Next: merge main → band, then push both.
      **Second push (2026-10-01, run 36914711767 on `3d18637`):** 16 of 17 green. `forge 26.2` hit the window timeout, and the kill
      didn't fire: the death follows `y/n: ` on the same line, and the matcher used `startsWith` (`gotchas.md`). Fix attempt 2 of 3
      (`2e63d34`): `contains`, a self-test that prints the prompt first (red before the fix), and the reader logs a stream that
      closes after a kill. Green locally: both self-tests, 116 tool tests, Forge 26.2 smoke PASS, and Forge 26.2 prod server 13/13.
      If the retry also times out on CI, stop and ask about `earlyWindowControl = false` (it would reverse answer 43).
      ✅ **Done (2026-10-01): run 36920611950 on `8374989` is green on all 17 jobs and released `v1.2.0+26.1-26.3`.** Keep-1 deleted
      v1.1.0's release and tag (backed up in `~/.ctcl-backups/release-v1.1.0+26.1-26.3/`).
      - Asset: `ctcl-1.2.0+26.1-26.3-Fabric-Quilt-Forge-NeoForge.jar` (68719 B, sha256 `2e069848…`). Compared with the swept local jar,
        every class is byte-identical. The only differences are Loom's `Fabric-Loom-Client-Only-Entries` order (file listing order
        differs on Linux and Windows) and line endings in `pack.mcmeta` and the two mixin jsons (Windows CRLF checkout).
      - Forge's window-timeout retry didn't fire in this run.
      - Minutes: 195 runner-min (203 billed) for the green run, recorded in `BANDS.md`. The whole of 16.6 used about 660 billed
        minutes: 217 for the first push, about 30 for the rerun (the jobs API can't separate rerun jobs from reused ones, so this
        is approximate), 213 for the second push, and 203 for the third.
      - *Merge:* main → band is docs-only (memory, `BANDS.md`, `TODO.md`) and `git merge-tree` reports no conflicts. Since the
        green sweep (`5b04a79`), the band changed only `release.py` + tests, a `settings.gradle` comment, and docs. So the
        re-check is the tool tests, `gradlew help`, and the plan job's commands (`check_pins.py` live, `release.py plan`).

**Rollback:**
- Before the push: `git revert` the stage commits on `band/26.1` and `main`. Nothing outside the repo has changed yet.
- After the push: `git revert` and push. CI then re-releases the old jar set as a new patch version. Restore v1.1.0 from
  `~/.ctcl-backups/` with `gh release create` if it's needed by name. Never force-push.

---

## Stage 17 — Public release in a fresh repo (Tier 2, owner answers 2026-10-22) ✅ 2026-10-22

Owner answers: the new repo takes `Wulfic/CTCL`; the old one becomes `Wulfic/CTCL-private-archive`, archived. Dev notes
are kept, redacted. Both releases are recreated. License: MIT. Decision: `decisions.md` 2026-10-22.

**Not doing:** deleting the old repo, its releases, or the old local folder; rewriting old history; rotating credentials
(owner only); carrying over the local-only tags `backup/26.1-pre-split` and `v1.0.0+26.1-26.3`, issues, or PRs.

- [x] 17.1 Stage the fresh repo from `main` `dfb7f79`, `band/26.1` `8374989`, `band/1.21.11` `d7c8b90`: one commit per
  branch (`main` the root, each band its child), authored with the GitHub noreply address.
- [x] 17.2 Scrub: `.env` and both MCP configs untracked and gitignored, `.mcp.example.json` added; LAN hosts, local
  paths, and "stays private" remarks redacted; ARR → MIT in `LICENSE`, README, and all six mod metadata files.
- [x] 17.3 Verify: a scan with the literal old secret values plus token/IP/path regexes over every committed blob: 0
  hits (the same scan finds 42 in the old `band/26.1`). Only the intended files differ from the old tips; every other
  blob is byte-identical. Python tool tests and `gradlew build` green.
- [x] 17.4 Back up both releases → `~/.ctcl-backups/public-migration/` (sha256 matches GitHub's digests).
- [x] 17.5 `gh repo rename CTCL-private-archive -R Wulfic/CTCL`, then repoint the old folder's `origin` at once.
  **Undo:** `gh repo rename CTCL -R Wulfic/CTCL-private-archive` (before 17.6 only).
- [x] 17.6 `gh repo create Wulfic/CTCL --private`; Actions off; push `main`, the two tags, both bands. **Undo:** delete
  the new repo (nothing public yet).
- [x] 17.7 Recreate both releases from the backup; verify the asset sha256s. Actions back on.
- [x] 17.8 `gh repo archive Wulfic/CTCL-private-archive --yes`. **Undo:** `gh repo unarchive Wulfic/CTCL-private-archive`.
- [x] 17.9 Owner go → `gh repo edit Wulfic/CTCL --visibility public`. **No real undo**: treat anything public as permanent.
- [x] 17.10 The local `CTCL` folder stays put: its `.git` was swapped for the clean one, so it mirrors `Wulfic/CTCL`
  exactly, with the untracked `.env` / MCP configs still in place. Old `.git` → `~/.ctcl-backups/ctcl-private.git`.

## Owner follow-ups (post-release)
- [ ] `TESTING.md` LAN checklist on 1.21.11 (old 6.1) with a second client and a vanilla guest.
- [ ] `TESTING.md` LAN checklist on 26.x (old 7.8b).
- [x] Housekeeping (owner answer 26, 2026-10-01). Each item went through the gates, and each has a verified bundle in `~/.ctcl-backups/`:
  - `stash@{0}` (`0c845d2`) dropped (also kept as a `.patch`);
  - local `ci/1.21.11-diag` (`5a34b22`) deleted;
  - remote `ci/1.21.11-display` (`d6f16e6`) and `ci/26.1-display` (`934de8a`) deleted. Both commits are in their band's history;
  - the throwaway `ci/26.1-forge-hang` (`6fd25d4`) deleted, local and remote.
- [ ] **Forge 26.1.2 restart hang** (`gotchas.md` 2026-10-01): it hung once in 8 CI runs, in vanilla's shutdown loop. If a run fails with
  `didn't exit within`, read `<console>-threads.txt` in the failure artifact before anything else.
- [ ] **Rotate every credential that was ever in the old repo** (it is archived, not deleted): the GitHub PAT, the
  Context7 key, and every password in `.env`. Then update the local `.env` / `.mcp.json`.

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
