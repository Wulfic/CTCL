# Gotchas

> **Append-only.** Traps, dead ends, and environment quirks that cost real time.
>
> The test for whether something belongs here: **would a competent developer hit this
> again in six months?** If yes, write it down. If it's obvious from reading the code,
> it does not belong here.
>
> Log a failed approach as carefully as a successful one. "We tried X, it doesn't work
> because Y" is what stops the next session from burning an hour on X.

## Entry template

```markdown
## YYYY-MM-DD — Short title
**Symptom:** what you actually saw (error text, wrong output, hang)
**Cause:** the real root cause
**Fix:** what worked
**Dead ends:** what looked right but wasn't, and why
**Affects:** src/path/to/file.ts
```

---

## 2026-08-12 — `.gitignore` does not cover `.env`
**Symptom:** `.env` is tracked in git despite a large "Secrets / config" section in
`.gitignore`.
**Cause:** That section only lists `credentials*`, `secrets*`, `*.pem`, `*.key`, `*.crt`.
There is no `.env` pattern, and a file already tracked stays tracked even after a matching
pattern is added.
**Fix:** Not applied — the repo owner opted to leave credential handling as-is for now.
**Update 2026-10-22:** Fixed for the public repo: `.env` and both MCP configs are untracked and gitignored; the template is `.mcp.example.json`.
Anything spawned from this template inherits both the tracked `.env` and the pattern gap.
**Dead ends:** Adding `.env` to `.gitignore` alone does nothing for an already-tracked
file; it needs `git rm --cached` as well.
**Affects:** `.gitignore`, `.env`, `.mcp.json`, `.claude/mcp.vscode-reference.json`

## 2026-08-12 — The two MCP config files disagreed on hosts (RESOLVED)
**Symptom:** `.mcp.json` and `.claude/mcp.vscode-reference.json` pointed at different self-hosted MCP hosts.
**Cause:** The self-hosted MCP stack moved from one host to another. `.mcp.json` was updated; the reference file
was not.
**Fix:** The reference file was realigned to the current hosts. (Host details are redacted in the public repo.)
**Dead ends:** We initially guessed the `.186` file might be the newer one because
`AGENTS.md` still described a docker-compose stack — the prose was simply stale too. The
`// description` comment in `.mcp.json` is the reliable source here, not the prose.
**Affects:** `.mcp.json`, `.claude/mcp.vscode-reference.json`

## 2026-08-12 — mcp-compressor 0.31.4 changed its CLI flags
**Symptom:** Server configs written with `--compression-level` and `--header` fail on
current mcp-compressor. Backends without an `Authorization` header fail differently —
mcp-compressor auto-attempts OAuth discovery, which these backends don't support.
**Cause:** 0.31.4 renamed `--compression-level` → `--compression`, `--header` → `-H`, and
now requires a `--` separator before the backend URL and its args.
**Fix:** Use `--compression`, `-H`, and `--`. For backends with no real auth, pass a
placeholder `-H "Authorization=Bearer unused"` to suppress OAuth discovery.
**Dead ends:** Omitting the Authorization header entirely looks correct for an unauthed
backend, but triggers the OAuth path and fails with an unrelated-looking error.
**Affects:** `.mcp.json`, `.claude/mcp.vscode-reference.json`

## 2026-08-12 — A newly created `.claude/settings.json` does not load in the session that created it
**Symptom:** Added `permissions.deny` / `permissions.ask` rules, then ran `dropdb --help`
(matches the `ask` rule `Bash(dropdb*)`). It executed straight through to
`bash: dropdb: command not found` — no prompt, no block.
**Cause:** The settings watcher only watches directories that already contained a settings
file when the session started. `.claude/settings.json` did not exist at session start, so
the new file stays invisible until the session restarts (or `/hooks` is opened, which
reloads config). Nothing wrong with the file — it parses and matches the published schema.
**Fix:** Restart the session, or open `/hooks` once. Verify afterward with a harmless
matching command such as `dropdb --help`.
**Dead ends:** Re-checking the JSON and the rule syntax — both were correct the whole time.
The failure looks exactly like a bad matcher pattern, which is the trap: **a permission
rule that silently doesn't load is indistinguishable from one that doesn't match.** Same
class of invisible-failure problem that got `mem0` dropped. Probe with a real command
rather than assuming the rules are live.
**Affects:** `.claude/settings.json`, `.claude/skills/guard-destructive/SKILL.md`

## 2026-09-23 — The 1.21.11 Loom plugin id is `net.fabricmc.fabric-loom-remap`, not `fabric-loom`
**Symptom:** The plan assumed the classic `fabric-loom` id for the obfuscated 1.21.11 band.
**Cause:** Since Loom 1.14 there are two plugin ids. `net.fabricmc.fabric-loom-remap` is for
obfuscated versions (≤ 1.21.11) and keeps `modImplementation` / `remapJar`.
`net.fabricmc.fabric-loom` is the non-remapping plugin for unobfuscated 26.x. The legacy
`fabric-loom` marker still resolves, which makes the wrong choice look fine.
**Fix:** `band/1.21.11` copies FabricMC/fabric-example-mod branch `1.21.11` (checked 2026-09-23):
- `id 'net.fabricmc.fabric-loom-remap'`, pinned to **1.17.21**. The example uses
  `1.17-SNAPSHOT`, which we don't use because snapshots aren't reproducible.
- Gradle **9.5.1**, `mappings loom.officialMojangMappings()`, `splitEnvironmentSourceSets()`.
- Loader **0.19.5** (the stable release for 1.21.11) and Fabric API **0.141.6+1.21.11**.
- `fabric.mod.json` uses `"minecraft": "~1.21.11"` and `"java": ">=21"`.
**Dead ends:** Reading the version list on fabricmc.net/develop from memory. Query
`https://meta.fabricmc.net/v2/versions/loader/<mc>` and the maven-metadata.xml files instead.
The example mod's branch per MC version (`git ls-remote`) is the reference for build scripts.
It has branches for `26.1`, `26.1.1`, `26.1.2`, `26.2`, and `26.3`.
**Affects:** `band/1.21.11` `build.gradle`, `gradle.properties`, `settings.gradle`

## 2026-09-23 — Killing `gradlew runClient` orphans the Minecraft JVM
**Symptom:** After stopping a background `./gradlew runClient`, the game window stayed open.
A `java.exe` running `-Dfabric.dli.config=...\CTCL\...` was still alive.
**Cause:** Loom launches the game in a child JVM. Killing the Gradle wrapper process
doesn't kill that child on Windows.
**Fix:** Close the game window normally, or find the child by its command line and stop
only that PID:
`Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ? { $_.CommandLine -match 'fabric\.dli\.config=.*CTCL' }`.
The title screen is reached once the log shows `Sound engine started` followed by the
atlas `Created:` lines.
**Dead ends:** `TaskStop` on the background shell reports success but only kills Gradle.
**Affects:** `runClient`, `runServer`, any automated launch check

## 2026-09-23 — Vanilla tickets are deduplicated by (type, level): no reference count
**Symptom:** None yet. Found reading the 1.21.11 `TicketStorage` source. Two tables in the same
chunk at the same size would add identical tickets. Turning either table off would
remove *the* ticket and unload chunks the other table still needs.
**Cause:** `TicketStorage.addTicket` finds an existing ticket with the same type and level
at that chunk, resets its timeout, and returns `false` without adding a second one.
`removeTicket` removes the first match. Tickets have no owner identity.
**Fix:** `LoaderTickets` keeps its own in-memory refcount keyed by `(ChunkPos, level)`.
It calls vanilla `addTicketWithRadius` only on 0→1 and `removeTicketWithRadius` only on
1→0. The refcount is rebuilt from `LoaderData` on level load, since tickets are
non-persistent. Mockito tests cover it through the `TicketSink` seam.
**Dead ends:** Relying on vanilla to count. Also giving each table its own ticket type,
which would mean a registry entry per table.
**Affects:** `LoaderTickets`, Stage 3

## 2026-09-23 — 1.21.11 ticket facts (from decompiled source)
- `TicketType` is a record `(long timeout, int flags)` in `BuiltInRegistries.TICKET_TYPE`.
  The flags are PERSIST=1, LOADING=2, SIMULATION=4, KEEP_DIMENSION_ACTIVE=8, and
  CAN_EXPIRE_IF_UNLOADED=16. Vanilla `FORCED` is 15.
- **Our type is `new TicketType(0L, 14)`**: load, simulate, and keep the dimension active,
  with no timeout and **no PERSIST**. It's registered with `Registry.register` in
  `onInitialize`. `TicketStorage.packTickets` saves only `persist()` types, so nothing
  of ours reaches `chunks.dat`.
- `addTicketWithRadius(type, pos, r)` places level `33 - r`, and ENTITY_TICKING is
  level ≤ 31. The level spreads +1 per chunk to all 8 neighbours (`ChunkTracker`), which
  is a Chebyshev square, and `SimulationChunkTracker` spreads the same way.
  **One ticket per table at the table's chunk with radius `r + 2`** (level `31 - r`)
  makes the full `(2r+1)²` square entity-ticking. Size 1 gives level 31, the same as
  `/forceload`'s `ChunkMap.FORCED_TICKET_LEVEL`.
- `TICKET_TYPE` is **not** synced. Fabric registry-sync 6.2.6 (Fabric API 0.141.6) has an
  allowlist of 28 synced registries, and `TICKET_TYPE` (intermediary `field_55881`) isn't
  on it. A custom ticket type is invisible to clients, so vanilla LAN guests can still join.
- In loaded chunks with no player nearby, random ticks and block entities still run,
  because `forEachBlockTickingChunk` iterates every entity-ticking chunk. **Natural mob
  spawning needs a player within 128 blocks** (`collectSpawningChunks` →
  `anyPlayerCloseEnoughForSpawningInternal`). This goes in the README.
**Affects:** `LoaderTickets`, `CtclMod`, `README.md`

## 2026-09-23 — A SavedData that fails to decode is silently replaced with empty data
**Symptom:** None yet. Found reading 1.21.11 `DimensionDataStorage`.
**Cause:**
- When the codec fails, `readSavedData` logs `Failed to parse saved data` and returns
  `null`. It also returns `null` on any exception.
- `computeIfAbsent` then constructs a **fresh empty instance** and `set()`s it, which
  marks it dirty, and the next save **overwrites the file**. Every stored table size is lost.
- `.resultOrPartial` also accepts a *partial* decode, so a lenient codec can drop entries
  with no failure at all.
**Fix (Stage 3.2):** Before calling `computeIfAbsent`, if `data/ctcl_loaders.dat` exists,
read and decode it ourselves. On failure, copy it to `ctcl_loaders.dat.corrupt-<timestamp>`,
log ERROR with both paths, and only then continue. Decode per entry, where a bad entry
is skipped with a WARN; one bad pos must not cost the whole map.
Pass **`null` as `DataFixTypes`**: Fabric API's object-builder `DimensionDataStorageMixin`
(21.1.40) makes `readTagFromDisk` skip datafixing for a null type. Using a vanilla
`DataFixTypes` would run vanilla fixers over our schema on version upgrades.
**Dead ends:** Trusting `computeIfAbsent` to "load or create". It can't tell a missing
file from a corrupt one.
**Affects:** `LoaderData`, Stage 3.2

## 2026-09-23 — Block-removal hook in 1.21.11 is best-effort only
**Symptom:** `onRemove` is gone, and 1.21.5 split it up.
**Cause:** The replacement is `BlockBehaviour.affectNeighborsAfterRemoval(state,
ServerLevel, pos, movedByPiston)`. `LevelChunk.setBlockState` calls it only when the
block changes **and** the flags include UPDATE_NEIGHBORS (1) or MOVE_BY_PISTON (64).
Pistons call it directly. Any `setBlock` without flag 1 skips it, including some
commands and editors. Block entities have a separate hook, `preRemoveSideEffects`, which
crafting tables don't use.
**Fix:** Mixin-add an override of `affectNeighborsAfterRemoval` on `CraftingTableBlock`
only, so no other block pays for the hot path. Keep the 20-tick validation pass as the
**guarantee**; the hook only makes release immediate.
**Affects:** Stage 3.5, `CraftingTableBlockMixin`

## 2026-09-23 — Crafting menu position and integrated-server pause (1.21.11)
- `CraftingMenu` has a `private final ContainerLevelAccess access`. For a real table it's
  `ContainerLevelAccess.create(level, pos)` from `CraftingTableBlock.getMenuProvider`.
  The 2×2 inventory grid is `InventoryMenu`, not `CraftingMenu`, but
  `CraftingMenu(int, Inventory)` uses `ContainerLevelAccess.NULL`, so the resolver must
  handle an empty access (`evaluate` returns empty). `stillValid` checks
  `Blocks.CRAFTING_TABLE` and distance. Get the position with an `@Accessor` for `access`,
  then `access.evaluate((level, pos) -> pos)`.
- `IntegratedServer.tickServer`: `paused = Minecraft.isPaused() || no players`.
  `Minecraft.pause` is true only when **not published to LAN**. In singleplayer, the
  pause menu stops all ticking, loaded chunks included. Once the world is open to LAN it
  never pauses. This goes in the README.
**Affects:** `CraftingMenuAccessor`, Stage 4.2, `README.md`

## 2026-09-23 — The server gametest world persists between runs; `clearRunDirectory` doesn't clear it
**Symptom:** The piston gametest failed differently on each run. The third run logged
`Restored 2 crafting table loader(s)` at startup: tables and saved sizes left over from
earlier runs were already at the test positions, so the piston faced two tables.
**Cause:** `runGameTest` reuses `build/run/gameTest/world`. In Loom 1.17.21,
`configureTests { clearRunDirectory = true }` only registers `deleteGameTestRunDir` for
the **client** gametest run (checked in the `FabricApiTesting` bytecode), so the setting
does nothing for server gametests.
**Fix:** Each gametest works in its own far region and clears it first
(`placeTable`). Clearing with `UPDATE_ALL` fires the removal hook, so stale entries are
released too. We did **not** add our own delete task, because hermeticity through the
tests themselves avoids writing deletion code. `./gradlew clean` resets the world by hand.
**Dead ends:** Believing `clearRunDirectory` covered both runs because the setting sits
on the shared `configureTests` block.
**Affects:** `build.gradle`, `src/gametest/.../LoaderGameTests.java`

## 2026-09-23 — Pushing a loader table with a piston can freeze the push
**Symptom:** In the piston gametest, the table stayed a `moving_piston` forever.
**Cause:** The push fires the removal hook and releases the table's own loader, as the
spec requires ("a moved table starts at 0"). If that loader was the only thing keeping
the chunk ticking, the `MovingPistonBlockEntity` never ticks again, so the push only
finishes when a player comes near.
**Fix:** This is expected product behaviour, and it goes in the README. The gametest
force-loads its chunk with a vanilla ticket to stand in for a nearby player.
**Affects:** `README.md` (Stage 6.4), `LoaderGameTests`

## 2026-09-23 — Client gametests: not part of `build`, and they pass silently
**Symptom:** `./gradlew runClientGameTest` ends with `BUILD SUCCESSFUL` and no "tests
passed" line, which looks the same as a test that never ran.
**Cause:** Fabric's client gametest runner just exits 0 when `runTest` returns. A
failure throws: `AssertionError: Timed out waiting for predicate` and exit 1.
`runClientGameTest` needs a display, so Loom doesn't attach it to `check`; `build` runs
only the server gametests.
**Fix:** Run `./gradlew runClientGameTest` by hand after any client change. It opens a
real window for about 2 minutes. We proved the test isn't vacuous by mutating one
expected label and watching it fail. Cursor coordinates for `TestInput.setCursorPos`
are **window pixels**, so multiply GUI coordinates by `getWindow().getGuiScale()`.
**Also:** `GameTestHelper.makeMockServerPlayerInLevel()` is deprecated for removal in
1.21.11. `GameTestSupport.joinTestPlayer` repeats its steps with non-deprecated calls, so
the build stays suppression-free under `-Werror`.
**Affects:** `src/gametest/.../CtclClientGameTest.java`, `GameTestSupport.java`

## 2026-09-23 — 26.1 porting sources, and the world-storage move
**Symptom:** Fabric's 26.1 blog post says "major changes to world storage" but doesn't
explain them. `data/ctcl_loaders.dat` paths are hard-coded in `tools/uninstall_check.py`.
**Cause:** In 26.1, per-dimension data moves to
`<world>/dimensions/<namespace>/<path>/data/`. `SavedDataType(Identifier, ...)` resolves
`ns:a/b` to `data/ns/a/b.dat`. `DimensionDataStorage` was renamed `SavedDataStorage`, not
one-to-one, and `MinecraftServer#getDataStorage` now exists for global data. A new
`FileFixerUpper` rewrites old world folders on first load of a newer version (copy-on-write
into a temp folder, then swapped in), so downgrading is rarely possible.
**Fix:** Read these, not memory:
- `https://fabricmc.net/2026/03/14/261.html`, plus the 26.2 and 26.3 posts on the same blog.
- The NeoForged primer: `gh api -H "Accept: application/vnd.github.raw"
  "repos/neoforged/.github/contents/primers/26.1/index.md"` (also `26.2` and `26.3`).
  It's vanilla-only and about 5000 lines, so grep it.
- The example mod's build files: `gh api ... repos/FabricMC/fabric-example-mod/contents/<file>?ref=26.1`.
- Stable versions: `meta.fabricmc.net/v2/versions/{loader,game}` and each artifact's `maven-metadata.xml`.
**Dead ends:** Assuming a `ctcl_loaders.dat` path carries over. Also, Fabric API
`0.145.x+26.1.1` and `0.155.x+26.1.2` are separate lines per patch version.
**Affects:** `band/26.1` `LoaderData.java`, `tools/uninstall_check.py`

## 2026-09-24 — 1.21.11 → 26.1 rename map (what actually broke)
**Symptom:** 13 main, 2 client, and 11 gametest compile errors after the build-script swap. All were renames.
**Cause:** Fabric API moved to Mojang names in 26.1, and vanilla refactored a few types.
**Fix:**
| 1.21.11 | 26.1 |
|---|---|
| `ServerWorldEvents.LOAD/UNLOAD` | `ServerLevelEvents.LOAD/UNLOAD` |
| `ServerTickEvents.END_WORLD_TICK` | `END_LEVEL_TICK` |
| `PayloadTypeRegistry.playC2S()/playS2C()` | `serverboundPlay()/clientboundPlay()` |
| `ScreenEvents.beforeRender(screen)` | `beforeExtract(screen)`; same lambda arity, `GuiGraphicsExtractor` |
| `Screens.getButtons(screen)` | `Screens.getWidgets(screen)` |
| `DimensionDataStorage` | `SavedDataStorage`; `getDataFile(Identifier)` is still private, and `readTagFromDisk(Path, DataFixTypes, int)` now takes the path |
| `SavedDataType("ctcl_loaders", …)` | `SavedDataType(Identifier, …)` → `data/<ns>/<path>.dat` |
| `new ChunkPos(pos)`, `.x`, `.z`, `ChunkPos.asLong` | `ChunkPos.containing(pos)`, `.x()`, `.z()`, `ChunkPos.pack` (it's a record now) |
| `singleplayer.getClientWorld()` (client gametest) | `getClientLevel()` |
| `data/chunks.dat` (vanilla tickets) | `dimensions/<ns>/<dim>/data/minecraft/chunk_tickets.dat` |
A `null` `DataFixTypes` is still safe: Fabric API's object-builder `SavedDataStorageMixin`
null-checks before vanilla's unguarded `type.update(...)`. The corrupt-file guard was
verified end to end on 26.1 by corrupting the gametest world's `loaders.dat` by hand.
**Dead ends:** Reading Fabric API names from memory. Read them from the module jars with
`javap` (the umbrella jar nests them under `META-INF/jars/`), or from `genSources`
(`.gradle/loom-cache/minecraftMaven/.../*-sources.jar`). 26.1 is unobfuscated, so
`javap` on `~/.gradle/caches/fabric-loom/minecraftMaven/.../minecraft-common-deobf-26.1.jar` works before genSources finishes.
**Affects:** `band/26.1` everything under `src/`, `tools/uninstall_check.py`

## 2026-09-24 — Testing hotfix versions without scratch branches
**Symptom:** The plan called for a scratch branch per hotfix, which then has to be deleted (a destructive step).
**Fix:** Override on the command line: `./gradlew build -Pminecraft_version=26.1.2
-Pfabric_api_version=0.155.3+26.1.2`, and pass `--minecraft 26.1.2` to the uninstall check.
Nothing to clean up. **Afterwards run `./gradlew clean build`:** the persistent gametest
world has been upgraded to the newer version, and the older one can't load it.
**Limit:** This recompiles against the hotfix, so it proves source compatibility, not
that the jar built against 26.1 runs unchanged on 26.1.2.
**Affects:** `TESTING.md`, band rule 1 widening tests (Stage 8)

## 2026-09-24 — main → band merges conflict on TODO.md and .agent/memory/
**Symptom:** `git merge main` on `band/1.21.11` conflicted on `TODO.md` and all three memory files.
**Cause:** Memory commits reach `main` by cherry-pick (band rule 4), so they're different
commits with the same content. The merge base stays old, and both sides edited the same lines.
**Fix:** Prove the band's docs equal some `main` commit (`git diff --stat HEAD <main-sha> --
TODO.md .agent/memory/` is empty), so `main` is a strict superset. Then take `main`'s side:
`git checkout --theirs -- TODO.md .agent/memory/...`, check that `git diff --cached main --
TODO.md .agent/memory` is empty, and commit. Once merged, the base moves forward, and the
next merge is clean as long as nothing new was cherry-picked in between.
**Dead ends:** Resolving hunk by hunk. The content is identical, so hand-resolving only risks dropping lines.
**Affects:** `BANDS.md` rule 4, every band branch

## 2026-09-24 — A gametest run can die after passing when the machine runs out of commit memory
**Symptom:** `runGameTest` logged "All 14 required tests passed", then failed with `os::commit_memory ...
The paging file is too small` (errno 1455). The next run restored 0 loaders, because the crashed run never saved.
**Cause:** Another project's Gradle build and two VS Code Java language servers were running at the same
time, leaving about 8 GB of free commit on a 40 GB limit. It wasn't our code, and no JVMs were orphaned.
**Fix:** Rerun it. If "Restored 0" follows a crash, check `build/run/gameTest/logs/latest.log` from a
clean run before suspecting the restore path. Don't kill processes that belong to other projects.
**Affects:** `./gradlew build` on this machine

## 2026-09-24 — Compile-time constants make "compiles on 26.3" differ from "the 26.1 jar runs on 26.3"
**Symptom:** The client gametest built on 26.1 passed on 26.1 but timed out on 26.3 in the production
harness: every click landed nowhere. The per-version recompile passed on 26.3.
**Cause:** `InputConstants.MOUSE_BUTTON_LEFT` is a `static final int`: 0 under GLFW (26.1) and 1 under
SDL (26.3). javac copies the value into the caller's class file, so the 26.1-built jar passed 0 on 26.3.
The recompile-per-version suite can't see this.
**Fix:** The test reads the button at runtime (`options.keyAttack.getDefaultKey().getValue()`). For
the mod itself, `checkReleaseJarBytes` requires the release jar to be byte-identical to each pinned
version's compile, so any inlined value that differs between versions fails CI. On 2026-09-24, all
33 classes were identical on 26.1 through 26.3.
**Affects:** `CtclClientGameTest`, `build.gradle` (`checkReleaseJarBytes`)

## 2026-09-24 — Loom's production run tasks add the project's own jar to `mods`
**Symptom:** The production client run on 26.3 loaded `ctcl 1.0.0+26.3` (the fresh recompile), not the
prebuilt 26.1 jar. The server run happened to pick the right one.
**Cause:** The `AbstractProductionRunTask` constructor (Loom 1.17.21, bytecode) adds `jar`/`remapJar`
and `productionRuntimeMods` to `mods`. `mods.from(...)` appends, so there were two `ctcl` jars, and
Loader picked either one.
**Fix:** Use `mods.setFrom(...)`. `checkProdMods` fails the task unless the effective set is exactly
fabric-api plus one ctcl jar and one gametest jar. Mutation-checked: reverting to `from` makes it refuse.
**Affects:** `build.gradle` (`prodServerGameTest`, `prodClientGameTest`)

## 2026-09-24 — The fabric-api bundle jar doesn't contain the gametest API modules
**Symptom:** The production server refused to start: `ctcl-gametest requires fabric-gametest-api-v1, which is missing`.
**Cause:** Of the modules in the fabric-api POM, `fabric-gametest-api-v1`, `fabric-client-gametest-api-v1`, and
`fabric-api-deprecated` aren't nested in the bundle jar. Dev runs get them as separate artifacts.
**Fix:** Resolve fabric-api transitively and keep only the bundle plus the two gametest modules (a name regex).
The count is asserted to be 3.
**Affects:** `build.gradle` (`fabricApiMods`)

## 2026-09-24 — Loom parses the source fabric.mod.json, so it must stay valid JSON
**Symptom:** `Failed to parse fabric.mod.json: src/main/resources/fabric.mod.json` at configuration time
after the `minecraft` dependency became a bare `${minecraft_depends}` placeholder (for a JSON array).
**Fix:** The source keeps `"minecraft": "${minecraft_depends}"`, which is a valid string, and
`processResources` filters out the whole quoted placeholder and puts the array in before `expand`.
**Affects:** `build.gradle` `processResources`, `fabric.mod.json`

## 2026-09-24 — A production server run dir shared across versions breaks on the older version
**Symptom:** `prodServerGameTest` on 26.1 failed right after a 26.3 run: `Not a map: "minecraft:sandstone"` chunk errors.
**Cause:** Both runs used `build/run/prodServerGameTest/world`, and 26.1 can't read chunks 26.3 saved. It's the
same trap as the hotfix gotcha above.
**Fix:** One run directory per version, `run/prodServerGameTest/<mc>` (and the same for the client). No deletion code.
**Affects:** `build.gradle`

## 2026-09-24 — Python on Windows reads text as cp1252 unless told otherwise
**Symptom:** A scripted edit's `assert s.count(old) == 1` failed on a line containing `–`.
**Cause:** `Path.read_text()` uses the locale encoding (cp1252) on Windows. Round-trips happen to
preserve bytes, but matching non-ASCII text fails.
**Fix:** Always pass `encoding="utf-8"` to `read_text` and `write_text` in tooling and one-off edits.
**Affects:** any Python that edits repo files

## 2026-09-24 — Loom run tasks use xvfb by themselves on CI
**Context:** `AbstractRunTask.useXvfb` (and `ClientProductionRunTask.useXVFB`) default to true when the
`CI` env var is set and `xvfb-run` exists (Loom 1.17.21, bytecode). So `runClientGameTest` needs no
wrapper on GitHub Actions. The workflow still installs xvfb if it's missing, because without it Loom
silently runs with no display.
**Affects:** `.github/workflows/ci.yml`

## 2026-09-24 — `gradlew` was committed as 100644, so Linux CI couldn't run it
**Symptom:** The first CI runs on both bands failed in about a second: `Process completed with exit code 126` on `./gradlew`.
**Cause:** The repo was created on Windows, which doesn't track the executable bit. `git ls-tree <band> gradlew`
showed `100644`. Locally, Git Bash runs it anyway, so nothing ever showed it.
**Fix:** `git update-index --chmod=+x gradlew` on `band/1.21.11` (`71f4447`), cherry-picked to `band/26.1`.
Any new script that CI runs directly needs the same (Python tools are run as `python3 <script>`, so they don't).
**Affects:** `gradlew` on every band

## 2026-09-28 — 26.3 client on CI: no GLX visual, no Vulkan surface, then a silent hang
**Symptom:** 26.3 verify: `Failed to create window for OpenGL context: Couldn't find matching GLX visual`, then
`Vulkan is not supported: Installed Vulkan doesn't implement the VK_KHR_surface extension`, then the client **hung**,
with no crash and no exit, until the job limit. 26.1 through 26.2 (GLFW) open a GL window on the same runner.
**Dead end:** *The 8-bit Xvfb screen.* Loom runs a bare `xvfb-run -a` (Debian default `1280x1024x8`). Running Gradle inside
`xvfb-run -s "-screen 0 1920x1080x24"`, with Loom's wrapper off, still gave the same GLX error. Reverted.
**Fix:** install `mesa-vulkan-drivers` (lavapipe), as FabricMC/fabric-api's own `client_test` job does on 26.3 with Loom's
default xvfb. The runner image has no Mesa Vulkan ICD, so the Vulkan fallback had no surface extension.
**Also:** every CI step that starts a client has `timeout-minutes`. A client with no backend hangs, and the first run burned 60 minutes.
**Look first:** when a Minecraft version breaks on CI, read Fabric API's `.github/workflows/build.yml` on that version's branch.
**Affects:** `.github/workflows/ci.yml`

## 2026-09-28 — 1.21.11 client gametest "Timeout loading world" on CI was the menu blur, not terrain
**Symptom:** On the 2-vCPU runner, the 1.21.11 client gametest timed out on world load. The log shows "Preparing spawn area: 16%" and then
"Synchronizing registries" with nothing after it for 2 minutes. 26.1 on the same runner passed in seconds. Passes locally.
**Cause (thread dumps from a throwaway CI run):** the server thread was parked in Fabric's `ThreadingImpl.enterPhase`. The client
gametest harness runs **one server tick per client frame**. The render thread was in `GameRenderer.processBlurEffect` (the menu
blur behind the loading screen), and two `llvmpipe` threads used about 170% of 2 CPUs. So the login crawled through configuration one frame at a time.
**Fix:** the test sets `client.options.menuBackgroundBlurriness().set(0)` before `worldBuilder().create()`.
**Dead ends:** a 24-bit Xvfb display (no change). Superflat (a previous session's uncommitted guess, never run): the stall is before
the player's chunks load, so terrain wasn't the cost. It's kept as `stash@{0}` in `../CTCL-1.21.11`.
**How to diagnose next time:** a background loop in the CI step that runs `jstack` (the target JVM's own) and `top -b -H -p <pid>`
into `build/reports/`, which the failure artifact uploads.
**Affects:** `src/gametest/.../CtclClientGameTest.java`

## 2026-09-28 — CI Mojang asset downloads can fail transiently
**Symptom:** A verify job failed in `:downloadAssets`: `Failed download after 3 attempts` from `resources.download.minecraft.net`.
The same job had passed on the previous run, with no code change.
**Fix:** `gh run rerun <id> --failed`. It's not a code problem. If it recurs often, cache `~/.gradle/caches/fabric-loom/assets`.
**Affects:** `.github/workflows/ci.yml` (any job that runs a client)
